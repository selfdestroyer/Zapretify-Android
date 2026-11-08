package io.github.selfdestroyer.zapretify.tgproxy

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Base64

/** Сервер ответил не 101: [status] нужен, чтобы отличить редирект (Telegram так блокирует DC) от прочих ошибок. */
internal class WsHandshakeException(val status: Int, message: String) : IOException(message)

/**
 * Минимальный клиент WebSocket (RFC 6455) поверх готового сокета: только то, что нужно мосту к Telegram —
 * бинарные сообщения, ping/pong, close. Свой, а не из библиотеки, чтобы подключаться к заданному IP с нужным
 * SNI и через SOCKS5. Запись потокобезопасна (pong из потока чтения и данные из потока записи).
 */
internal class WsConn private constructor(private val socket: Socket, private val input: InputStream) {
    private val output: OutputStream = socket.getOutputStream()
    private val writeLock = Any()

    // Состояние чтения текущего кадра.
    private var frameRemaining = 0L
    private var frameMask: ByteArray? = null
    private var maskPos = 0
    private var messageIsBinary = false
    private var closed = false

    fun setTimeout(ms: Int) { socket.soTimeout = ms }

    fun writeBinary(data: ByteArray, off: Int = 0, len: Int = data.size - off) = writeFrame(0x2, data, off, len)

    /**
     * Читает полезную нагрузку бинарных сообщений в [buf]. Границы сообщений не важны: всё идёт одним потоком
     * клиенту. -1 — соединение закрыто.
     */
    fun read(buf: ByteArray): Int {
        while (true) {
            if (closed) return -1
            if (frameRemaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), frameRemaining).toInt())
                if (n < 0) { closed = true; return -1 }
                frameRemaining -= n
                frameMask?.let { m -> for (i in 0 until n) { buf[i] = (buf[i].toInt() xor m[maskPos and 3].toInt()).toByte(); maskPos++ } }
                if (messageIsBinary) return n
                continue
            }
            readFrameHeader() ?: return -1
        }
    }

    /** Разбирает заголовок; управляющие кадры обрабатывает сам. null — конец соединения. */
    private fun readFrameHeader(): Unit? {
        val h = ByteArray(2)
        if (!readFully(input, h)) { closed = true; return null }
        val opcode = h[0].toInt() and 0x0f
        val masked = (h[1].toInt() and 0x80) != 0
        var len = (h[1].toInt() and 0x7f).toLong()
        if (len == 126L) {
            val e = ByteArray(2); if (!readFully(input, e)) { closed = true; return null }
            len = (((e[0].toInt() and 0xff) shl 8) or (e[1].toInt() and 0xff)).toLong()
        } else if (len == 127L) {
            val e = ByteArray(8); if (!readFully(input, e)) { closed = true; return null }
            len = 0; for (b in e) len = (len shl 8) or (b.toLong() and 0xff)
        }
        val mask = if (masked) ByteArray(4).also { if (!readFully(input, it)) { closed = true; return null } } else null

        when (opcode) {
            0x8 -> { // close
                runCatching { writeFrame(0x8, ByteArray(0), 0, 0) }
                closed = true
                return null
            }
            0x9, 0xA -> { // ping / pong: читаем целиком, на ping отвечаем pong
                val payload = ByteArray(len.toInt())
                if (!readFully(input, payload)) { closed = true; return null }
                mask?.let { m -> for (i in payload.indices) payload[i] = (payload[i].toInt() xor m[i and 3].toInt()).toByte() }
                if (opcode == 0x9) writeFrame(0xA, payload, 0, payload.size)
            }
            else -> { // 0x0 продолжение, 0x1 текст, 0x2 бинарное
                if (opcode != 0x0) messageIsBinary = opcode == 0x2
                frameRemaining = len
                frameMask = mask
                maskPos = 0
            }
        }
        return Unit
    }

    private fun writeFrame(opcode: Int, data: ByteArray, off: Int, len: Int) {
        val header = java.io.ByteArrayOutputStream(14)
        header.write(0x80 or opcode)
        when {
            len < 126 -> header.write(0x80 or len)
            len <= 0xffff -> { header.write(0x80 or 126); header.write(len shr 8); header.write(len and 0xff) }
            else -> { header.write(0x80 or 127); for (i in 7 downTo 0) header.write(((len.toLong() shr (8 * i)) and 0xff).toInt()) }
        }
        val mask = ByteArray(4).also { secureRandom.nextBytes(it) }
        header.write(mask)
        val payload = ByteArray(len)
        for (i in 0 until len) payload[i] = (data[off + i].toInt() xor mask[i and 3].toInt()).toByte()
        synchronized(writeLock) {
            output.write(header.toByteArray())
            output.write(payload)
            output.flush()
        }
    }

    fun close() { closed = true; runCatching { socket.close() } }

    companion object {
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        /**
         * Рукопожатие WebSocket по [socket]: GET [path] с Host [host], подпротокол binary, Origin web.telegram.org.
         * Тайм-аут рукопожатия — [timeoutMs]; после него чтение без тайм-аута (неактивность ловит мост).
         */
        fun open(socket: Socket, host: String, path: String, timeoutMs: Int): WsConn {
            try {
                socket.soTimeout = timeoutMs
                val key = Base64.getEncoder().encodeToString(ByteArray(16).also { secureRandom.nextBytes(it) })
                val req = "GET $path HTTP/1.1\r\n" +
                    "Host: $host\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "Sec-WebSocket-Protocol: binary\r\n" +
                    "Origin: https://web.telegram.org\r\n\r\n"
                socket.getOutputStream().apply { write(req.toByteArray(Charsets.US_ASCII)); flush() }

                val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
                val head = readHttpHead(input)
                val lines = head.split("\r\n")
                val status = lines.first().split(' ').getOrNull(1)?.toIntOrNull() ?: 0
                if (status != 101) throw WsHandshakeException(status, "WebSocket: ответ $status")
                val accept = lines.drop(1).firstOrNull { it.startsWith("sec-websocket-accept:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()
                val expected = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII)))
                if (accept != expected) throw WsHandshakeException(status, "WebSocket: неверный Sec-WebSocket-Accept")
                socket.soTimeout = 0
                return WsConn(socket, input)
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw e
            }
        }

        private fun readHttpHead(input: InputStream): String {
            val out = java.io.ByteArrayOutputStream()
            var matched = 0
            val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
            while (matched < 4) {
                val b = input.read()
                if (b < 0) throw IOException("WebSocket: соединение закрыто во время рукопожатия")
                out.write(b)
                matched = if (b.toByte() == end[matched]) matched + 1 else if (b.toByte() == end[0]) 1 else 0
                if (out.size() > 16 * 1024) throw IOException("WebSocket: слишком длинный ответ")
            }
            return String(out.toByteArray(), Charsets.ISO_8859_1).trimEnd()
        }

        fun isTimeout(e: Throwable): Boolean = e is SocketTimeoutException || e.cause is SocketTimeoutException
    }
}
