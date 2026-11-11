package io.github.selfdestroyer.zapretify.vpn

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.ServiceCompat
import io.github.selfdestroyer.zapretify.StatusNotification
import io.github.selfdestroyer.zapretify.engine.EngineController
import io.github.selfdestroyer.zapretify.engine.NativeBridge
import io.github.selfdestroyer.zapretify.engine.Strategies
import io.github.selfdestroyer.zapretify.engine.StrategyValidator
import io.github.selfdestroyer.zapretify.selection.ClientHello
import io.github.selfdestroyer.zapretify.selection.Outcome
import io.github.selfdestroyer.zapretify.selection.TlsProbe
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Локальный VPN: трафик выбранных приложений идёт в TUN, оттуда hev-socks5-tunnel передаёт его в SOCKS5
 * byedpi, а byedpi отправляет его в сеть с приёмами обхода DPI. Никакого внешнего сервера нет — всё на телефоне.
 *
 * Уроки Mac-версии (план, раздел 3.4): маршруты и адреса и для IPv4, и для IPv6; собственное приложение в VPN
 * не входит (петля трафика); через VPN идут только нужные приложения.
 */
class ZapretVpnService : VpnService() {
    @Volatile private var tun: ParcelFileDescriptor? = null
    private var byedpiThread: Thread? = null
    private var tunnelThread: Thread? = null
    private val lock = Any()
    /** Номер запуска движка: наблюдатель прежнего запуска по нему понимает, что он больше не нужен. */
    @Volatile private var generation = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown(null)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                ServiceCompat.startForeground(this, StatusNotification.ID, StatusNotification.build(this),
                    if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
                val args = intent.getStringExtra(EXTRA_ARGS).orEmpty()
                thread(name = "engine-start") { restart(args) }
            }
        }
        return START_NOT_STICKY
    }

    /** Пользователь выключил VPN в настройках или его забрало другое VPN-приложение. */
    override fun onRevoke() {
        shutdown("VPN отключён системой или другим VPN-приложением")
    }

    override fun onDestroy() {
        stopEngine()
        super.onDestroy()
    }

    private fun restart(args: String) = synchronized(lock) {
        stopEngine()
        try {
            val apps = establishTun()
            startByedpi(args)
            startTunnel()
            val check = selfCheck()
            if (check == Check.BROKEN) {
                throw IllegalStateException("с этой стратегией не открываются обычные сайты — выберите другую в разделе «Стратегии»")
            }
            EngineController.onStarted(apps)
            if (check == Check.PARTIAL) {
                EngineController.reportError("С этой стратегией часть обычных сайтов может не открываться. Если заметите — выберите другую.")
            }
            watch(generation)
        } catch (e: Exception) {
            shutdown("Не удалось включить обход: ${e.message}")
        }
    }

    private enum class Check { OK, PARTIAL, BROKEN }

    /**
     * Самопроверка после запуска (план, раздел 3.4, пункт 14): открывается ли через движок обычный, никем не
     * заблокированный сайт. Проверка — тем же большим ClientHello в двух раскладках, что и в подборе.
     * Если через движок не открывается ничего, а напрямую открывается — стратегия ломает обычные сайты: с ней
     * браузер в VPN остался бы без интернета, поэтому обход не включается. Если не открывается и напрямую —
     * интернета сейчас нет, стратегия ни при чём.
     */
    private fun selfCheck(): Check {
        val engine = TlsProbe(InetSocketAddress(EngineController.SOCKS_HOST, EngineController.SOCKS_PORT))
        val pool = Executors.newFixedThreadPool(CONTROL_HOSTS.size * 2 + 1)
        try {
            val viaEngine = CONTROL_HOSTS.flatMap { host -> ClientHello.Layout.entries.map { layout -> pool.submit(Callable { engine.check(host, layout) }) } }
            val direct = pool.submit(Callable { TlsProbe(null).check(CONTROL_HOSTS.first(), ClientHello.Layout.SNI_FIRST) })
            val passed = viaEngine.count { runCatching { it.get() }.getOrNull() == Outcome.OK }
            return when {
                passed == viaEngine.size -> Check.OK
                passed > 0 -> Check.PARTIAL
                runCatching { direct.get() }.getOrNull() == Outcome.OK -> Check.BROKEN
                else -> Check.OK // интернета нет — не вина стратегии
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Следит за движком, пока он работает: если byedpi или туннель завершились сами, VPN снимается — иначе
     * приложения из списка остались бы «в VPN» без интернета.
     */
    private fun watch(gen: Int) {
        thread(name = "engine-watch", isDaemon = true) {
            while (true) {
                Thread.sleep(1000)
                synchronized(lock) {
                    if (generation != gen || tun == null) return@thread
                    val byedpiAlive = byedpiThread?.isAlive == true
                    val tunnelAlive = tunnelThread?.isAlive == true
                    if (!byedpiAlive || !tunnelAlive) {
                        shutdown("Обход остановился сам (${if (!byedpiAlive) "движок" else "туннель"}), YouTube и Discord идут напрямую.")
                        return@thread
                    }
                }
            }
        }
    }

    private fun establishTun(): List<String> {
        val pm = packageManager
        val builder = Builder()
            .setSession("Zapretify")
            .setMtu(MTU)
            .addAddress(TUN_IPV4, 32)
            .addAddress(TUN_IPV6, 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer(DNS_SERVER)
        // Только выбранные приложения; наше собственное в список не входит — его соединения идут мимо VPN.
        val names = mutableListOf<String>()
        for ((pkg, label) in DEFAULT_APPS) {
            try {
                pm.getPackageInfo(pkg, 0)
                builder.addAllowedApplication(pkg)
                if (label !in names) names += label
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        if (names.isEmpty()) throw IllegalStateException("на телефоне нет приложений, для которых нужен обход")
        tun = builder.establish() ?: throw IllegalStateException("нет разрешения на VPN")
        return names
    }

    private fun startByedpi(args: String) {
        val strategyArgs = Strategies.splitArgs(args)
        StrategyValidator.check(strategyArgs)?.let { throw IllegalArgumentException("стратегия отклонена: $it") }
        // Серверы видео YouTube byedpi подключает только по IPv4 — см. app/src/main/jni/zapretify_resolv.c.
        val argv = arrayOf("ciadpi", "-i", EngineController.SOCKS_HOST, "-p", EngineController.SOCKS_PORT.toString()) +
            strategyArgs
        byedpiThread = thread(name = "byedpi") { NativeBridge.runByedpi(argv) }
        // Ждём, пока SOCKS5 начнёт принимать соединения.
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            if (byedpiThread?.isAlive != true) throw IllegalStateException("byedpi завершился при запуске — проверьте параметры стратегии")
            val ok = runCatching { Socket().use { it.connect(InetSocketAddress(EngineController.SOCKS_HOST, EngineController.SOCKS_PORT), 200) } }.isSuccess
            if (ok) return
            Thread.sleep(100)
        }
        throw IllegalStateException("byedpi не начал принимать соединения")
    }

    private fun startTunnel() {
        val fd = tun?.fd ?: throw IllegalStateException("нет TUN")
        // Журнал туннеля не растёт бесконечно: большой файл начинается заново.
        val logFile = java.io.File(cacheDir, "hev.log")
        if (logFile.length() > MAX_TUNNEL_LOG) runCatching { logFile.delete() }
        val config = tunnelConfig(logFile.absolutePath)
        var rc = 0
        val t = thread(name = "tunnel") { rc = NativeBridge.runTunnel(config, fd) }
        tunnelThread = t
        // Туннель работает, пока его не остановят; если он вышел сразу — трафик приложений никуда не пойдёт.
        t.join(500)
        if (!t.isAlive) throw IllegalStateException("туннель не запустился (код $rc)")
    }

    private fun stopEngine() {
        generation++ // наблюдатель прежнего запуска завершится
        if (NativeBridge.available) {
            runCatching { NativeBridge.stopTunnel() }
            runCatching { NativeBridge.stopByedpi() }
        }
        tunnelThread?.join(3000); tunnelThread = null
        byedpiThread?.join(3000); byedpiThread = null
        runCatching { tun?.close() }; tun = null
    }

    private fun shutdown(error: String?) {
        stopEngine()
        EngineController.onStopped(error)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_START = "io.github.selfdestroyer.zapretify.START_ENGINE"
        const val ACTION_STOP = "io.github.selfdestroyer.zapretify.STOP_ENGINE"
        const val EXTRA_ARGS = "args"
        private const val MAX_TUNNEL_LOG = 512 * 1024L
        /** Сайты, которые открыты и без обхода: по ним движок проверяет себя после запуска. */
        private val CONTROL_HOSTS = listOf("ya.ru", "vk.com")
        private const val MTU = 8500
        private const val TUN_IPV4 = "198.18.0.1"
        private const val TUN_IPV6 = "fc00::1"
        /** DNS внутри туннеля: hev отвечает сам (mapdns), а имя сайта уходит в SOCKS5 — byedpi резолвит его сам. */
        private const val DNS_SERVER = "198.18.0.2"

        /** Приложения, которым нужен обход (план, раздел 3.4, пункт 7). Telegram не входит — он ходит через наш прокси. */
        val DEFAULT_APPS: List<Pair<String, String>> = listOf(
            "com.google.android.youtube" to "YouTube",
            "com.google.android.apps.youtube.music" to "YouTube Music",
            "com.discord" to "Discord",
            "com.android.chrome" to "Chrome",
            "org.mozilla.firefox" to "Firefox",
            "com.yandex.browser" to "Яндекс Браузер",
            "com.opera.browser" to "Opera",
            "com.microsoft.emmx" to "Edge",
            "com.brave.browser" to "Brave",
            "com.sec.android.app.sbrowser" to "Samsung Internet",
            "com.heytap.browser" to "Браузер OnePlus/OPPO",
        )

        /**
         * Конфигурация hev-socks5-tunnel (YAML, ключи — по conf/main.yml версии 2.19.3): TUN с нашими адресами,
         * SOCKS5 byedpi как выход (UDP тоже — для голоса Discord), mapdns: приложения получают от DNS адреса из
         * 100.64.0.0/10, а hev отдаёт byedpi имя сайта. Так DNS провайдера не участвует, а движок видит домен.
         */
        fun tunnelConfig(logFile: String): String = """
            |tunnel:
            |  mtu: $MTU
            |  ipv4: $TUN_IPV4
            |  ipv6: '$TUN_IPV6'
            |socks5:
            |  port: ${EngineController.SOCKS_PORT}
            |  address: ${EngineController.SOCKS_HOST}
            |  udp: 'udp'
            |mapdns:
            |  address: $DNS_SERVER
            |  port: 53
            |  network: 100.64.0.0
            |  netmask: 255.192.0.0
            |  cache-size: 10000
            |misc:
            |  log-file: $logFile
            |  log-level: warn
            |""".trimMargin()
    }
}
