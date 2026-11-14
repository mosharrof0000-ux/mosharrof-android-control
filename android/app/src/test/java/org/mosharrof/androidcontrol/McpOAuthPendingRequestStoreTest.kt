package org.mosharrof.androidcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpOAuthPendingRequestStoreTest {
    @Test
    fun storageIdIsStableAndDoesNotExposeConnectorId() {
        val connectorId = "trusted-mcp-connector"

        val first = McpOAuthPendingRequestStore.storageId(connectorId)
        val second = McpOAuthPendingRequestStore.storageId(connectorId)

        assertEquals(first, second)
        assertFalse(first.contains(connectorId))
        assertTrue(first.matches(Regex("oauth\\.pending\\.[0-9a-f]{64}")))
        assertTrue(first.length <= 80)
    }

    @Test
    fun issuerBindingStorageIdIsHashedAndSeparatedFromPkceRequest() {
        val connectorId = "trusted-mcp-connector"
        val first = McpOAuthPendingRequestStore.issuerStorageId(connectorId)
        val second = McpOAuthPendingRequestStore.issuerStorageId(connectorId)

        assertEquals(first, second)
        assertFalse(first.contains(connectorId))
        assertTrue(first.matches(Regex("oauth\\.iss\\.[0-9a-f]{64}")))
        assertTrue(first.length <= 80)
        assertNotEquals(McpOAuthPendingRequestStore.storageId(connectorId), first)
    }

    @Test
    fun differentConnectorsUseDifferentStorageIds() {
        assertNotEquals(
            McpOAuthPendingRequestStore.storageId("connector-alpha"),
            McpOAuthPendingRequestStore.storageId("connector-beta")
        )
    }

    @Test
    fun rejectsInvalidConnectorIds() {
        listOf("", "x", "../connector", "connector with spaces", "x".repeat(81))
            .forEach { connectorId ->
                assertFalse(runCatching {
                    McpOAuthPendingRequestStore.storageId(connectorId)
                }.isSuccess)
            }
    }
}
