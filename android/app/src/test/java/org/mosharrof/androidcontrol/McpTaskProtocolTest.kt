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
}
