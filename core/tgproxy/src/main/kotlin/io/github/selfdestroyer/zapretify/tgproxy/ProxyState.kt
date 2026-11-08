package io.github.selfdestroyer.zapretify.tgproxy

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal data class DcKey(val dc: Int, val isMedia: Boolean)

/** Счётчики соединений и трафика (Stats в Go). */
class ProxyStats {
    val connectionsTotal = AtomicLong()
    val connectionsActive = AtomicLong()
    val connectionsWs = AtomicLong()
    val connectionsTcp = AtomicLong()
    val connectionsCf = AtomicLong()
    val connectionsBad = AtomicLong()
    val wsErrors = AtomicLong()
    val bytesUp = AtomicLong()
    val bytesDown = AtomicLong()
    val poolHits = AtomicLong()
    val poolMisses = AtomicLong()
}

/**
 * Состояние одного запуска прокси (state.go в Go): паузы после неудач по DC и по IP, временный чёрный
 * список DC, неудачи Cloudflare-доменов и счётчик подряд неудачных попыток Cloudflare-пути.
 */
internal class ProxyState(private val log: (String) -> Unit, private val now: () -> Long = System::currentTimeMillis) {
    val stats = ProxyStats()
    private val dcFailUntil = ConcurrentHashMap<DcKey, Long>()
    private val ipFailUntil = ConcurrentHashMap<String, Long>()
    private val blacklist = ConcurrentHashMap<DcKey, Long>()
    private val cfDomainFailUntil = ConcurrentHashMap<String, Long>()
    private val cfFailStreak = AtomicLong()
    @Volatile var onIpCooldown: (String) -> Unit = {}

    fun setCooldown(k: DcKey) { dcFailUntil[k] = now() + Const.DC_FAIL_COOLDOWN_MS }
    fun inCooldown(k: DcKey) = (dcFailUntil[k] ?: 0) > now()
    fun clearCooldown(k: DcKey) { dcFailUntil.remove(k) }

    fun setIpCooldown(ip: String) {
        if (ip.isEmpty()) return
        ipFailUntil[ip] = now() + Const.IP_FAIL_COOLDOWN_MS
        onIpCooldown(ip)
    }
    fun inIpCooldown(ip: String): Boolean {
        if (ip.isEmpty()) return false
        val until = ipFailUntil[ip] ?: return false
        if (until > now()) return true
        ipFailUntil.remove(ip)
        return false
    }

    fun setBlacklisted(k: DcKey) { blacklist[k] = now() + Const.DC_BLACKLIST_TTL_MS }
    fun isBlacklisted(k: DcKey): Boolean {
        val until = blacklist[k] ?: return false
        if (until > now()) return true
        blacklist.remove(k)
        return false
    }

    fun markCfDomainFailed(domain: String) { cfDomainFailUntil[domain] = now() + Const.CF_PROXY_FAIL_COOLDOWN_MS }

    /** Неудача Cloudflare-пути. На 4-й подряд — подсказка в журнале (интерфейс показывает её через [cfProxyBlocked]). */
    fun noteCfFailure() {
        if (cfFailStreak.incrementAndGet() == Const.CF_BLOCKED_AFTER.toLong()) {
            log("[tg-ws-proxy] Провайдер, похоже, блокирует путь через Cloudflare: исходящие соединения прокси нужно пустить через движок обхода")
        }
    }
    /** Telegram ответил любым путём — блокировки Cloudflare-пути нет. */
    fun telegramReachable() = cfFailStreak.set(0)
    fun cfProxyBlocked() = cfFailStreak.get() >= Const.CF_BLOCKED_AFTER
}
