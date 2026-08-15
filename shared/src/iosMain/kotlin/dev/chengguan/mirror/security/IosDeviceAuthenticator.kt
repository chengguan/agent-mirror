package dev.chengguan.mirror.security

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import kotlin.coroutines.resume

actual fun platformDeviceAuthenticator(): DeviceAuthenticator = IosDeviceAuthenticator()

/**
 * LocalAuthentication is Apple's owner-auth API (Face ID / Touch ID / passcode).
 * LAPolicyDeviceOwnerAuthentication allows passcode fallback.
 */
class IosDeviceAuthenticator : DeviceAuthenticator {
    @OptIn(ExperimentalForeignApi::class)
    override val canAuthenticate: Boolean
        get() = LAContext().canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, error = null)

    override suspend fun authenticate(reason: String): AuthResult {
        if (!canAuthenticate) return AuthResult.Unavailable
        val safeReason = sanitizeUserMessage(reason).ifEmpty { "Unlock Mirror" }
        return suspendCancellableCoroutine { continuation ->
            val context = LAContext()
            context.localizedCancelTitle = "Cancel"
            context.evaluatePolicy(
                LAPolicyDeviceOwnerAuthentication,
                localizedReason = safeReason,
            ) { success, error ->
                if (!continuation.isActive) return@evaluatePolicy
                when {
                    success -> continuation.resume(AuthResult.Success)
                    error == null -> continuation.resume(AuthResult.Failed)
                    error.code == LA_ERROR_USER_CANCEL ||
                        error.code == LA_ERROR_SYSTEM_CANCEL ||
                        error.code == LA_ERROR_APP_CANCEL ->
                        continuation.resume(AuthResult.Cancelled)
                    else -> continuation.resume(AuthResult.Failed)
                }
            }
        }
    }
}

private const val LA_ERROR_USER_CANCEL = -2L
private const val LA_ERROR_SYSTEM_CANCEL = -4L
private const val LA_ERROR_APP_CANCEL = -9L
