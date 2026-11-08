package io.github.selfdestroyer.zapretify.tgproxy

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService

/**
 * Запас заранее открытых WebSocket к Telegram, чтобы новое соединение клиента не ждало рукопожатия (pool.go).
 * На каждый DC и IP держится до [size] соединений не старше 2 минут.
 */
internal class WsPool(
    private val size: Int,
    private val executor: ExecutorService,
    private val state: ProxyState,
    private val connect: (target: String, domains: List<String>, timeoutMs: Int) -> WsConn,
) {
    private data class Key(val dc: Int, val isMedia: Boolean, val target: String)
    private class Entry(val conn: WsConn, val created: Long)

    private val idle = HashMap<Key, ArrayDeque<Entry>>()
    private val refilling = ConcurrentHashMap.newKeySet<Key>()
    private val lock = Any()

    fun get(dc: DcKey, target: String, domains: List<String>): WsConn? {
        val key = Key(dc.dc, dc.isMedia, target)
        val now = System.currentTimeMillis()
        while (true) {
            val entry = synchronized(lock) { idle[key]?.removeFirstOrNull() }
            scheduleRefill(key, domains)
            if (entry == null) {
                state.stats.poolMisses.incrementAndGet()
                return null
            }
            if (now - entry.created > Const.WS_POOL_MAX_AGE_MS) { entry.conn.close(); continue }
            state.stats.poolHits.incrementAndGet()
            return entry.conn
        }
    }

    private fun scheduleRefill(key: Key, domains: List<String>) {
        if (size <= 0 || !refilling.add(key)) return
        try {
            executor.execute {
                try { refill(key, domains) } finally { refilling.remove(key) }
            }
        } catch (_: Exception) {
            refilling.remove(key) // пул остановлен
        }
    }

    private fun refill(key: Key, domains: List<String>) {
        while (true) {
            if (synchronized(lock) { idle[key]?.size ?: 0 } >= size) return
            if (state.inIpCooldown(key.target)) return
            val conn = try { connect(key.target, domains, Const.POOL_CONNECT_TIMEOUT_MS) } catch (_: Exception) { return }
            synchronized(lock) {
                if (state.inIpCooldown(key.target)) { conn.close(); return }
                idle.getOrPut(key) { ArrayDeque() }.addLast(Entry(conn, System.currentTimeMillis()))
            }
        }
    }

    fun discardTarget(target: String) {
        val stale = synchronized(lock) {
            val keys = idle.keys.filter { it.target == target }
            keys.flatMap { idle.remove(it).orEmpty() }
        }
        stale.forEach { it.conn.close() }
    }

    fun reset() {
        val all = synchronized(lock) { idle.values.flatten().also { idle.clear() } }
        all.forEach { it.conn.close() }
    }
}
