package io.github.selfdestroyer.zapretify.tgproxy

/** Адреса, через которые прокси ходит к Telegram, — для проверок доступности снаружи модуля. */
object ProxyPaths {
    /** Узел пути через Cloudflare для дата-центра [dc] (по умолчанию второй — основной). */
    fun cloudflareHost(dc: Int = 2): String = "kws$dc.${Const.DEFAULT_CF_PROXY_DOMAIN}"
}
