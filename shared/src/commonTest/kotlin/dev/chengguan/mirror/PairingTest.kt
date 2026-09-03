package dev.chengguan.mirror

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingTest {
    private val valid =
        "mirror://v1?host=192.168.1.20&port=8787" +
            "&sid=01a003db-06b0-7a53-9d42-f263250c7890" +
            "&tok=abcdefghijklmnopqrstuvwxyz012345" +
            "&fp=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun looksLikePairingPayloadAcceptsScheme() {
        assertTrue(looksLikePairingPayload(valid))
        assertTrue(looksLikePairingPayload("MIRROR:$valid"))
        assertTrue(!looksLikePairingPayload("https://example.com"))
        assertTrue(!looksLikePairingPayload(""))
    }

    @Test
    fun parseValidLanPairing() {
        val pairing = parsePairing(valid)
        assertNotNull(pairing)
        assertEquals("192.168.1.20", pairing.host)
        assertEquals(8787, pairing.port)
        assertEquals("01a003db-06b0-7a53-9d42-f263250c7890", pairing.sessionId)
        assertEquals(false, pairing.requireApple)
    }

    @Test
    fun parseRequireAppleFlag() {
        val pairing = parsePairing("$valid&apple=1")
        assertNotNull(pairing)
        assertEquals(true, pairing.requireApple)
        assertTrue(!("$valid&apple=1").contains("@"))
    }

    @Test
    fun rejectPublicIp() {
        val raw = valid.replace("192.168.1.20", "8.8.8.8")
        assertNull(parsePairing(raw))
    }

    @Test
    fun acceptTailscaleCgnat() {
        val raw = valid.replace("192.168.1.20", "100.77.197.23")
        assertNotNull(parsePairing(raw))
    }

    @Test
    fun rejectNearbyNonTailscale100() {
        assertNull(parsePairing(valid.replace("192.168.1.20", "100.63.0.1")))
        assertNull(parsePairing(valid.replace("192.168.1.20", "100.128.0.1")))
    }

    @Test
    fun rejectHostname() {
        val raw = valid.replace("192.168.1.20", "evil.example")
        assertNull(parsePairing(raw))
    }

    @Test
    fun acceptTailscaleMagicDns() {
        val raw = valid.replace("192.168.1.20", "chengs-macbook-air.tailb2aa5a.ts.net")
        assertNotNull(parsePairing(raw))
    }

    @Test
    fun rejectFakeTsNet() {
        assertNull(parsePairing(valid.replace("192.168.1.20", "evil.ts.net.attacker.com")))
        assertNull(parsePairing(valid.replace("192.168.1.20", "ts.net")))
        assertNull(parsePairing(valid.replace("192.168.1.20", "x.ts.net")))
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
        val json = """{"messages":[{"role":"user","text":"hi"}],"status":{"phase":"working","detail":"Reading file","model":"grok-4.6","inbox":0,"tui":true,"usage_percent":55,"context_percent":17,"billing":true,"billing_kind":"weekly","billing_resets":"2026-08-20","subscription_tier":"X Premium+","tokens_used":262235,"tokens_window":500000}}"""
        val status = parseSessionStatus(json)
        assertNotNull(status)
        assertEquals("working", status.phase)
        assertEquals("Reading file", status.detail)
        assertEquals("grok-4.6", status.model)
        assertEquals(true, status.tui)
        assertEquals(55, status.usagePercent)
        assertEquals(17, status.contextPercent)
        assertEquals(true, status.billing)
        assertEquals("weekly", status.billingKind)
        assertEquals("2026-08-20", status.billingResets)
        assertEquals("X Premium+", status.subscriptionTier)
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

    @Test
    fun sessionCatalogRoundTripPreservesPairing() {
        val pairing = parsePairing(valid)
        assertNotNull(pairing)
        val rec = SessionRecord(id = pairing.sessionId, name = "Air", pairing = pairing, archived = false)
        val encoded = encodeSessionCatalog(listOf(rec), pairing.sessionId)
        val (items, active) = parseSessionCatalog(encoded)
        assertEquals(1, items.size)
        assertEquals("Air", items[0].name)
        assertEquals(pairing.sessionId, active)
        assertEquals(pairing.host, items[0].pairing?.host)
        assertEquals(pairing.token, items[0].pairing?.token)
    }

    @Test
    fun defaultSessionNamePrefersHostnameThenMagicDns() {
        val pairing = parsePairing(valid.replace("192.168.1.20", "chengs-macbook-air.tailb2aa5a.ts.net"))
        assertNotNull(pairing)
        assertEquals("office", defaultSessionName(pairing, "office"))
        assertEquals("chengs-macbook-air", defaultSessionName(pairing, null))
    }

    @Test
    fun migrateLegacyPairing() {
        val rec = migrateLegacyPairing(valid)
        assertNotNull(rec)
        assertEquals("01a003db-06b0-7a53-9d42-f263250c7890", rec.id)
        assertEquals(false, rec.archived)
    }

    @Test
    fun encodeArchiveFitsMaxBytes() {
        val long = "x".repeat(4_096)
        val messages = (1..200).map { ChatMessage("user", long) }
        val raw = encodeArchive(messages, maxBytes = 8_192)
        assertTrue(raw.encodeToByteArray().size <= 8_192)
        assertTrue(parseArchive(raw).isNotEmpty() || raw == """{"messages":[]}""")
    }

    @Test
    fun collapseConsecutiveDuplicateUserTurns() {
        val hello = ChatMessage("user", "hello")
        val reply = ChatMessage("assistant", "hi")
        val collapsed = collapseDuplicateUserTurns(listOf(hello, hello, reply, hello))
        assertEquals(listOf(hello, reply, hello), collapsed)
    }

    @Test
    fun rollbackOptimisticUserDropsMatchingTail() {
        val prior = ChatMessage("assistant", "ok")
        val sent = ChatMessage("user", "hello")
        assertEquals(listOf(prior), rollbackOptimisticUser(listOf(prior, sent), "hello"))
    }

    @Test
    fun rollbackOptimisticUserLeavesServerThreadAlone() {
        val prior = ChatMessage("user", "hello")
        val reply = ChatMessage("assistant", "hi")
        val thread = listOf(prior, reply)
        assertEquals(thread, rollbackOptimisticUser(thread, "hello"))
        assertEquals(emptyList(), rollbackOptimisticUser(emptyList(), "hello"))
    }
}
