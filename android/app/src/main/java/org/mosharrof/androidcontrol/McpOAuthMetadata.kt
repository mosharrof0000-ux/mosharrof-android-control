package org.mosharrof.androidcontrol

import java.net.URI

/** Fail-closed validators for OAuth 2.1 public-client and CIMD metadata. */
internal object McpOAuthMetadata {
    /** RFC 9728 metadata URL; resource path is preserved after the well-known segment. */
    fun protectedResourceMetadataUrl(resourceUrl: String): String? =
        wellKnownUrl(resourceUrl, "oauth-protected-resource")

    /** RFC 8414 authorization-server metadata URL. */
    fun authorizationServerMetadataUrl(issuer: String): String? =
        wellKnownUrl(issuer, "oauth-authorization-server")

    fun validateProtectedResourceMetadata(
        expectedResource: String,
        metadata: Map<String, Any?>
    ): Boolean {
        if (!isHttpsUrl(expectedResource)) return false
        if (metadata["resource"] != expectedResource) return false
        val servers = metadata["authorization_servers"] as? List<*> ?: return false
        return servers.isNotEmpty() && servers.size <= 16 &&
            servers.all { isHttpsIssuer(it as? String) }
    }

    private fun wellKnownUrl(value: String, metadataName: String): String? = runCatching {
        if (!isHttpsUrl(value)) return null
        val uri = URI(value)
        require(uri.rawQuery == null && uri.rawFragment == null && uri.userInfo == null)
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val base = "${uri.scheme}://${uri.rawAuthority}/.well-known/$metadataName"
        (base + path).takeIf { it.length <= 4_096 }
    }.getOrNull()

    fun validateClientMetadata(
        metadataUrl: String,
        expectedRedirectUri: String,
        metadata: Map<String, Any?>
    ): Boolean {
        if (!isHttpsUrl(metadataUrl)) return false
        if (metadata["client_id"] != metadataUrl) return false
        val clientName = metadata["client_name"] as? String ?: return false
        if (clientName.isBlank() || clientName.length > 128) return false
        if (metadata["token_endpoint_auth_method"] != "none") return false
        if (metadata["application_type"] != "native") return false
        if (metadata.containsKey("client_secret")) return false

        val redirects = metadata["redirect_uris"] as? List<*> ?: return false
        if (redirects.isEmpty() || redirects.size > 16) return false
        if (redirects.none { it == expectedRedirectUri }) return false
        if (redirects.any { !isValidRedirectUri(it as? String) }) return false

        val grants = metadata["grant_types"] as? List<*> ?: return false
        if (grants.isEmpty() || grants.size > 16 || grants.none { it == "authorization_code" }) return false
        val responses = metadata["response_types"] as? List<*> ?: return false
        if (responses.isEmpty() || responses.size > 16 || responses.none { it == "code" }) return false
        return true
    }

    fun validateAuthorizationServerMetadata(
        expectedIssuer: String,
        metadata: Map<String, Any?>
    ): Boolean {
        if (!isHttpsIssuer(expectedIssuer)) return false
        if (metadata["issuer"] != expectedIssuer) return false
        if (!isHttpsUrl(metadata["authorization_endpoint"] as? String)) return false
        if (!isHttpsUrl(metadata["token_endpoint"] as? String)) return false

        val responses = metadata["response_types_supported"] as? List<*> ?: return false
        if (responses.isEmpty() || responses.size > 32 || responses.none { it == "code" }) return false
        val grants = metadata["grant_types_supported"] as? List<*> ?: return false
        if (grants.isEmpty() || grants.size > 32 || grants.none { it == "authorization_code" }) return false
        val authMethods = metadata["token_endpoint_auth_methods_supported"] as? List<*> ?: return false
        if (authMethods.isEmpty() || authMethods.size > 32 || authMethods.none { it == "none" }) return false
        val pkce = metadata["code_challenge_methods_supported"] as? List<*> ?: return false
        if (pkce.isEmpty() || pkce.size > 32 || pkce.none { it == "S256" }) return false
        return true
    }

    fun validateAuthorizationIssuer(responseIssuer: String?, expectedIssuer: String): Boolean =
        isHttpsIssuer(expectedIssuer) && !responseIssuer.isNullOrBlank() && responseIssuer == expectedIssuer

    /** RFC 8414 issuer identifiers must not contain query or fragment components. */
    private fun isHttpsIssuer(value: String?): Boolean {
        val issuer = value ?: return false
        if (!isHttpsUrl(issuer)) return false
        return runCatching {
            val uri = URI(issuer)
            uri.rawQuery == null && uri.rawFragment == null
        }.getOrDefault(false)
    }

    private fun isHttpsUrl(value: String?): Boolean = runCatching {
        if (value.isNullOrBlank() || value.length > 4_096) return false
        val uri = URI(value)
        uri.scheme.equals("https", true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.fragment == null
    }.getOrDefault(false)

    private fun isValidRedirectUri(value: String?): Boolean = runCatching {
        if (value.isNullOrBlank() || value.length > 2_048) return false
        val uri = URI(value)
        val scheme = uri.scheme ?: return false
        val safeScheme = scheme.matches(Regex("[A-Za-z][A-Za-z0-9+.-]*")) &&
            !scheme.equals("javascript", true) &&
            !scheme.equals("data", true) &&
            !scheme.equals("file", true) &&
            !scheme.equals("content", true) &&
            !scheme.equals("intent", true)
        val safeHttpRedirect = if (scheme.equals("http", true)) {
            uri.host in setOf("localhost", "127.0.0.1", "::1") && uri.userInfo == null
        } else {
            true
        }
        safeScheme && safeHttpRedirect && uri.userInfo == null && uri.fragment == null
    }.getOrDefault(false)
}
