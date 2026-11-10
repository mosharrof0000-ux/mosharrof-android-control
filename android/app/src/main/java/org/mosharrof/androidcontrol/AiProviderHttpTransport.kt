package org.mosharrof.androidcontrol

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

enum class AiProviderRetryClass { NEVER, TRANSIENT, RATE_LIMITED }

/** Cancellation is cooperative between stages and disconnects an in-flight HTTP connection. */
class AiProviderCancellation {
    private val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    private val activeConnection = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>(null)

    fun cancel() {
        cancelled.set(true)
        activeConnection.get()?.disconnect()
    }

    internal fun attach(connection: HttpURLConnection) {
        activeConnection.set(connection)
        throwIfCancelled()
    }

    internal fun detach(connection: HttpURLConnection) {
        activeConnection.compareAndSet(connection, null)
    }

    fun throwIfCancelled() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) {
            throw java.util.concurrent.CancellationException("AI request was cancelled.")
        }
    }

    fun isCancelled(): Boolean = cancelled.get()
}

class AiProviderHttpException(
    val statusCode: Int,
    val retryClass: AiProviderRetryClass
) : IllegalStateException("AI provider returned HTTP $statusCode.")

data class AiProviderHttpResponse(
    val statusCode: Int,
    val payload: JSONObject
)

/**
 * Bounded HTTPS POST transport shared by all outbound provider adapters.
 * It does not retry POSTs automatically: model requests may incur duplicate
 * charges or side effects if a previous response was lost.
 */
class AiProviderHttpTransport(
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    }
) {
    companion object {
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 60_000
        const val MAX_REQUEST_BYTES = 512 * 1024
        const val MAX_RESPONSE_BYTES = 256 * 1024

        private val ALLOWED_HEADERS = setOf(
            "content-type",
            "authorization",
            "x-goog-api-key",
            "x-api-key",
            "anthropic-version"
        )
    }

    fun post(\n        request: AiProviderHttpRequest,\n        cancellation: AiProviderCancellation = AiProviderCancellation()\n    ): AiProviderHttpResponse {
        cancellation.throwIfCancelled()\n        val uri = runCatching { URI(request.url) }.getOrElse {
            throw IllegalArgumentException("AI provider URL is invalid.")
        }
        require(
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.fragment == null &&
                uri.query == null
        ) { "AI provider requests require a fixed HTTPS URL without credentials or query parameters." }

        require(request.headers.keys.any { it.equals("Content-Type", ignoreCase = true) }) {
            "AI provider request is missing Content-Type."
        }
        request.headers.forEach { (name, value) ->
            require(name.lowercase() in ALLOWED_HEADERS) {
                "AI provider request contains an unsupported header."
            }
            require(name.matches(Regex("[A-Za-z0-9-]{1,64}"))) {
                "AI provider header name is invalid."
            }
            require(value.none { it.isISOControl() }) {
                "AI provider header value contains control characters."
            }
            require(value.length <= 8_192) {
                "AI provider header value exceeds the configured limit."
            }
        }

        val bodyBytes = JSONObject(request.body).toString().toByteArray(Charsets.UTF_8)
        require(bodyBytes.size <= MAX_REQUEST_BYTES) {
            "AI provider request body exceeds the configured limit."
        }

        val connection = connectionFactory(URL(request.url))
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            request.headers.forEach { (name, value) ->
                connection.setRequestProperty(name, value)
            }
            connection.setFixedLengthStreamingMode(bodyBytes.size)
            connection.outputStream.use { output -> output.write(bodyBytes) }

            val status = connection.responseCode
            val responseStream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val raw = readBoundedAiResponse(responseStream, MAX_RESPONSE_BYTES)
            if (status !in 200..299) {
                throw AiProviderHttpException(status, classifyStatus(status))
            }
            require(raw.isNotBlank()) { "AI provider returned an empty response." }
            return AiProviderHttpResponse(status, JSONObject(raw))
        } finally {
            connection.disconnect()
        }
    }

    private fun classifyStatus(status: Int): AiProviderRetryClass = when (status) {
        408, 425, 500, 502, 503, 504 -> AiProviderRetryClass.TRANSIENT
        429 -> AiProviderRetryClass.RATE_LIMITED
        else -> AiProviderRetryClass.NEVER
    }
}
