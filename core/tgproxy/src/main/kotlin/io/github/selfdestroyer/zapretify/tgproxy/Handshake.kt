package io.github.selfdestroyer.zapretify.tgproxy

import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.math.abs

internal val secureRandom = SecureRandom()

/** Что удалось узнать из 64-байтного рукопожатия клиента (core/tgproxy/crypto.go, tryHandshake). */
internal class HandshakeInfo(
    val dc: Int,
    val isMedia: Boolean,
    val protoTag: ByteArray,
    /** Байты 8..56 рукопожатия: ключ и IV, из которых строятся шифры клиента. */
    val clientDecPrekeyAndIv: ByteArray,
)

/** Четыре потока шифрования соединения: от клиента, к клиенту, к Telegram, от Telegram. */
internal class Ciphers(val cltDec: AesCtr, val cltEnc: AesCtr, val tgEnc: AesCtr, val tgDec: AesCtr)

internal fun sha256(vararg parts: ByteArray): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    parts.forEach { md.update(it) }
    return md.digest()
}

internal fun ByteArray.reversedCopy(): ByteArray = ByteArray(size) { this[size - 1 - it] }

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private fun isKnownTag(tag: ByteArray) =
    tag.contentEquals(Const.TAG_ABRIDGED) || tag.contentEquals(Const.TAG_INTERMEDIATE) || tag.contentEquals(Const.TAG_SECURE)

/** Расшифровывает рукопожатие ключом из секрета. null — чужой клиент или другой секрет. */
internal fun tryHandshake(handshake: ByteArray, secret: ByteArray): HandshakeInfo? {
    if (handshake.size != Const.HANDSHAKE_LEN) return null
    val prekeyAndIv = handshake.copyOfRange(Const.SKIP_LEN, Const.SKIP_LEN + Const.PREKEY_LEN + Const.IV_LEN)
    val key = sha256(prekeyAndIv.copyOfRange(0, Const.PREKEY_LEN), secret)
    val decrypted = handshake.copyOf()
    AesCtr(key, prekeyAndIv.copyOfRange(Const.PREKEY_LEN, prekeyAndIv.size)).xor(decrypted)

    val tag = decrypted.copyOfRange(Const.PROTO_TAG_POS, Const.PROTO_TAG_POS + 4)
    if (!isKnownTag(tag)) return null
    val dcIdx = ((decrypted[Const.DC_IDX_POS].toInt() and 0xff) or
        ((decrypted[Const.DC_IDX_POS + 1].toInt() and 0xff) shl 8)).toShort().toInt()
    return HandshakeInfo(abs(dcIdx), dcIdx < 0, tag, prekeyAndIv)
}

/**
 * 64 байта, которые прокси отправляет Telegram первыми: случайный ключ и IV, а в зашифрованном хвосте —
 * протокол и номер DC (отрицательный для медиа). Как generateRelayInit в Go.
 */
internal fun generateRelayInit(protoTag: ByteArray, dcIdx: Short): ByteArray {
    val rnd = ByteArray(Const.HANDSHAKE_LEN)
    while (true) {
        secureRandom.nextBytes(rnd)
        if (rnd[0] == Const.RESERVED_FIRST) continue
        if (Const.RESERVED_START.any { rnd.startsWith(it) }) continue
        if (rnd[4] == 0.toByte() && rnd[5] == 0.toByte() && rnd[6] == 0.toByte() && rnd[7] == 0.toByte()) continue
        break
    }
    val encrypted = rnd.copyOf()
    AesCtr(rnd.copyOfRange(8, 40), rnd.copyOfRange(40, 56)).xor(encrypted)

    val tail = ByteArray(8)
    protoTag.copyInto(tail, 0, 0, 4)
    tail[4] = (dcIdx.toInt() and 0xff).toByte()
    tail[5] = ((dcIdx.toInt() shr 8) and 0xff).toByte()
    val rand2 = ByteArray(2).also { secureRandom.nextBytes(it) }
    tail[6] = rand2[0]; tail[7] = rand2[1]

    val result = rnd.copyOf()
    for (i in 0 until 8) {
        val keystream = (encrypted[56 + i].toInt() xor rnd[56 + i].toInt())
        result[56 + i] = (tail[i].toInt() xor keystream).toByte()
    }
    return result
}

/** Шифры соединения (buildCiphers в Go). Потоки от клиента и к Telegram сдвинуты на 64 байта рукопожатий. */
internal fun buildCiphers(clientDecPrekeyAndIv: ByteArray, relayInit: ByteArray, secret: ByteArray): Ciphers {
    val cltDec = AesCtr(sha256(clientDecPrekeyAndIv.copyOfRange(0, 32), secret), clientDecPrekeyAndIv.copyOfRange(32, 48))
    val rev = clientDecPrekeyAndIv.reversedCopy()
    val cltEnc = AesCtr(sha256(rev.copyOfRange(0, 32), secret), rev.copyOfRange(32, 48))
    val tgEnc = AesCtr(relayInit.copyOfRange(8, 40), relayInit.copyOfRange(40, 56))
    val relayRev = relayInit.copyOfRange(8, 56).reversedCopy()
    val tgDec = AesCtr(relayRev.copyOfRange(0, Const.KEY_LEN), relayRev.copyOfRange(Const.KEY_LEN, 48))
    cltDec.skip(Const.HANDSHAKE_LEN)
    tgEnc.skip(Const.HANDSHAKE_LEN)
    return Ciphers(cltDec, cltEnc, tgEnc, tgDec)
}

internal fun protoFromTag(tag: ByteArray): Int = when {
    tag.contentEquals(Const.TAG_ABRIDGED) -> Const.PROTO_ABRIDGED
    tag.contentEquals(Const.TAG_INTERMEDIATE) -> Const.PROTO_INTERMEDIATE
    else -> Const.PROTO_PADDED_INTERMEDIATE
}

/** Домены WebSocket Telegram для DC; для медиа сначала kws<dc>-1. */
internal fun wsDomains(dc: Int, isMedia: Boolean): List<String> =
    if (isMedia) listOf("kws$dc-1.web.telegram.org", "kws$dc.web.telegram.org")
    else listOf("kws$dc.web.telegram.org", "kws$dc-1.web.telegram.org")

internal fun signedDc(dc: Int, media: Boolean): Short = (if (media) -dc else dc).toShort()
