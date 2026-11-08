package io.github.selfdestroyer.zapretify.tgproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProxyLinkTest {
    private val secret = "0123456789abcdef0123456789abcdef"

    @Test
    fun matchesGoFormat() {
        assertEquals("tg://proxy?server=127.0.0.1&port=1443&secret=dd$secret", ProxyLink.build("", 1443, secret))
        assertEquals("tg://proxy?server=127.0.0.1&port=1443&secret=dd$secret", ProxyLink.build("0.0.0.0", 1443, secret))
    }

    @Test
    fun rejectsBadInput() {
        assertThrows(IllegalArgumentException::class.java) { ProxyLink.build("", 0, secret) }
        assertThrows(IllegalArgumentException::class.java) { ProxyLink.build("", 1443, "XYZ") }
    }
}
