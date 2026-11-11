package io.github.selfdestroyer.zapretify.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.security.SecureRandom

class SelectionTest {
    private fun u16(b: ByteArray, off: Int) = ((b[off].toInt() and 0xff) shl 8) or (b[off + 1].toInt() and 0xff)

    /** Разбирает ClientHello и возвращает смещения расширений по типу. */
    private fun extensionOffsets(hello: ByteArray): Map<Int, Int> {
        assertEquals(0x16, hello[0].toInt())
        assertEquals(hello.size - 5, u16(hello, 3))
        assertEquals(0x01, hello[5].toInt())
        assertEquals(hello.size - 9, ((hello[6].toInt() and 0xff) shl 16) or u16(hello, 7))
        var p = 9 + 2 + 32
        p += 1 + (hello[p].toInt() and 0xff) // session id
        p += 2 + u16(hello, p) // cipher suites
        p += 1 + (hello[p].toInt() and 0xff) // compression
        val end = p + 2 + u16(hello, p)
        assertEquals(hello.size, end)
        p += 2
        val out = LinkedHashMap<Int, Int>()
        while (p < end) {
            out[u16(hello, p)] = p
            p += 4 + u16(hello, p + 2)
        }
        assertEquals(end, p)
        return out
    }

    @Test
    fun clientHelloIsWellFormedAndBig() {
        for (layout in ClientHello.Layout.entries) {
            val hello = ClientHello.build("www.youtube.com", layout, SecureRandom())
            val ext = extensionOffsets(hello)
            // Больше одного TCP-пакета — как у Chrome и приложения YouTube.
            assertTrue("size ${hello.size}", hello.size in 1600..2000)
            assertTrue(ext.keys.containsAll(listOf(0x0000, 0x000a, 0x000d, 0x0010, 0x002b, 0x0033)))
            val sni = ext.getValue(0x0000)
            assertEquals("www.youtube.com", String(hello, sni + 9, u16(hello, sni + 7)))
            if (layout == ClientHello.Layout.SNI_FIRST) assertTrue(sni < 200) else assertTrue(sni > 1300)
            val ks = ext.getValue(0x0033)
            assertEquals(ClientHello.GROUP_X25519_MLKEM768, u16(hello, ks + 6))
            assertEquals(1216, u16(hello, ks + 8))
        }
    }

    @Test
    fun mlkemKeyCoefficientsAreInRange() {
        val key = ClientHello.mlkem768Key(SecureRandom())
        assertEquals(1184, key.size)
        for (i in 0 until 384) {
            val b0 = key[3 * i].toInt() and 0xff; val b1 = key[3 * i + 1].toInt() and 0xff; val b2 = key[3 * i + 2].toInt() and 0xff
            assertTrue((b0 or ((b1 and 0x0f) shl 8)) < 3329)
            assertTrue(((b1 shr 4) or (b2 shl 4)) < 3329)
        }
    }

    @Test
    fun serverHelloIsRecognised() {
        fun stream(vararg b: Int) = ByteArrayInputStream(ByteArray(b.size) { b[it].toByte() })
        assertTrue(TlsProbe.isServerHello(stream(0x16, 3, 3, 0, 0x7a, 0x02, 0, 0, 0x76)))
        assertFalse(TlsProbe.isServerHello(stream(0x15, 3, 3, 0, 2, 2, 40))) // оповещение об ошибке
        assertFalse(TlsProbe.isServerHello(stream(0x16, 3, 3, 0, 0x7a, 0x0b, 0, 0, 0)))
    }

    @Test
    fun videoHostFromCluster() {
        assertEquals("rr1---sn-5go7yner.googlevideo.com", VideoCluster.hostFor("arn09s18"))
        assertEquals("arn09s18", VideoCluster.parseCluster("203.0.113.7 => arn09s18 (203.0.113.0/24)\n\nDebug Info:\nabc"))
        assertEquals("example-abc1", VideoCluster.parseCluster("2001:db8::1 => example-abc1 (2001:db8::/40) [u]"))
        assertNull(VideoCluster.parseCluster("<html>blocked</html>"))
    }

