package org.mosharrof.androidcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class McpOAuthRefreshRecoveryTest {
    @Test
    fun everyRefreshFailureHasSafeUserGuidance() {
        McpOAuthRefreshFailure.entries.forEach { reason ->
            assertFalse(reason.userMessage.isBlank())
            assertFalse(reason.userMessage.contains("access_token", ignoreCase = true))
            assertFalse(reason.userMessage.contains("refresh_token", ignoreCase = true))
        }
    }

    @Test
    fun refreshRejectedGuidanceDisablesBlindAutomaticRetry() {
        val message = McpOAuthRefreshFailure.REFRESH_REJECTED.userMessage
        assert(message.contains("স্বয়ংক্রিয় retry বন্ধ"))
        assert(message.contains("manual retry"))
        assert(message.contains("আবার Login"))
    }

    @Test
    fun typedFailurePreservesReasonAndCauseWithoutExposingCauseText() {
        val cause = IllegalStateException("sensitive provider response")
        val failure = McpOAuthRefreshException(
            McpOAuthRefreshFailure.REFRESH_REJECTED,
            cause
        )
        assertEquals(McpOAuthRefreshFailure.REFRESH_REJECTED, failure.reason)
        assertEquals(cause, failure.cause)
        assertNotNull(failure.message)
        assertFalse(failure.message!!.contains("sensitive provider response"))
    }
}
