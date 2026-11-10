package org.mosharrof.androidcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class AiResponseBoundsTest {
    @Test
    fun readsResponseWithinLimit() {
        val bytes = ByteArray(256 * 1024) { 'a'.code.toByte() }
        assertEquals(bytes.size, readBoundedAiResponse(ByteArrayInputStream(bytes)).length)
    }

    @Test
    fun rejectsResponseOverLimit() {
        val bytes = ByteArray(257) { 'b'.code.toByte() }
        assertThrows(IllegalArgumentException::class.java) {
            readBoundedAiResponse(ByteArrayInputStream(bytes), 256)
        }
    }

    @Test
    fun nullResponseStreamReturnsEmptyString() {
        assertEquals("", readBoundedAiResponse(null))
    }

    @Test
    fun rejectsUnreasonableConfiguredLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            readBoundedAiResponse(ByteArrayInputStream(byteArrayOf()), 1024 * 1024 + 1)
        }
    }
}
