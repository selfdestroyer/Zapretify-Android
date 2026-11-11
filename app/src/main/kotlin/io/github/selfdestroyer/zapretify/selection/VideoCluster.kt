package io.github.selfdestroyer.zapretify.selection

import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Настоящий сервер видео YouTube для сети пользователя.
 *
 * Google сам говорит, какой кластер видео назначен адресу, с которого пришёл запрос:
 * `https://redirector.googlevideo.com/report_mapping?di=no` → строка «адрес => имя-кластера (подсеть)».
 * Имя сервера получается из имени кластера простой заменой символов (см. [hostFor]) — выведено из собственных
 * замеров 10.11.2026. Чужие серверы видео из готовых списков о видео в сети пользователя ничего не говорят.
 */
object VideoCluster {
    private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"
    private val mappingLine = Regex("""=>\s*([a-z0-9-]+)""")

    /** Имя сервера видео для кластера: каждый символ с номером i в [ALPHABET] меняется на символ с номером (7·i + 7) mod 36. */
    fun hostFor(cluster: String): String {
        val coded = cluster.lowercase().map { c ->
            val i = ALPHABET.indexOf(c)
            if (i < 0) c else ALPHABET[(7 * i + 7) % 36]
        }.joinToString("")
        return "rr1---sn-$coded.googlevideo.com"
    }

    /** Имя кластера из ответа report_mapping; null, если ответ не похож на ожидаемый. */
    fun parseCluster(response: String): String? =
        response.lineSequence().firstNotNullOfOrNull { mappingLine.find(it)?.groupValues?.get(1) }?.takeIf { it.length in 3..40 }

    /**
     * Спрашивает кластер через SOCKS5 движка обычным HTTPS-запросом (с проверкой сертификата).
     * Вызывать, когда стратегия уже открывает redirector.googlevideo.com. null — не получилось.
     */
    fun discover(socks: InetSocketAddress?, ip: java.net.InetAddress? = null, timeoutMs: Int = 5000, onError: (Throwable) -> Unit = {}): String? =
        runCatching { fetch(socks, timeoutMs, ip) }.onFailure(onError).getOrNull()

    /** [socks] = null — напрямую (для проверки без движка). */
    internal fun fetch(socks: InetSocketAddress?, timeoutMs: Int, ip: java.net.InetAddress? = null): String? {
        val host = "redirector.googlevideo.com"
        Socket().use { raw ->
            if (socks == null) raw.connect(InetSocketAddress(host, 443), timeoutMs)
            else {
                raw.connect(socks, timeoutMs)
                raw.soTimeout = timeoutMs
                TlsProbe.socks5Connect(raw, host, ip)
            }
            raw.soTimeout = timeoutMs
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, 443, true) as SSLSocket
            tls.use {
                it.startHandshake()
                it.getOutputStream().apply {
                    write("GET /report_mapping?di=no HTTP/1.1\r\nHost: $host\r\nUser-Agent: Zapretify\r\nAccept: */*\r\nConnection: close\r\n\r\n".toByteArray())
                    flush()
                }
                val text = it.getInputStream().readUpTo(4096).toString(Charsets.ISO_8859_1)
                if (!text.startsWith("HTTP/1.1 200")) throw java.io.IOException("ответ: ${text.take(60)}")
                return parseCluster(text.substringAfter("\r\n\r\n", ""))
            }
        }
    }

    private fun java.io.InputStream.readUpTo(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(2048)
        while (out.size() < limit) {
            val r = try { read(buf) } catch (_: Exception) { -1 }
            if (r < 0) break
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }
}
