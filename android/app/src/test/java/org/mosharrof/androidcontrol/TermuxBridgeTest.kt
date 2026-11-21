package org.mosharrof.androidcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxBridgeTest {
    @Test
    fun onlyFixedReadOnlyOperationsAreSupported() {
        assertTrue(TermuxBridge.supportsOperation("termux.python_version"))
        assertTrue(TermuxBridge.supportsOperation("termux.git_video_status"))
        assertTrue(TermuxBridge.supportsOperation("termux.storage_report"))
        assertTrue(TermuxBridge.supportsOperation("termux.system_info"))
        assertFalse(TermuxBridge.supportsOperation("termux.shell"))
        assertFalse(TermuxBridge.supportsOperation("../python"))
    }

    @Test
    fun termuxInternalErrorsOverrideShellExitCode() {
        assertEquals(0, TermuxBridge.effectiveExitCode(0, -1))
        assertEquals(7, TermuxBridge.effectiveExitCode(7, -1))
        assertEquals(-13, TermuxBridge.effectiveExitCode(0, -13))
    }
}
