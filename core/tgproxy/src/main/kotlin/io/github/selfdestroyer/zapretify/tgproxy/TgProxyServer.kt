package io.github.selfdestroyer.zapretify.tgproxy

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Соединение с клиентом Telegram: прямое или внутри Fake TLS. */
internal class ClientConn(val input: InputStream, val output: OutputStream, val socket: Socket, val label: String) {
    fun close() { runCatching { socket.close() } }
}

/**
 * Локальный MTProto-прокси для Telegram — перенос core/tgproxy из Go-версии Zapretify.
 *
 * Путь для каждого соединения клиента: готовый WebSocket из пула → новый WebSocket к Telegram (kws<dc>.web.telegram.org
 * по IP дата-центра) → запасные пути: свои Cloudflare Worker, Cloudflare-путь, прямой TCP к DC. После неудач
 * DC и IP получают паузу, а редиректы на всех доменах — временный чёрный список, как в Go.
 *
 * @param log получает строки журнала (на русском, с префиксом [tg-ws-proxy]), как в десктопной версии.
 */
class TgProxyServer(private val log: (String) -> Unit = {}) {
    private val lock = Any()
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private var scheduler: ScheduledExecutorService? = null
    private var pool: WsPool? = null
    private var state = ProxyState(log)
    private var config: ProxyConfig? = null
    private var secret = ByteArray(0)
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()

    val isRunning: Boolean get() = running
    val stats: ProxyStats get() = state.stats

    /** Последние попытки Cloudflare-пути подряд не удались — для подсказки «Если Telegram не подключается». */
    val cfProxyBlocked: Boolean get() = running && state.cfProxyBlocked()

    /** Ссылка tg://proxy для «Открыть в Telegram» (секрет с префиксом dd). */
    fun link(): String? = config?.let { ProxyLink.build(it.host, it.port, it.cleanSecret) }

    fun start(cfg: ProxyConfig) {
        synchronized(lock) { startLocked(cfg) }
    }

