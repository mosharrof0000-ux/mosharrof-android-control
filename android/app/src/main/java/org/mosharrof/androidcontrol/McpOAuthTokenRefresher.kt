package org.mosharrof.androidcontrol

import android.content.Context
import net.openid.appauth.AuthorizationService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Obtains a fresh Bearer access token from encrypted AppAuth state.
 *
 * AppAuth handles expiry checks and refresh-token exchange. The updated state
 * is encrypted and persisted before the token is handed to an in-memory caller.
 * Concurrent refresh attempts for the same connector are rejected to avoid
 * refresh-token rotation races between independent AuthState instances.
 */
internal object McpOAuthTokenRefresher {
    internal class FreshBearerToken internal constructor(
        internal val value: String
    )

    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    fun withFreshToken(
        context: Context,
        connectorId: String,
        authorizationService: AuthorizationService,
        onComplete: (Result<FreshBearerToken>) -> Unit
    ) {
        if (!inFlight.add(connectorId)) {
            onComplete(Result.failure(McpOAuthRefreshException(
                McpOAuthRefreshFailure.REFRESH_ALREADY_RUNNING
            )))
            return
        }

        val callbackDelivered = AtomicBoolean(false)
        fun finish(result: Result<FreshBearerToken>) {
            if (callbackDelivered.compareAndSet(false, true)) {
                inFlight.remove(connectorId)
                onComplete(result)
            }
        }

        val state = McpOAuthAuthStateStore.read(
            context.applicationContext,
            connectorId
        ).getOrElse {
            finish(Result.failure(McpOAuthRefreshException(
                McpOAuthRefreshFailure.ENCRYPTED_STATE_UNAVAILABLE,
                it
            )))
            return
        } ?: run {
            finish(Result.failure(McpOAuthRefreshException(
                McpOAuthRefreshFailure.AUTHORIZATION_STATE_MISSING
            )))
            return
        }

        val originalRequest = state.lastAuthorizationResponse?.request
        val resource = originalRequest?.additionalParameters?.get("resource")
        if (resource.isNullOrBlank() || !McpHttpClient.isSafeEndpoint(resource)) {
            finish(Result.failure(McpOAuthRefreshException(
                McpOAuthRefreshFailure.RESOURCE_BINDING_INVALID
            )))
            return
        }

        try {
            state.performActionWithFreshTokens(
                authorizationService,
                mapOf("resource" to resource)
            ) { accessToken, _, authException ->
                val result = runCatching {
                    if (authException != null || accessToken.isNullOrBlank()) {
                        throw McpOAuthRefreshException(
                            McpOAuthRefreshFailure.REFRESH_REJECTED,
                            authException
                        )
                    }

                    val tokenType = state.lastTokenResponse?.tokenType
                    if (!tokenType.equals("Bearer", ignoreCase = true)) {
                        throw McpOAuthRefreshException(
                            McpOAuthRefreshFailure.BEARER_TOKEN_REQUIRED
                        )
                    }

                    McpOAuthAuthStateStore.save(
                        context.applicationContext,
                        connectorId,
                        state
                    ).getOrElse {
                        throw McpOAuthRefreshException(
                            McpOAuthRefreshFailure.ENCRYPTED_STATE_SAVE_FAILED,
                            it
                        )
                    }

                    FreshBearerToken(accessToken)
                }
                finish(result)
            }
        } catch (_: Exception) {
            finish(Result.failure(McpOAuthRefreshException(
                McpOAuthRefreshFailure.REFRESH_START_FAILED
            )))
        }
    }
}
