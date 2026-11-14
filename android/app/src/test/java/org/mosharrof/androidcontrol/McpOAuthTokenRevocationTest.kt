package org.mosharrof.androidcontrol

import org.junit.Assert.*
import org.junit.Test
import java.net.URL
import java.net.URLDecoder

class McpOAuthTokenRevocationTest {
    @Test
    fun postsRfc7009FormToValidatedHttpsEndpoint() {
        val token = "refresh+token/with=reserved"
        var capturedUrl: URL? = null
        var capturedHeaders: Map<String, String>? = null
        var capturedBody: String? = null

        val result = McpOAuthTokenRevocation.revoke(
            revocationEndpoint = "https://auth.example.com/revoke",
            clientId = "https://client.example.com/metadata",
            token = token,
            tokenTypeHint = "refresh_token"
        ) { url, body, headers ->
            capturedUrl = url
            capturedHeaders = headers
            capturedBody = body.toString(Charsets.UTF_8)
            200
        }

        assertTrue(result.isSuccess)
        assertEquals("https://auth.example.com/revoke", capturedUrl.toString())
        assertEquals(
            "application/x-www-form-urlencoded; charset=UTF-8",
            capturedHeaders?.get("Content-Type")
        )
        val fields = capturedBody!!.split("&").associate {
            val pair = it.split("=", limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
        }
        assertEquals(token, fields["token"])
        assertEquals("refresh_token", fields["token_type_hint"])
        assertEquals("https://client.example.com/metadata", fields["client_id"])
    }

    @Test
    fun rejectsInsecureEndpointBeforeNetworkCall() {
        var called = false
        val result = McpOAuthTokenRevocation.revoke(
            "http://auth.example.com/revoke",
            "https://client.example.com/metadata",
            "opaque-token"
        ) { _, _, _ ->
            called = true
            200
        }
        assertTrue(result.isFailure)
        assertFalse(called)
    }

    @Test
    fun rejectsRedirectAndNonSuccessResponses() {
        val redirect = McpOAuthTokenRevocation.revoke(
            "https://auth.example.com/revoke",
            "https://client.example.com/metadata",
            "opaque-token"
        ) { _, _, _ -> 302 }
        assertTrue(redirect.isFailure)

        val serverError = McpOAuthTokenRevocation.revoke(
            "https://auth.example.com/revoke",
            "https://client.example.com/metadata",
            "opaque-token"
        ) { _, _, _ -> 503 }
        assertTrue(serverError.isFailure)
    }

    @Test
    fun rejectsInvalidTokenHintAndOversizedInputs() {
        assertTrue(
            McpOAuthTokenRevocation.revoke(
                "https://auth.example.com/revoke",
                "https://client.example.com/metadata",
                "opaque-token",
                "id_token"
            ) { _, _, _ -> 200 }.isFailure
        )
        assertTrue(
            McpOAuthTokenRevocation.revoke(
                "https://auth.example.com/revoke",
                "https://client.example.com/metadata",
                "x".repeat(8_193)
            ) { _, _, _ -> 200 }.isFailure
        )
    }

    @Test
    fun rejectsEndpointUserInfoAndFragmentBeforeNetworkCall() {
        val endpoints = listOf(
            "https://user:password@auth.example.com/revoke",
            "https://auth.example.com/revoke#fragment"
        )
        endpoints.forEach { endpoint ->
            var called = false
            val result = McpOAuthTokenRevocation.revoke(
                endpoint,
                "https://client.example.com/metadata",
                "opaque-token"
            ) { _, _, _ ->
                called = true
                200
            }
            assertTrue(result.isFailure)
            assertFalse(called)
        }
    }

    @Test
    fun rejectsBlankAndOversizedClientOrTokenBeforeNetworkCall() {
        val invalidInputs = listOf(
            Triple("https://auth.example.com/revoke", "", "opaque-token"),
            Triple("https://auth.example.com/revoke", "x".repeat(4_097), "opaque-token"),
            Triple("https://auth.example.com/revoke", "https://client.example.com/metadata", "")
        )
        invalidInputs.forEach { (endpoint, clientId, token) ->
            var called = false
            val result = McpOAuthTokenRevocation.revoke(
                endpoint,
                clientId,
                token
            ) { _, _, _ ->
                called = true
                200
            }
            assertTrue(result.isFailure)
            assertFalse(called)
        }
    }

    @Test
    fun acceptsAccessTokenHintAndEncodesClientAndTokenFields() {
        val token = "access token/with+reserved"
        var capturedBody: String? = null
        val result = McpOAuthTokenRevocation.revoke(
            revocationEndpoint = "https://auth.example.com/revoke",
            clientId = "https://client.example.com/metadata",
            token = token,
            tokenTypeHint = "access_token"
        ) { _, body, _ ->
            capturedBody = body.toString(Charsets.UTF_8)
            200
        }
        assertTrue(result.isSuccess)
        val fields = capturedBody!!.split("&").associate {
            val pair = it.split("=", limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
        }
        assertEquals(token, fields["token"])
        assertEquals("access_token", fields["token_type_hint"])
    }

}
