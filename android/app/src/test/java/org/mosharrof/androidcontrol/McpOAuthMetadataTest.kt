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
    fun derivesOAuthWellKnownUrlsAndValidatesResourceBinding() {
        assertEquals(
            "https://mcp.example.com/.well-known/oauth-protected-resource/api",
            McpOAuthMetadata.protectedResourceMetadataUrl("https://mcp.example.com/api")
        )
        assertNull(McpOAuthMetadata.protectedResourceMetadataUrl("https://mcp.example.com/api?x=1"))
        assertEquals(
            "https://auth.example.com/.well-known/oauth-authorization-server/tenant",
            McpOAuthMetadata.authorizationServerMetadataUrl("https://auth.example.com/tenant")
        )
        val resource = "https://mcp.example.com/api"
        val metadata = mapOf<String, Any?>(
            "resource" to resource,
            "authorization_servers" to listOf(issuer)
        )
        assertTrue(McpOAuthMetadata.validateProtectedResourceMetadata(resource, metadata))
        assertFalse(
            McpOAuthMetadata.validateProtectedResourceMetadata(
                resource,
                metadata + ("resource" to "https://evil.example/api")
            )
        )
        assertFalse(
            McpOAuthMetadata.validateProtectedResourceMetadata(
                resource,
                metadata + ("authorization_servers" to listOf("http://auth.example.com"))
            )
        )
    }

    @Test
    fun rejectsAuthorizationServerListEntriesWithQueryOrFragment() {
        val resource = "https://mcp.example.com/api"
        val metadata = mapOf<String, Any?>(
            "resource" to resource,
            "authorization_servers" to listOf(issuer)
        )

        assertTrue(McpOAuthMetadata.validateProtectedResourceMetadata(resource, metadata))
        assertFalse(
            McpOAuthMetadata.validateProtectedResourceMetadata(
                resource,
                metadata + ("authorization_servers" to listOf(issuer + "?tenant=unexpected"))
            )
        )
        assertFalse(
            McpOAuthMetadata.validateProtectedResourceMetadata(
                resource,
                metadata + ("authorization_servers" to listOf(issuer + "#tenant"))
            )
        )
    }

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
    fun acceptsOptionalHttpsRevocationEndpointAndRejectsUnsafeAdvertisedEndpoint() {
        val valid = serverMetadata() + ("revocation_endpoint" to "$issuer/revoke")
        assertTrue(McpOAuthMetadata.validateAuthorizationServerMetadata(issuer, valid))
        assertEquals(
            "$issuer/revoke",
            McpOAuthMetadata.validatedRevocationEndpoint(valid)
        )

        val missing = serverMetadata()
        assertTrue(McpOAuthMetadata.validateAuthorizationServerMetadata(issuer, missing))
        assertNull(McpOAuthMetadata.validatedRevocationEndpoint(missing))

        val insecure = serverMetadata() + ("revocation_endpoint" to "http://auth.example.com/revoke")
        assertFalse(McpOAuthMetadata.validateAuthorizationServerMetadata(issuer, insecure))
        assertNull(McpOAuthMetadata.validatedRevocationEndpoint(insecure))

        val fragment = serverMetadata() + ("revocation_endpoint" to "$issuer/revoke#fragment")
        assertFalse(McpOAuthMetadata.validateAuthorizationServerMetadata(issuer, fragment))
    }

    @Test
    fun enforcesRfc9207IssuerWhenAdvertisedAndValidatesAnySuppliedIssuer() {
        val required = serverMetadata() +
            ("authorization_response_iss_parameter_supported" to true)
        assertTrue(McpOAuthMetadata.validateAuthorizationServerMetadata(issuer, required))
        assertEquals(true, McpOAuthMetadata.authorizationResponseIssuerRequired(required))
        assertTrue(McpOAuthMetadata.validateAuthorizationIssuer(issuer, issuer, true))
        assertFalse(McpOAuthMetadata.validateAuthorizationIssuer(null, issuer, true))
        assertFalse(McpOAuthMetadata.validateAuthorizationIssuer("https://evil.example", issuer, true))

        val optional = serverMetadata() +
            ("authorization_response_iss_parameter_supported" to false)
        assertTrue(McpOAuthMetadata.validateAuthorizationServerMetadata(issuer, optional))
        assertEquals(false, McpOAuthMetadata.authorizationResponseIssuerRequired(optional))
        assertTrue(McpOAuthMetadata.validateAuthorizationIssuer(null, issuer, false))
        assertTrue(McpOAuthMetadata.validateAuthorizationIssuer(issuer, issuer, false))
        assertFalse(McpOAuthMetadata.validateAuthorizationIssuer("https://evil.example", issuer, false))

        val malformed = serverMetadata() +
            ("authorization_response_iss_parameter_supported" to "true")
        assertFalse(McpOAuthMetadata.validateAuthorizationServerMetadata(issuer, malformed))
        assertEquals(null, McpOAuthMetadata.authorizationResponseIssuerRequired(malformed))
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
    fun rejectsOversizedCimdMetadataCollectionsAndNames() {
        assertFalse(
            McpOAuthMetadata.validateClientMetadata(
                metadataUrl,
                redirect,
                clientMetadata() + ("client_name" to "x".repeat(129))
            )
        )
        assertFalse(
            McpOAuthMetadata.validateClientMetadata(
                metadataUrl,
                redirect,
                clientMetadata() + ("redirect_uris" to List(17) { redirect })
            )
        )
        assertFalse(
            McpOAuthMetadata.validateClientMetadata(
                metadataUrl,
                redirect,
                clientMetadata() + ("grant_types" to List(17) { "authorization_code" })
            )
        )
    }

    @Test
    fun rejectsAuthorizationServerIssuerWithQueryComponent() {
        val queryIssuer = issuer + "?tenant=unexpected"
        assertNull(McpOAuthMetadata.authorizationServerMetadataUrl(queryIssuer))
        assertFalse(
            McpOAuthMetadata.validateAuthorizationServerMetadata(
                queryIssuer,
                serverMetadata() + ("issuer" to queryIssuer)
            )
        )
        val fragmentIssuer = issuer + "#tenant"
        assertNull(McpOAuthMetadata.authorizationServerMetadataUrl(fragmentIssuer))
        assertFalse(
            McpOAuthMetadata.validateAuthorizationServerMetadata(
                fragmentIssuer,
                serverMetadata() + ("issuer" to fragmentIssuer)
            )
        )
    }

    @Test
    fun rejectsOversizedAuthorizationServerCapabilityLists() {
        assertFalse(
            McpOAuthMetadata.validateAuthorizationServerMetadata(
                issuer,
                serverMetadata() + ("code_challenge_methods_supported" to List(33) { "S256" })
            )
        )
    }

    @Test
    fun requiresAuthorizationResponseIssuerMatch() {
        assertTrue(McpOAuthMetadata.validateAuthorizationIssuer(issuer, issuer))
        assertFalse(McpOAuthMetadata.validateAuthorizationIssuer(null, issuer))
        assertFalse(McpOAuthMetadata.validateAuthorizationIssuer("https://evil.example", issuer))
        val queryIssuer = issuer + "?tenant=unexpected"
        assertFalse(McpOAuthMetadata.validateAuthorizationIssuer(queryIssuer, queryIssuer))
    }
}
