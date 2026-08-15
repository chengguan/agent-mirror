package dev.chengguan.mirror.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.fragment.app.FragmentActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.lang.ref.WeakReference

/**
 * Application context for Keystore-backed storage and BiometricPrompt.
 * Set from MirrorApplication / MainActivity only.
 */
object AndroidSecurityHost {
    @Volatile
    var context: WeakReference<Context>? = null

    @Volatile
    var activity: WeakReference<FragmentActivity>? = null

    fun requireContext(): Context? = context?.get()?.applicationContext
}

actual fun platformSecureStore(): SecureStore = AndroidSecureStore()

/**
 * Android Keystore MasterKey + EncryptedSharedPreferences (AES256-GCM).
 * This is the Android counterpart of Keychain — not plaintext SharedPreferences.
 */
class AndroidSecureStore : SecureStore {
    private val prefs: SharedPreferences? by lazy { openPrefs() }

    override fun write(key: String, value: ByteArray): Boolean {
        if (key.isEmpty() || value.size > InputLimits.MAX_STORE_BYTES) return false
        val encoded = Base64.encodeToString(value, Base64.NO_WRAP)
        return runCatching {
            prefs?.edit()?.putString(key, encoded)?.commit() == true
        }.getOrDefault(false)
    }

    override fun read(key: String): ByteArray? {
        val encoded = prefs?.getString(key, null) ?: return null
        return runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()
    }

    override fun delete(key: String) {
        prefs?.edit()?.remove(key)?.commit()
    }

    private fun openPrefs(): SharedPreferences? {
        val context = AndroidSecurityHost.requireContext() ?: return null
        return runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "mirror_keystore",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrNull()
    }
}
