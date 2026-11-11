package org.mosharrof.androidcontrol

import android.content.Intent
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.TokenRequest

/**
 * Converts an AppAuth callback into a PKCE token request only when it matches
 * the exact encrypted pending request previously created by this app.
 *
 * This class does not perform network I/O or persist token responses.
 */
internal object McpOAuthAuthorizationCallbackHandler {

    fun validateAuthorizationResponse(
        callbackIntent: Intent?,
        pendingRequest: AuthorizationRequest?,
        expectedIssuer: String,
        issuerRequired: Boolean
    ): Result<AuthorizationResponse> = runCatching {
        requireNotNull(callbackIntent) { "OAuth callback অনুপস্থিত।" }
        val originalRequest = requireNotNull(pendingRequest) {
            "OAuth pending request পাওয়া যায়নি; callback প্রত্যাখ্যান করা হয়েছে।"
        }

        require(AuthorizationException.fromIntent(callbackIntent) == null) {
            "OAuth authorization ব্যর্থ হয়েছে বা ব্যবহারকারী বাতিল করেছেন।"
        }

        val response = AuthorizationResponse.fromIntent(callbackIntent)
            ?: error("OAuth callback-এ authorization response অনুপস্থিত।")

        validateResponseAgainstPendingRequest(response, originalRequest)

        require(McpOAuthMetadata.validateAuthorizationIssuer(
            response.additionalParameters["iss"],
            expectedIssuer,
            issuerRequired
        )) {
            "OAuth authorization-response issuer যাচাই ব্যর্থ; token exchange বন্ধ।"
        }

        McpOAuthAuthorizationCallbackValidator.validate(
            expectedState = originalRequest.state,
            returnedState = response.state,
            authorizationCode = response.authorizationCode,
            codeVerifier = originalRequest.codeVerifier,
            codeChallengeMethod = originalRequest.codeVerifierChallengeMethod
        ).getOrThrow()

        response
    }

    fun createValidatedTokenRequest(
        callbackIntent: Intent?,
        pendingRequest: AuthorizationRequest?,
        expectedIssuer: String,
        issuerRequired: Boolean
    ): Result<TokenRequest> = validateAuthorizationResponse(
        callbackIntent,
        pendingRequest,
        expectedIssuer,
        issuerRequired
    ).map { response ->
        // AppAuth carries the original PKCE verifier into this token exchange request.
        response.createTokenExchangeRequest()
    }

    private fun validateResponseAgainstPendingRequest(
        response: AuthorizationResponse,
        pending: AuthorizationRequest
    ) {
        val callbackRequest = response.request

        require(callbackRequest.state == pending.state) {
            "OAuth callback অন্য authorization request-এর সঙ্গে যুক্ত।"
        }
        require(callbackRequest.clientId == pending.clientId) {
            "OAuth callback-এর client ID মেলেনি।"
        }
        require(callbackRequest.redirectUri == pending.redirectUri) {
            "OAuth callback-এর redirect URI মেলেনি।"
        }
        require(callbackRequest.responseType == pending.responseType) {
            "OAuth callback-এর response type মেলেনি।"
        }
        require(callbackRequest.additionalParameters == pending.additionalParameters) {
            "OAuth callback-এর resource parameter মূল অনুরোধের সঙ্গে মেলেনি।"
        }
        require(callbackRequest.configuration.authorizationEndpoint ==
            pending.configuration.authorizationEndpoint
        ) {
            "OAuth callback-এর authorization endpoint মেলেনি।"
        }
        require(callbackRequest.configuration.tokenEndpoint ==
            pending.configuration.tokenEndpoint
        ) {
            "OAuth callback-এর token endpoint মেলেনি।"
        }
        require(callbackRequest.codeVerifier == pending.codeVerifier &&
            callbackRequest.codeVerifierChallenge == pending.codeVerifierChallenge &&
            callbackRequest.codeVerifierChallengeMethod == AuthorizationRequest.CODE_CHALLENGE_METHOD_S256
        ) {
            "OAuth callback-এর PKCE request মূল অনুরোধের সঙ্গে মেলেনি।"
        }
    }
}
