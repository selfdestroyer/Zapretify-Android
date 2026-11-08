package io.github.selfdestroyer.zapretify.tgproxy

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket

/**
 * Живой тест с настоящим Telegram (как TestLiveTelegramRoundTrip в Go): клиент подключается к прокси и просит
 * у DC 2 req_pq_multi. Ответ resPQ значит, что работает вся цепочка — прокси, WebSocket или запасной путь,
 * Telegram и обратно. Нужен интернет; запуск: ZAPRETIFY_LIVE_TESTS=1 ./gradlew :core:tgproxy:test --tests '*Live*'
 * Через SOCKS5 движка: дополнительно ZAPRETIFY_LIVE_SOCKS=127.0.0.1:1080.
 */
class LiveTelegramTest {
    private fun live(fakeTls: Boolean, tag: ByteArray) {
        assumeTrue("задайте ZAPRETIFY_LIVE_TESTS=1", System.getenv("ZAPRETIFY_LIVE_TESTS") != null)
        val socks = System.getenv("ZAPRETIFY_LIVE_SOCKS")?.split(':')?.let { java.net.InetSocketAddress(it[0], it[1].toInt()) }
        val server = TgProxyServer { println(it) }
        val port = freePort()
        server.start(ProxyConfig(secretHex = TEST_SECRET_HEX, port = port, socks = socks))
        try {
            Socket(InetAddress.getLoopbackAddress(), port).use { s ->
                s.soTimeout = 30_000
                val (input, output) = if (fakeTls) fakeTlsClient(s, TEST_SECRET) else s.getInputStream() to s.getOutputStream()
                val start = System.currentTimeMillis()
                val c = clientHandshake(TEST_SECRET, tag, 2)
                output.write(c.handshake)
                // Нешифрованное сообщение MTProto: auth_key_id 0, message_id, длина, req_pq_multi#be7e8ef1 + nonce.
                val msg = ByteArray(40)
                val msgId = (System.currentTimeMillis() / 1000) shl 32
                for (i in 0 until 8) msg[8 + i] = (msgId shr (8 * i)).toByte()
                msg[16] = 20
                byteArrayOf(0xf1.toByte(), 0x8e.toByte(), 0x7e, 0xbe.toByte()).copyInto(msg, 20)
                secureRandom.nextBytes(ByteArray(16).also { it.copyInto(msg, 24) })
                val frame = intermediateFrame(msg).also { c.enc.xor(it) }
                output.write(frame); output.flush()
                val body = readIntermediate(input, c.dec)
                val constructor = (body[20].toInt() and 0xff) or ((body[21].toInt() and 0xff) shl 8) or
                    ((body[22].toInt() and 0xff) shl 16) or ((body[23].toInt() and 0xff) shl 24)
                assertEquals("ожидался resPQ", 0x05162463, constructor)
                println("resPQ от Telegram DC 2 за ${System.currentTimeMillis() - start} мс; ${server.stats.connectionsWs.get()} WS, ${server.stats.connectionsCf.get()} CF, ${server.stats.connectionsTcp.get()} TCP")
            }
        } finally {
            server.stop()
        }
    }

    @Test fun ddLink() = live(fakeTls = false, tag = Const.TAG_SECURE)
    @Test fun obfuscatedIntermediate() = live(fakeTls = false, tag = Const.TAG_INTERMEDIATE)
    @Test fun fakeTlsEe() = live(fakeTls = true, tag = Const.TAG_INTERMEDIATE)
}
