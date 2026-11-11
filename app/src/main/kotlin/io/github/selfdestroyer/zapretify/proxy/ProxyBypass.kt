package io.github.selfdestroyer.zapretify.proxy

import io.github.selfdestroyer.zapretify.engine.NativeBridge
import io.github.selfdestroyer.zapretify.engine.Strategies
import io.github.selfdestroyer.zapretify.engine.StrategyValidator
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Движок обхода только для Telegram-прокси — без VPN. Нужен, когда обход YouTube и Discord выключен в настройках,
 * а прокси без него не пробивается (путь через Cloudflare режется по имени сайта). Тот же byedpi, что и в VPN,
 * но слушает свой порт, и через него ходит только прокси. byedpi в приложении одновременно может быть только
 * один, поэтому перед запуском VPN-движка или подбора этот нужно остановить ([stop]).
 */
internal object ProxyBypass {
    const val PORT = 10802
    private val address = InetSocketAddress("127.0.0.1", PORT)
    @Volatile private var engine: Thread? = null

    val running: Boolean get() = engine?.isAlive == true
    val socks: InetSocketAddress? get() = if (running) address else null

    /** Запустить со стратегией [args]. true — движок слушает порт. */
    @Synchronized
    fun start(args: String): Boolean {
        if (running) return true
        if (!NativeBridge.available) return false
        val strategy = Strategies.splitArgs(args)
        if (StrategyValidator.check(strategy) != null) return false
        val argv = arrayOf("ciadpi", "-i", "127.0.0.1", "-p", PORT.toString()) + strategy
        val t = thread(name = "byedpi-proxy", isDaemon = true) { NativeBridge.runByedpi(argv) }
        engine = t
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            if (!t.isAlive) { engine = null; return false }
            if (runCatching { Socket().use { it.connect(address, 200) } }.isSuccess) return true
            Thread.sleep(50)
        }
        stop()
        return false
    }

    /** Остановить и дождаться (до ~3 с): после этого byedpi свободен для VPN-движка или подбора. */
    @Synchronized
    fun stop() {
        val t = engine ?: return
        engine = null
        repeat(30) {
            if (!t.isAlive) return
            runCatching { NativeBridge.stopByedpi() }
            t.join(100)
        }
    }
}
