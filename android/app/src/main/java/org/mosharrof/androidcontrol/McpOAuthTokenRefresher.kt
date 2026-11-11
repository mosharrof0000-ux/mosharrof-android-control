package org.mosharrof.androidcontrol

import android.content.Context
import net.openid.appauth.AuthorizationService
import java.util.concurrent.ConcurrentHashMap

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
            onComplete(Result.failure(IllegalStateException(
                "এই Connector-এর OAuth refresh ইতিমধ্যে চলছে।"
            )))
            return
        }

        val state = McpOAuthAuthStateStore.read(
            context.applicationContext,
            connectorId
        ).getOrElse {
            inFlight.remove(connectorId)
            onComplete(Result.failure(IllegalStateException(
                "Encrypted OAuth state পড়া যায়নি। আবার সংযোগ করুন।"
            )))
            return
        } ?: run {
            inFlight.remove(connectorId)
            onComplete(Result.failure(IllegalStateException(
                "এই Connector-এর OAuth credentials সংরক্ষিত নেই।"
            )))
            return
        }

        state.performActionWithFreshTokens(authorizationService) { accessToken, _, authException ->
            try {
                if (authException != null || accessToken.isNullOrBlank()) {
                    onComplete(Result.failure(IllegalStateException(
                        "OAuth access token refresh ব্যর্থ হয়েছে।"
                    )))
                    return@performActionWithFreshTokens
                }

                val tokenType = state.lastTokenResponse?.tokenType
                if (!tokenType.equals("Bearer", ignoreCase = true)) {
                    onComplete(Result.failure(IllegalStateException(
                        "OAuth token type Bearer নয়; MCP request বন্ধ রাখা হয়েছে।"
                    )))
                    return@performActionWithFreshTokens
                }

                val persisted = McpOAuthAuthStateStore.save(
                    context.applicationContext,
                    connectorId,
                    state
                )
                if (persisted.isFailure) {
                    onComplete(Result.failure(IllegalStateException(
                        "Refreshed OAuth state encrypted storage-এ সংরক্ষণ করা যায়নি।"
                    )))
                    return@performActionWithFreshTokens
                }

                onComplete(Result.success(FreshBearerToken(accessToken)))
            } finally {
                inFlight.remove(connectorId)
            }
        }
    }
}
