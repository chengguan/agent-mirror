package dev.chengguan.mirror

/**
 * OWASP M4 — treat QR / deep-link query as untrusted.
 * Host must be a dotted IPv4; we do not allow hostnames (SSRF / DNS rebinding).
 */
data class Pairing(
    val host: String,
    val port: Int,
    val sessionId: String,
    val token: String,
    val fingerprint: String,
    val requireApple: Boolean = false,
)

fun parsePairing(raw: String): Pairing? {
    val trimmed = raw.trim()
    if (trimmed.length > 2_000) return null
    val uri = trimmed.removePrefix("GROK-MIRROR:").let {
        if (it.startsWith("grok-mirror://", ignoreCase = true)) it else return null
    }
    val qIndex = uri.indexOf('?')
    if (qIndex < 0) return null
    val query = uri.substring(qIndex + 1)
    val map = mutableMapOf<String, String>()
    for (part in query.split('&')) {
        val eq = part.indexOf('=')
        if (eq <= 0) continue
        val key = part.substring(0, eq)
        val value = decodeQuery(part.substring(eq + 1))
        map[key] = value
    }
    val host = map["host"] ?: return null
    val port = map["port"]?.toIntOrNull() ?: return null
    val sid = map["sid"] ?: return null
    val tok = map["tok"] ?: return null
    val fp = (map["fp"] ?: "").lowercase().removePrefix("sha256:")
    if (!isAllowedPairingHost(host)) return null
    if (port !in 1..65535) return null
    if (!sid.matches(Regex("""^[A-Za-z0-9_-]{8,80}$"""))) return null
    if (tok.length < 16 || tok.length > 128) return null
    if (!fp.matches(Regex("""^[0-9a-f]{64}$"""))) return null
    val apple = (map["apple"] ?: "").lowercase()
    val requireApple = apple == "1" || apple == "true"
    return Pairing(host, port, sid, tok, fp, requireApple)
}

fun isAllowedPairingHost(host: String): Boolean =
    isLanIpv4(host) || isTailscaleMagicDns(host)

/** Tailscale MagicDNS only — e.g. mac.tailxxxx.ts.net. Not arbitrary hostnames. */
fun isTailscaleMagicDns(host: String): Boolean {
    val h = host.trim().lowercase().trimEnd('.')
    if (h.length > 253 || !h.endsWith(".ts.net") || h == "ts.net") return false
    val labels = h.split('.')
    if (labels.size < 4) return false
    return labels.all { label ->
        label.isNotEmpty() && label.length <= 63 &&
            label[0].isLetterOrDigit() && label.last().isLetterOrDigit() &&
            label.all { ch -> ch.isLetterOrDigit() || ch == '-' }
    }
}

/**
 * RFC1918, loopback, or Tailscale CGNAT (100.64/10). Never a public hostname
 * or a routable public IPv4 (SSRF / DNS rebinding).
 */
fun isLanIpv4(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != 4) return false
    val nums = parts.map { part ->
        if (part.isEmpty() || (part.length > 1 && part[0] == '0')) return false
        part.toIntOrNull() ?: return false
    }
    if (nums.any { it !in 0..255 }) return false
    val a = nums[0]
    val b = nums[1]
    if (a == 10) return true
    if (a == 127 && nums[1] == 0 && nums[2] == 0 && nums[3] == 1) return true
    if (a == 192 && b == 168) return true
    if (a == 172 && b in 16..31) return true
    // Tailscale / RFC6598 shared address space — not a public IP.
    if (a == 100 && b in 64..127) return true
    return false
}

fun escapeJson(value: String): String = buildString(value.length + 8) {
    for (ch in value) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch.code < 32) {
                append("\\u")
                append(ch.code.toString(16).padStart(4, '0'))
            } else {
                append(ch)
            }
        }
    }
}

private fun decodeQuery(value: String): String =
    value.replace('+', ' ').replace(Regex("%[0-9A-Fa-f]{2}")) { match ->
        val code = match.value.substring(1).toInt(16)
        if (code in 32..126) code.toChar().toString() else ""
    }

data class ChatMessage(val role: String, val text: String)

data class SessionStatus(
    val phase: String,
    val detail: String,
    val model: String = "",
    val inbox: Int = 0,
    val tui: Boolean = false,
    val usagePercent: Int = 0,
    val tokensUsed: Int = 0,
    val tokensWindow: Int = 0,
    val turns: Int = 0,
    val compactions: Int = 0,
    val toolCalls: Int = 0,
    val durationSeconds: Int = 0,
    val tokensBeforeCompaction: Int = 0,
    val contextPercent: Int = 0,
    val billing: Boolean = false,
    val billingKind: String = "",
    val billingResets: String = "",
    val subscriptionTier: String = "",
    val hostname: String = "",
)

