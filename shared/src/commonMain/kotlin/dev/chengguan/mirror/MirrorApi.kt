package dev.chengguan.mirror

/**
 * HTTPS to the Mac companion. The actuals pin [Pairing.fingerprint]
 * (OWASP M5). ATS stays on; we do not set NSAllowsArbitraryLoads.
 */
interface MirrorApi {
    suspend fun fetchMessages(): Result<List<ChatMessage>>
    suspend fun send(text: String): Result<List<ChatMessage>>
}

expect fun platformMirrorApi(pairing: Pairing): MirrorApi
