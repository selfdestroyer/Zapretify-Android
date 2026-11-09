package io.github.selfdestroyer.zapretify.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class StrategyTest {
    @Test
    fun splitArgsKeepsQuotes() {
        assertEquals(listOf("-n", "google.com", "-Qr", "-f-1"), Strategies.splitArgs("""-n "google.com" -Qr -f-1"""))
        assertEquals(listOf("-l", ":a b"), Strategies.splitArgs("-l ':a b'"))
        assertEquals(emptyList<String>(), Strategies.splitArgs("   "))
    }

    @Test
    fun validatorAllowsDesyncOptions() {
        for (s in listOf("-d1 -d3+s -s6+s -r1+s -S -a1 -As", "--disorder 1 --auto=torst --tlsrec 1+s", "--fake -1 --ttl 8",
            """-o1 -r-5+se -a1 -At,r,s -d1 -n "google.com" -Qr -f-1 -a1""", "-H :youtube.com -l :abc")) {
            assertNull(s, StrategyValidator.check(Strategies.splitArgs(s)))
        }
    }

    @Test
    fun validatorRejectsDangerousOptions() {
        for (s in listOf("-D", "--daemon", "-w /sdcard/x", "-p 9999", "-i 0.0.0.0", "--ip=0.0.0.0", "-y /sdcard/cache",
            "--cache-dump -", "-E", "-I 1.2.3.4", "-P /tmp/s", "-C 1.2.3.4", "-H /etc/hosts", "-l /data/x", "-h", "--help",
            "stray", "-s")) {
            assertNotNull(s, StrategyValidator.check(Strategies.splitArgs(s)))
        }
    }

    /** Все стратегии из strategies/byedpi.json проходят проверку. */
    @Test
    fun bundledStrategiesAreValid() {
        val json = JSONObject(File("../strategies/byedpi.json").readText())
        val arr = json.getJSONArray("strategies")
        for (i in 0 until arr.length()) {
            val args = arr.getJSONObject(i).getString("args")
            assertNull(args, StrategyValidator.check(Strategies.splitArgs(args)))
        }
    }
}
