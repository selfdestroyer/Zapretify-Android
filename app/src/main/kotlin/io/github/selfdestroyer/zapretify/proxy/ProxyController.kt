package io.github.selfdestroyer.zapretify.proxy

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.selfdestroyer.zapretify.tgproxy.ProxyConfig
import io.github.selfdestroyer.zapretify.tgproxy.TgProxyServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Что показывает интерфейс о Telegram-прокси. */
data class ProxyState(
    val phase: Phase = Phase.Off,
    val link: String? = null,
    val error: String? = null,
    val cfBlocked: Boolean = false,
    val log: List<String> = emptyList(),
) {
    enum class Phase { Off, Starting, On }
}

/**
 * Единая точка управления прокси для экрана и сервиса. Состояние отдаётся потоком [state] (план, раздел 4:
 * без локального HTTP-сервера и без опроса). Сам прокси живёт в [TgProxyService], пока включён.
 */
// Хранится контекст приложения (не экрана), он живёт столько же, сколько процесс: утечки нет.
@android.annotation.SuppressLint("StaticFieldLeak")
object ProxyController {
    private lateinit var app: Context
    lateinit var settings: ProxySettings
        private set
    private val server = TgProxyServer { line -> appendLog(line) }
    private val _state = MutableStateFlow(ProxyState())
    val state: StateFlow<ProxyState> = _state.asStateFlow()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    fun init(context: Context) {
        app = context.applicationContext
        settings = ProxySettings(app)
        _state.update { it.copy(link = linkFor()) }
    }

    fun linkFor(): String = io.github.selfdestroyer.zapretify.tgproxy.ProxyLink.build("127.0.0.1", settings.port, settings.secret)

    /** Включить: запускает сервис, а он — прокси. */
    fun enable() {
        settings.enabled = true
        _state.update { it.copy(phase = ProxyState.Phase.Starting, error = null) }
        ContextCompat.startForegroundService(app, Intent(app, TgProxyService::class.java).setAction(TgProxyService.ACTION_START))
    }

    fun disable() {
        settings.enabled = false
        app.startService(Intent(app, TgProxyService::class.java).setAction(TgProxyService.ACTION_STOP))
    }

    fun regenerateSecret() {
        val wasOn = server.isRunning
        if (wasOn) server.stop()
        settings.regenerateSecret()
        _state.update { it.copy(link = linkFor()) }
        // Сервис пересоберёт уведомление с новой ссылкой и снова поднимет прокси.
        if (wasOn) ContextCompat.startForegroundService(app, Intent(app, TgProxyService::class.java).setAction(TgProxyService.ACTION_START))
    }

    /** Вызывается сервисом. Сокет открывается не в главном потоке. */
    internal fun startServer() {
        thread(name = "tgproxy-start") {
            try {
                server.start(ProxyConfig(secretHex = settings.secret, port = settings.port))
                _state.update { it.copy(phase = ProxyState.Phase.On, link = linkFor(), error = null) }
            } catch (e: Exception) {
                appendLog("[tg-ws-proxy] Не удалось запустить: $e")
                _state.update { it.copy(phase = ProxyState.Phase.Off, error = "Не удалось запустить прокси: ${e.message}") }
                disable()
            }
        }
    }

    internal fun stopServer() {
        thread(name = "tgproxy-stop") { server.stop() }
        _state.update { it.copy(phase = ProxyState.Phase.Off, cfBlocked = false) }
    }

    val isRunning: Boolean get() = server.isRunning

    private fun appendLog(line: String) {
        val stamped = "${time.format(Date())} $line"
        _state.update { s -> s.copy(log = (s.log + stamped).takeLast(MAX_LOG), cfBlocked = server.cfProxyBlocked) }
    }

    private const val MAX_LOG = 200
}
