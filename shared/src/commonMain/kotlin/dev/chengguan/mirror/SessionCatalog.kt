package dev.chengguan.mirror

import dev.chengguan.mirror.security.InputLimits
import dev.chengguan.mirror.security.MAX_ARCHIVE_BYTES

const val ADD_TAB_ID = "+"
const val MAX_SESSIONS = 12
const val SESSIONS_KEY = "sessions.v1"
const val BACKGROUND_REFRESH_KEY = "bg.refresh.v1"

data class SessionRecord(
    val id: String,
    val name: String,
    val pairing: Pairing?,
    val archived: Boolean,
    val unread: Boolean = false,
)

fun defaultSessionName(pairing: Pairing, hostname: String? = null): String {
    val reported = hostname?.trim().orEmpty()
    if (reported.isNotEmpty() && !reported.equals("localhost", ignoreCase = true)) {
        return reported.take(40)
    }
    val host = pairing.host.trim()
    if (host.endsWith(".ts.net", ignoreCase = true) && host.contains('.')) {
        return host.substringBefore('.').take(40)
    }
    return host.take(40)
}

fun draftStoreKey(sessionId: String): String = "draft.$sessionId"

fun archiveFileName(sessionId: String): String = "archive.$sessionId"

fun encodeSessionCatalog(records: List<SessionRecord>, activeId: String?): String {
    val items = records.take(MAX_SESSIONS).joinToString(",") { rec ->
        val pairingUrl = rec.pairing?.let(::encodePairing).orEmpty()
        """{"id":"${escapeJson(rec.id)}","name":"${escapeJson(rec.name.take(40))}",""" +
            """"a":${if (rec.archived) 1 else 0},"p":"${escapeJson(pairingUrl)}"}"""
    }
    return """{"v":1,"active":"${escapeJson(activeId.orEmpty())}","items":[$items]}"""
}

fun parseSessionCatalog(raw: String): Pair<List<SessionRecord>, String?> {
    if (raw.isEmpty() || raw.length > InputLimits.MAX_STORE_BYTES) {
        return emptyList<SessionRecord>() to null
    }
    val itemsKey = raw.indexOf("\"items\"")
    val arrayStart = if (itemsKey >= 0) raw.indexOf('[', itemsKey) else -1
    if (arrayStart < 0) return emptyList<SessionRecord>() to null
    val out = ArrayList<SessionRecord>()
    var i = arrayStart
    while (i < raw.length && out.size < MAX_SESSIONS) {
        val range = nextCatalogObject(raw, i) ?: break
        i = range.second + 1
        val obj = raw.substring(range.first, range.second + 1)
        val id = jsonCatalogString(obj, "id")?.let(::unescapeJson)?.trim().orEmpty()
        if (id.length !in 8..80) continue
        val name = jsonCatalogString(obj, "name")?.let(::unescapeJson)?.trim().orEmpty().take(40)
        val archived = obj.contains("\"a\":1") || jsonCatalogString(obj, "a") == "1"
        val pairingRaw = jsonCatalogString(obj, "p")?.let(::unescapeJson).orEmpty()
        val pairing = if (pairingRaw.isEmpty()) null else parsePairing(pairingRaw)
        if (!archived && pairing == null) continue
        out.add(
            SessionRecord(
                id = id,
                name = name.ifEmpty { pairing?.let { defaultSessionName(it) } ?: id.take(8) },
                pairing = if (archived) null else pairing,
                archived = archived,
            ),
        )
    }
    val active = jsonCatalogString(raw, "active")?.let(::unescapeJson)?.trim().orEmpty()
    val activeId = active.takeIf { want -> out.any { it.id == want } }
    return out to activeId
}

fun migrateLegacyPairing(raw: String): SessionRecord? {
    val pairing = parsePairing(raw) ?: return null
    return SessionRecord(
        id = pairing.sessionId,
        name = defaultSessionName(pairing),
        pairing = pairing,
        archived = false,
    )
}

fun encodeArchive(messages: List<ChatMessage>, maxBytes: Int = MAX_ARCHIVE_BYTES): String {
    var take = minOf(200, messages.size)
    var textCap = 4_096
    if (take == 0) return """{"messages":[]}"""
    while (take > 0) {
        val clipped = messages.takeLast(take).map { it.copy(text = it.text.take(textCap)) }
        val body = clipped.joinToString(",") { message ->
            """{"role":"${escapeJson(message.role)}","text":"${escapeJson(message.text)}"}"""
        }
        val raw = """{"messages":[$body]}"""
        if (raw.encodeToByteArray().size <= maxBytes) return raw
        if (textCap > 512) textCap /= 2 else take = (take * 3 / 4).coerceAtLeast(0)
        if (take == 0) return """{"messages":[]}"""
    }
    return """{"messages":[]}"""
}

fun parseArchive(raw: String): List<ChatMessage> = parseMessageList(raw)

private fun jsonCatalogString(obj: String, name: String): String? {
    val key = "\"$name\""
    val at = obj.indexOf(key)
    if (at < 0) return null
    var p = at + key.length
    while (p < obj.length && (obj[p].isWhitespace() || obj[p] == ':')) p++
    if (p >= obj.length) return null
    if (obj[p] != '"') return null
    return readCatalogString(obj, p)
}

private fun readCatalogString(src: String, quoteIndex: Int): String? {
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

private fun nextCatalogObject(src: String, start: Int): Pair<Int, Int>? {
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
