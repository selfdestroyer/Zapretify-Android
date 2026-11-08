package io.github.selfdestroyer.zapretify.tgproxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket

class WebSocketTest {
    /** Рукопожатие, маскированные кадры от клиента, ответ на ping и склейка сообщения из двух кадров. */
    @Test
    fun echoWithPingAndContinuation() {
        EchoWsServer().use { server ->
            val ws = WsConn.open(Socket(InetAddress.getLoopbackAddress(), server.port), "kws2.web.telegram.org", "/apiws", 3000)
            ws.setTimeout(3000)
            val msg = ByteArray(100) { it.toByte() }
            ws.writeBinary(msg)
            val got = ByteArrayOutputStream()
            val buf = ByteArray(64)
            while (got.size() < msg.size) {
                val n = ws.read(buf)
                if (n < 0) break
                got.write(buf, 0, n)
            }
            assertArrayEquals(msg, got.toByteArray())
            assertTrue("на ping нет pong", server.gotPong)
            ws.close()
        }
    }
}
