package org.mosharrof.androidcontrol

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

/**
 * RFC 7009 revocation transport for public OAuth clients.
 *
 * Call only from a background dispatcher. The caller must supply an endpoint
 * obtained from validated authorization-server metadata and must not delete
 * local credentials unless this function succeeds.
 */
internal object McpOAuthTokenRevocation {
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val MAX_TOKEN_CHARS = 8_192
    private const val MAX_CLIENT_ID_CHARS = 4_096

    fun revoke(
        revocationEndpoint: String,
        clientId: String,
        token: String,
        tokenTypeHint: String = "refresh_token",
        post: (URL, ByteArray, Map<String, String>) -> Int = ::postForm
    ): Result<Unit> = runCatching {
        require(isSafeRevocationEndpoint(revocationEndpoint)) {
            "OAuth revocation endpoint নিরাপদ HTTPS URL নয়।"
        }
        require(clientId.isNotBlank() && clientId.length <= MAX_CLIENT_ID_CHARS) {
            "OAuth client ID অবৈধ।"
        }
        require(token.isNotBlank() && token.length <= MAX_TOKEN_CHARS) {
            "OAuth token অবৈধ।"
        }
        require(tokenTypeHint == "access_token" || tokenTypeHint == "refresh_token") {
            "OAuth token type hint অবৈধ।"
        }

        val form = listOf(
            "token" to token,
            "token_type_hint" to tokenTypeHint,
            "client_id" to clientId
        ).joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }.toByteArray(Charsets.UTF_8)

        val headers = mapOf(
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "Accept" to "application/json"
        )
        val status = post(URL(revocationEndpoint), form, headers)
        require(status == HttpURLConnection.HTTP_OK) {
            "OAuth revocation server সফল HTTP 200 response দেয়নি।"
        }
    }

    private fun postForm(
        url: URL,
        body: ByteArray,
        headers: Map<String, String>
    ): Int {
        val connection = (url.openConnection() as? HttpURLConnection)
            ?: throw IllegalStateException("OAuth revocation connection তৈরি করা যায়নি।")
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            return connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun isSafeRevocationEndpoint(value: String): Boolean = runCatching {
        if (value.isBlank() || value.length > 4_096) return false
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.rawFragment == null
    }.getOrDefault(false)
}
