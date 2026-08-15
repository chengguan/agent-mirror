package dev.chengguan.mirror.security

/** Keychain / Keystore only. Never UserDefaults. */
interface SecureStore {
    fun write(key: String, value: ByteArray): Boolean
    fun read(key: String): ByteArray?
    fun delete(key: String)
}

expect fun platformSecureStore(): SecureStore

const val PAIRING_KEY = "pairing.v1"
const val STATUS_EXPANDED_KEY = "status.expanded.v1"
