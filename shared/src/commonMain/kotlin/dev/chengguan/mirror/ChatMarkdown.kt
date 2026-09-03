package dev.chengguan.mirror

enum class ChatStyle { Normal, Bold, Italic, Code, Path, Color }

data class ChatRun(val text: String, val style: ChatStyle, val colorName: String? = null)

/** Named colors only — do not accept raw hex from the payload. */
val CHAT_COLOR_NAMES = setOf("red", "amber", "green", "blue")

/**
 * Tiny inline markdown for chat bubbles. Markers are stripped; unmatched
 * asterisks stay visible. Not HTML — spans only (OWASP M7).
 */
fun parseInlineMarkdown(raw: String): List<ChatRun> {
    if (raw.isEmpty()) return emptyList()
    val out = ArrayList<ChatRun>()
    val buf = StringBuilder()
    var style = ChatStyle.Normal
    var colorName: String? = null
    fun flush() {
        if (buf.isNotEmpty()) {
            out.add(ChatRun(buf.toString(), style, colorName))
            buf.clear()
        }
    }
    fun emit(text: String, next: ChatStyle, nextColor: String? = null) {
        if (text.isEmpty()) return
        if ((style != next || colorName != nextColor) && buf.isNotEmpty()) flush()
        style = next
        colorName = nextColor
        buf.append(text)
    }

    var i = 0
    while (i < raw.length) {
        val color = colorTagAt(raw, i)
        when {
            color != null -> {
                emit(color.second, ChatStyle.Color, color.first)
                i = color.third
            }
            raw.startsWith("**", i) -> {
                val end = raw.indexOf("**", i + 2)
                if (end > i + 2) {
                    emit(raw.substring(i + 2, end), ChatStyle.Bold)
                    i = end + 2
                } else {
                    emit("**", ChatStyle.Normal)
                    i += 2
                }
            }
            raw[i] == '`' -> {
                val end = raw.indexOf('`', i + 1)
                if (end > i + 1) {
                    emit(raw.substring(i + 1, end), ChatStyle.Code)
                    i = end + 1
                } else {
                    emit("`", ChatStyle.Normal)
                    i++
                }
            }
            raw[i] == '*' -> {
                val end = nextSingleAsterisk(raw, i + 1)
                if (end > i + 1) {
                    emit(raw.substring(i + 1, end), ChatStyle.Italic)
                    i = end + 1
                } else {
                    emit("*", ChatStyle.Normal)
                    i++
                }
            }
            else -> {
                emit(raw[i].toString(), ChatStyle.Normal)
                i++
            }
        }
    }
    flush()
    return paintTuiTokens(out)
}

private fun paintTuiTokens(runs: List<ChatRun>): List<ChatRun> {
    val token = Regex("""[^\s]+""")
    val out = ArrayList<ChatRun>(runs.size)
    for (run in runs) {
        if (run.style != ChatStyle.Normal) {
            out.add(run)
            continue
        }
        var last = 0
        for (match in token.findAll(run.text)) {
            if (match.range.first > last) {
                out.add(ChatRun(run.text.substring(last, match.range.first), ChatStyle.Normal))
            }
            val rawTok = match.value
            val core = rawTok.trimEnd { it in ".,;:!?)" }
            val style = if (isPath(core)) ChatStyle.Path else ChatStyle.Normal
            if (core.length < rawTok.length) {
                out.add(ChatRun(core, style))
                out.add(ChatRun(rawTok.substring(core.length), ChatStyle.Normal))
            } else {
                out.add(ChatRun(rawTok, style))
            }
            last = match.range.last + 1
        }
        if (last < run.text.length) {
            out.add(ChatRun(run.text.substring(last), ChatStyle.Normal))
        }
    }
    return mergeAdjacent(out)
}

private fun mergeAdjacent(runs: List<ChatRun>): List<ChatRun> {
    if (runs.isEmpty()) return runs
    val out = ArrayList<ChatRun>(runs.size)
    var cur = runs[0]
    for (i in 1 until runs.size) {
        val next = runs[i]
        if (next.style == cur.style && next.colorName == cur.colorName) {
            cur = ChatRun(cur.text + next.text, cur.style, cur.colorName)
        } else {
            out.add(cur)
            cur = next
        }
    }
    out.add(cur)
    return out
}

private fun isPath(token: String): Boolean {
    if (token.startsWith("https://") || token.startsWith("http://") || token.startsWith("mirror://")) {
        return false
    }
    if (token.startsWith("~/") || token.startsWith("/") || token.startsWith("./")) {
        return token.length > 2
    }
    return '/' in token && '.' in token
}

/** Triple of (name, inner text, index after the closing tag). */
private fun colorTagAt(raw: String, i: Int): Triple<String, String, Int>? {
    if (i >= raw.length || raw[i] != '[') return null
    val close = raw.indexOf(']', i + 1)
    if (close <= i + 1) return null
    val name = raw.substring(i + 1, close).lowercase()
    if (name !in CHAT_COLOR_NAMES) return null
    val endTag = "[/$name]"
    val end = raw.indexOf(endTag, close + 1, ignoreCase = true)
    if (end <= close + 1) return null
    return Triple(name, raw.substring(close + 1, end), end + endTag.length)
}

private fun nextSingleAsterisk(raw: String, from: Int): Int {
    var i = from
    while (i < raw.length) {
        if (raw[i] == '*' && !raw.startsWith("**", i)) return i
        if (raw.startsWith("**", i)) i += 2 else i++
    }
    return -1
}
