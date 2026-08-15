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
    if (!isLanIpv4(host)) return null
    if (port !in 1..65535) return null
    if (!sid.matches(Regex("""^[A-Za-z0-9_-]{8,80}$"""))) return null
    if (tok.length < 16 || tok.length > 128) return null
    if (!fp.matches(Regex("""^[0-9a-f]{64}$"""))) return null
    return Pairing(host, port, sid, tok, fp)
}

/** RFC1918 or loopback only — v1 is same-Wi-Fi, never a public hostname. */
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

fun parseMessageList(json: String): List<ChatMessage> {
    if (json.length > 2_000_000) return emptyList()
    val out = ArrayList<ChatMessage>()
    val regex = Regex(
        """\{[^{}]*"role"\s*:\s*"(user|assistant)"[^{}]*"text"\s*:\s*"((?:\\.|[^"\\])*)"|\{[^{}]*"text"\s*:\s*"((?:\\.|[^"\\])*)"[^{}]*"role"\s*:\s*"(user|assistant)\"""",
    )
    for (m in regex.findAll(json)) {
        val role = m.groupValues[1].ifEmpty { m.groupValues[4] }
        val raw = m.groupValues[2].ifEmpty { m.groupValues[3] }
        val text = unescapeJson(raw).take(16_384)
        if (role.isNotEmpty() && text.isNotEmpty()) {
            out.add(ChatMessage(role, text))
        }
    }
    return out
}

private fun unescapeJson(raw: String): String =
    raw.replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\")