data class MirrorSnapshot(
    val messages: List<ChatMessage>,
    val status: SessionStatus? = null,
)

/**
 * Linear scan — do not use Regex on the companion payload. Kotlin/Native
 * Regex.findAll stack-overflows on a long session (this one crashed Pair).
 */
fun parseMessageList(json: String): List<ChatMessage> {
    if (json.isEmpty()) return emptyList()
    val src = if (json.length > 4_000_000) json.substring(json.length - 4_000_000) else json
    val out = ArrayList<ChatMessage>()
    val messagesKey = src.indexOf("\"messages\"")
    val arrayStart = if (messagesKey >= 0) src.indexOf('[', messagesKey) else -1
    var i = if (arrayStart >= 0) arrayStart else 0
    while (i < src.length) {
        val range = nextJsonObject(src, i) ?: break
        i = range.second + 1
        val obj = src.substring(range.first, range.second + 1)
        val role = jsonStringField(obj, "role") ?: continue
        if (role != "user" && role != "assistant") continue
        val text = jsonStringField(obj, "text")
            ?.let { cleanChatText(unescapeJson(it)).take(16_384) } ?: continue
        if (text.isNotEmpty()) out.add(ChatMessage(role, text))
    }
    return if (out.size > 2_000) out.takeLast(2_000) else out
}

fun parseSessionStatus(json: String): SessionStatus? {
    if (json.isEmpty()) return null
    var at = json.lastIndexOf("\"phase\"")
    while (at >= 0) {
        val range = nextJsonObject(json, json.lastIndexOf('{', at).coerceAtLeast(0)) ?: break
        val obj = json.substring(range.first, range.second + 1)
        val phase = jsonStringField(obj, "phase")?.let(::unescapeJson)
        if (phase != null && phase in setOf("idle", "thinking", "working", "queued")) {
            val tuiRaw = jsonStringField(obj, "tui")
            val tui = tuiRaw == "true" || obj.contains("\"tui\":true")
            return SessionStatus(
                phase = phase,
                detail = jsonStringField(obj, "detail")?.let(::unescapeJson).orEmpty(),
                model = jsonStringField(obj, "model")?.let(::unescapeJson).orEmpty(),
                inbox = jsonIntField(obj, "inbox") ?: 0,
                tui = tui,
                usagePercent = jsonIntField(obj, "usage_percent") ?: 0,
                tokensUsed = jsonIntField(obj, "tokens_used") ?: 0,
                tokensWindow = jsonIntField(obj, "tokens_window") ?: 0,
                turns = jsonIntField(obj, "turns") ?: 0,
                compactions = jsonIntField(obj, "compactions") ?: 0,
                toolCalls = jsonIntField(obj, "tool_calls") ?: 0,
                durationSeconds = jsonIntField(obj, "duration_seconds") ?: 0,
                tokensBeforeCompaction = jsonIntField(obj, "tokens_before_compaction") ?: 0,
                contextPercent = jsonIntField(obj, "context_percent") ?: 0,
                billing = obj.contains("\"billing\":true") || jsonStringField(obj, "billing") == "true",
                billingKind = jsonStringField(obj, "billing_kind")?.let(::unescapeJson).orEmpty(),
                billingResets = jsonStringField(obj, "billing_resets")?.let(::unescapeJson).orEmpty(),
                subscriptionTier = jsonStringField(obj, "subscription_tier")?.let(::unescapeJson).orEmpty(),
                hostname = jsonStringField(obj, "hostname")?.let(::unescapeJson).orEmpty(),
            )
        }
        at = json.lastIndexOf("\"phase\"", at - 1)
    }
    return null
}

fun parseSnapshot(json: String): MirrorSnapshot =
    MirrorSnapshot(parseMessageList(json), parseSessionStatus(json))

/** Strip TUI harness wrappers so bubbles show only the spoken line. */
fun cleanChatText(raw: String): String {
    var text = stripTagged(raw, "system-reminder", keepInner = false)
    text = stripTagged(text, "monitor-event", keepInner = true)
    return text.trim()
}

