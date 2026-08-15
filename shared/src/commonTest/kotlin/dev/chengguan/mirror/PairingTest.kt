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
    fun stripsMonitorEventTags() {
        val json =
            """{"messages":[{"role":"user","text":"<monitor-event task_id=\"abc\">\n[Watch Mirror inbox for phone sends] MIRROR Hello from phone\n</monitor-event>"}]}"""
        val messages = parseMessageList(json)
        assertEquals(1, messages.size)
        assertEquals("Hello from phone", messages[0].text)
    }

    @Test
    fun parseMessageListEscapedQuotes() {
        val json = """{"messages":[{"role":"user","text":"say \"hi\"\nnext"}]}"""
        val messages = parseMessageList(json)
        assertEquals(1, messages.size)
        assertEquals("say \"hi\"\nnext", messages[0].text)
    }

    @Test
    fun parseSessionStatusFromSnapshot() {
        val json = """{"messages":[{"role":"user","text":"hi"}],"status":{"phase":"working","detail":"Reading file","model":"grok-4.6","inbox":0,"tui":true,"usage_percent":52,"tokens_used":262235,"tokens_window":500000}}"""
        val status = parseSessionStatus(json)
        assertNotNull(status)
        assertEquals("working", status.phase)
        assertEquals("Reading file", status.detail)
        assertEquals("grok-4.6", status.model)
        assertEquals(true, status.tui)
        assertEquals(52, status.usagePercent)
        assertEquals(262235, status.tokensUsed)
    }

    @Test
    fun unescapeUnicodeApostrophe() {
        assertEquals("don\u2019t", unescapeJson("don\\u2019t"))
        assertEquals("'", unescapeJson("\\u0027"))
        val json = """{"messages":[{"role":"assistant","text":"it\u2019s fine"}]}"""
        val messages = parseMessageList(json)
        assertEquals("it\u2019s fine", messages[0].text)
    }

    @Test
    fun parseMessageListIgnoresBracesInsideStrings() {
        val json = """{"messages":[{"role":"assistant","text":"use {this} object"}]}"""
        val messages = parseMessageList(json)
        assertEquals(1, messages.size)
        assertEquals("use {this} object", messages[0].text)
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
