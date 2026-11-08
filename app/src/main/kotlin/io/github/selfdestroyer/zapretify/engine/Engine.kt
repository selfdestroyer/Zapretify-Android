package io.github.selfdestroyer.zapretify.engine

/**
 * Движок обхода блокировок (план, раздел 3.3). Интерфейс и подбор не знают, какой движок работает:
 * версия 1 — ByeDpiEngine (VpnService + byedpi, этап 3), версия 2 — NfqwsEngine (root).
 */
interface Engine {
    val isRunning: Boolean
    val activeStrategy: String?
    fun start(strategy: String)
    fun stop()
}
