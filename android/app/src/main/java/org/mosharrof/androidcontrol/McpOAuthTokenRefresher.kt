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
            onComplete(Result.failure(IllegalStateException(
                "এই Connector-এর OAuth refresh ইতিমধ্যে চলছে।"
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
            finish(Result.failure(IllegalStateException(
                "Encrypted OAuth state পড়া যায়নি। আবার সংযোগ করুন।"
            )))
            return
        } ?: run {
            finish(Result.failure(IllegalStateException(
                "এই Connector-এর OAuth credentials সংরক্ষিত নেই।"
            )))
            return
        }

        try {
            state.performActionWithFreshTokens(authorizationService) { accessToken, _, authException ->
                val result = runCatching {
                    if (authException != null || accessToken.isNullOrBlank()) {
                        throw IllegalStateException("OAuth access token refresh ব্যর্থ হয়েছে।")
                    }

                    val tokenType = state.lastTokenResponse?.tokenType
                    if (!tokenType.equals("Bearer", ignoreCase = true)) {
                        throw IllegalStateException(
                            "OAuth token type Bearer নয়; MCP request বন্ধ রাখা হয়েছে।"
                        )
                    }

                    McpOAuthAuthStateStore.save(
                        context.applicationContext,
                        connectorId,
                        state
                    ).getOrElse {
                        throw IllegalStateException(
                            "Refreshed OAuth state encrypted storage-এ সংরক্ষণ করা যায়নি।"
                        )
                    }

                    FreshBearerToken(accessToken)
                }
                finish(result)
            }
        } catch (_: Exception) {
            finish(Result.failure(IllegalStateException(
                "OAuth refresh শুরু করা যায়নি। আবার সংযোগ করুন।"
            )))
        }
    }
}
