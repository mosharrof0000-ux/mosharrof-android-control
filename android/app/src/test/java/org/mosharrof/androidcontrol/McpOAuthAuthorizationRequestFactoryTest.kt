package org.mosharrof.androidcontrol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpOAuthAuthorizationRequestFactoryTest {
    private val issuer = "https://auth.example.com"
    private val clientMetadataUrl = "https://client.example.com/oauth-client.json"

    private val authorizationServerMetadata = mapOf<String, Any?>(
        "issuer" to issuer,
        "authorization_endpoint" to "$issuer/authorize",
        "token_endpoint" to "$issuer/token",
        "response_types_supported" to listOf("code"),
        "grant_types_supported" to listOf("authorization_code"),
        "token_endpoint_auth_methods_supported" to listOf("none"),
        "code_challenge_methods_supported" to listOf("S256")
    )

    private val clientMetadata = mapOf<String, Any?>(
        "client_id" to clientMetadataUrl,
        "client_name" to "MOSHARROF Android",
        "application_type" to "native",
        "token_endpoint_auth_method" to "none",
        "redirect_uris" to listOf(McpOAuthAuthorizationRequestFactory.REDIRECT_URI),
        "grant_types" to listOf("authorization_code"),
        "response_types" to listOf("code")
    )

    @Test
    fun acceptsValidatedPublicClientAndS256ServerMetadata() {
        assertTrue(
            McpOAuthAuthorizationRequestFactory.validateInputs(
                issuer, authorizationServerMetadata,
                clientMetadataUrl, clientMetadata, listOf("mcp:tools")
            )
        )
    }

    @Test
    fun rejectsServerWithoutS256() {
        assertFalse(
            McpOAuthAuthorizationRequestFactory.validateInputs(
                issuer,
                authorizationServerMetadata + ("code_challenge_methods_supported" to listOf("plain")),
                clientMetadataUrl, clientMetadata, listOf("mcp:tools")
            )
        )
    }

    @Test
    fun rejectsClientSecretAndWrongRedirect() {
        assertFalse(
            McpOAuthAuthorizationRequestFactory.validateInputs(
                issuer, authorizationServerMetadata, clientMetadataUrl,
                clientMetadata + ("client_secret" to "must-not-exist"),
                listOf("mcp:tools")
            )
        )
        assertFalse(
            McpOAuthAuthorizationRequestFactory.validateInputs(
                issuer, authorizationServerMetadata, clientMetadataUrl,
                clientMetadata + ("redirect_uris" to listOf("org.attacker:/redirect")),
                listOf("mcp:tools")
            )
        )
    }

    @Test
    fun rejectsMalformedOrUnboundedScopes() {
        assertFalse(
            McpOAuthAuthorizationRequestFactory.validateInputs(
                issuer, authorizationServerMetadata, clientMetadataUrl,
                clientMetadata, emptyList()
            )
        )
        assertFalse(
            McpOAuthAuthorizationRequestFactory.validateInputs(
                issuer, authorizationServerMetadata, clientMetadataUrl,
                clientMetadata, listOf("mcp:tools", "mcp:tools")
            )
        )
        assertFalse(
            McpOAuthAuthorizationRequestFactory.validateInputs(
                issuer, authorizationServerMetadata, clientMetadataUrl,
                clientMetadata, listOf("scope with spaces")
            )
        )
        assertFalse(
            McpOAuthAuthorizationRequestFactory.validateInputs(
                issuer, authorizationServerMetadata, clientMetadataUrl,
                clientMetadata, listOf("x".repeat(129))
            )
        )
    }

    @Test
    fun rejectsCimdForDifferentClientId() {
        assertFalse(
            McpOAuthAuthorizationRequestFactory.validateInputs(
                issuer, authorizationServerMetadata, clientMetadataUrl,
                clientMetadata + ("client_id" to "https://other.example/client"),
                listOf("mcp:tools")
            )
        )
    }
}
