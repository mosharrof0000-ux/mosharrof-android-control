package org.mosharrof.androidcontrol

import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/**
 * Short-lived, single-use approval grants bound to one tool and exact arguments.
 */
internal object ToolApprovalGrants {

    private const val TTL_MILLIS = 60_000L
    private const val MAX_GRANTS = 1_000
    private const val MAX_ARGUMENT_DEPTH = 32
    private const val MAX_ARGUMENT_NODES = 2_048
    private val JSON_NUMBER = Regex("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?")

    private data class Grant(
        val connectorId: String,
        val toolId: String,
        val argumentsHash: String,
        val expiresAt: Long
    )

    private val grants = linkedMapOf<String, Grant>()

    @Synchronized
    fun issue(
        connectorId: String,
        toolId: String,
        arguments: Map<String, Any?>,
        nowMillis: Long = System.currentTimeMillis()
    ): String {
        require(connectorId.isNotBlank()) { "Connector ID অনুপস্থিত।" }
        require(toolId.isNotBlank()) { "Tool ID অনুপস্থিত।" }

        // Remove expired entries before enforcing the storage limit.
        grants.entries.removeAll { nowMillis >= it.value.expiresAt }
        require(grants.size < MAX_GRANTS) {
            "অনুমোদনের Grant সীমা পূর্ণ। পরে আবার চেষ্টা করুন।"
        }

        val hash = fingerprint(arguments)
        val token = UUID.randomUUID().toString()

        grants[token] = Grant(
            connectorId = connectorId,
            toolId = toolId,
            argumentsHash = hash,
            expiresAt = Math.addExact(nowMillis, TTL_MILLIS)
        )

        return token
    }

    /**
     * Every attempted use consumes the token, including mismatched attempts.
     */
    @Synchronized
    fun consume(
        token: String,
        connectorId: String,
        toolId: String,
        arguments: Map<String, Any?>,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (token.isBlank()) return false

        val grant = grants.remove(token) ?: return false

        if (nowMillis >= grant.expiresAt) return false
        if (grant.connectorId != connectorId) return false
        if (grant.toolId != toolId) return false

        return runCatching {
            grant.argumentsHash == fingerprint(arguments)
        }.getOrDefault(false)
    }

    @Synchronized
    fun revokeConnector(connectorId: String) {
        grants.entries.removeAll { it.value.connectorId == connectorId }
    }

    @Synchronized
    fun clearForTests() {
        grants.clear()
    }

    private fun fingerprint(arguments: Map<String, Any?>): String {
        val canonical = canonicalValue(arguments, 0, intArrayOf(0))
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))

        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun canonicalValue(value: Any?, depth: Int, visited: IntArray): String {
        require(depth <= MAX_ARGUMENT_DEPTH && ++visited[0] <= MAX_ARGUMENT_NODES) {
            "Approval input nesting অথবা node limit অতিক্রম করেছে।"
        }
        return when (value) {
            null -> "null"
            is String -> JSONObject.quote(value)
            is Boolean -> value.toString()
            is Byte, is Short, is Int, is Long -> value.toString()
            is Float -> {
                require(value.isFinite()) { "অবৈধ numeric input।" }
                value.toString()
            }
            is Double -> {
                require(value.isFinite()) { "অবৈধ numeric input।" }
                value.toString()
            }
            is Number -> {
                val numeric = value.toString()
                require(
                    JSON_NUMBER.matches(numeric) &&
                    numeric.toDoubleOrNull()?.isFinite() == true
                ) { "অবৈধ numeric input।" }
                numeric
            }
            is Map<*, *> -> {
                val entries = value.entries.map { entry ->
                    val key = entry.key as? String
                        ?: error("Input object-এর key String হতে হবে।")
                    key to entry.value
                }.sortedBy { it.first }

                entries.joinToString(
                    prefix = "{",
                    postfix = "}",
                    separator = ","
                ) { (key, item) ->
                    "${JSONObject.quote(key)}:${canonicalValue(item, depth + 1, visited)}"
                }
            }
            is List<*> -> value.joinToString(
                prefix = "[",
                postfix = "]",
                separator = ","
            ) { canonicalValue(it, depth + 1, visited) }
            else -> error("অসমর্থিত input data type: ${value.javaClass.simpleName}")
        }
}
}
