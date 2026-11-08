package io.github.selfdestroyer.zapretify.tgproxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class FakeTlsTest {
    /** Длины в заголовках ServerHello совпадают с байтами: в Go до 1.5.0 случайная часть была 28 байт. */
    @Test
    fun serverHelloTemplateLengths() {
        val t = FakeTls.serverHelloTemplate()
        val recordLen = ((t[3].toInt() and 0xff) shl 8) or (t[4].toInt() and 0xff)
        assertEquals(t.size - 5, recordLen)
        val hsLen = ((t[6].toInt() and 0xff) shl 16) or ((t[7].toInt() and 0xff) shl 8) or (t[8].toInt() and 0xff)
        assertEquals(recordLen - 4, hsLen)
        assertEquals(0x20, t[43].toInt())
    }

    @Test
    fun clientHelloVerification() {
        assertNotNull(FakeTls.verifyClientHello(clientHello(TEST_SECRET), TEST_SECRET))
        assertNull(FakeTls.verifyClientHello(clientHello(TEST_SECRET), ByteArray(16)))
        val old = System.currentTimeMillis() / 1000 - 600
        assertNull(FakeTls.verifyClientHello(clientHello(TEST_SECRET, old), TEST_SECRET))
        val tampered = clientHello(TEST_SECRET).also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull(FakeTls.verifyClientHello(tampered, TEST_SECRET))
    }

    /** Случайная часть ServerHello — HMAC(секрет, client random + ответ с нулями на её месте). */
    @Test
    fun serverHelloIsSigned() {
        val hello = FakeTls.verifyClientHello(clientHello(TEST_SECRET), TEST_SECRET)!!
        val resp = FakeTls.buildServerHello(TEST_SECRET, hello.clientRandom, hello.sessionId)
        val random = resp.copyOfRange(11, 43)
        val zeroed = resp.copyOf().also { it.fill(0, 11, 43) }
        assertArrayEquals(FakeTls.hmacSha256(TEST_SECRET, hello.clientRandom + zeroed), random)
        assertArrayEquals(hello.sessionId, resp.copyOfRange(44, 76))
        val sizes = resp.size - 127 - 6 - 5
        assert(sizes in 1900..2100) { "размер записи $sizes" }
    }

    @Test
    fun recordsRoundTripAndSkipCcs() {
        val data = ByteArray(40000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        out.write(FakeTls.CCS_FRAME)
        FakeTlsOutputStream(out).write(data)
        val back = FakeTlsInputStream(ByteArrayInputStream(out.toByteArray())).readBytes()
        assertArrayEquals(data, back)
    }
}
