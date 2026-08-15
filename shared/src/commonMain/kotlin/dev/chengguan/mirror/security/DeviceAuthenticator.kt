package dev.chengguan.mirror.security

sealed class AuthResult {
    data object Success : AuthResult()
    data object Cancelled : AuthResult()
    data object Failed : AuthResult()
    data object Unavailable : AuthResult()
}

interface DeviceAuthenticator {
    val canAuthenticate: Boolean
    suspend fun authenticate(reason: String): AuthResult
}

expect fun platformDeviceAuthenticator(): DeviceAuthenticator
