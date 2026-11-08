package io.github.selfdestroyer.zapretify.tgproxy

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Режим Fake TLS (ссылки ee…): клиент шлёт ClientHello, подписанный секретом, прокси отвечает ServerHello,
 * дальше MTProto идёт внутри записей TLS application data. Как core/tgproxy/fake_tls.go.
 */
internal object FakeTls {
    const val RECORD_CCS = 0x14
    const val RECORD_HANDSHAKE = 0x16
    const val RECORD_APP_DATA = 0x17
    const val CLIENT_RANDOM_OFFSET = 11
    const val CLIENT_RANDOM_LEN = 32
    const val SESSION_ID_OFFSET = 44
    const val SESSION_ID_LEN = 32
    const val RECORD_MAX_LEN = 16384
    const val TIMESTAMP_TOLERANCE_S = 120L

    val CCS_FRAME = byteArrayOf(0x14, 0x03, 0x03, 0x00, 0x01, 0x01)

    /**
     * ServerHello: заголовок записи, заголовок handshake, версия, случайная часть 32 байта (смещение 11, туда
     * пишется HMAC), session id (смещение 44), TLS_AES_128_GCM_SHA256, key_share x25519 (ключ с 89), TLS 1.3.
     * Случайная часть — именно 32 байта: в Go до 1.5.0 их было 28, и длины записи не сходились с байтами.
     */
    fun serverHelloTemplate(): ByteArray {
        val t = ByteArray(127)
        byteArrayOf(0x16, 0x03, 0x03, 0x00, 0x7a, 0x02, 0x00, 0x00, 0x76, 0x03, 0x03).copyInto(t, 0)
        t[43] = 0x20
        byteArrayOf(0x13, 0x01, 0x00, 0x00, 0x2e, 0x00, 0x33, 0x00, 0x24, 0x00, 0x1d, 0x00, 0x20).copyInto(t, 76)
        byteArrayOf(0x00, 0x2b, 0x00, 0x02, 0x03, 0x04).copyInto(t, 121)
        return t
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    class ClientHello(val clientRandom: ByteArray, val sessionId: ByteArray)

    /** Проверяет подпись ClientHello и метку времени (±120 с). null — не наш клиент. */
    fun verifyClientHello(data: ByteArray, secret: ByteArray, nowSec: Long = System.currentTimeMillis() / 1000): ClientHello? {
        if (data.size < 43) return null
        if ((data[0].toInt() and 0xff) != RECORD_HANDSHAKE || data[5].toInt() != 0x01) return null
        val clientRandom = data.copyOfRange(CLIENT_RANDOM_OFFSET, CLIENT_RANDOM_OFFSET + CLIENT_RANDOM_LEN)
        val zeroed = data.copyOf().also { it.fill(0, CLIENT_RANDOM_OFFSET, CLIENT_RANDOM_OFFSET + CLIENT_RANDOM_LEN) }
        val expected = hmacSha256(secret, zeroed)
        if (!MessageDigest.isEqual(expected.copyOfRange(0, 28), clientRandom.copyOfRange(0, 28))) return null

        var ts = 0L
        for (i in 0 until 4) ts = ts or (((clientRandom[28 + i].toInt() xor expected[28 + i].toInt()) and 0xff).toLong() shl (8 * i))
        if (nowSec - ts > TIMESTAMP_TOLERANCE_S || ts - nowSec > TIMESTAMP_TOLERANCE_S) return null

        val sessionId = ByteArray(SESSION_ID_LEN)
        if (data.size >= SESSION_ID_OFFSET + SESSION_ID_LEN && data[43].toInt() == 0x20) {
            data.copyInto(sessionId, 0, SESSION_ID_OFFSET, SESSION_ID_OFFSET + SESSION_ID_LEN)
        }
        return ClientHello(clientRandom, sessionId)
    }

    /** Ответ клиенту: ServerHello, ChangeCipherSpec и запись application data из случайных байт (1900–2100). */
    fun buildServerHello(secret: ByteArray, clientRandom: ByteArray, sessionId: ByteArray): ByteArray {
        val sh = serverHelloTemplate()
        sessionId.copyInto(sh, SESSION_ID_OFFSET, 0, SESSION_ID_LEN)
        ByteArray(32).also { secureRandom.nextBytes(it) }.copyInto(sh, 89)

        val encSize = 1900 + secureRandom.nextInt(201)
        val appRecord = ByteArray(5 + encSize)
        appRecord[0] = RECORD_APP_DATA.toByte(); appRecord[1] = 0x03; appRecord[2] = 0x03
        appRecord[3] = (encSize shr 8).toByte(); appRecord[4] = encSize.toByte()
        ByteArray(encSize).also { secureRandom.nextBytes(it) }.copyInto(appRecord, 5)

        val response = sh + CCS_FRAME + appRecord
        val serverRandom = hmacSha256(secret, clientRandom + response)
        serverRandom.copyInto(response, CLIENT_RANDOM_OFFSET, 0, 32)
        return response
    }

    /** Режет данные на записи application data не длиннее 16 КБ. */
    fun wrapRecords(data: ByteArray, off: Int = 0, len: Int = data.size - off): ByteArray {
        if (len <= 0) return ByteArray(0)
        val out = java.io.ByteArrayOutputStream(len + (len / RECORD_MAX_LEN + 1) * 5)
        var pos = off
        val end = off + len
        while (pos < end) {
            val n = minOf(RECORD_MAX_LEN, end - pos)
            out.write(RECORD_APP_DATA); out.write(0x03); out.write(0x03); out.write(n shr 8); out.write(n and 0xff)
            out.write(data, pos, n)
            pos += n
        }
        return out.toByteArray()
    }
}

/** Читает MTProto из записей TLS: ChangeCipherSpec пропускает, любая запись кроме application data — конец. */
internal class FakeTlsInputStream(private val raw: InputStream) : InputStream() {
    private var buf = ByteArray(0)
    private var pos = 0

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (pos >= buf.size) {
            val payload = readPayload() ?: return -1
            buf = payload; pos = 0
        }
        val n = minOf(len, buf.size - pos)
        buf.copyInto(b, off, pos, pos + n)
        pos += n
        return n
    }

