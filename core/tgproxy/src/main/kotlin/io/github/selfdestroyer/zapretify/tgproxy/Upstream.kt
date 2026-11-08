package io.github.selfdestroyer.zapretify.tgproxy

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Исходящие соединения прокси. Если задан [socks] (на Android — локальный SOCKS5 движка byedpi), все
 * соединения к Telegram и Cloudflare идут через него: так обходится блокировка пути через Cloudflare
 * по имени сайта (план, раздел 3.4, пункт 3). Без него — напрямую.
 */
internal class Upstream(private val socks: InetSocketAddress?) {

    fun connectTcp(host: String, port: Int, timeoutMs: Int): Socket {
        val socket = if (socks != null) Socket(Proxy(Proxy.Type.SOCKS, socks)) else Socket()
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            val target = when {
                // IP-адрес не резолвится и уходит в SOCKS как IP.
                isIpLiteral(host) -> InetSocketAddress(InetAddress.getByName(host), port)
                // Имя через SOCKS резолвит сам прокси (так обходится и подмена DNS провайдером).
                socks != null -> InetSocketAddress.createUnresolved(host, port)
                else -> InetSocketAddress(host, port)
            }
            socket.connect(target, timeoutMs)
            return socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    /**
     * TLS к [host]:[port] с SNI [sni]. Сертификат не проверяется — как InsecureSkipVerify в Go: Telegram и
     * Cloudflare-путь используются только как транспорт, данные MTProto зашифрованы сами по себе.
     */
    fun connectTls(host: String, port: Int, sni: String, timeoutMs: Int): SSLSocket {
        val plain = connectTcp(host, port, timeoutMs)
        try {
            val ssl = trustAll.socketFactory.createSocket(plain, sni, port, true) as SSLSocket
            ssl.sslParameters = ssl.sslParameters.apply { serverNames = listOf(SNIHostName(sni)) }
            ssl.soTimeout = timeoutMs
            ssl.startHandshake()
            return ssl
        } catch (e: Exception) {
            runCatching { plain.close() }
            throw e
        }
    }

    companion object {
        private val trustAll: SSLContext by lazy {
            SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }), secureRandom)
            }
        }

        private val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
        fun isIpLiteral(host: String) = ipv4.matches(host) || host.contains(':')
    }
}