    private fun startLocked(cfg: ProxyConfig) {
        if (running) return
        val clean = cfg.cleanSecret
        require(Regex("^[0-9a-f]{32}$").matches(clean)) { "секрет — 32 hex-символа" }
        val ss = ServerSocket(cfg.port, 128, InetAddress.getByName(cfg.host.ifEmpty { "127.0.0.1" }))
        val threadNo = AtomicInteger()
        val exec = Executors.newCachedThreadPool { r -> Thread(r, "tgproxy-${threadNo.incrementAndGet()}").apply { isDaemon = true } }
        val sched = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tgproxy-idle").apply { isDaemon = true } }
        val st = ProxyState(log)
        val upstream = Upstream(cfg.socks)
        val wsPool = WsPool(cfg.poolSize, exec, st) { target, domains, timeout -> connectWsAny(upstream, cfg, target, domains, timeout) }
        st.onIpCooldown = { ip -> wsPool.discardTarget(ip) }

        serverSocket = ss; executor = exec; scheduler = sched; state = st; pool = wsPool
        config = cfg; secret = hexToBytes(clean); running = true
        log("[tg-ws-proxy] Сервер запущен на ${cfg.host}:${cfg.port} (Secret: ${clean.take(6)}***, CFProxy: ${cfg.cfProxy}, SOCKS5: ${cfg.socks ?: "нет"})")
        Thread({ acceptLoop(ss, cfg, upstream, exec, sched, st, wsPool) }, "tgproxy-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        if (!running) return
        running = false
        runCatching { serverSocket?.close() }
        activeSockets.forEach { runCatching { it.close() } }
        activeSockets.clear()
        pool?.reset()
        executor?.shutdownNow()
        scheduler?.shutdownNow()
        serverSocket = null; executor = null; scheduler = null; pool = null
        log("[tg-ws-proxy] Сервер остановлен")
    }

    private fun acceptLoop(ss: ServerSocket, cfg: ProxyConfig, upstream: Upstream, exec: ExecutorService,
                           sched: ScheduledExecutorService, st: ProxyState, wsPool: WsPool) {
        val sessions = Semaphore(cfg.maxConns)
        while (running) {
            val socket = try { ss.accept() } catch (e: SocketException) { if (!running) return else continue }
            st.stats.connectionsTotal.incrementAndGet()
            if (!sessions.tryAcquire()) {
                log("[tg-ws-proxy] Достигнут лимит сессий (${cfg.maxConns}), соединение от ${socket.remoteSocketAddress} отклонено")
                runCatching { socket.close() }
                continue
            }
            activeSockets += socket
            try {
                exec.execute {
                    try {
                        Session(socket, cfg, upstream, exec, sched, st, wsPool).run()
                    } catch (e: Exception) {
                        if (running) log("[tg-ws-proxy] [${socket.remoteSocketAddress}] ошибка: $e")
                    } finally {
                        runCatching { socket.close() }
                        activeSockets -= socket
                        sessions.release()
                    }
                }
            } catch (_: Exception) {
                runCatching { socket.close() }; activeSockets -= socket; sessions.release()
            }
        }
    }

    /** Одно соединение клиента (handleClient и handleMTProtoClient в Go). */
    private inner class Session(
        private val socket: Socket,
        private val cfg: ProxyConfig,
        private val upstream: Upstream,
        private val exec: ExecutorService,
        private val sched: ScheduledExecutorService,
        private val st: ProxyState,
        private val wsPool: WsPool,
    ) {
        private val label = socket.remoteSocketAddress.toString().removePrefix("/")

        fun run() {
            st.stats.connectionsActive.incrementAndGet()
            try { handle() } finally { st.stats.connectionsActive.decrementAndGet() }
        }

        private fun handle() {
            socket.tcpNoDelay = true
            socket.soTimeout = Const.CLIENT_HANDSHAKE_TIMEOUT_MS
            val rawIn = socket.getInputStream()
            val rawOut = socket.getOutputStream()
            val first = rawIn.read()
            if (first < 0) return

            val client: ClientConn
            val hs = ByteArray(Const.HANDSHAKE_LEN)
            if (first == FakeTls.RECORD_HANDSHAKE) {
                val hdr = ByteArray(5).also { it[0] = first.toByte() }
                if (!readFully(rawIn, hdr, 1, 4)) return
                val recordLen = ((hdr[3].toInt() and 0xff) shl 8) or (hdr[4].toInt() and 0xff)
                if (recordLen <= 0) return
                val body = ByteArray(recordLen)
                if (!readFully(rawIn, body)) return
                val hello = FakeTls.verifyClientHello(hdr + body, secret) ?: return
                rawOut.write(FakeTls.buildServerHello(secret, hello.clientRandom, hello.sessionId))
                rawOut.flush()
                val tlsIn = FakeTlsInputStream(rawIn)
                if (!readFully(tlsIn, hs)) return
                client = ClientConn(tlsIn, FakeTlsOutputStream(rawOut), socket, label)
            } else {
                hs[0] = first.toByte()
                if (!readFully(rawIn, hs, 1, Const.HANDSHAKE_LEN - 1)) return
                client = ClientConn(rawIn, rawOut, socket, label)
            }
            socket.soTimeout = 0

            val hi = tryHandshake(hs, secret)
            if (hi == null) {
                st.stats.connectionsBad.incrementAndGet()
                return
            }
            handleMtproto(client, hi)
        }

        private fun handleMtproto(client: ClientConn, hi: HandshakeInfo) {
            val proto = protoFromTag(hi.protoTag)
            val mediaTag = if (hi.isMedia) " media" else ""
            val relayInit = generateRelayInit(hi.protoTag, signedDc(hi.dc, hi.isMedia))
            val ciphers = buildCiphers(hi.clientDecPrekeyAndIv, relayInit, secret)
            val key = DcKey(hi.dc, hi.isMedia)

            fun doFallback(setState: Boolean, wsFailedRedirect: Boolean, allRedirect: Boolean, primaryTarget: String) {
                if (setState) {
                    if (wsFailedRedirect && allRedirect) st.setBlacklisted(key) else st.setCooldown(key)
                }
                val fallback = cfg.dcIps[hi.dc] ?: primaryTarget
                if (cfg.cfWorkers.isNotEmpty() && tryWorker(client, hi, fallback, relayInit, ciphers)) return
                val useCf = cfg.cfProxy && cfg.cfDomains.isNotEmpty()
                if (useCf && tryCfProxy(client, hi, proto, relayInit, ciphers)) return
                if (fallback.isNotEmpty()) {
                    log("[tg-ws-proxy] [$label] DC${hi.dc}$mediaTag -> TCP fallback к $fallback:${cfg.telegramPort}")
                    if (tcpFallback(client, fallback, relayInit, ciphers)) return
                }
                log("[tg-ws-proxy] [$label] DC${hi.dc}$mediaTag нет доступных путей fallback")
            }

            if (st.isBlacklisted(key)) return doFallback(false, false, false, "")
            val primary = cfg.dcIps[hi.dc] ?: return doFallback(false, false, false, "")
            val targets = listOf(primary)
            val hasAnyCf = cfg.cfWorkers.isNotEmpty() || (cfg.cfProxy && cfg.cfDomains.isNotEmpty())
            val direct = if (hasAnyCf) targets.filter { !st.inIpCooldown(it) } else targets
            val wsTarget = direct.firstOrNull() ?: primary
            val domains = wsDomains(Const.DC_OVERRIDES[hi.dc] ?: hi.dc, hi.isMedia)

            if (!cfg.webSocket || st.inCooldown(key)) return doFallback(false, false, false, primary)

            wsPool.get(key, wsTarget, domains)?.let { ws ->
                st.stats.connectionsWs.incrementAndGet()
                st.telegramReachable()
                if (sendRelayInit(ws, relayInit)) return bridgeWs(client, ws, ciphers, MsgSplitter(relayInit, proto))
            }

            var wsFailedRedirect = false
            var allRedirect = true
            var timedOut = false
            for (target in direct) for (domain in domains) {
                val ws = try {
                    connectWs(upstream, cfg, target, domain, Const.WS_CONNECT_TIMEOUT_MS)
                } catch (e: Exception) {
                    if (WsConn.isTimeout(e)) timedOut = true
                    if (e is WsHandshakeException && e.status in REDIRECTS) wsFailedRedirect = true else allRedirect = false
                    null
                } ?: continue
                st.clearCooldown(key)
                st.stats.connectionsWs.incrementAndGet()
                st.telegramReachable()
                if (sendRelayInit(ws, relayInit)) return bridgeWs(client, ws, ciphers, MsgSplitter(relayInit, proto))
            }
            if (timedOut && direct.isNotEmpty()) st.setIpCooldown(direct[0])
            doFallback(true, wsFailedRedirect, allRedirect, primary)
        }

        private fun sendRelayInit(ws: WsConn, relayInit: ByteArray): Boolean =
            try { ws.writeBinary(relayInit); true } catch (_: Exception) { ws.close(); false }

        /** Cloudflare-путь: wss://kws<dc>.<домен>/apiws. Неудачи считаются для подсказки о блокировке. */
        private fun tryCfProxy(client: ClientConn, hi: HandshakeInfo, proto: Int, relayInit: ByteArray, ciphers: Ciphers): Boolean {
            val mediaTag = if (hi.isMedia) " media" else ""
            for (base in cfg.cfDomains) {
                val domain = "kws${hi.dc}.$base"
                log("[tg-ws-proxy] [$label] DC${hi.dc}$mediaTag -> CF proxy wss://$domain/apiws")
                val ws = try {
                    WsConn.open(upstream.connectTls(domain, 443, domain, Const.WS_CONNECT_TIMEOUT_MS), domain, "/apiws", Const.WS_CONNECT_TIMEOUT_MS)
                } catch (e: Exception) {
                    st.stats.wsErrors.incrementAndGet()
                    st.markCfDomainFailed(base)
                    log("[tg-ws-proxy] [$label] CF proxy $domain недоступен: $e")
                    st.noteCfFailure()
                    continue
                }
                if (!sendRelayInit(ws, relayInit)) {
                    log("[tg-ws-proxy] [$label] CF proxy $domain: ошибка отправки")
                    st.noteCfFailure()
                    st.markCfDomainFailed(base)
                    continue
                }
                st.stats.connectionsCf.incrementAndGet()
                st.telegramReachable()
                bridgeWs(client, ws, ciphers, MsgSplitter(relayInit, proto))
                return true
            }
            return false
        }

        /** Свои Cloudflare Worker пользователя: wss://<worker>/apiws?dc=<dc>&dst=<ip>. */
        private fun tryWorker(client: ClientConn, hi: HandshakeInfo, dst: String, relayInit: ByteArray, ciphers: Ciphers): Boolean {
            if (dst.isEmpty()) return false
            val mediaTag = if (hi.isMedia) " media" else ""
            for (worker in cfg.cfWorkers) {
                log("[tg-ws-proxy] [$label] DC${hi.dc}$mediaTag -> CF worker wss://$worker/apiws?dst=$dst")
                val ws = try {
                    WsConn.open(upstream.connectTls(worker, 443, worker, Const.WS_CONNECT_TIMEOUT_MS), worker,
                        "/apiws?dc=${hi.dc}&dst=$dst", Const.WS_CONNECT_TIMEOUT_MS)
                } catch (_: Exception) {
                    st.stats.wsErrors.incrementAndGet()
                    continue
                }
                if (!sendRelayInit(ws, relayInit)) continue
                st.stats.connectionsCf.incrementAndGet()
                bridgeWs(client, ws, ciphers, null)
                return true
            }
            return false
        }

        private fun tcpFallback(client: ClientConn, dst: String, relayInit: ByteArray, c: Ciphers): Boolean {
            val remote = try {
                upstream.connectTcp(dst, cfg.telegramPort, Const.TCP_DIAL_TIMEOUT_MS)
            } catch (e: Exception) {
                log("[tg-ws-proxy] TCP fallback to $dst:${cfg.telegramPort} failed: $e")
                return false
            }
            st.stats.connectionsTcp.incrementAndGet()
            st.telegramReachable()
            try {
                remote.getOutputStream().apply { write(relayInit); flush() }
            } catch (_: Exception) {
                runCatching { remote.close() }
                return false
            }
            val rin = remote.getInputStream()
            val rout = remote.getOutputStream()
            bridge(client, close = { runCatching { remote.close() } },
                up = { buf, n -> rout.write(buf, 0, n); rout.flush() },
                upEnd = {},
                down = { buf -> rin.read(buf) },
                c = c)
            return true
        }

        private fun bridgeWs(client: ClientConn, ws: WsConn, c: Ciphers, splitter: MsgSplitter?) {
            bridge(client, close = { ws.close() },
                up = { buf, n ->
                    if (splitter == null) ws.writeBinary(buf, 0, n)
                    else splitter.split(buf.copyOf(n)).forEach { ws.writeBinary(it) }
                },
                upEnd = { splitter?.flush()?.forEach { runCatching { ws.writeBinary(it) } } },
                down = { buf -> ws.read(buf) },
                c = c)
        }

        /**
         * Двусторонний мост. Поток вверх (клиент → Telegram) идёт в текущем потоке, вниз — в отдельном.
         * Когда одна сторона закрылась, закрываются обе; 5 минут без данных в обе стороны — тоже.
         */
        private fun bridge(client: ClientConn, close: () -> Unit, up: (ByteArray, Int) -> Unit, upEnd: () -> Unit,
                           down: (ByteArray) -> Int, c: Ciphers) {
            val lastActivity = AtomicLong(System.currentTimeMillis())
            val closeAll = { client.close(); close() }
            val watchdog = sched.scheduleAtFixedRate({
                if (System.currentTimeMillis() - lastActivity.get() >= Const.IO_IDLE_TIMEOUT_MS) closeAll()
            }, Const.IDLE_CHECK_INTERVAL_MS, Const.IDLE_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS)

            val downDone = java.util.concurrent.CountDownLatch(1)
            exec.execute {
                val buf = ByteArray(64 * 1024)
                try {
                    while (true) {
                        val n = down(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        lastActivity.set(System.currentTimeMillis())
                        st.stats.bytesDown.addAndGet(n.toLong())
                        c.tgDec.xor(buf, 0, n)
                        c.cltEnc.xor(buf, 0, n)
                        client.output.write(buf, 0, n)
                        client.output.flush()
                    }
                } catch (_: Exception) {
                } finally {
                    closeAll()
                    downDone.countDown()
                }
            }

            val buf = ByteArray(64 * 1024)
            try {
                while (true) {
                    val n = client.input.read(buf)
                    if (n < 0) { upEnd(); break }
                    if (n == 0) continue
                    lastActivity.set(System.currentTimeMillis())
                    st.stats.bytesUp.addAndGet(n.toLong())
                    c.cltDec.xor(buf, 0, n)
                    c.tgEnc.xor(buf, 0, n)
                    up(buf, n)
                }
            } catch (_: Exception) {
            } finally {
                closeAll()
                downDone.await(5, TimeUnit.SECONDS)
                watchdog.cancel(false)
            }
        }
    }

    private fun connectWs(upstream: Upstream, cfg: ProxyConfig, target: String, domain: String, timeoutMs: Int): WsConn =
        WsConn.open(upstream.connectTls(target, cfg.telegramPort, domain, timeoutMs), domain, "/apiws", timeoutMs)

    private fun connectWsAny(upstream: Upstream, cfg: ProxyConfig, target: String, domains: List<String>, timeoutMs: Int): WsConn {
        var last: Exception? = null
        for (d in domains) {
            try { return connectWs(upstream, cfg, target, d, timeoutMs) } catch (e: Exception) { last = e }
        }
        throw last ?: java.io.IOException("нет доменов для подключения")
    }

    companion object {
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        internal fun hexToBytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
