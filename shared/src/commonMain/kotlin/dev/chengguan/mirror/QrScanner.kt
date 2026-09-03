package dev.chengguan.mirror

/**
 * Process-local QR host. iOS/Android install a native camera or photo-album
 * reader that never logs the payload (it contains tok). Pairing validation
 * stays in [parsePairing] — the host only returns the raw string.
 */
interface QrScanHost {
    fun scan(onResult: (payload: String?, error: String?) -> Unit)
    fun pickFromAlbum(onResult: (payload: String?, error: String?) -> Unit)
}

object QrScanner {
    var host: QrScanHost? = null
}

fun looksLikePairingPayload(raw: String): Boolean {
    val trimmed = raw.trim()
    if (trimmed.length > 2_000) return false
    return trimmed.startsWith("mirror://", ignoreCase = true) ||
        trimmed.startsWith("MIRROR:")
}
