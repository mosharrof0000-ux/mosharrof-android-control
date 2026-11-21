package org.mosharrof.androidcontrol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpOAuthTokenExchangeOutcomeTest {
    @Test
    fun permitsMcpConnectionOnlyAfterPendingPkceRequestIsCleared() {
        assertTrue(
            McpOAuthTokenExchange.isReadyForMcpConnection(
                Result.success(McpOAuthTokenExchange.Outcome(pendingRequestCleared = true))
            )
        )
        assertFalse(
            McpOAuthTokenExchange.isReadyForMcpConnection(
                Result.success(McpOAuthTokenExchange.Outcome(pendingRequestCleared = false))
            )
        )
    }

    @Test
    fun rejectsFailedTokenExchange() {
        assertFalse(
            McpOAuthTokenExchange.isReadyForMcpConnection(
                Result.failure(IllegalStateException("exchange failed"))
            )
        )
    }
}
