package org.mosharrof.androidcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class RelayClientSecurityTest {
    @Test
    fun normalizeAcceptsHttpsBaseAndRejectsUnsafeUrlParts() {
        assertEquals(
            "https://relay.example.workers.dev",
            RelayClient.normalize("https://relay.example.workers.dev/")
        )
        assertRejected("http://relay.example.workers.dev")
        assertRejected("https://user:pass@relay.example.workers.dev")
        assertRejected("https://relay.example.workers.dev?token=secret")
        assertRejected("https://relay.example.workers.dev#fragment")
    }

    @Test
    fun commandIdsCannotInjectPathSegments() {
        assertTrue(RelayClient.isValidCommandId("job_123-ABC"))
        assertFalse(RelayClient.isValidCommandId("../other"))
        assertFalse(RelayClient.isValidCommandId("job/result"))
        assertFalse(RelayClient.isValidCommandId("job?x=1"))
        assertFalse(RelayClient.isValidCommandId("x".repeat(129)))
    }

    @Test
    fun relayResponseReaderRejectsOversizedBody() {
        val failure = runCatching {
            RelayClient.readLimited(ByteArrayInputStream("12345".toByteArray()), 4)
        }.exceptionOrNull()
        assertTrue(failure is IOException)
    }

    @Test
    fun relayResponseReaderAcceptsBodyWithinLimit() {
        assertEquals(
            "safe",
            RelayClient.readLimited(ByteArrayInputStream("safe".toByteArray()), 4)
        )
    }

    private fun assertRejected(value: String) {
        try {
            RelayClient.normalize(value)
            fail("Expected unsafe relay URL to be rejected: $value")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
