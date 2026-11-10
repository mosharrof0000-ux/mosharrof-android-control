package org.mosharrof.androidcontrol

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class KeyAliasPolicyTest {
    private val key = SecretKeySpec(ByteArray(16), "AES")

    @Test
    fun absentAliasCreatesNewKey() {
        var calls = 0
        val result = KeyAliasPolicy.resolve(false, null, "unavailable") {
            calls++
            key
        }
        assertSame(key, result)
        assertEquals(1, calls)
    }

    @Test
    fun existingAliasReturnsExistingKey() {
        var calls = 0
        val result = KeyAliasPolicy.resolve(true, key, "unavailable") {
            calls++
            key
        }
        assertSame(key, result)
        assertEquals(0, calls)
    }

    @Test
    fun existingAliasWithoutKeyFailsClosed() {
        var calls = 0
        try {
            KeyAliasPolicy.resolve(true, null, "key unavailable") {
                calls++
                key
            }
            fail("Expected IllegalStateException")
        } catch (error: IllegalStateException) {
            assertEquals("key unavailable", error.message)
        }
        assertEquals(0, calls)
    }

    @Test
    fun absentAliasDoesNotReuseSuppliedStaleKey() {
        var calls = 0
        val result = KeyAliasPolicy.resolve(false, key, "unavailable") {
            calls++
            SecretKeySpec(ByteArray(16) { 1 }, "AES")
        }
        assertNotSame(key, result)
        assertEquals(1, calls)
    }
}
