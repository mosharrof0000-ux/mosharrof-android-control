package org.mosharrof.androidcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpOAuthRefreshRecoveryTest {
    @Test
    fun everyRefreshFailureHasSafeUserGuidance() {
        McpOAuthRefreshFailure.values().forEach { reason ->
            assertFalse(reason.userMessage.isBlank())
            assertFalse(reason.userMessage.contains("access_token", ignoreCase = true))
            assertFalse(reason.userMessage.contains("refresh_token", ignoreCase = true))
        }
    }

    @Test
    fun refreshRejectedGuidanceDisablesBlindAutomaticRetry() {
        val message = McpOAuthRefreshFailure.REFRESH_REJECTED.userMessage
        assertTrue(message.contains("স্বয়ংক্রিয় retry বন্ধ"))
        assertTrue(message.contains("manual retry"))
        assertTrue(message.contains("আবার Login"))
    }

    @Test
    fun typedFailureKeepsOnlySafeReasonAndNeverRetainsRawCause() {
        val failure = McpOAuthRefreshException(McpOAuthRefreshFailure.REFRESH_REJECTED)
        assertEquals(McpOAuthRefreshFailure.REFRESH_REJECTED, failure.reason)
        assertEquals(null, failure.cause)
        assertNotNull(failure.message)
    }

    @Test
    fun blankAccessTokenIsRejectedBeforePersistence() {
        var persisted = false
        val result = McpOAuthRefreshCommit.persistBeforeRelease(
            accessToken = "  ",
            tokenType = "Bearer",
            authorizationError = null
        ) {
            persisted = true
            Result.success(Unit)
        }
        assertTrue(result.isFailure)
        assertFalse(persisted)
        assertEquals(
            McpOAuthRefreshFailure.REFRESH_REJECTED,
            (result.exceptionOrNull() as McpOAuthRefreshException).reason
        )
    }

    @Test
    fun missingTokenTypeIsRejectedBeforePersistence() {
        var persisted = false
        val result = McpOAuthRefreshCommit.persistBeforeRelease(
            accessToken = "opaque-token",
            tokenType = null,
            authorizationError = null
        ) {
            persisted = true
            Result.success(Unit)
        }
        assertTrue(result.isFailure)
        assertFalse(persisted)
        assertEquals(
            McpOAuthRefreshFailure.BEARER_TOKEN_REQUIRED,
            (result.exceptionOrNull() as McpOAuthRefreshException).reason
        )
    }

    @Test
    fun refreshedBearerTokenIsReleasedOnlyAfterEncryptedStatePersists() {
        var persisted = false
        val result = McpOAuthRefreshCommit.persistBeforeRelease(
            accessToken = "fresh-access-token",
            tokenType = "Bearer",
            authorizationError = null
        ) {
            persisted = true
            Result.success(Unit)
        }
        assertTrue(result.isSuccess)
        assertTrue(persisted)
        assertEquals("fresh-access-token", result.getOrNull())
    }

    @Test
    fun rejectedRefreshNeverAttemptsToPersistOrReleaseToken() {
        var persisted = false
        val result = McpOAuthRefreshCommit.persistBeforeRelease(
            accessToken = null,
            tokenType = "Bearer",
            authorizationError = IllegalStateException("sensitive provider body")
        ) {
            persisted = true
            Result.success(Unit)
        }
        assertTrue(result.isFailure)
        assertFalse(persisted)
        assertEquals(
            McpOAuthRefreshFailure.REFRESH_REJECTED,
            (result.exceptionOrNull() as McpOAuthRefreshException).reason
        )
    }

    @Test
    fun nonBearerResponseNeverAttemptsToPersistOrReleaseToken() {
        var persisted = false
        val result = McpOAuthRefreshCommit.persistBeforeRelease(
            accessToken = "opaque-token",
            tokenType = "MAC",
            authorizationError = null
        ) {
            persisted = true
            Result.success(Unit)
        }
        assertTrue(result.isFailure)
        assertFalse(persisted)
        assertEquals(
            McpOAuthRefreshFailure.BEARER_TOKEN_REQUIRED,
            (result.exceptionOrNull() as McpOAuthRefreshException).reason
        )
    }

    @Test
    fun encryptedPersistenceFailureNeverReleasesRefreshedToken() {
        val result = McpOAuthRefreshCommit.persistBeforeRelease(
            accessToken = "fresh-access-token",
            tokenType = "Bearer",
            authorizationError = null
        ) {
            Result.failure(IllegalStateException("sensitive storage detail"))
        }
        assertTrue(result.isFailure)
        val failure = result.exceptionOrNull() as McpOAuthRefreshException
        assertEquals(McpOAuthRefreshFailure.ENCRYPTED_STATE_SAVE_FAILED, failure.reason)
        assertFalse(failure.message!!.contains("sensitive storage detail"))
    }

    @Test
    fun thrownPersistenceFailureNeverReleasesRefreshedToken() {
        val result = McpOAuthRefreshCommit.persistBeforeRelease(
            accessToken = "fresh-access-token",
            tokenType = "Bearer",
            authorizationError = null
        ) {
            throw IllegalStateException("sensitive storage detail")
        }
        assertTrue(result.isFailure)
        assertEquals(
            McpOAuthRefreshFailure.ENCRYPTED_STATE_SAVE_FAILED,
            (result.exceptionOrNull() as McpOAuthRefreshException).reason
        )
    }

}
