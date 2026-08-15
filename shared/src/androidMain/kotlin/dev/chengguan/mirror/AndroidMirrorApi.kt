package dev.chengguan.mirror

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

actual fun platformMirrorApi(pairing: Pairing): MirrorApi = AndroidMirrorApi(pairing)

/**
 * HttpsURLConnection with a leaf SHA-256 pin from the pairing QR (OWASP M5).
 * System CAs are not used: the companion cert is self-signed. Trust is the pin,
 * plus an exact IPv4 hostname match. Cleartext is never used.
 */
class AndroidMirrorApi(private val pairing: Pairing) : MirrorApi {
    override suspend fun fetchMessages(): Result<List<ChatMessage>> =
        request("GET", "/v1/messages", null)

    override suspend fun send(text: String): Result<List<ChatMessage>> =
        request("POST", "/v1/message", """{"text":"${escapeJson(text)}"}""")

    private suspend fun request(
        method: String,
        path: String,
        body: String?,
    ): Result<List<ChatMessage>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("https://${pairing.host}:${pairing.port}$path")
            val conn = (url.openConnection() as HttpsURLConnection).apply {
                sslSocketFactory = pinningContext(pairing.fingerprint).socketFactory
                hostnameVerifier = HostnameVerifier { host, _ -> host == pairing.host }
                requestMethod = method
                connectTimeout = 10_000
                readTimeout = if (method == "POST") 180_000 else 20_000
                instanceFollowRedirects = false
                setRequestProperty("Authorization", "Bearer ${pairing.token}")
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            try {
                if (body != null) {
                    conn.outputStream.use { it.write(body.encodeToByteArray()) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val payload = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) error("http $code")
                parseMessageList(payload)
            } finally {
                conn.disconnect()
            }
        }
    }
}

private fun pinningContext(expectedHex: String): SSLContext {
    val expected = expectedHex.lowercase()
    val tm = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            throw CertificateException("client certificates are not used")
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (chain.isEmpty()) throw CertificateException("empty chain")
            val leaf = chain[0]
            leaf.checkValidity()
            val digest = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
            val hex = digest.joinToString("") { byte ->
                val v = byte.toInt() and 0xFF
                v.toString(16).padStart(2, '0')
            }
            if (!MessageDigest.isEqual(hex.encodeToByteArray(), expected.encodeToByteArray())) {
                throw CertificateException("pin mismatch")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    return SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(tm), SecureRandom())
    }
}
