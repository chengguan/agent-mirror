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
        request("GET", "/v1/messages", null)

    override suspend fun send(text: String): Result<List<ChatMessage>> =
        request("POST", "/v1/message", """{"text":"${escapeJson(text)}"}""")

    private suspend fun request(
        method: String,
        path: String,
        body: String?,
    ): Result<List<ChatMessage>> = suspendCancellableCoroutine { continuation ->
        val client = IosHttp.client
        if (client == null) {
            continuation.resume(Result.failure(IllegalStateException("http not installed")))
            return@suspendCancellableCoroutine
        }
        val url = "https://${pairing.host}:${pairing.port}$path"
        client.request(method, url, pairing.token, pairing.fingerprint, pairing.host, body) { payload, error ->
            if (!continuation.isActive) return@request
            if (error != null || payload == null) {
                continuation.resume(Result.failure(IllegalStateException(error ?: "empty")))
            } else {
                continuation.resume(Result.success(parseMessageList(payload)))
            }
        }
    }
}
