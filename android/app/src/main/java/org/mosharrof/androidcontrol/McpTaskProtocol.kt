package org.mosharrof.androidcontrol

import java.math.BigDecimal

/** Bounded parser for the 2026 MCP Tasks extension wire envelopes. */
object McpTaskProtocol {
    private const val MAX_TASK_ID_CHARS = 256
    private const val MAX_POLL_INTERVAL_MS = 300_000L
    private const val MAX_INPUT_RESPONSE_DEPTH = 16
    private const val MAX_INPUT_RESPONSE_NODES = 1_024
    private const val MAX_INPUT_RESPONSE_COLLECTION_SIZE = 128
    private const val MAX_INPUT_RESPONSE_STRING_CHARS = 8_192

    enum class Status {
        WORKING, INPUT_REQUIRED, COMPLETED, CANCELLED, FAILED
    }

    data class TaskHandle(
        val taskId: String,
        val status: Status,
        val ttlMs: Long?,
        val pollIntervalMs: Long
    )

    data class Snapshot(
        val taskId: String,
        val status: Status,
        val ttlMs: Long?,
        val pollIntervalMs: Long,
        val statusMessage: String?,
        val inputRequests: Map<String, Any?>?,
        val result: Map<String, Any?>?,
        val error: Map<String, Any?>?
    ) {
        val isTerminal: Boolean
            get() = status in setOf(Status.COMPLETED, Status.CANCELLED, Status.FAILED)
    }

    fun parseHandle(result: Map<String, Any?>): TaskHandle? {
        if (result["resultType"] != "task") return null
        val taskId = safeTaskId(result["taskId"]) ?: return null
        val status = parseStatus(result["status"]) ?: return null
        if (status !in setOf(Status.WORKING, Status.INPUT_REQUIRED)) return null
        val pollInterval = boundedInteger(result["pollIntervalMs"], 1, MAX_POLL_INTERVAL_MS)
            ?: return null
        val ttl = parseTtl(result["ttlMs"]) ?: if (result.containsKey("ttlMs") && result["ttlMs"] != null) return null else null
        return TaskHandle(taskId, status, ttl, pollInterval)
    }

    fun parseSnapshot(task: Map<String, Any?>): Snapshot? {
        val taskId = safeTaskId(task["taskId"]) ?: return null
        val status = parseStatus(task["status"]) ?: return null
        val pollInterval = boundedInteger(task["pollIntervalMs"], 1, MAX_POLL_INTERVAL_MS)
            ?: return null
        val ttl = parseTtl(task["ttlMs"])
        if (task.containsKey("ttlMs") && task["ttlMs"] != null && ttl == null) return null
        val requests = task["inputRequests"] as? Map<String, Any?>
        if (status == Status.INPUT_REQUIRED) {
            if (requests == null || !McpProtocol.validateInputRequests(requests)) return null
        } else if (requests != null && requests.isNotEmpty()) {
            return null
        }
        val result = task["result"] as? Map<String, Any?>
        val error = task["error"] as? Map<String, Any?>
        if (status == Status.COMPLETED && result == null) return null
        if (status == Status.FAILED && error == null) return null
        if (status != Status.COMPLETED && result != null) return null
        if (status != Status.FAILED && error != null) return null
        val message = task["statusMessage"] as? String
        if (task.containsKey("statusMessage") && message == null) return null
        return Snapshot(taskId, status, ttl, pollInterval, message, requests, result, error)
    }

    /** Validate bounded JSON object envelopes before any tasks/update request is sent. */
    fun validateInputResponsePayloads(responses: Map<String, Any?>): Boolean {
        if (responses.isEmpty() || responses.size > 16) return false
        val nodeCount = intArrayOf(0)
        return responses.all { (requestKey, response) ->
            if (requestKey.isBlank() || requestKey.length > 128) return@all false
            val payload = response as? Map<*, *> ?: return@all false
            payload.isNotEmpty() &&
                validateInputResponseObject(payload, depth = 0, nodeCount = nodeCount)
        }
    }

    private fun validateInputResponseObject(
        value: Map<*, *>,
        depth: Int,
        nodeCount: IntArray
    ): Boolean {
        if (depth > MAX_INPUT_RESPONSE_DEPTH || value.size > MAX_INPUT_RESPONSE_COLLECTION_SIZE) {
            return false
        }
        return value.all { (key, nestedValue) ->
            key is String && key.isNotBlank() && key.length <= 128 &&
                validateInputResponseValue(nestedValue, depth + 1, nodeCount)
        }
    }

    private fun validateInputResponseValue(
        value: Any?,
        depth: Int,
        nodeCount: IntArray
    ): Boolean {
        nodeCount[0]++
        if (nodeCount[0] > MAX_INPUT_RESPONSE_NODES || depth > MAX_INPUT_RESPONSE_DEPTH) {
            return false
        }
        return when (value) {
            null, is Boolean -> true
            is String -> value.length <= MAX_INPUT_RESPONSE_STRING_CHARS
            is Number -> value.toString().length <= 128 &&
                runCatching { BigDecimal(value.toString()) }.isSuccess
            is Map<*, *> -> validateInputResponseObject(value, depth, nodeCount)
            is List<*> -> value.size <= MAX_INPUT_RESPONSE_COLLECTION_SIZE &&
                value.all { validateInputResponseValue(it, depth + 1, nodeCount) }
            else -> false
        }
    }

    /** Accept only one object response for every validated server request key. */
    fun validateInputResponses(
        requests: Map<String, Any?>,
        responses: Map<String, Any?>
    ): Boolean {
        return McpProtocol.validateInputRequests(requests) &&
            responses.size == requests.size &&
            responses.keys == requests.keys &&
            validateInputResponsePayloads(responses)
    }

    private fun safeTaskId(value: Any?): String? =
        (value as? String)?.takeIf {
            it.isNotBlank() && it.length <= MAX_TASK_ID_CHARS &&
                it.all { ch -> ch.code in 0x21..0x7E }
        }

    private fun parseStatus(value: Any?): Status? = when (value) {
        "working" -> Status.WORKING
        "input_required" -> Status.INPUT_REQUIRED
        "completed" -> Status.COMPLETED
        "cancelled" -> Status.CANCELLED
        "failed" -> Status.FAILED
        else -> null
    }

    private fun parseTtl(value: Any?): Long? {
        if (value == null) return null
        return boundedInteger(value, 0, Long.MAX_VALUE)
    }

    private fun boundedInteger(value: Any?, min: Long, max: Long): Long? {
        if (value !is Number) return null
        val number = runCatching { BigDecimal(value.toString()).longValueExact() }.getOrNull()
            ?: return null
        return number.takeIf { it in min..max }
    }
}
