package org.mosharrof.androidcontrol

import java.security.MessageDigest

/**
 * Validates the authorization-code callback before any token endpoint request.
 * The caller must pass values extracted from AppAuth's AuthorizationResponse
 * and the original request; this helper performs no network or persistence IO.
 */
internal object McpOAuthAuthorizationCallbackValidator {
    data class ValidatedCode internal constructor(
        val authorizationCode: String,
        val codeVerifier: String
    )

    private val verifierPattern = Regex("[A-Za-z0-9._~-]{43,128}")

    fun validate(
        expectedState: String?,
        returnedState: String?,
        authorizationCode: String?,
        codeVerifier: String?,
        codeChallengeMethod: String?
    ): Result<ValidatedCode> = runCatching {
        require(!expectedState.isNullOrBlank() && expectedState.length <= 512) {
            "OAuth request state অনুপস্থিত বা অবৈধ।"
        }
        require(!returnedState.isNullOrBlank() && returnedState.length <= 512) {
            "OAuth callback state অনুপস্থিত বা অবৈধ।"
        }
        require(MessageDigest.isEqual(
            expectedState.toByteArray(Charsets.UTF_8),
            returnedState.toByteArray(Charsets.UTF_8)
        )) {
            "OAuth callback state মেলেনি; অনুরোধ বাতিল করা হয়েছে।"
        }
        require(!authorizationCode.isNullOrBlank() && authorizationCode.length <= 4096) {
            "Authorization code অনুপস্থিত বা অবৈধ।"
        }
        require(!codeVerifier.isNullOrBlank() && verifierPattern.matches(codeVerifier)) {
            "PKCE code verifier অনুপস্থিত বা অবৈধ।"
        }
        require(codeChallengeMethod == "S256") {
            "OAuth callback-এর PKCE পদ্ধতি S256 নয়।"
        }
        ValidatedCode(authorizationCode, codeVerifier)
    }
}
