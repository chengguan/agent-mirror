package dev.chengguan.mirror.security

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionComplete
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

actual fun platformProtectedFiles(): ProtectedFiles = IosProtectedFiles()

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosProtectedFiles : ProtectedFiles {
    private fun path(name: String): String? {
        if (!safeFileName(name)) return null
        val bases = NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            true,
        )
        val base = bases.firstOrNull() as? String ?: return null
        val dir = "$base/Mirror"
        val fm = NSFileManager.defaultManager
        if (!fm.fileExistsAtPath(dir)) {
            fm.createDirectoryAtPath(dir, true, null, null)
        }
        fm.setAttributes(mapOf(NSFileProtectionKey to NSFileProtectionComplete), dir, null)
        return "$dir/$name"
    }

    override fun write(name: String, value: ByteArray): Boolean {
        if (value.size > MAX_ARCHIVE_BYTES) return false
        val dest = path(name) ?: return false
        val data = value.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = value.size.toULong())
        }
        if (!data.writeToFile(dest, atomically = true)) return false
        NSFileManager.defaultManager.setAttributes(
            mapOf(NSFileProtectionKey to NSFileProtectionComplete),
            dest,
            null,
        )
        return true
    }

    override fun read(name: String): ByteArray? {
        val dest = path(name) ?: return null
        val data = NSData.dataWithContentsOfFile(dest) ?: return null
        val length = data.length.toInt()
        if (length <= 0) return ByteArray(0)
        val out = ByteArray(length)
        out.usePinned { pinned ->
            memcpy(pinned.addressOf(0), data.bytes, data.length)
        }
        return out
    }

    override fun delete(name: String) {
        val dest = path(name) ?: return
        NSFileManager.defaultManager.removeItemAtPath(dest, null)
    }
}
