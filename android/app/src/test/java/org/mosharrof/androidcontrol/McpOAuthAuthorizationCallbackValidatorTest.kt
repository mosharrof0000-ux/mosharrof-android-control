package org.mosharrof.androidcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpOAuthAuthorizationCallbackValidatorTest {
    private val state = "state-value-generated-by-appauth"
    private val verifier = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ012"

    @Test
    fun acceptsMatchingStateAuthorizationCodeAndS256Verifier() {
        val result = McpOAuthAuthorizationCallbackValidator.validate(
            state, state, "authorization-code", verifier, "S256"
        )

        assertTrue(result.isSuccess)
        assertEquals("authorization-code", result.getOrThrow().authorizationCode)
        assertEquals(verifier, result.getOrThrow().codeVerifier)
    }

    @Test
    fun rejectsMissingOrMismatchedState() {
        assertFalse(validate(expected = null).isSuccess)
        assertFalse(validate(returned = null).isSuccess)
        assertFalse(validate(returned = "attacker-controlled-state").isSuccess)
    }

    @Test
    fun rejectsMissingOrOversizedAuthorizationCode() {
        assertFalse(validate(code = null).isSuccess)
        assertFalse(validate(code = "").isSuccess)
        assertFalse(validate(code = "x".repeat(4097)).isSuccess)
    }

    @Test
    fun rejectsInvalidPkceVerifier() {
        assertFalse(validate(verifierValue = null).isSuccess)
        assertFalse(validate(verifierValue = "too-short").isSuccess)
        assertFalse(validate(verifierValue = "x".repeat(129)).isSuccess)
        assertFalse(validate(verifierValue = "x".repeat(42)).isSuccess)
        assertFalse(validate(verifierValue = "x".repeat(43) + "!").isSuccess)
    }

    @Test
    fun rejectsNonS256Pkce() {
        assertFalse(validate(method = "plain").isSuccess)
        assertFalse(validate(method = null).isSuccess)
    }

    private fun validate(
        expected: String? = state,
        returned: String? = state,
        code: String? = "authorization-code",
        verifierValue: String? = verifier,
        method: String? = "S256"
    ) = McpOAuthAuthorizationCallbackValidator.validate(
        expected, returned, code, verifierValue, method
    )
}
