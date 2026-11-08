package io.github.selfdestroyer.zapretify.tgproxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.Collections

/**
 * Вся цепочка без интернета: клиент Telegram → прокси → «поддельный DC» по TCP и обратно. Проверяет
 * шифры в обе стороны, relay init, Fake TLS и выход через SOCKS5 (как через byedpi на Android).
 */
class ProxyEndToEndTest {
    private fun run(fakeTls: Boolean, viaSocks: Boolean, tag: ByteArray, dc: Int) {
        FakeTelegram().use { tg ->
            MiniSocks5().use { socks ->
                val logs = Collections.synchronizedList(mutableListOf<String>())
                val server = TgProxyServer { logs += it }
                val port = freePort()
                server.start(ProxyConfig(
                    secretHex = TEST_SECRET_HEX, port = port, cfProxy = false, webSocket = false, poolSize = 0,
                    dcIps = mapOf(kotlin.math.abs(dc) to "127.0.0.1"), telegramPort = tg.port,
                    socks = if (viaSocks) socks.address else null,
                ))
                try {
                    Socket(InetAddress.getLoopbackAddress(), port).use { s ->
                        s.soTimeout = 5000
                        val (input, output) = if (fakeTls) fakeTlsClient(s, TEST_SECRET) else s.getInputStream() to s.getOutputStream()
                        val c = clientHandshake(TEST_SECRET, tag, dc)
                        output.write(c.handshake); output.flush()
                        for (i in 1..3) roundTrip(input, output, c, "message $i".toByteArray())
                        roundTrip(input, output, c, ByteArray(100_000) { (it % 253).toByte() })
                    }
                    assertEquals(dc, tg.lastDc)
                    assertArrayEquals(tag, tg.lastTag)
                    assertEquals(if (viaSocks) 1 else 0, socks.connects.get())
                    assertTrue(logs.toString(), logs.any { "TCP fallback" in it })
                } finally {
                    server.stop()
                }
            }
        }
    }

    private fun roundTrip(input: InputStream, output: OutputStream, c: ClientCrypto, payload: ByteArray) {
        val frame = intermediateFrame(payload).also { c.enc.xor(it) }
        output.write(frame); output.flush()
        assertArrayEquals("echo:".toByteArray() + payload, readIntermediate(input, c.dec))
    }

    @Test fun ddDirect() = run(fakeTls = false, viaSocks = false, tag = Const.TAG_SECURE, dc = 2)
    @Test fun intermediateMediaDc() = run(fakeTls = false, viaSocks = false, tag = Const.TAG_INTERMEDIATE, dc = -4)
    @Test fun fakeTls() = run(fakeTls = true, viaSocks = false, tag = Const.TAG_INTERMEDIATE, dc = 2)
    @Test fun throughSocks5() = run(fakeTls = false, viaSocks = true, tag = Const.TAG_SECURE, dc = 2)
    @Test fun fakeTlsThroughSocks5() = run(fakeTls = true, viaSocks = true, tag = Const.TAG_SECURE, dc = 5)

    @Test
    fun foreignClientIsDropped() {
        val server = TgProxyServer()
        val port = freePort()
        server.start(ProxyConfig(secretHex = TEST_SECRET_HEX, port = port, cfProxy = false, webSocket = false, poolSize = 0))
        try {
            Socket(InetAddress.getLoopbackAddress(), port).use { s ->
                s.soTimeout = 5000
                s.getOutputStream().write(clientHandshake(ByteArray(16) { 1 }, Const.TAG_SECURE, 2).handshake)
                assertEquals(-1, s.getInputStream().read())
            }
            assertEquals(1L, server.stats.connectionsBad.get())
        } finally {
            server.stop()
        }
    }

    @Test
    fun cfBlockedAfterFourFailures() {
        val st = ProxyState({})
        repeat(3) { st.noteCfFailure() }
        assertEquals(false, st.cfProxyBlocked())
        st.noteCfFailure()
        assertEquals(true, st.cfProxyBlocked())
        st.telegramReachable()
        assertEquals(false, st.cfProxyBlocked())
    }
}
