package io.github.selfdestroyer.zapretify.tgproxy

/**
 * Ссылка для добавления прокси в Telegram, как в Go-версии (core/tgproxy/server.go, GetProxyLink):
 * tg://proxy?server=<host>&port=<port>&secret=dd<secret>. Пустой адрес и 0.0.0.0 заменяются на 127.0.0.1.
 */
object ProxyLink {
    private val secretRe = Regex("^[0-9a-f]{32}$")

    fun build(host: String, port: Int, secret: String): String {
        require(port in 1..65535) { "порт вне диапазона: $port" }
        require(secretRe.matches(secret)) { "секрет — 32 hex-символа в нижнем регистре" }
        val h = if (host.isEmpty() || host == "0.0.0.0") "127.0.0.1" else host
        return "tg://proxy?server=$h&port=$port&secret=dd$secret"
    }

    /** Ссылка Fake TLS (fakeTLSConnectLink в Go): secret=ee<секрет><домен маскировки в hex>. */
    fun buildFakeTls(host: String, port: Int, secret: String, domain: String): String {
        require(secretRe.matches(secret)) { "секрет — 32 hex-символа в нижнем регистре" }
        val hexDomain = domain.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
        return "tg://proxy?server=$host&port=$port&secret=ee$secret$hexDomain"
    }
}
