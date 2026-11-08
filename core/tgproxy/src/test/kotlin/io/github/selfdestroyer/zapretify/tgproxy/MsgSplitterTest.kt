package io.github.selfdestroyer.zapretify.tgproxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MsgSplitterTest {
    private val relay = generateRelayInit(Const.TAG_INTERMEDIATE, 2)

    /** Поток к Telegram так, как его шифрует tgEnc (после 64 байт relay init). */
    private fun encrypt(plain: ByteArray) = plain.copyOf().also {
        AesCtr(relay.copyOfRange(8, 40), relay.copyOfRange(40, 56)).apply { skip(64) }.xor(it)
    }

    @Test
    fun onePacket() {
        val wire = encrypt(intermediateFrame("hello telegram world".toByteArray()))
        val parts = MsgSplitter(relay, Const.PROTO_INTERMEDIATE).split(wire)
        assertEquals(1, parts.size)
        assertArrayEquals(wire, parts[0])
    }

    @Test
    fun twoPacketsInOneChunkAndOneAcrossChunks() {
        val a = intermediateFrame(ByteArray(8) { 1 }); val b = intermediateFrame(ByteArray(12) { 2 })
        val wire = encrypt(a + b)
        val s = MsgSplitter(relay, Const.PROTO_INTERMEDIATE)
        assertEquals(2, s.split(wire).size)

        val s2 = MsgSplitter(relay, Const.PROTO_INTERMEDIATE)
        assertEquals(0, s2.split(wire.copyOfRange(0, 6)).size)
        val rest = s2.split(wire.copyOfRange(6, wire.size))
        assertEquals(2, rest.size)
        assertArrayEquals(wire.copyOfRange(0, a.size), rest[0])
    }

    @Test
    fun abridged() {
        val relayA = generateRelayInit(Const.TAG_ABRIDGED, 2)
        val plain = byteArrayOf(2) + ByteArray(8) { 9 } // длина 2*4 байт
        val wire = plain.copyOf().also { AesCtr(relayA.copyOfRange(8, 40), relayA.copyOfRange(40, 56)).apply { skip(64) }.xor(it) }
        val parts = MsgSplitter(relayA, Const.PROTO_ABRIDGED).split(wire)
        assertEquals(1, parts.size)
        assertEquals(9, parts[0].size)
    }

    @Test
    fun flushReturnsTail() {
        val wire = encrypt(intermediateFrame(ByteArray(16)))
        val s = MsgSplitter(relay, Const.PROTO_INTERMEDIATE)
        assertEquals(0, s.split(wire.copyOfRange(0, 10)).size)
        assertArrayEquals(wire.copyOfRange(0, 10), s.flush().single())
    }
}
