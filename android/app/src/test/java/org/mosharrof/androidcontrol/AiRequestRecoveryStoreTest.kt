package org.mosharrof.androidcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRequestRecoveryStoreTest {
    private class FakePersistence : AiRequestRecoveryPersistence {
        private var pending = false
        private var recordedAt = 0L

        override fun isPending(): Boolean = pending
        override fun recordedAtEpochMs(): Long = recordedAt

        override fun savePending(recordedAtEpochMs: Long) {
            pending = true
            recordedAt = recordedAtEpochMs
        }

        override fun clear() {
            pending = false
            recordedAt = 0L
        }
    }

    @Test
    fun unknownOutcomeSurvivesStoreRecreation() {
        val persistence = FakePersistence()
        AiRequestRecoveryStore(persistence).recordUnknownOutcome(1_800_000_000_000L)

        val recreatedStore = AiRequestRecoveryStore(persistence)
        assertTrue(recreatedStore.hasPendingUnknownOutcome())
        assertEquals(1_800_000_000_000L, recreatedStore.pendingRecordedAtEpochMs())
    }

    @Test
    fun reviewedOutcomeClearsMarkerAndTimestamp() {
        val persistence = FakePersistence()
        val store = AiRequestRecoveryStore(persistence)
        store.recordUnknownOutcome(1234L)

        store.markReviewed()

        assertFalse(store.hasPendingUnknownOutcome())
        assertEquals(0L, store.pendingRecordedAtEpochMs())
    }

    @Test
    fun recoveryRecordContainsOnlyMarkerAndTimestamp() {
        val persistence = FakePersistence()
        AiRequestRecoveryStore(persistence).recordUnknownOutcome(5678L)

        // The persistence contract exposes no prompt, credential, tool-argument,
        // request-body, or provider-response fields to store.
        assertTrue(persistence.isPending())
        assertEquals(5678L, persistence.recordedAtEpochMs())
    }

    @Test
    fun negativeTimestampIsRejectedWithoutWriting() {
        val persistence = FakePersistence()
        val store = AiRequestRecoveryStore(persistence)

        assertTrue(runCatching { store.recordUnknownOutcome(-1L) }.isFailure)
        assertFalse(persistence.isPending())
    }
}
