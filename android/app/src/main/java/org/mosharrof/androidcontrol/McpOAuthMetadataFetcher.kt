package org.mosharrof.androidcontrol

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class McpOAuthMetadataDiscovery(
    val resourceMetadata: Map<String, Any?>,
    val authorizationServerIssuer: String,
    val authorizationServerMetadata: Map<String, Any?>
)

/**
 * Bounded, HTTPS-only OAuth metadata retrieval.
 *
 * Redirects are deliberately disabled: metadata cannot silently move discovery
 * to a different origin. This class retrieves and validates metadata only; it
 * does not authorize, exchange, persist, or refresh tokens.
 */
class McpOAuthMetadataFetcher(
    private val connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    }
) {
    companion object {
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 10_000
        const val MAX_METADATA_BYTES = 65_536
        private const val MAX_DEPTH = 16
        private const val MAX_NODES = 2_048
        private const val MAX_COLLECTION_SIZE = 64
        private const val MAX_STRING_CHARS = 8_192
    }

    fun discover(resourceUrl: String): McpOAuthMetadataDiscovery? {
        val metadataUrl = McpOAuthMetadata.protectedResourceMetadataUrl(resourceUrl) ?: return null
        val resourceMetadata = fetchJsonObject(metadataUrl) ?: return null
        if (!McpOAuthMetadata.validateProtectedResourceMetadata(resourceUrl, resourceMetadata)) {
            return null
        }

        val issuers = resourceMetadata["authorization_servers"] as? List<*> ?: return null
        for (candidate in issuers) {
            val issuer = candidate as? String ?: continue
            val serverMetadataUrl = McpOAuthMetadata.authorizationServerMetadataUrl(issuer) ?: continue
            val serverMetadata = fetchJsonObject(serverMetadataUrl) ?: continue
            if (McpOAuthMetadata.validateAuthorizationServerMetadata(issuer, serverMetadata)) {
                return McpOAuthMetadataDiscovery(resourceMetadata, issuer, serverMetadata)
            }
        }
        return null
    }

    fun fetchClientMetadata(
        metadataUrl: String,
        expectedRedirectUri: String
    ): Map<String, Any?>? {
        val metadata = fetchJsonObject(metadataUrl) ?: return null
        return metadata.takeIf {
            McpOAuthMetadata.validateClientMetadata(metadataUrl, expectedRedirectUri, it)
        }
    }

    private fun fetchJsonObject(metadataUrl: String): Map<String, Any?>? {
        val url = runCatching { URL(metadataUrl) }.getOrNull() ?: return null
        if (!url.protocol.equals("https", ignoreCase = true) || url.host.isNullOrBlank() ||
            url.userInfo != null || url.ref != null
        ) return null

        val connection = runCatching { connectionFactory(url) }.getOrNull() ?: return null
        return try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.doInput = true
            connection.setRequestProperty("Accept", "application/json, application/*+json")
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val contentType = connection.contentType
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
                ?: return null
            if (contentType != "application/json" && !contentType.endsWith("+json")) return null
            val declaredLength = connection.contentLengthLong
            if (declaredLength > MAX_METADATA_BYTES) return null

            val bytes = connection.inputStream.use { readBounded(it) } ?: return null
            val parsed = runCatching {
                JSONObject(String(bytes, StandardCharsets.UTF_8))
            }.getOrNull() ?: return null
            toBoundedMap(parsed)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun readBounded(input: java.io.InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4_096)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_METADATA_BYTES) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun toBoundedMap(json: JSONObject): Map<String, Any?>? {
        val budget = intArrayOf(0)
        val value = convert(json, 0, budget) as? Map<*, *> ?: return null
        if (value.keys.any { it !is String }) return null
        @Suppress("UNCHECKED_CAST")
        return value as Map<String, Any?>
    }

    private fun convert(value: Any?, depth: Int, budget: IntArray): Any? {
        if (depth > MAX_DEPTH || ++budget[0] > MAX_NODES) throw IllegalArgumentException("bounds")
        return when {
            value == null || value === JSONObject.NULL -> null
            value is String -> value.takeIf { it.length <= MAX_STRING_CHARS }
                ?: throw IllegalArgumentException("string")
            value is Boolean -> value
            value is Number -> value.takeIf { it.toDouble().isFinite() }
                ?: throw IllegalArgumentException("number")
            value is JSONObject -> {
                if (value.length() > MAX_COLLECTION_SIZE) throw IllegalArgumentException("object")
                val result = LinkedHashMap<String, Any?>()
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key.length > 256) throw IllegalArgumentException("key")
                    result[key] = convert(value.get(key), depth + 1, budget)
                }
                result
            }
            value is JSONArray -> {
                if (value.length() > MAX_COLLECTION_SIZE) throw IllegalArgumentException("array")
                List(value.length()) { index -> convert(value.get(index), depth + 1, budget) }
            }
            else -> throw IllegalArgumentException("type")
        }
    }
}
