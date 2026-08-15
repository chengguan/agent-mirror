package dev.chengguan.mirror

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingTest {
    private val valid =
        "grok-mirror://v1?host=192.168.1.20&port=8787" +
            "&sid=01a003db-06b0-7a53-9d42-f263250c7890" +
            "&tok=abcdefghijklmnopqrstuvwxyz012345" +
            "&fp=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun parseValidLanPairing() {
        val pairing = parsePairing(valid)
        assertNotNull(pairing)
        assertEquals("192.168.1.20", pairing.host)
        assertEquals(8787, pairing.port)
        assertEquals("01a003db-06b0-7a53-9d42-f263250c7890", pairing.sessionId)
    }

    @Test
    fun rejectPublicIp() {
        val raw = valid.replace("192.168.1.20", "8.8.8.8")
        assertNull(parsePairing(raw))
    }

    @Test
    fun rejectHostname() {
        val raw = valid.replace("192.168.1.20", "evil.example")
        assertNull(parsePairing(raw))
    }

    @Test
    fun rejectLeadingZeros() {
        val raw = valid.replace("192.168.1.20", "192.168.001.020")
        assertNull(parsePairing(raw))
    }

    @Test
    fun acceptLoopback() {
        val raw = valid.replace("192.168.1.20", "127.0.0.1")
        assertNotNull(parsePairing(raw))
    }

    @Test
    fun rejectShortToken() {
        val raw = valid.replace("abcdefghijklmnopqrstuvwxyz012345", "short")
        assertNull(parsePairing(raw))
    }

    @Test
    fun parseMessageListBothOrders() {
        val json = """{"messages":[{"role":"user","text":"Hi"},{"text":"Hello","role":"assistant"}]}"""
        val messages = parseMessageList(json)
        assertEquals(2, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("Hi", messages[0].text)
        assertEquals("assistant", messages[1].role)
        assertEquals("Hello", messages[1].text)
    }

    @Test
    fun escapeJsonRoundTripShape() {
        val escaped = escapeJson("say \"hi\"\n\\")
        assertTrue(escaped.contains("\\\""))
        assertTrue(escaped.contains("\\n"))
        assertTrue(escaped.contains("\\\\"))
    }

    @Test
    fun isLanIpv4Bounds() {
        assertTrue(isLanIpv4("10.0.0.1"))
        assertTrue(isLanIpv4("172.16.0.1"))
        assertTrue(isLanIpv4("172.31.255.255"))
        assertTrue(!isLanIpv4("172.32.0.1"))
        assertTrue(!isLanIpv4("11.0.0.1"))
    }
}
