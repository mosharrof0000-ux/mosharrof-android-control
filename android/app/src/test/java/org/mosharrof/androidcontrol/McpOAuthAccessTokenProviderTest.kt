package org.mosharrof.androidcontrol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpOAuthAccessTokenProviderTest {
    @Test
    fun exactRegisteredResourceBindingIsAccepted() {
        assertTrue(
            McpOAuthAccessTokenProvider.isExactResourceBinding(
                "https://mcp.example.com/rpc",
                "https://mcp.example.com/rpc"
            )
        )
    }

    @Test
    fun differentPathOrTrailingSlashIsRejected() {
        assertFalse(
            McpOAuthAccessTokenProvider.isExactResourceBinding(
                "https://mcp.example.com/rpc",
                "https://mcp.example.com/other"
            )
        )
        assertFalse(
            McpOAuthAccessTokenProvider.isExactResourceBinding(
                "https://mcp.example.com/rpc",
                "https://mcp.example.com/rpc/"
            )
        )
    }

    @Test
    fun missingOrUnsafeResourceIsRejected() {
        assertFalse(
            McpOAuthAccessTokenProvider.isExactResourceBinding(
                "https://mcp.example.com/rpc",
                null
            )
        )
        assertFalse(
            McpOAuthAccessTokenProvider.isExactResourceBinding(
                "http://mcp.example.com/rpc",
                "http://mcp.example.com/rpc"
            )
        )
        assertFalse(
            McpOAuthAccessTokenProvider.isExactResourceBinding(
                "https://mcp.example.com/rpc",
                "https://user:password@mcp.example.com/rpc"
            )
        )
    }
}
