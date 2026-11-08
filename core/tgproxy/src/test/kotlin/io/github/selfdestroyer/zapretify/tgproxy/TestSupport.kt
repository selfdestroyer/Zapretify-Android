package io.github.selfdestroyer.zapretify.tgproxy

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

const val TEST_SECRET_HEX = "b48c4c7fc559caa2ec22dd91a2a562a7"
val TEST_SECRET: ByteArray = TgProxyServer.hexToBytes(TEST_SECRET_HEX)

fun freePort(): Int = ServerSocket(0).use { it.localPort }

/** Клиентская сторона obfuscated2 (как clientObfuscated2 в Go-тесте): шлёт рукопожатие, отдаёт шифры клиента. */
internal class ClientCrypto(val enc: AesCtr, val dec: AesCtr, val handshake: ByteArray)

internal fun clientHandshake(secret: ByteArray, tag: ByteArray, dc: Int): ClientCrypto {
    val hs = ByteArray(64)
    while (true) {
        secureRandom.nextBytes(hs)
        if (hs[0] != 0xef.toByte() && hs[0] != 0x16.toByte() && !(hs[4] == 0.toByte() && hs[5] == 0.toByte() && hs[6] == 0.toByte() && hs[7] == 0.toByte())) break
    }
    tag.copyInto(hs, 56)
    hs[60] = (dc and 0xff).toByte(); hs[61] = ((dc shr 8) and 0xff).toByte()
    val enc = AesCtr(sha256(hs.copyOfRange(8, 40), secret), hs.copyOfRange(40, 56))
    val rev = hs.copyOfRange(8, 56).reversedCopy()
    val dec = AesCtr(sha256(rev.copyOfRange(0, 32), secret), rev.copyOfRange(32, 48))
    val encrypted = hs.copyOf().also { enc.xor(it) }
    encrypted.copyInto(hs, 56, 56, 64)
    return ClientCrypto(enc, dec, hs)
}

/** ClientHello, подписанный секретом (как clientFakeTLS в Go-тесте). [timestamp] можно сдвинуть для проверки. */
internal fun clientHello(secret: ByteArray, timestamp: Long = System.currentTimeMillis() / 1000): ByteArray {
    val body = java.io.ByteArrayOutputStream()
    body.write(byteArrayOf(0x01, 0, 0, 0, 0x03, 0x03))
    body.write(ByteArray(32))
    body.write(0x20)
    body.write(ByteArray(32).also { secureRandom.nextBytes(it) })
    body.write(byteArrayOf(0x00, 0x02, 0x13, 0x01, 0x01, 0x00))
    val b = body.toByteArray()
    val l = b.size - 4
    b[1] = (l shr 16).toByte(); b[2] = (l shr 8).toByte(); b[3] = l.toByte()
    val rec = byteArrayOf(0x16, 0x03, 0x01, (b.size shr 8).toByte(), b.size.toByte()) + b
    val mac = FakeTls.hmacSha256(secret, rec)
    mac.copyInto(rec, 11, 0, 28)
    for (i in 0 until 4) rec[11 + 28 + i] = (mac[28 + i].toInt() xor ((timestamp shr (8 * i)).toInt() and 0xff)).toByte()
    return rec
}

/** Клиент Fake TLS: шлёт ClientHello, пропускает ответ сервера, отдаёт потоки внутри записей TLS. */
internal fun fakeTlsClient(socket: Socket, secret: ByteArray): Pair<InputStream, OutputStream> {
    val out = socket.getOutputStream()
    val input = socket.getInputStream()
    out.write(clientHello(secret)); out.flush()
    val hdr = ByteArray(5); readFullyOrThrow(input, hdr)
    readFullyOrThrow(input, ByteArray(((hdr[3].toInt() and 0xff) shl 8) or (hdr[4].toInt() and 0xff)))
    val tlsIn = FakeTlsInputStream(input)
    checkNotNull(tlsIn.readPayload()) { "нет записи с паддингом" }
    return tlsIn to FakeTlsOutputStream(out)
}

/** Пакет intermediate: 4 байта длины (LE) и данные. */
internal fun intermediateFrame(payload: ByteArray): ByteArray {
    val n = payload.size
    return byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte()) + payload
}

internal fun readIntermediate(input: InputStream, dec: AesCtr): ByteArray {
    val hdr = ByteArray(4); readFullyOrThrow(input, hdr); dec.xor(hdr)
    val n = (hdr[0].toInt() and 0xff) or ((hdr[1].toInt() and 0xff) shl 8) or ((hdr[2].toInt() and 0xff) shl 16)
    val body = ByteArray(n); readFullyOrThrow(input, body); dec.xor(body)
    return body
}

/**
 * Поддельный DC Telegram по TCP: принимает relay init, проверяет в нём протокол и DC, затем отвечает на каждый
 * пакет intermediate тем же пакетом с префиксом "echo:". Так проверяется вся цепочка шифров прокси без интернета.
 */
