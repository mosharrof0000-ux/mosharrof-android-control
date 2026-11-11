package org.mosharrof.androidcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class McpOAuthAuthStateStoreTest {
    @Test
    fun storageIdIsStableAndDoesNotExposeConnectorId() {
        val first = McpOAuthAuthStateStore.storageId("connector-alpha")
        val second = McpOAuthAuthStateStore.storageId("connector-alpha")

        assertEquals(first, second)
        assertNotEquals("connector-alpha", first)
        assertEquals(70, first.length)
        assert(first.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{1,79}")))
    }

    @Test
    fun differentConnectorsReceiveDifferentStorageIds() {
        assertNotEquals(
            McpOAuthAuthStateStore.storageId("connector-alpha"),
            McpOAuthAuthStateStore.storageId("connector-beta")
        )
    }

    @Test
    fun invalidConnectorIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            McpOAuthAuthStateStore.storageId("../unsafe")
        }
        assertThrows(IllegalArgumentException::class.java) {
            McpOAuthAuthStateStore.storageId("x")
        }
        assertThrows(IllegalArgumentException::class.java) {
            McpOAuthAuthStateStore.storageId("a".repeat(81))
        }
    }
}
