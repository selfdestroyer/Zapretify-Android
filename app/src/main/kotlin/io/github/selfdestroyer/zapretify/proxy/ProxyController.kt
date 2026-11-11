package io.github.selfdestroyer.zapretify.proxy

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.selfdestroyer.zapretify.StatusNotification
import io.github.selfdestroyer.zapretify.engine.EngineController
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
        thread(name = "proxy-bypass-stop") { ProxyBypass.stop() }
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
                // Пока работает обход, исходящие соединения прокси идут через SOCKS5 byedpi (план, 3.4.3).
                server.start(ProxyConfig(secretHex = settings.secret, port = settings.port, socks = EngineController.socks ?: ProxyBypass.socks))
                _state.update { it.copy(phase = ProxyState.Phase.On, link = linkFor(), error = null) }
                StatusNotification.refresh(app)
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
        StatusNotification.refresh(app)
    }

    val isRunning: Boolean get() = server.isRunning

    /** Обход включился или выключился: перезапустить прокси, чтобы он шёл через движок или напрямую. */
    internal fun onUpstreamChanged() {
        if (!server.isRunning) return
        thread(name = "tgproxy-restart") {
            server.stop()
            appendLog(when {
                EngineController.socks != null -> "[tg-ws-proxy] Обход включён: соединения пойдут через движок"
                ProxyBypass.running -> "[tg-ws-proxy] Соединения пойдут через движок обхода только для прокси"
                else -> "[tg-ws-proxy] Обход выключен: соединения пойдут напрямую"
            })
            startServer()
        }
    }

    private fun appendLog(line: String) {
        val stamped = "${time.format(Date())} $line"
        _state.update { s -> s.copy(log = (s.log + stamped).takeLast(MAX_LOG), cfBlocked = server.cfProxyBlocked) }
        if (line.contains("нет доступных путей")) noPath++
        maybeUseBypass()
    }

    private var noPath = 0

    /** Подбору нужен byedpi: остановить движок прокси. true — он работал (после подбора включить снова). */
    internal fun releaseBypassForSelection(): Boolean {
        val was = ProxyBypass.running
        if (was) { ProxyBypass.stop(); onUpstreamChanged() }
        return was
    }

    internal fun resumeBypass() {
        val args = EngineController.state.value.strategy?.args ?: return
        if (ProxyBypass.start(args)) onUpstreamChanged()
    }

    /** Остановить движок прокси (перед запуском VPN-движка или подбора). Ждёт до ~3 с — не из главного потока. */
    fun releaseBypass() {
        if (!ProxyBypass.running) return
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            val t = thread(name = "proxy-bypass-release") { ProxyBypass.stop() }
            t.join(3500)
        } else ProxyBypass.stop()
    }

    /**
     * Прокси не пробивается сам (путь через Cloudflare заблокирован или пути к Telegram нет), а обход YouTube и
     * Discord выключен — тогда прокси пойдёт через свой движок обхода без VPN. В настройках об этом не пишем:
     * это внутренняя часть прокси.
     */
    private fun maybeUseBypass() {
        val stuck = server.cfProxyBlocked || noPath >= 2
        if (!stuck || ProxyBypass.running || EngineController.socks != null || !server.isRunning) return
        if (EngineController.state.value.phase != io.github.selfdestroyer.zapretify.engine.EngineState.Phase.Off) return
        val args = EngineController.state.value.strategy?.args ?: return
        noPath = 0
        thread(name = "proxy-bypass-start") {
            if (ProxyBypass.start(args)) {
                appendLog("[tg-ws-proxy] Напрямую прокси не пробивается — включаю движок обхода только для него")
                onUpstreamChanged()
            }
        }
    }

    private const val MAX_LOG = 200
}
