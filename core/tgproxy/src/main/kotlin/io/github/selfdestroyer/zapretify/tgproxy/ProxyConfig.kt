package io.github.selfdestroyer.zapretify.tgproxy

import java.net.InetSocketAddress

/**
 * Настройки Telegram-прокси. По умолчанию — как в десктопном Zapretify: слушает 127.0.0.1:1443, запасной
 * путь через Cloudflare включён.
 *
 * @property secretHex секрет из 32 hex-символов; префикс dd/ee отбрасывается.
 * @property socks локальный SOCKS5 движка обхода: через него идут все исходящие соединения (план, 3.4.3).
 * @property cfProxy пробовать путь через Cloudflare, если прямой WebSocket к Telegram не открылся.
 * @property cfUserDomains дополнительные базовые домены Cloudflare-пути.
 * @property cfWorkers свои Cloudflare Worker (wss://<worker>/apiws?dc=…&dst=…), пробуются первыми.
 */
data class ProxyConfig(
    val secretHex: String,
    val host: String = "127.0.0.1",
    val port: Int = 1443,
    val socks: InetSocketAddress? = null,
    val cfProxy: Boolean = true,
    val cfUserDomains: List<String> = emptyList(),
    val cfWorkers: List<String> = emptyList(),
    val maxConns: Int = Const.DEFAULT_MAX_CONNS,
    val poolSize: Int = Const.DEFAULT_POOL_SIZE,
    /** Адреса DC. Меняются только в тестах. */
    val dcIps: Map<Int, String> = Const.DC_DEFAULT_IPS,
    /** Порт Telegram для TCP и WebSocket. Меняется только в тестах. */
    val telegramPort: Int = 443,
    /** false — не пробовать прямой WebSocket (только в тестах запасных путей). */
    val webSocket: Boolean = true,
) {
    /** Секрет без префикса dd/ee, как в Go: префикс снимается только у строки из 34 символов. */
    internal val cleanSecret: String
        get() {
            var s = secretHex.trim()
            if (s.length == 34 && (s.lowercase().startsWith("dd") || s.lowercase().startsWith("ee"))) s = s.substring(2)
            return s.take(32).lowercase()
        }

    internal val cfDomains: List<String>
        get() = (listOf(Const.DEFAULT_CF_PROXY_DOMAIN) + cfUserDomains)
            .map { it.trim().trim('.').lowercase() }.filter { it.isNotEmpty() }.distinct()
}
