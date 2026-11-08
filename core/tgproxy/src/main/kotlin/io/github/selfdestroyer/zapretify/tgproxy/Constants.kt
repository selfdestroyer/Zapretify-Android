package io.github.selfdestroyer.zapretify.tgproxy

// Константы протокола и тайм-ауты — те же, что в Go-версии (core/tgproxy/constants.go).
internal object Const {
    const val HANDSHAKE_LEN = 64
    const val SKIP_LEN = 8
    const val PREKEY_LEN = 32
    const val KEY_LEN = 32
    const val IV_LEN = 16
    const val PROTO_TAG_POS = 56
    const val DC_IDX_POS = 60

    const val PROTO_ABRIDGED = 0xEFEFEFEF.toInt()
    const val PROTO_INTERMEDIATE = 0xEEEEEEEE.toInt()
    const val PROTO_PADDED_INTERMEDIATE = 0xDDDDDDDD.toInt()

    const val WS_POOL_MAX_AGE_MS = 120_000L
    const val IP_FAIL_COOLDOWN_MS = 3_600_000L
    const val DC_FAIL_COOLDOWN_MS = 60_000L
    const val DC_BLACKLIST_TTL_MS = 600_000L
    const val IO_IDLE_TIMEOUT_MS = 300_000L
    const val IDLE_CHECK_INTERVAL_MS = 5_000L
    const val WS_CONNECT_TIMEOUT_MS = 5_000
    const val POOL_CONNECT_TIMEOUT_MS = 8_000
    const val CLIENT_HANDSHAKE_TIMEOUT_MS = 10_000
    const val TCP_DIAL_TIMEOUT_MS = 10_000
    const val MAX_SPLITTER_PACKET_BYTES = 8 * 1024 * 1024
    const val DEFAULT_MAX_CONNS = 256
    const val DEFAULT_POOL_SIZE = 4
    const val CF_PROXY_FAIL_COOLDOWN_MS = 60_000L
    const val CF_BLOCKED_AFTER = 4

    val TAG_ABRIDGED = byteArrayOf(0xef.toByte(), 0xef.toByte(), 0xef.toByte(), 0xef.toByte())
    val TAG_INTERMEDIATE = byteArrayOf(0xee.toByte(), 0xee.toByte(), 0xee.toByte(), 0xee.toByte())
    val TAG_SECURE = byteArrayOf(0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte())

    /** Начала, которые Telegram принимает за другие протоколы: случайное начало relay init не должно с ними совпасть. */
    val RESERVED_START: List<ByteArray> = listOf(
        "HEAD".toByteArray(), "POST".toByteArray(), "GET ".toByteArray(),
        TAG_INTERMEDIATE, TAG_SECURE,
        byteArrayOf(0x16, 0x03, 0x01, 0x02),
    )
    const val RESERVED_FIRST = 0xef.toByte()

    /** Адреса дата-центров Telegram для WebSocket и прямого TCP. */
    val DC_DEFAULT_IPS: Map<Int, String> = mapOf(
        1 to "149.154.175.50",
        2 to "149.154.167.51",
        3 to "149.154.175.100",
        4 to "149.154.167.91",
        5 to "149.154.171.5",
        203 to "91.105.192.100",
    )

    /** Для WebSocket DC 203 обслуживается доменами DC 2. */
    val DC_OVERRIDES: Map<Int, Int> = mapOf(203 to 2)

    /** Базовый домен Cloudflare-пути (поддомены kws<dc>.<домен>). */
    const val DEFAULT_CF_PROXY_DOMAIN = "pclead.co.uk"
}
