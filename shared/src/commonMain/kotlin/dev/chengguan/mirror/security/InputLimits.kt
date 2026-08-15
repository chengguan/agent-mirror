package dev.chengguan.mirror.security

/** OWASP M4 — pairing URLs, drafts, and Keychain payloads are untrusted. */
object InputLimits {
    const val MAX_STORE_BYTES = 8 * 1024
    const val USER_MESSAGE_MAX = 240
    const val DRAFT_MAX = 16_384
}

fun sanitizeUserMessage(raw: String): String {
    val trimmed = raw.trim().replace(Regex("[\\p{Cntrl}&&[^\n]]"), "")
    return trimmed.take(InputLimits.USER_MESSAGE_MAX)
}

fun constantTimeEquals(left: String, right: String): Boolean {
    if (left.length != right.length) return false
    var acc = 0
    for (i in left.indices) {
        acc = acc or (left[i].code xor right[i].code)
    }
    return acc == 0
}
