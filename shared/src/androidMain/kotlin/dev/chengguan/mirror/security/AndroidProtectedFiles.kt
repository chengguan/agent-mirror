package dev.chengguan.mirror.security

import java.io.File

actual fun platformProtectedFiles(): ProtectedFiles = AndroidProtectedFiles()

class AndroidProtectedFiles : ProtectedFiles {
    private fun file(name: String): File? {
        if (!safeFileName(name)) return null
        val ctx = AndroidSecurityHost.requireContext() ?: return null
        val dir = File(ctx.filesDir, "mirror")
        if (!dir.exists() && !dir.mkdirs()) return null
        return File(dir, name)
    }

    override fun write(name: String, value: ByteArray): Boolean {
        if (value.size > MAX_ARCHIVE_BYTES) return false
        val target = file(name) ?: return false
        return runCatching { target.writeBytes(value); true }.getOrDefault(false)
    }

    override fun read(name: String): ByteArray? {
        val target = file(name) ?: return null
        if (!target.isFile) return null
        return runCatching { target.readBytes() }.getOrNull()
    }

    override fun delete(name: String) {
        file(name)?.delete()
    }
}
