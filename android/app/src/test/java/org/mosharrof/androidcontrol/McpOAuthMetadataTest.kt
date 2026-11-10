package org.mosharrof.androidcontrol

import org.junit.Assert.*
import org.junit.Test

class McpOAuthMetadataTest {
    private val metadataUrl = "https://client.example.com/.well-known/oauth-client"
    private val redirect = "org.mosharrof.androidcontrol:/oauth2redirect"
    private val issuer = "https://auth.example.com"

    private fun clientMetadata() = mapOf<String, Any?>(
        "client_id" to metadataUrl,
        "client_name" to "MOSHARROF Android",
        "application_type" to "native",
        "token_endpoint_auth_method" to "none",
        "redirect_uris" to listOf(redirect),
        "grant_types" to listOf("authorization_code"),
        "response_types" to listOf("code")
    )

    private fun serverMetadata() = mapOf<String, Any?>(
        "issuer" to issuer,
        "authorization_endpoint" to "$issuer/authorize",
        "token_endpoint" to "$issuer/token",
        "response_types_supported" to listOf("code"),
        "grant_types_supported" to listOf("authorization_code"),
        "token_endpoint_auth_methods_supported" to listOf("none"),
        "code_challenge_methods_supported" to listOf("S256")
    )

    @Test
    fun acceptsPublicNativeCimdClientMetadata() {
        assertTrue(
            McpOAuthMetadata.validateClientMetadata(
                metadataUrl, redirect, clientMetadata()
            )
        )
    }

    @Test
    fun rejectsClientSecretAndMismatchedClientId() {
        assertFalse(
            McpOAuthMetadata.validateClientMetadata(
                metadataUrl, redirect, clientMetadata() + ("client_secret" to "never-store")
            )
        )
        assertFalse(
            McpOAuthMetadata.validateClientMetadata(
                metadataUrl, redirect, clientMetadata() + ("client_id" to "https://attacker.example/client")
            )
        )
    }

    @Test
    fun rejectsUnsafeRedirectSchemes() {
        val unsafe = clientMetadata() + (
            "redirect_uris" to listOf("javascript:alert(1)")
        )
        assertFalse(McpOAuthMetadata.validateClientMetadata(metadataUrl, "javascript:alert(1)", unsafe))
        val nonLoopbackHttp = clientMetadata() + (
            "redirect_uris" to listOf("http://attacker.example/callback")
        )
        assertFalse(
            McpOAuthMetadata.validateClientMetadata(
                metadataUrl, "http://attacker.example/callback", nonLoopbackHttp
            )
        )
    }

    @Test
    fun acceptsAuthorizationServerMetadataWithPkceS256() {
        assertTrue(
            McpOAuthMetadata.validateAuthorizationServerMetadata(issuer, serverMetadata())
        )
    }

    @Test
    fun rejectsWrongIssuerAndMissingPkce() {
        assertFalse(
            McpOAuthMetadata.validateAuthorizationServerMetadata(
                issuer, serverMetadata() + ("issuer" to "https://other.example")
            )
        )
        assertFalse(
            McpOAuthMetadata.validateAuthorizationServerMetadata(
                issuer, serverMetadata() + ("code_challenge_methods_supported" to listOf("plain"))
            )
        )
    }

    @Test
    fun requiresAuthorizationResponseIssuerMatch() {
        assertTrue(McpOAuthMetadata.validateAuthorizationIssuer(issuer, issuer))
        assertFalse(McpOAuthMetadata.validateAuthorizationIssuer(null, issuer))
        assertFalse(McpOAuthMetadata.validateAuthorizationIssuer("https://evil.example", issuer))
    }
}
