package io.github.selfdestroyer.zapretify.engine

/**
 * Движок обхода блокировок (план, раздел 3.3). Интерфейс и подбор не знают, какой движок работает:
 * версия 1 — ByeDpiEngine (VpnService + byedpi), версия 2 — NfqwsEngine (root).
 */
interface Engine {
    val isRunning: Boolean
    val activeStrategy: Strategy?
    fun start(strategy: Strategy)
    fun stop()
}
