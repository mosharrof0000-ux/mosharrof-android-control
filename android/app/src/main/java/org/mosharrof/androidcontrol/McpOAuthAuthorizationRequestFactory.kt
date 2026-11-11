package org.mosharrof.androidcontrol

import android.net.Uri
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues

/**
 * Builds a public native-client Authorization Code request only after both
 * authorization-server metadata and CIMD have been validated.
 *
 * AppAuth generates the PKCE verifier/challenge; this wrapper rejects any
 * request that does not resolve to S256. It does not launch a browser or
 * exchange/store tokens.
 */
internal object McpOAuthAuthorizationRequestFactory {
    const val REDIRECT_URI = "org.mosharrof.androidcontrol:/oauth2redirect"

    fun validateInputs(
        issuer: String,
        authorizationServerMetadata: Map<String, Any?>,
        clientMetadataUrl: String,
        clientMetadata: Map<String, Any?>,
        scopes: List<String>
    ): Boolean {
        if (!McpOAuthMetadata.validateAuthorizationServerMetadata(
                issuer, authorizationServerMetadata
            )
        ) return false
        if (!McpOAuthMetadata.validateClientMetadata(
                clientMetadataUrl, REDIRECT_URI, clientMetadata
            )
        ) return false
        if (scopes.isEmpty() || scopes.size > 32 || scopes.size != scopes.toSet().size) {
            return false
        }
        if (scopes.any { scope ->
                scope.isBlank() || scope.length > 128 ||
                    !scope.matches(Regex("[\\u0021\\u0023-\\u005B\\u005D-\\u007E]+"))
            }
        ) return false
        return true
    }

    fun build(
        issuer: String,
        authorizationServerMetadata: Map<String, Any?>,
        clientMetadataUrl: String,
        clientMetadata: Map<String, Any?>,
        scopes: List<String>
    ): AuthorizationRequest {
        require(validateInputs(
            issuer,
            authorizationServerMetadata,
            clientMetadataUrl,
            clientMetadata,
            scopes
        )) { "OAuth authorization prerequisites are invalid." }

        val authorizationEndpoint =
            authorizationServerMetadata["authorization_endpoint"] as String
        val tokenEndpoint = authorizationServerMetadata["token_endpoint"] as String
        val clientId = clientMetadata["client_id"] as String
        val configuration = AuthorizationServiceConfiguration(
            Uri.parse(authorizationEndpoint),
            Uri.parse(tokenEndpoint)
        )
        val request = AuthorizationRequest.Builder(
            configuration,
            clientId,
            ResponseTypeValues.CODE,
            Uri.parse(REDIRECT_URI)
        )
            .setScope(scopes.joinToString(" "))
            .build()

        check(request.codeVerifier != null &&
            request.codeVerifierChallenge != null &&
            request.codeVerifierChallengeMethod == AuthorizationRequest.CODE_CHALLENGE_METHOD_S256
        ) { "AppAuth did not create an S256 PKCE request." }
        return request
    }
}
