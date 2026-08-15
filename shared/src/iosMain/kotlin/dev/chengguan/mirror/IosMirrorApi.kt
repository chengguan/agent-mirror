package dev.chengguan.mirror

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Installed from Swift (`PinnedCompanionClient`) so pinning uses CryptoKit +
 * URLSession — Apple APIs, not a homemade digest (AGENTS.md).
 */
fun interface IosHttpClient {
    fun request(
        method: String,
        url: String,
        token: String,
        pin: String,
        host: String,
        body: String?,
        onResult: (payload: String?, error: String?) -> Unit,
    )
}

object IosHttp {
    var client: IosHttpClient? = null
}

actual fun platformMirrorApi(pairing: Pairing): MirrorApi = IosMirrorApi(pairing)

class IosMirrorApi(private val pairing: Pairing) : MirrorApi {
    override suspend fun fetchMessages(): Result<List<ChatMessage>> =
        pull().map { it.messages }

    override suspend fun send(text: String): Result<List<ChatMessage>> =
        sendSnapshot(text).map { it.messages }

    override suspend fun pull(): Result<MirrorSnapshot> =
        request("GET", "/v1/messages", null)

    private suspend fun sendSnapshot(text: String): Result<MirrorSnapshot> =
        request("POST", "/v1/message", """{"text":"${escapeJson(text)}"}""")

    private suspend fun request(
        method: String,
        path: String,
        body: String?,
    ): Result<MirrorSnapshot> = suspendCancellableCoroutine { continuation ->
        val client = IosHttp.client
        if (client == null) {
            continuation.resume(Result.failure(IllegalStateException("http not installed")))
            return@suspendCancellableCoroutine
        }
        val url = "https://${pairing.host}:${pairing.port}$path"
        var resumed = false
        client.request(method, url, pairing.token, pairing.fingerprint, pairing.host, body) { payload, error ->
            if (resumed || !continuation.isActive) return@request
            resumed = true
            val result = if (error != null || payload == null) {
                Result.failure(IllegalStateException(error ?: "empty"))
            } else {
                runCatching { parseSnapshot(payload) }.getOrElse { MirrorSnapshot(emptyList()) }.let { Result.success(it) }
            }
            runCatching { continuation.resume(result) }
        }
    }
}
