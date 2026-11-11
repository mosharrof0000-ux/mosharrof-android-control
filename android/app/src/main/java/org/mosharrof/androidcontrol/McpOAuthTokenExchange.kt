package org.mosharrof.androidcontrol

import android.content.Context
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationRequest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Performs an authorization-code + PKCE exchange only after the callback
 * handler validates it against the encrypted original request.
 *
 * Tokens are never returned to UI callers or written to logs. A successful
 * Bearer token response is persisted through AndroidKeyStore encryption.
 */
internal object McpOAuthTokenExchange {

    data class Outcome(
        /** False means the encrypted pending request still needs safe cleanup. */
        val pendingRequestCleared: Boolean
    )

    fun exchange(
        context: Context,
        connectorId: String,
        callbackIntent: android.content.Intent?,
        pendingRequest: AuthorizationRequest?,
        authorizationService: AuthorizationService,
        onComplete: (Result<Outcome>) -> Unit
    ) {
        val response = McpOAuthAuthorizationCallbackHandler
            .validateAuthorizationResponse(callbackIntent, pendingRequest)
            .getOrElse {
                onComplete(Result.failure(IllegalStateException(
                    "OAuth callback যাচাই ব্যর্থ হয়েছে; token exchange করা হয়নি।"
                )))
                return
            }

        val expectedResource = pendingRequest?.additionalParameters?.get("resource")
        if (expectedResource.isNullOrBlank() || !McpHttpClient.isSafeEndpoint(expectedResource)) {
            onComplete(Result.failure(IllegalStateException(
                "OAuth authorization request-এ বৈধ protected resource অনুপস্থিত।"
            )))
            return
        }

        // AppAuth's no-argument helper does not copy authorization request parameters
        // into the token request; pass RFC 8707 resource explicitly.
        val tokenRequest = runCatching {
            response.createTokenExchangeRequest(mapOf("resource" to expectedResource))
        }.getOrElse {
            onComplete(Result.failure(IllegalStateException(
                "OAuth token request তৈরি করা যায়নি।"
            )))
            return
        }
        if (tokenRequest.additionalParameters["resource"] != expectedResource) {
            onComplete(Result.failure(IllegalStateException(
                "OAuth token request-এর protected resource মূল অনুরোধের সঙ্গে মেলেনি।"
            )))
            return
        }

        val authState = AuthState(response, null)
        val completed = AtomicBoolean(false)

        authorizationService.performTokenRequest(tokenRequest) { tokenResponse, authException ->
            if (!completed.compareAndSet(false, true)) return@performTokenRequest

            if (authException != null || tokenResponse == null) {
                onComplete(Result.failure(IllegalStateException(
                    "OAuth token exchange ব্যর্থ হয়েছে; সংরক্ষিত credentials অপরিবর্তিত আছে。"
                )))
                return@performTokenRequest
            }

            if (tokenResponse.accessToken.isNullOrBlank() ||
                !tokenResponse.tokenType.equals("Bearer", ignoreCase = true)
            ) {
                onComplete(Result.failure(IllegalStateException(
                    "OAuth server বৈধ Bearer access token দেয়নি; credentials সংরক্ষণ করা হয়নি।"
                )))
                return@performTokenRequest
            }

            authState.update(tokenResponse, null)
            val persisted = McpOAuthAuthStateStore.save(
                context.applicationContext,
                connectorId,
                authState
            )
            if (persisted.isFailure) {
                onComplete(Result.failure(IllegalStateException(
                    "OAuth token encrypted storage-এ সংরক্ষণ করা যায়নি।"
                )))
                return@performTokenRequest
            }

            // Keep the pending PKCE verifier until the successful token response is durable.
            val pendingCleared = McpOAuthPendingRequestStore
                .delete(context.applicationContext, connectorId)
                .isSuccess
            onComplete(Result.success(Outcome(pendingRequestCleared = pendingCleared)))
        }
    }
}
