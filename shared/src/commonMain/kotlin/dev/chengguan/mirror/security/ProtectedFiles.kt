package dev.chengguan.mirror.security

/** App-sandbox files with data protection. Not for pairing tokens. */
interface ProtectedFiles {
    fun write(name: String, value: ByteArray): Boolean
    fun read(name: String): ByteArray?
    fun delete(name: String)
}

expect fun platformProtectedFiles(): ProtectedFiles

const val MAX_ARCHIVE_BYTES = 256 * 1024

fun safeFileName(name: String): Boolean =
    name.isNotEmpty() && name.length <= 80 && name.all { ch ->
        ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-'
    }
