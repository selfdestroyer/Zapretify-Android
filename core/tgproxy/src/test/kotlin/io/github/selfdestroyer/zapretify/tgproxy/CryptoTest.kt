package io.github.selfdestroyer.zapretify.tgproxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoTest {
    @Test
    fun handshakeIsParsed() {
        for ((tag, dc) in listOf(Const.TAG_SECURE to 2, Const.TAG_INTERMEDIATE to 4, Const.TAG_ABRIDGED to -5)) {
            val c = clientHandshake(TEST_SECRET, tag, dc)
            val hi = tryHandshake(c.handshake, TEST_SECRET)
            assertNotNull(hi); hi!!
            assertArrayEquals(tag, hi.protoTag)
            assertEquals(kotlin.math.abs(dc), hi.dc)
            assertEquals(dc < 0, hi.isMedia)
        }
    }

    @Test
    fun wrongSecretIsRejected() {
        val c = clientHandshake(TEST_SECRET, Const.TAG_SECURE, 2)
        assertNull(tryHandshake(c.handshake, ByteArray(16) { 7 }))
    }

    /** Telegram расшифровывает relay init своим ключом и должен увидеть протокол и DC (в том числе медиа). */
    @Test
    fun relayInitCarriesTagAndDc() {
        repeat(50) {
            for (dc in listOf<Short>(2, -4, 203)) {
                val relay = generateRelayInit(Const.TAG_INTERMEDIATE, dc)
                assertEquals(64, relay.size)
                assertTrue(relay[0] != 0xef.toByte())
                val plain = relay.copyOf().also { AesCtr(relay.copyOfRange(8, 40), relay.copyOfRange(40, 56)).xor(it) }
                assertArrayEquals(Const.TAG_INTERMEDIATE, plain.copyOfRange(56, 60))
                val got = ((plain[60].toInt() and 0xff) or ((plain[61].toInt() and 0xff) shl 8)).toShort()
                assertEquals(dc, got)
            }
        }
    }

    /** Что клиент зашифровал после рукопожатия, прокси расшифровывает через cltDec. */
    @Test
    fun clientStreamDecrypts() {
        val c = clientHandshake(TEST_SECRET, Const.TAG_SECURE, 2)
        val hi = tryHandshake(c.handshake, TEST_SECRET)!!
        val ciphers = buildCiphers(hi.clientDecPrekeyAndIv, generateRelayInit(hi.protoTag, 2), TEST_SECRET)
        val msg = "hello telegram".toByteArray()
        val wire = msg.copyOf().also { c.enc.xor(it) }
        ciphers.cltDec.xor(wire)
        assertArrayEquals(msg, wire)
        // И обратно: cltEnc прокси ↔ dec клиента.
        val back = msg.copyOf().also { ciphers.cltEnc.xor(it) }
        c.dec.xor(back)
        assertArrayEquals(msg, back)
    }

    @Test
    fun aesCtrStreamsAcrossCalls() {
        val key = ByteArray(32) { it.toByte() }; val iv = ByteArray(16) { (it * 3).toByte() }
        val whole = ByteArray(100) { it.toByte() }.also { AesCtr(key, iv).xor(it) }
        val parts = ByteArray(100) { it.toByte() }
        AesCtr(key, iv).apply { xor(parts, 0, 7); xor(parts, 7, 50); xor(parts, 57, 43) }
        assertArrayEquals(whole, parts)
    }

    @Test
    fun configCleansSecret() {
        assertEquals(TEST_SECRET_HEX, ProxyConfig("dd$TEST_SECRET_HEX").cleanSecret)
        assertEquals(TEST_SECRET_HEX, ProxyConfig("EE${TEST_SECRET_HEX.uppercase()}").cleanSecret)
        assertEquals(TEST_SECRET_HEX, ProxyConfig(" $TEST_SECRET_HEX ").cleanSecret)
    }
}