private fun stripTagged(src: String, tag: String, keepInner: Boolean): String {
    val open = "<$tag"
    val close = "</$tag>"
    val out = StringBuilder(src.length)
    var i = 0
    while (i < src.length) {
        val start = src.indexOf(open, i, ignoreCase = true)
        if (start < 0) {
            out.append(src, i, src.length)
            break
        }
        out.append(src, i, start)
        val gt = src.indexOf('>', start)
        if (gt < 0) {
            out.append(src, start, src.length)
            break
        }
        val end = src.indexOf(close, gt + 1, ignoreCase = true)
        if (end < 0) {
            out.append(src, start, src.length)
            break
        }
        if (keepInner) {
            var inner = src.substring(gt + 1, end).trim()
            val bracket = inner.indexOf(']')
            if (inner.startsWith("[") && bracket > 0) {
                inner = inner.substring(bracket + 1).trim()
            }
            if (inner.startsWith("MIRROR ", ignoreCase = true)) {
                inner = inner.substring(7)
            }
            out.append(inner.trim())
        }
        i = end + close.length
    }
    return out.toString()
}

private fun nextJsonObject(src: String, start: Int): Pair<Int, Int>? {
    val open = src.indexOf('{', start)
    if (open < 0) return null
    var depth = 0
    var inString = false
    var escape = false
    for (j in open until src.length) {
        val ch = src[j]
        if (inString) {
            when {
                escape -> escape = false
                ch == '\\' -> escape = true
                ch == '"' -> inString = false
            }
            continue
        }
        when (ch) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return open to j
            }
        }
    }
    return null
}

private fun jsonIntField(obj: String, name: String): Int? {
    val key = "\"$name\""
    val at = obj.indexOf(key)
    if (at < 0) return null
    var p = at + key.length
    while (p < obj.length && (obj[p].isWhitespace() || obj[p] == ':')) p++
    val start = p
    while (p < obj.length && obj[p] in '0'..'9') p++
    if (p == start) return null
    return obj.substring(start, p).toIntOrNull()
}

private fun jsonStringField(obj: String, name: String): String? {
    val key = "\"$name\""
    var i = 0
    while (i < obj.length) {
        val at = obj.indexOf(key, i)
        if (at < 0) return null
        var colon = at + key.length
        while (colon < obj.length && obj[colon].isWhitespace()) colon++
        if (colon >= obj.length || obj[colon] != ':') {
            i = at + 1
            continue
        }
        var p = colon + 1
        while (p < obj.length && obj[p].isWhitespace()) p++
        if (p >= obj.length || obj[p] != '"') return null
        return readJsonString(obj, p)
    }
    return null
}

private fun readJsonString(src: String, quoteIndex: Int): String? {
    if (quoteIndex >= src.length || src[quoteIndex] != '"') return null
    val out = StringBuilder()
    var i = quoteIndex + 1
    var escape = false
    while (i < src.length) {
        val ch = src[i]
        if (escape) {
            out.append(ch)
            escape = false
            i++
            continue
        }
        when (ch) {
            '\\' -> {
                escape = true
                out.append(ch)
            }
            '"' -> return out.toString()
            else -> out.append(ch)
        }
        i++
    }
    return null
}

internal fun unescapeJson(raw: String): String = buildString(raw.length) {
    var i = 0
    while (i < raw.length) {
        if (raw[i] != '\\' || i + 1 >= raw.length) {
            append(raw[i])
            i++
            continue
        }
        when (raw[i + 1]) {
            'n' -> { append('\n'); i += 2 }
            't' -> { append('\t'); i += 2 }
            'r' -> { append('\r'); i += 2 }
            '"' -> { append('"'); i += 2 }
            '\'' -> { append('\''); i += 2 }
            '\\' -> { append('\\'); i += 2 }
            '/' -> { append('/'); i += 2 }
            'b' -> { append('\b'); i += 2 }
            'f' -> { append('\u000C'); i += 2 }
            'u' -> {
                val code = parseHex4(raw, i + 2)
                if (code == null) {
                    append(raw[i])
                    i++
                } else {
                    append(code.toChar())
                    i += 6
                    if (code in 0xD800..0xDBFF && i + 5 < raw.length && raw[i] == '\\' && raw[i + 1] == 'u') {
                        val low = parseHex4(raw, i + 2)
                        if (low != null && low in 0xDC00..0xDFFF) {
                            append(low.toChar())
                            i += 6
                        }
                    }
                }
            }
            else -> {
                append(raw[i])
                i++
            }
        }
    }
}

private fun parseHex4(raw: String, at: Int): Int? {
    if (at + 3 >= raw.length) return null
    return raw.substring(at, at + 4).toIntOrNull(16)
}
