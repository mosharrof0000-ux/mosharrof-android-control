package org.mosharrof.androidcontrol

import org.junit.Assert.*
import org.junit.Test

class McpTaskProtocolTest {
    @Test
    fun parsesOnlyBoundedTaskHandles() {
        val handle = McpTaskProtocol.parseHandle(
            mapOf(
                "resultType" to "task",
                "taskId" to "task-123",
                "status" to "working",
                "ttlMs" to 60_000,
                "pollIntervalMs" to 2_000
            )
        )
        assertNotNull(handle)
        assertEquals("task-123", handle?.taskId)
        assertEquals(McpTaskProtocol.Status.WORKING, handle?.status)
        assertEquals(2_000L, handle?.pollIntervalMs)

        assertNull(
            McpTaskProtocol.parseHandle(
                mapOf(
                    "resultType" to "task",
                    "taskId" to " ",
                    "status" to "working",
                    "pollIntervalMs" to 1
                )
            )
        )
        assertNull(
            McpTaskProtocol.parseHandle(
                mapOf(
                    "resultType" to "task",
                    "taskId" to "task-1",
                    "status" to "working",
                    "pollIntervalMs" to 300_001
                )
            )
        )
    }

    @Test
    fun parsesInputRequiredTaskSnapshotWithValidatedRequests() {
        val snapshot = McpTaskProtocol.parseSnapshot(
            mapOf(
                "taskId" to "task-123",
                "status" to "input_required",
                "ttlMs" to 60_000,
                "pollIntervalMs" to 1_000,
                "inputRequests" to mapOf(
                    "confirm" to mapOf(
                        "method" to "elicitation/create",
                        "params" to mapOf("mode" to "form")
                    )
                )
            )
        )
        assertNotNull(snapshot)
        assertEquals(McpTaskProtocol.Status.INPUT_REQUIRED, snapshot?.status)
        assertFalse(snapshot?.isTerminal ?: true)
    }

    @Test
    fun parsesTerminalTaskStatesAndRejectsMissingPayloads() {
        val completed = McpTaskProtocol.parseSnapshot(
            mapOf(
                "taskId" to "task-1",
                "status" to "completed",
                "pollIntervalMs" to 1_000,
                "result" to mapOf("content" to emptyList<Any>())
            )
        )
        assertTrue(completed?.isTerminal ?: false)

        val failedWithoutError = McpTaskProtocol.parseSnapshot(
            mapOf(
                "taskId" to "task-2",
                "status" to "failed",
                "pollIntervalMs" to 1_000
            )
        )
        assertNull(failedWithoutError)

        val cancelled = McpTaskProtocol.parseSnapshot(
            mapOf(
                "taskId" to "task-3",
                "status" to "cancelled",
                "pollIntervalMs" to 1_000
            )
        )
        assertTrue(cancelled?.isTerminal ?: false)
    }
 
    @Test
    fun rejectsMalformedTaskSnapshotsAndInvalidStatePayloads() {
        val base = mapOf<String, Any?>(
            "taskId" to "task-1",
            "status" to "working",
            "pollIntervalMs" to 1_000
        )
        assertNotNull(McpTaskProtocol.parseSnapshot(base))
        assertNull(McpTaskProtocol.parseSnapshot(base + ("taskId" to "task id")))
        assertNull(McpTaskProtocol.parseSnapshot(base + ("taskId" to "x".repeat(257))))
        assertNull(McpTaskProtocol.parseSnapshot(base + ("status" to "unknown")))
        assertNull(McpTaskProtocol.parseSnapshot(base + ("pollIntervalMs" to 0)))
        assertNull(McpTaskProtocol.parseSnapshot(base + ("pollIntervalMs" to 1.5)))
        assertNull(McpTaskProtocol.parseSnapshot(base + ("ttlMs" to -1)))
        assertNull(McpTaskProtocol.parseSnapshot(base + ("ttlMs" to "60000")))
        assertNull(
            McpTaskProtocol.parseSnapshot(
                base + ("inputRequests" to mapOf("bad" to mapOf("method" to "tools/call", "params" to emptyMap<String, Any?>())))
            )
        )
        assertNull(
            McpTaskProtocol.parseSnapshot(
                base + ("result" to mapOf("content" to emptyList<Any>()))
            )
        )
        assertNull(
            McpTaskProtocol.parseSnapshot(
                base + ("error" to mapOf("message" to "unexpected"))
            )
        )
    }

    @Test
    fun taskInputResponsesRequireExactKeysAndObjectPayloads() {
        val requests = mapOf<String, Any?>(
            "confirm" to mapOf(
                "method" to "elicitation/create",
                "params" to mapOf("mode" to "form")
            )
        )
        assertTrue(
            McpTaskProtocol.validateInputResponses(
                requests,
                mapOf("confirm" to mapOf("action" to "accept", "content" to mapOf("ok" to true)))
            )
        )
        assertFalse(
            McpTaskProtocol.validateInputResponses(
                requests,
                mapOf("unexpected" to mapOf("action" to "accept"))
            )
        )
        assertFalse(
            McpTaskProtocol.validateInputResponses(
                requests,
                mapOf("confirm" to "accept")
            )
        )
        assertFalse(
            McpTaskProtocol.validateInputResponses(
                requests,
                mapOf("confirm" to emptyMap<String, Any?>())
            )
        )
    }

    @Test
    fun taskRequestBuildersBindTaskIdsAndValidateUpdateAnswers() {
        val get = McpProtocol.modernTasksGetRequest(
            "rpc-1", "task-1", "MOSHARROF", "1"
        )
        assertEquals("tasks/get", get.method)
        assertEquals("task-1", get.params["taskId"])
        assertEquals("task-1", McpHttpClient.standardNameHeader("tasks/get", get.params))

        val update = McpProtocol.modernTasksUpdateRequest(
            "rpc-2", "task-1", mapOf("confirm" to mapOf("action" to "accept")),
            "MOSHARROF", "1"
        )
        assertEquals("tasks/update", update.method)
        assertEquals("task-1", McpHttpClient.standardNameHeader("tasks/update", update.params))

        val cancel = McpProtocol.modernTasksCancelRequest(
            "rpc-3", "task-1", "MOSHARROF", "1"
        )
        assertEquals("tasks/cancel", cancel.method)
        assertEquals("task-1", McpHttpClient.standardNameHeader("tasks/cancel", cancel.params))

        assertThrows(IllegalArgumentException::class.java) {
            McpProtocol.modernTasksUpdateRequest(
                "rpc-4", "task-1", emptyMap(), "MOSHARROF", "1"
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            McpProtocol.modernTasksGetRequest(
                "rpc-5", "x".repeat(257), "MOSHARROF", "1"
            )
        }
    }
}
