package org.mosharrof.androidcontrol

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores only task recovery metadata. Server payloads, elicitation data, results,
 * errors and credentials are deliberately never persisted here.
 */
data class McpTaskRecord(
    val connectorId: String,
    val taskId: String,
    val status: String,
    val ttlMs: Long?,
    val pollIntervalMs: Long,
    val observedAtEpochMs: Long
) {
    val isPending: Boolean
        get() = status == "WORKING" || status == "INPUT_REQUIRED"

    fun isExpired(nowEpochMs: Long): Boolean =
        ttlMs != null && (ttlMs == 0L || nowEpochMs - observedAtEpochMs >= ttlMs)
}

interface McpTaskStore {
    fun upsert(record: McpTaskRecord, nowEpochMs: Long = System.currentTimeMillis())
    fun find(connectorId: String, taskId: String, nowEpochMs: Long = System.currentTimeMillis()): McpTaskRecord?
    fun list(connectorId: String, nowEpochMs: Long = System.currentTimeMillis()): List<McpTaskRecord>
    fun remove(connectorId: String, taskId: String)
    fun removeConnector(connectorId: String)
}

/** Small bounded in-memory implementation for isolated tests and non-Android callers. */
class InMemoryMcpTaskStore : McpTaskStore {
    private val records = linkedMapOf<Pair<String, String>, McpTaskRecord>()

    @Synchronized
    override fun upsert(record: McpTaskRecord, nowEpochMs: Long) {
        prune(nowEpochMs)
        records[record.connectorId to record.taskId] = record
        while (records.size > MAX_RECORDS) records.remove(records.keys.first())
    }

    @Synchronized
    override fun find(connectorId: String, taskId: String, nowEpochMs: Long): McpTaskRecord? {
        prune(nowEpochMs)
        return records[connectorId to taskId]
    }

    @Synchronized
    override fun list(connectorId: String, nowEpochMs: Long): List<McpTaskRecord> {
        prune(nowEpochMs)
        return records.values.filter { it.connectorId == connectorId && it.isPending }
    }

    @Synchronized
    override fun remove(connectorId: String, taskId: String) {
        records.remove(connectorId to taskId)
    }

    @Synchronized
    override fun removeConnector(connectorId: String) {
        records.keys.filter { it.first == connectorId }.forEach(records::remove)
    }

    private fun prune(nowEpochMs: Long) {
        records.entries.removeAll { it.value.isExpired(nowEpochMs) }
    }

    private companion object { const val MAX_RECORDS = 100 }
}

/** Android durable store; writes are atomic at the SharedPreferences value level. */
class SharedPreferencesMcpTaskStore(
    private val preferences: SharedPreferences
) : McpTaskStore {
    @Synchronized
    override fun upsert(record: McpTaskRecord, nowEpochMs: Long) {
        val records = readRecords(nowEpochMs).toMutableList()
        records.removeAll { it.connectorId == record.connectorId && it.taskId == record.taskId }
        records.add(record)
        val bounded = records.takeLast(MAX_RECORDS)
        writeRecords(bounded)
    }

    @Synchronized
    override fun find(connectorId: String, taskId: String, nowEpochMs: Long): McpTaskRecord? =
        readRecords(nowEpochMs).firstOrNull { it.connectorId == connectorId && it.taskId == taskId }

    @Synchronized
    override fun list(connectorId: String, nowEpochMs: Long): List<McpTaskRecord> =
        readRecords(nowEpochMs).filter { it.connectorId == connectorId && it.isPending }

    @Synchronized
    override fun remove(connectorId: String, taskId: String) {
        writeRecords(readRecords().filterNot { it.connectorId == connectorId && it.taskId == taskId })
    }

    @Synchronized
    override fun removeConnector(connectorId: String) {
        writeRecords(readRecords().filterNot { it.connectorId == connectorId })
    }

    private fun readRecords(nowEpochMs: Long = System.currentTimeMillis()): List<McpTaskRecord> {
        val raw = preferences.getString(KEY, null) ?: return emptyList()
        val parsed = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val record = runCatching {
                        McpTaskRecord(
                            connectorId = item.getString("connectorId"),
                            taskId = item.getString("taskId"),
                            status = item.getString("status"),
                            ttlMs = if (item.isNull("ttlMs")) null else item.getLong("ttlMs"),
                            pollIntervalMs = item.getLong("pollIntervalMs"),
                            observedAtEpochMs = item.getLong("observedAtEpochMs")
                        )
                    }.getOrNull() ?: continue
                    if (record.connectorId.isNotBlank() &&
                        record.taskId.isNotBlank() && record.taskId.length <= 256 &&
                        record.status in VALID_STATUSES &&
                        (record.ttlMs == null || record.ttlMs >= 0L) &&
                        record.pollIntervalMs in 1L..300_000L &&
                        !record.isExpired(nowEpochMs)
                    ) add(record)
                }
            }
        }.getOrElse { emptyList() }
        if (parsed.size > MAX_RECORDS || parsed.size != runCatching { JSONArray(raw).length() }.getOrDefault(-1)) {
            writeRecords(parsed.takeLast(MAX_RECORDS))
        }
        return parsed.takeLast(MAX_RECORDS)
    }

    private fun writeRecords(records: List<McpTaskRecord>) {
        val array = JSONArray()
        records.takeLast(MAX_RECORDS).forEach { record ->
            array.put(JSONObject().apply {
                put("connectorId", record.connectorId)
                put("taskId", record.taskId)
                put("status", record.status)
                put("ttlMs", record.ttlMs ?: JSONObject.NULL)
                put("pollIntervalMs", record.pollIntervalMs)
                put("observedAtEpochMs", record.observedAtEpochMs)
            })
        }
        preferences.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "mcp_task_recovery_metadata_v1"
        const val MAX_RECORDS = 100
        val VALID_STATUSES = setOf("WORKING", "INPUT_REQUIRED", "COMPLETED", "CANCELLED", "FAILED")
    }
}