    /** Поддельное устройство: какие адреса открывает какая стратегия. */
    private class FakeBackend(
        private val opens: Map<String, Set<String>>,
        private val noTcp: Set<String> = emptySet(),
        private val cluster: String? = "arn09s18",
        private val brokenEngine: Set<String> = emptySet(),
    ) : SelectionBackend {
        var current = ""
        val started = mutableListOf<String>()
        override fun startEngine(args: String): Boolean { current = args; started += args; return args !in brokenEngine }
        override fun stopEngine() {}
        override fun probe(target: Target, layout: ClientHello.Layout): Outcome = when {
            target.id in noTcp -> Outcome.NO_TCP
            target.id in opens[current].orEmpty() -> Outcome.OK
            else -> Outcome.TIMEOUT
        }
        override fun discoverVideoCluster(redirector: InetAddress?): String? = cluster
        override fun resolve(host: String): List<InetAddress> =
            listOf(InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1)), InetAddress.getByAddress(ByteArray(16).also { it[0] = 0x20; it[1] = 0x01 }))
    }

    private val all = StrategySelector.defaultTargets("tg.example").map { it.id }.toSet()

    @Test
    fun ranksStrategiesAndRepeatsTheBest() {
        val video = setOf(StrategySelector.VIDEO4)
        val backend = FakeBackend(
            opens = mapOf(
                "" to setOf(StrategySelector.CONTROL),
                "-a" to all + video,
                "-b" to all - "dc_gw",
                "-c" to setOf("yt_web", "yt_api"), // ломает обычные сайты
            ),
            noTcp = setOf(StrategySelector.VIDEO6),
            brokenEngine = setOf("-x"),
        )
        val cands = listOf(Candidate("c", "c", "-c"), Candidate("x", "x", "-x"), Candidate("b", "b", "-b"), Candidate("a", "a", "-a"))
        val report = StrategySelector(backend, cands, StrategySelector.defaultTargets("tg.example"), topCount = 2, extraRuns = 2).run()

        assertEquals(listOf("a", "b", "c", "x"), report.results.map { it.candidate.id })
        assertEquals(100, report.results[0].score)
        assertEquals(3, report.results[0].runs) // первый прогон и два повторных
        assertEquals(3, report.results[1].runs)
        assertEquals(1, report.results[2].runs)
        assertTrue(report.results[3].engineFailed)
        assertTrue(report.results[0].ok(StrategySelector.VIDEO4))
        // Сервер видео по IPv6 недоступен совсем — не считается провалом стратегии.
        assertEquals(setOf(StrategySelector.VIDEO6), report.unreachable)
        assertEquals("rr1---sn-5go7yner.googlevideo.com", report.videoHost)
        assertTrue(report.baseline.getValue(StrategySelector.CONTROL).ok)
        assertFalse(report.baseline.getValue("yt_web").ok)
        // «-c» открывает два адреса, но ломает контрольный сайт — оценка низкая.
        assertTrue(report.results[2].score < 25)
        assertFalse(report.cancelled)
        // Адреса узнаются заранее: движку передаются IP, а не имена.
        assertTrue(report.targets.all { it.ip != null })
        assertTrue(report.unresolved.isEmpty())
    }

    /** Лучшая стратегия не открывает видео, другая открывает — подбор собирает объединённую, и она побеждает. */
    @Test
    fun combinesAStrategyForVideo() {
        val video = setOf(StrategySelector.VIDEO4, StrategySelector.VIDEO6)
        val combined = "-H:googlevideo.com -v -An -a"
        val backend = FakeBackend(opens = mapOf(
            "" to setOf(StrategySelector.CONTROL),
            "-a" to all,
            "-v" to video + StrategySelector.CONTROL + "yt_api",
            "-g -At -x" to all + video - "dc_gw" - "dc_web" - "yt_web" - "yt_img" - "tg_cf", // с группами — в объединённую не годится
            combined to all + video - StrategySelector.REDIRECTOR,
        ))
        val cands = listOf(Candidate("a", "a", "-a"), Candidate("g", "g", "-g -At -x"), Candidate("v", "v", "-v"))
        val report = StrategySelector(backend, cands, StrategySelector.defaultTargets("tg.example"), topCount = 1, extraRuns = 1).run()
        val first = report.results.first()
        assertEquals(StrategySelector.COMBINED, first.candidate.id)
        assertEquals(combined, first.candidate.args)
        assertEquals(2, first.runs)
        assertTrue(first.ok(StrategySelector.VIDEO4))
        assertTrue(first.score > report.results.first { it.candidate.id == "a" }.score)
    }

    @Test
    fun cancelStopsEarly() {
        val backend = FakeBackend(opens = mapOf("-a" to all))
        var calls = 0
        val report = StrategySelector(backend, listOf(Candidate("a", "a", "-a"), Candidate("b", "b", "-b")),
            isCancelled = { calls++ > 0 }).run()
        assertTrue(report.cancelled)
        assertEquals(listOf("a"), report.results.map { it.candidate.id })
    }

    /** Живая проверка: настоящие серверы отвечают ServerHello на наш ClientHello в обеих раскладках. */
    @Test
    fun liveServersAnswerOurClientHello() {
        assumeTrue(System.getenv("ZAPRETIFY_LIVE_TESTS") == "1")
        val probe = TlsProbe(null, 6000)
        // Сайты, которые обычно не блокируются: проверяется наш ClientHello, а не сеть.
        for (host in listOf("www.cloudflare.com", "github.com", "www.microsoft.com", "ya.ru")) {
            for (layout in ClientHello.Layout.entries) {
                assertEquals("$host $layout", Outcome.OK, probe.check(host, layout))
            }
        }
    }

    /** Живая проверка: Google отвечает именем кластера видео (в сети, где YouTube открыт без обхода). */
    @Test
    fun liveVideoClusterIsReported() {
        assumeTrue(System.getenv("ZAPRETIFY_LIVE_VIDEO") == "1")
        val cluster = VideoCluster.fetch(null, 8000)
        assertTrue("cluster=$cluster", cluster != null && cluster.length >= 3)
    }

    /**
     * Живая проверка через настоящий byedpi (собранный для компьютера и запущенный на порту ZAPRETIFY_SOCKS_PORT):
     * печатает, что открывается. Ничего не утверждает — это инструмент сверки с подбором по USB.
     */
    @Test
    fun liveThroughEngine() {
        val port = System.getenv("ZAPRETIFY_SOCKS_PORT")?.toIntOrNull()
        assumeTrue(port != null)
        val probe = TlsProbe(java.net.InetSocketAddress("127.0.0.1", port!!))
        for (t in StrategySelector.defaultTargets()) {
            val ip = runCatching { InetAddress.getAllByName(t.host).first() }.getOrNull()
            println("ENGINE ${t.id.padEnd(14)} " + ClientHello.Layout.entries.joinToString(" ") { "$it=${probe.check(t.host, it, ip)}" })
        }
    }
}
