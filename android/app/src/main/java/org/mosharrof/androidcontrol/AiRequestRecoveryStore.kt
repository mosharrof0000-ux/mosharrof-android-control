package org.mosharrof.androidcontrol

import android.content.SharedPreferences

/**
 * Minimal persistence boundary for an unresolved provider-request outcome.
 *
 * Only a boolean marker and observation timestamp are persisted. This API
 * intentionally has no fields for prompts, credentials, tool arguments,
 * provider responses, or request payloads.
 */
internal interface AiRequestRecoveryPersistence {
    fun isPending(): Boolean
    fun recordedAtEpochMs(): Long
    fun savePending(recordedAtEpochMs: Long)
    fun clear()
}

internal class AiRequestRecoveryStore(
    private val persistence: AiRequestRecoveryPersistence
) {
    fun hasPendingUnknownOutcome(): Boolean = persistence.isPending()

    fun pendingRecordedAtEpochMs(): Long =
        if (persistence.isPending()) persistence.recordedAtEpochMs() else 0L

    fun recordUnknownOutcome(recordedAtEpochMs: Long) {
        require(recordedAtEpochMs >= 0L) { "Recovery timestamp must not be negative." }
        persistence.savePending(recordedAtEpochMs)
    }

    fun markReviewed() {
        persistence.clear()
    }
}

internal class SharedPreferencesAiRequestRecoveryPersistence(
    private val preferences: SharedPreferences
) : AiRequestRecoveryPersistence {
    override fun isPending(): Boolean =
        preferences.getBoolean(KEY_PENDING, false)

    override fun recordedAtEpochMs(): Long =
        preferences.getLong(KEY_RECORDED_AT, 0L)

    override fun savePending(recordedAtEpochMs: Long) {
        preferences.edit()
            .putBoolean(KEY_PENDING, true)
            .putLong(KEY_RECORDED_AT, recordedAtEpochMs)
            .apply()
    }

    override fun clear() {
        preferences.edit()
            .remove(KEY_PENDING)
            .remove(KEY_RECORDED_AT)
            .apply()
    }

    private companion object {
        const val KEY_PENDING = "pending_unknown_outcome"
        const val KEY_RECORDED_AT = "recorded_at_epoch_ms"
    }
}
