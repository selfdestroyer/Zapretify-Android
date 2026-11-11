package io.github.selfdestroyer.zapretify.selection

import java.io.EOFException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException

/** Чем закончилась проверка одного адреса. */
enum class Outcome {
    /** Сервер ответил на ClientHello — обход работает. */
    OK,
    /** Не удалось даже установить TCP-соединение: сервер недоступен или закрыт по IP-адресу. */
    NO_TCP,
    /** Соединение есть, но ответа на ClientHello нет — блокировка по имени сайта (пакеты отбрасываются). */
    TIMEOUT,
    /** Соединение сброшено после ClientHello — блокировка по имени сайта (сброс). */
    RESET,
    /** Пришёл не ответ сервера (TLS-оповещение или мусор): стратегия испортила ClientHello. */
    BROKEN,
}

/**
 * Проверка «открывается ли сайт» через SOCKS5 движка: соединение, большой ClientHello ([ClientHello]), ожидание
 * ServerHello. [socks] = null — напрямую, без движка.
 */
class TlsProbe(
    private val socks: InetSocketAddress?,
    /** Сколько ждать ответ сервера на ClientHello. */
    private val timeoutMs: Int = 4000,
    /** Сколько ждать TCP-соединение (у движка — ответ SOCKS5, он приходит после соединения с сервером). */
    private val connectTimeoutMs: Int = 3000,
) {

    /**
     * @param host имя сайта (SNI)
     * @param ip если задан — соединяться с этим адресом, а не разрешать имя (проверка IPv4 и IPv6 по отдельности)
     */
    fun check(host: String, layout: ClientHello.Layout, ip: InetAddress? = null): Outcome {
        val socket = try {
            connect(host, ip)
        } catch (_: Exception) {
            return Outcome.NO_TCP
        }
        return socket.use { s ->
            try {
                s.soTimeout = timeoutMs
                s.getOutputStream().apply { write(ClientHello.build(host, layout)); flush() }
                if (isServerHello(s.getInputStream())) Outcome.OK else Outcome.BROKEN
            } catch (_: SocketTimeoutException) {
                Outcome.TIMEOUT
            } catch (_: EOFException) {
                Outcome.RESET
            } catch (_: SocketException) {
                Outcome.RESET
            } catch (_: Exception) {
                Outcome.BROKEN
            }
        }
    }

    private fun connect(host: String, ip: InetAddress?): Socket {
        val s = Socket()
        try {
            if (socks == null) {
                s.connect(if (ip != null) InetSocketAddress(ip, 443) else InetSocketAddress(host, 443), connectTimeoutMs)
            } else {
                s.connect(socks, connectTimeoutMs)
                s.soTimeout = connectTimeoutMs
                socks5Connect(s, host, ip)
            }
            return s
        } catch (e: Exception) {
            runCatching { s.close() }
            throw e
        }
    }

    internal companion object {
        /** SOCKS5 CONNECT на порт 443: по имени (имя разрешает движок) или по IP-адресу. */
        fun socks5Connect(s: Socket, host: String, ip: InetAddress?) {
            val out = s.getOutputStream()
            val inp = s.getInputStream()
            out.write(byteArrayOf(5, 1, 0)); out.flush()
            val greet = inp.readExactly(2)
            if (greet[0].toInt() != 5 || greet[1].toInt() != 0) throw SocketException("SOCKS5: отказ")
            val addr = when {
                ip is Inet4Address -> byteArrayOf(1) + ip.address
                ip != null -> byteArrayOf(4) + ip.address
                else -> host.toByteArray(Charsets.US_ASCII).let { byteArrayOf(3, it.size.toByte()) + it }
            }
            out.write(byteArrayOf(5, 1, 0) + addr + byteArrayOf(0x01, 0xbb.toByte())); out.flush()
            val head = inp.readExactly(4)
            if (head[1].toInt() != 0) throw SocketException("SOCKS5: код ${head[1]}")
            val rest = when (head[3].toInt()) {
                1 -> 4 + 2
                4 -> 16 + 2
                3 -> (inp.readExactly(1)[0].toInt() and 0xff) + 2
                else -> throw SocketException("SOCKS5: неизвестный тип адреса")
            }
            inp.readExactly(rest)
        }

        /** Первая TLS-запись ответа — рукопожатие с ServerHello (в том числе HelloRetryRequest: это тоже ответ сервера). */
        fun isServerHello(inp: InputStream): Boolean {
            val header = inp.readExactly(5)
            val length = ((header[3].toInt() and 0xff) shl 8) or (header[4].toInt() and 0xff)
            if (header[0].toInt() != 0x16 || header[1].toInt() != 0x03 || length < 4) return false
            return inp.readExactly(1)[0].toInt() == 0x02
        }

        fun InputStream.readExactly(n: Int): ByteArray {
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = read(buf, off, n - off)
                if (r < 0) throw EOFException()
                off += r
            }
            return buf
        }
    }
}