    /** Следующая непустая запись application data; null — конец потока. */
    fun readPayload(): ByteArray? {
        while (true) {
            val hdr = ByteArray(5)
            if (!readFully(raw, hdr)) return null
            val type = hdr[0].toInt() and 0xff
            val len = ((hdr[3].toInt() and 0xff) shl 8) or (hdr[4].toInt() and 0xff)
            if (type == FakeTls.RECORD_CCS) {
                if (len > 0 && !readFully(raw, ByteArray(len))) return null
                continue
            }
            if (type != FakeTls.RECORD_APP_DATA) return null
            if (len == 0) continue
            val data = ByteArray(len)
            if (!readFully(raw, data)) return null
            return data
        }
    }

    override fun close() = raw.close()
}

/** Пишет данные записями TLS application data. */
internal class FakeTlsOutputStream(private val raw: OutputStream) : OutputStream() {
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        raw.write(FakeTls.wrapRecords(b, off, len))
    }
    override fun flush() = raw.flush()
    override fun close() = raw.close()
}

/** Читает ровно [buf].size байт; false — поток закончился раньше. */
internal fun readFully(input: InputStream, buf: ByteArray, off: Int = 0, len: Int = buf.size - off): Boolean {
    var done = 0
    while (done < len) {
        val n = input.read(buf, off + done, len - done)
        if (n < 0) return false
        done += n
    }
    return true
}

internal fun readFullyOrThrow(input: InputStream, buf: ByteArray) {
    if (!readFully(input, buf)) throw EOFException()
}
