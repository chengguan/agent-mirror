package dev.chengguan.mirror.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val AUTHENTICATORS = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

actual fun platformDeviceAuthenticator(): DeviceAuthenticator = AndroidDeviceAuthenticator()

class AndroidDeviceAuthenticator : DeviceAuthenticator {
    override val canAuthenticate: Boolean
        get() {
            val context = AndroidSecurityHost.requireContext() ?: return false
            val status = BiometricManager.from(context)
                .canAuthenticate(AUTHENTICATORS)
            return status == BiometricManager.BIOMETRIC_SUCCESS
        }

    override suspend fun authenticate(reason: String): AuthResult {
        val activity = AndroidSecurityHost.activity?.get()
            ?: return AuthResult.Unavailable
        if (!canAuthenticate) return AuthResult.Unavailable
        val safeReason = sanitizeUserMessage(reason).ifEmpty { "Unlock Mirror" }

        return suspendCancellableCoroutine { continuation ->
            val executor = ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (continuation.isActive) continuation.resume(AuthResult.Success)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (!continuation.isActive) return
                        continuation.resume(
                            when (errorCode) {
                                BiometricPrompt.ERROR_USER_CANCELED,
                                BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                                BiometricPrompt.ERROR_CANCELED,
                                -> AuthResult.Cancelled
                                BiometricPrompt.ERROR_HW_NOT_PRESENT,
                                BiometricPrompt.ERROR_HW_UNAVAILABLE,
                                BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
                                BiometricPrompt.ERROR_NO_BIOMETRICS,
                                -> AuthResult.Unavailable
                                else -> AuthResult.Failed
                            },
                        )
                    }

                    override fun onAuthenticationFailed() {
                        // Intermediate failure (wrong face). Wait for error/success.
                    }
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Mirror")
                .setSubtitle(safeReason)
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build()
            activity.runOnUiThread { prompt.authenticate(info) }
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        }
    }
}