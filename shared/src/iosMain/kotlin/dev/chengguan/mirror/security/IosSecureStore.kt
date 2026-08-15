package dev.chengguan.mirror.security

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy

private const val SERVICE = "dev.chengguan.mirror.secure"

actual fun platformSecureStore(): SecureStore = IosSecureStore()

/**
 * Apple Keychain via Security.framework — the required store for secrets
 * (AGENTS.md). Accessibility is ThisDeviceOnly so the item is not backed up
 * to iCloud or migrated off-device.
 */
@OptIn(ExperimentalForeignApi::class)
class IosSecureStore : SecureStore {
    override fun write(key: String, value: ByteArray): Boolean {
        if (key.isEmpty() || value.size > InputLimits.MAX_STORE_BYTES) return false
        delete(key)
        val data = value.toNSData() ?: return false
        return memScoped {
            val dict = mutableDict()
            CFDictionaryAddValue(dict, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(dict, kSecAttrService, SERVICE.bridge())
            CFDictionaryAddValue(dict, kSecAttrAccount, key.bridge())
            CFDictionaryAddValue(dict, kSecValueData, data.bridge())
            CFDictionaryAddValue(
                dict,
                kSecAttrAccessible,
                kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            )
            SecItemAdd(dict, null) == errSecSuccess
        }
    }

    override fun read(key: String): ByteArray? = memScoped {
        val dict = mutableDict()
        CFDictionaryAddValue(dict, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(dict, kSecAttrService, SERVICE.bridge())
        CFDictionaryAddValue(dict, kSecAttrAccount, key.bridge())
        CFDictionaryAddValue(dict, kSecReturnData, kCFBooleanTrue)
        CFDictionaryAddValue(dict, kSecMatchLimit, kSecMatchLimitOne)
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(dict, result.ptr)
        if (status != errSecSuccess) return@memScoped null
        val data = CFBridgingRelease(result.value) as? NSData ?: return@memScoped null
        data.toByteArray()
    }

    override fun delete(key: String) {
        memScoped {
            val dict = mutableDict()
            CFDictionaryAddValue(dict, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(dict, kSecAttrService, SERVICE.bridge())
            CFDictionaryAddValue(dict, kSecAttrAccount, key.bridge())
            SecItemDelete(dict)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun mutableDict(): CFDictionaryRef =
    CFDictionaryCreateMutable(kCFAllocatorDefault, 8, null, null)!!

@OptIn(ExperimentalForeignApi::class)
private fun String.bridge(): CFTypeRef? = CFBridgingRetain(this)

@OptIn(ExperimentalForeignApi::class)
private fun NSData.bridge(): CFTypeRef? = CFBridgingRetain(this)

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData? {
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val out = ByteArray(length.toInt())
    if (out.isEmpty()) return out
    out.usePinned { pinned ->
        memcpy(pinned.addressOf(0), bytes, length)
    }
    return out
}
