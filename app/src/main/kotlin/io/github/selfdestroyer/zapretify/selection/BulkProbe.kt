package io.github.selfdestroyer.zapretify.selection

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Проверка «данные идут дальше первых килобайт». Часть провайдеров пропускает начало соединения с Cloudflare
 * (около 16 КБ), если не может прочитать имя сайта, а потом замораживает его. Рукопожатие при этом проходит,
 * и обычная проверка видит «работает», а Discord не загружает каналы (замер дома, 11.11.2026).
 * Скачивает [bytes] байт с тестового адреса Cloudflare через SOCKS5 движка: дошло всё — OK, остановилось — TIMEOUT.
 */
class BulkProbe(private val socks: InetSocketAddress?, private val timeoutMs: Int = 7000) {
    fun check(host: String, path: String, ip: InetAddress?, bytes: Int): Outcome {
        val raw = Socket()
        return try {
            if (socks == null) raw.connect(InetSocketAddress(ip ?: InetAddress.getByName(host), 443), 4000)
            else {
                raw.connect(socks, 4000)
                raw.soTimeout = 4000
                try { TlsProbe.socks5Connect(raw, host, ip) } catch (_: Exception) { return Outcome.NO_TCP }
            }
            raw.soTimeout = timeoutMs
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, 443, true) as SSLSocket
            tls.use {
                it.startHandshake()
                it.outputStream.write("GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: Zapretify\r\nConnection: close\r\n\r\n".toByteArray())
                it.outputStream.flush()
                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(16 * 1024)
                var got = 0
                while (got < bytes && System.currentTimeMillis() < deadline) {
                    val n = try { it.inputStream.read(buf) } catch (_: java.net.SocketTimeoutException) { return Outcome.TIMEOUT }
                    if (n < 0) break
                    got += n
                }
                if (got >= bytes) Outcome.OK else Outcome.TIMEOUT
            }
        } catch (_: java.net.SocketTimeoutException) {
            Outcome.TIMEOUT
        } catch (_: java.net.SocketException) {
            Outcome.RESET
        } catch (_: Exception) {
            Outcome.BROKEN
        } finally {
            runCatching { raw.close() }
        }
    }
}