internal class FakeTelegram : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort
    @Volatile var lastDc = 0
    @Volatile var lastTag = ByteArray(0)
    val connections = AtomicInteger()

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                connections.incrementAndGet()
                thread(isDaemon = true) { s.use { serve(it) } }
            }
        }
    }

    private fun serve(s: Socket) {
        val input = s.getInputStream(); val out = s.getOutputStream()
        val relay = ByteArray(64); if (!readFully(input, relay)) return
        val dec = AesCtr(relay.copyOfRange(8, 40), relay.copyOfRange(40, 56))
        val plain = relay.copyOf().also { dec.xor(it) }
        lastTag = plain.copyOfRange(56, 60)
        lastDc = ((plain[60].toInt() and 0xff) or ((plain[61].toInt() and 0xff) shl 8)).toShort().toInt()
        val rev = relay.copyOfRange(8, 56).reversedCopy()
        val enc = AesCtr(rev.copyOfRange(0, 32), rev.copyOfRange(32, 48))
        while (true) {
            val body = runCatching { readIntermediate(input, dec) }.getOrNull() ?: return
            val reply = intermediateFrame("echo:".toByteArray() + body)
            enc.xor(reply)
            out.write(reply); out.flush()
        }
    }

    override fun close() = server.close()
}

/** Простой SOCKS5 (без авторизации, только CONNECT): считает соединения, чтобы проверить, что прокси идёт через него. */
internal class MiniSocks5 : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val address: InetSocketAddress get() = InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort)
    val connects = AtomicInteger()

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { serve(s) }; runCatching { s.close() } }
            }
        }
    }

    private fun serve(s: Socket) {
        val input = s.getInputStream(); val out = s.getOutputStream()
        val greet = ByteArray(2); readFullyOrThrow(input, greet)
        readFullyOrThrow(input, ByteArray(greet[1].toInt()))
        out.write(byteArrayOf(5, 0)); out.flush()
        val req = ByteArray(4); readFullyOrThrow(input, req)
        val host = when (req[3].toInt()) {
            1 -> InetAddress.getByAddress(ByteArray(4).also { readFullyOrThrow(input, it) }).hostAddress
            3 -> String(ByteArray(input.read()).also { readFullyOrThrow(input, it) })
            else -> error("тип адреса ${req[3]}")
        }
        val p = ByteArray(2).also { readFullyOrThrow(input, it) }
        val port = ((p[0].toInt() and 0xff) shl 8) or (p[1].toInt() and 0xff)
        val remote = Socket(host, port)
        connects.incrementAndGet()
        out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
        val t = thread(isDaemon = true) { runCatching { remote.getInputStream().copyTo(out) }; runCatching { s.close() } }
        runCatching { input.copyTo(remote.getOutputStream()) }
        runCatching { remote.close() }
        t.join(2000)
    }

    override fun close() = server.close()
}

/** Сервер WebSocket для проверки клиента: шлёт ping, затем отвечает эхом на бинарные сообщения. */
internal class EchoWsServer : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort
    @Volatile var gotPong = false

    init {
        thread(isDaemon = true) {
            val s = runCatching { server.accept() }.getOrNull() ?: return@thread
            runCatching { serve(s) }
            runCatching { s.close() }
        }
    }

    private fun serve(s: Socket) {
        val input = s.getInputStream(); val out = s.getOutputStream()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
        val key = head.lines().first { it.startsWith("Sec-WebSocket-Key:") }.substringAfter(':').trim()
        val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
        out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\nSec-WebSocket-Protocol: binary\r\n\r\n".toByteArray())
        out.write(byteArrayOf(0x89.toByte(), 2, 'h'.code.toByte(), 'i'.code.toByte())) // ping
        out.flush()
        while (true) {
            val h = ByteArray(2); if (!readFully(input, h)) return
            val op = h[0].toInt() and 0x0f
            var len = h[1].toInt() and 0x7f
            if (len == 126) { val e = ByteArray(2); readFullyOrThrow(input, e); len = ((e[0].toInt() and 0xff) shl 8) or (e[1].toInt() and 0xff) }
            val mask = ByteArray(4); readFullyOrThrow(input, mask)
            val data = ByteArray(len); readFullyOrThrow(input, data)
            for (i in data.indices) data[i] = (data[i].toInt() xor mask[i and 3].toInt()).toByte()
            when (op) {
                0xA -> gotPong = String(data) == "hi"
                0x2 -> {
                    // Ответ двумя кадрами: бинарное начало и продолжение — клиент должен склеить их в поток.
                    val half = data.size / 2
                    out.write(byteArrayOf(0x02, half.toByte())); out.write(data, 0, half)
                    out.write(byteArrayOf(0x80.toByte(), (data.size - half).toByte())); out.write(data, half, data.size - half)
                    out.flush()
                }
                0x8 -> return
            }
        }
    }

    override fun close() = server.close()
}
