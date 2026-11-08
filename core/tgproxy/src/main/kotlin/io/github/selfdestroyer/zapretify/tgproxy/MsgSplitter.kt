package io.github.selfdestroyer.zapretify.tgproxy

/**
 * Режет поток к Telegram на целые MTProto-пакеты, чтобы каждый уходил отдельным WebSocket-сообщением
 * (msgSplitter в Go). Длину пакета видно только в расшифрованном виде, поэтому у сплиттера своя копия
 * потока tgEnc; наружу отдаются зашифрованные куски. Если формат не распознан — отключается и пропускает всё.
 */
internal class MsgSplitter(relayInit: ByteArray, private val proto: Int) {
    private val dec = AesCtr(relayInit.copyOfRange(8, 40), relayInit.copyOfRange(40, 56)).also { it.skip(Const.HANDSHAKE_LEN) }
    private var cipherBuf = ByteArray(0)
    private var plainBuf = ByteArray(0)
    private var disabled = false

    fun split(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        if (disabled) return listOf(chunk)
        if (cipherBuf.size + chunk.size > Const.MAX_SPLITTER_PACKET_BYTES) {
            val parts = ArrayList<ByteArray>(2)
            if (cipherBuf.isNotEmpty()) parts += cipherBuf
            parts += chunk
            cipherBuf = ByteArray(0); plainBuf = ByteArray(0); disabled = true
            return parts
        }
        cipherBuf += chunk
        val plain = chunk.copyOf().also { dec.xor(it) }
        plainBuf += plain

        val parts = ArrayList<ByteArray>(2)
        while (cipherBuf.isNotEmpty()) {
            val next = nextPacketLen()
            if (next < 0) break
            if (next == 0) {
                parts += cipherBuf
                cipherBuf = ByteArray(0); plainBuf = ByteArray(0); disabled = true
                break
            }
            parts += cipherBuf.copyOfRange(0, next)
            cipherBuf = cipherBuf.copyOfRange(next, cipherBuf.size)
            plainBuf = plainBuf.copyOfRange(next, plainBuf.size)
        }
        return parts
    }

    fun flush(): List<ByteArray> {
        if (cipherBuf.isEmpty()) return emptyList()
        val tail = cipherBuf
        cipherBuf = ByteArray(0); plainBuf = ByteArray(0)
        return listOf(tail)
    }

    /** -1 — данных не хватает, 0 — формат не распознан, иначе длина пакета. */
    private fun nextPacketLen(): Int {
        if (plainBuf.isEmpty()) return -1
        return when (proto) {
            Const.PROTO_ABRIDGED -> nextAbridgedLen()
            Const.PROTO_INTERMEDIATE, Const.PROTO_PADDED_INTERMEDIATE -> nextIntermediateLen()
            else -> 0
        }
    }

    private fun u8(i: Int) = plainBuf[i].toInt() and 0xff

    private fun nextAbridgedLen(): Int {
        val first = u8(0)
        val headerLen: Int
        val payloadLen: Long
        if (first == 0x7F || first == 0xFF) {
            if (plainBuf.size < 4) return -1
            headerLen = 4
            payloadLen = (u8(1).toLong() or (u8(2).toLong() shl 8) or (u8(3).toLong() shl 16)) * 4
        } else {
            headerLen = 1
            payloadLen = (first and 0x7F).toLong() * 4
        }
        if (payloadLen <= 0) return 0
        val packetLen = headerLen + payloadLen
        if (packetLen > Const.MAX_SPLITTER_PACKET_BYTES) return 0
        if (plainBuf.size < packetLen) return -1
        return packetLen.toInt()
    }

    private fun nextIntermediateLen(): Int {
        if (plainBuf.size < 4) return -1
        val raw = u8(0).toLong() or (u8(1).toLong() shl 8) or (u8(2).toLong() shl 16) or (u8(3).toLong() shl 24)
        val payloadLen = raw and 0x7FFFFFFF
        if (payloadLen <= 0) return 0
        val packetLen = 4 + payloadLen
        if (packetLen > Const.MAX_SPLITTER_PACKET_BYTES) return 0
        if (plainBuf.size < packetLen) return -1
        return packetLen.toInt()
    }
}
