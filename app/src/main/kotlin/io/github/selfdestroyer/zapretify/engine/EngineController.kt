package io.github.selfdestroyer.zapretify.engine

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import io.github.selfdestroyer.zapretify.proxy.ProxyController
import io.github.selfdestroyer.zapretify.vpn.ZapretVpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.InetSocketAddress

/** Что показывает интерфейс об обходе блокировок. */
data class EngineState(
    val phase: Phase = Phase.Off,
    val strategy: Strategy? = null,
    val strategies: List<Strategy> = emptyList(),
    val error: String? = null,
    /** Какие приложения идут через обход (названия для экрана). */
    val apps: List<String> = emptyList(),
    val nativeAvailable: Boolean = NativeBridge.available,
) {
    enum class Phase { Off, Starting, On }
}

/**
 * Управление обходом (ByeDpiEngine через VpnService) для экрана и сервиса. Состояние — поток [state].
 * Пока обход работает, Telegram-прокси ходит наружу через SOCKS5 byedpi (план, раздел 3.4, пункт 3).
 */
// Хранится контекст приложения (не экрана): утечки нет.
@SuppressLint("StaticFieldLeak")
object EngineController {
    const val SOCKS_HOST = "127.0.0.1"
    const val SOCKS_PORT = 10800

    private lateinit var app: Context
    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    fun init(context: Context) {
        app = context.applicationContext
        val list = runCatching { Strategies.load(app) }.getOrDefault(emptyList())
        val prefs = app.getSharedPreferences("engine", Context.MODE_PRIVATE)
        val selected = list.firstOrNull { it.id == prefs.getString(KEY_STRATEGY, null) } ?: list.firstOrNull()
        _state.update { it.copy(strategies = list, strategy = selected) }
    }

    fun select(strategy: Strategy) {
        app.getSharedPreferences("engine", Context.MODE_PRIVATE).edit { putString(KEY_STRATEGY, strategy.id) }
        val wasOn = _state.value.phase != EngineState.Phase.Off
        _state.update { it.copy(strategy = strategy) }
        if (wasOn) start() // сервис перезапустит движок с новой стратегией
    }

    /** Запуск. Разрешение на VPN (VpnService.prepare) экран получает заранее. */
    fun start() {
        val s = _state.value.strategy ?: return
        if (!NativeBridge.available) {
            _state.update { it.copy(error = "Движок обхода не входит в эту сборку") }
            return
        }
        _state.update { it.copy(phase = EngineState.Phase.Starting, error = null) }
        ContextCompat.startForegroundService(app,
            Intent(app, ZapretVpnService::class.java).setAction(ZapretVpnService.ACTION_START).putExtra(ZapretVpnService.EXTRA_ARGS, s.args))
    }

    fun stop() {
        app.startService(Intent(app, ZapretVpnService::class.java).setAction(ZapretVpnService.ACTION_STOP))
    }

    /** Адрес SOCKS5 движка, пока он работает; иначе null. */
    val socks: InetSocketAddress?
        get() = if (_state.value.phase == EngineState.Phase.On) InetSocketAddress(SOCKS_HOST, SOCKS_PORT) else null

    // Вызывается сервисом.
    internal fun onStarted(apps: List<String>) {
        _state.update { it.copy(phase = EngineState.Phase.On, apps = apps, error = null) }
        ProxyController.onUpstreamChanged()
    }

    internal fun onStopped(error: String? = null) {
        val was = _state.value.phase
        _state.update { it.copy(phase = EngineState.Phase.Off, apps = emptyList(), error = error) }
        if (was == EngineState.Phase.On) ProxyController.onUpstreamChanged()
    }

    private const val KEY_STRATEGY = "strategy"
}
