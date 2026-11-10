package org.mosharrof.androidcontrol

import java.net.URI

/** Fail-closed validators for OAuth 2.1 public-client and CIMD metadata. */
internal object McpOAuthMetadata {
    fun validateClientMetadata(
        metadataUrl: String,
        expectedRedirectUri: String,
        metadata: Map<String, Any?>
    ): Boolean {
        if (!isHttpsUrl(metadataUrl)) return false
        if (metadata["client_id"] != metadataUrl) return false
        if ((metadata["client_name"] as? String).isNullOrBlank()) return false
        if (metadata["token_endpoint_auth_method"] != "none") return false
        if (metadata["application_type"] != "native") return false
        if (metadata.containsKey("client_secret")) return false

        val redirects = metadata["redirect_uris"] as? List<*> ?: return false
        if (redirects.none { it == expectedRedirectUri }) return false
        if (redirects.any { !isValidRedirectUri(it as? String) }) return false

        val grants = metadata["grant_types"] as? List<*> ?: return false
        if (grants.none { it == "authorization_code" }) return false
        val responses = metadata["response_types"] as? List<*> ?: return false
        if (responses.none { it == "code" }) return false
        return true
    }

    fun validateAuthorizationServerMetadata(
        expectedIssuer: String,
        metadata: Map<String, Any?>
    ): Boolean {
        if (!isHttpsUrl(expectedIssuer)) return false
        if (metadata["issuer"] != expectedIssuer) return false
        if (!isHttpsUrl(metadata["authorization_endpoint"] as? String)) return false
        if (!isHttpsUrl(metadata["token_endpoint"] as? String)) return false

        val responses = metadata["response_types_supported"] as? List<*> ?: return false
        if (responses.none { it == "code" }) return false
        val grants = metadata["grant_types_supported"] as? List<*> ?: return false
        if (grants.none { it == "authorization_code" }) return false
        val authMethods = metadata["token_endpoint_auth_methods_supported"] as? List<*> ?: return false
        if (authMethods.none { it == "none" }) return false
        val pkce = metadata["code_challenge_methods_supported"] as? List<*> ?: return false
        if (pkce.none { it == "S256" }) return false
        return true
    }

    fun validateAuthorizationIssuer(responseIssuer: String?, expectedIssuer: String): Boolean =
        !responseIssuer.isNullOrBlank() && responseIssuer == expectedIssuer

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
        !uri.scheme.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.fragment == null &&
            !uri.scheme.equals("javascript", true)
    }.getOrDefault(false)
}
