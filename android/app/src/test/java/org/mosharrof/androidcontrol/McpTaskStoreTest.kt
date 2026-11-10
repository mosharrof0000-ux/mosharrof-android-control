package org.mosharrof.androidcontrol

import android.content.SharedPreferences
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
    @Test
    fun sharedPreferencesStoreRestoresMetadataWithoutPersistingServerPayloads() {
        val preferences = MemorySharedPreferences()
        val firstProcessStore = SharedPreferencesMcpTaskStore(preferences)
        firstProcessStore.upsert(record(taskId = "restore-me", status = "INPUT_REQUIRED"))

        val afterRestart = SharedPreferencesMcpTaskStore(preferences)
        val restored = afterRestart.list("connector-a", nowEpochMs = 2_000L)

        assertEquals(1, restored.size)
        assertEquals("restore-me", restored.single().taskId)
        assertEquals("INPUT_REQUIRED", restored.single().status)
        assertFalse(preferences.getString("mcp_task_recovery_metadata_v1", "")!!.contains("inputRequests"))
        assertFalse(preferences.getString("mcp_task_recovery_metadata_v1", "")!!.contains("result"))
    }

    private class MemorySharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = MemoryEditor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class MemoryEditor : SharedPreferences.Editor {
            private val changes = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearAll = false
            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { changes[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = apply { changes[key] = values?.toSet() }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { changes[key] = value }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { changes[key] = value }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { changes[key] = value }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { changes[key] = value }
            override fun remove(key: String): SharedPreferences.Editor = apply { removals.add(key) }
            override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
            override fun commit(): Boolean {
                if (clearAll) values.clear()
                removals.forEach(values::remove)
                changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                return true
            }
            override fun apply() { commit() }
        }
    }

}
