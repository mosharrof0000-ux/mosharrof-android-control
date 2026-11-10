package org.mosharrof.androidcontrol

import org.junit.Assert.*
import org.junit.Test

class McpTaskStoreTest {
    private fun record(
        connectorId: String = "connector-a",
        taskId: String = "task-1",
        status: String = "WORKING",
        ttlMs: Long? = 10_000L,
        observedAt: Long = 1_000L
    ) = McpTaskRecord(
        connectorId = connectorId,
        taskId = taskId,
        status = status,
        ttlMs = ttlMs,
        pollIntervalMs = 1_000L,
        observedAtEpochMs = observedAt
    )

    @Test
    fun restoresOnlyPendingTasksAndKeepsConnectorScopesSeparate() {
        val store = InMemoryMcpTaskStore()
        store.upsert(record())
        store.upsert(record(taskId = "task-input", status = "INPUT_REQUIRED"))
        store.upsert(record(taskId = "task-done", status = "COMPLETED"))
        store.upsert(record(connectorId = "connector-b", taskId = "other"))

        val pending = store.list("connector-a", nowEpochMs = 2_000L)
        assertEquals(setOf("task-1", "task-input"), pending.map { it.taskId }.toSet())
        assertEquals(listOf("other"), store.list("connector-b", 2_000L).map { it.taskId })
        assertNull(store.find("connector-a", "other", 2_000L))
    }

    @Test
    fun expiresRecordsUsingServerTtlAndAllowsNonExpiringTasks() {
        val store = InMemoryMcpTaskStore()
        store.upsert(record(taskId = "expires"))
        store.upsert(record(taskId = "no-expiry", ttlMs = null))

        assertNotNull(store.find("connector-a", "expires", nowEpochMs = 10_999L))
        assertNull(store.find("connector-a", "expires", nowEpochMs = 11_000L))
        assertNotNull(store.find("connector-a", "no-expiry", nowEpochMs = 99_999L))
    }

    @Test
    fun removesAllConnectorTasksOnReconnectOrDisconnect() {
        val store = InMemoryMcpTaskStore()
        store.upsert(record(taskId = "one"))
        store.upsert(record(taskId = "two"))
        store.upsert(record(connectorId = "connector-b", taskId = "keep"))

        store.removeConnector("connector-a")

        assertTrue(store.list("connector-a", 2_000L).isEmpty())
        assertEquals(listOf("keep"), store.list("connector-b", 2_000L).map { it.taskId })
    }

    @Test
    fun capsStoredTaskMetadataAtOneHundredRecords() {
        val store = InMemoryMcpTaskStore()
        repeat(105) { index ->
            store.upsert(record(taskId = "task-$index", ttlMs = null, observedAt = 1_000L + index))
        }

        val remaining = store.list("connector-a", nowEpochMs = 2_000L)
        assertEquals(100, remaining.size)
        assertFalse(remaining.any { it.taskId == "task-0" })
        assertTrue(remaining.any { it.taskId == "task-104" })
    }
}
