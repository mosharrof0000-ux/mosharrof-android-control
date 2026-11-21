package org.mosharrof.androidcontrol

import android.content.Context
import android.os.Looper
import net.openid.appauth.AuthorizationService

/**
 * Resolves a short-lived bearer credential for one MCP HTTP request.
 * Implementations must never persist the resolved token outside encrypted AuthState.
 */
internal interface McpAccessTokenResolver {
    fun resolve(
        connectorId: String,
        expectedEndpoint: String,
        onComplete: (Result<McpOAuthTokenRefresher.FreshBearerToken>) -> Unit
    )
}

/**
 * Resolves OAuth credentials for one registered MCP endpoint.
 *
 * This is the only supported bridge from a connector identity to the encrypted
 * AppAuth state. Call from a background executor; the refresher callback may be
 * delivered on AppAuth's callback executor, so callers must dispatch transport
 * work to their IO executor rather than assuming callback-thread affinity.
 */
internal class McpOAuthAccessTokenProvider(
    private val context: Context,
    private val authorizationService: AuthorizationService
) : McpAccessTokenResolver {
    override fun resolve(
        connectorId: String,
        expectedEndpoint: String,
        onComplete: (Result<McpOAuthTokenRefresher.FreshBearerToken>) -> Unit
    ) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onComplete(Result.failure(
                IllegalStateException("OAuth token resolution must start off the Android main thread.")
            ))
            return
        }

        val connector = ConnectorRegistry.connectorSnapshot()
            .firstOrNull { it.id == connectorId }
        if (connector == null ||
            connector.transport != ConnectorRegistry.Transport.MCP_STREAMABLE_HTTP ||
            !McpHttpClient.isSafeEndpoint(expectedEndpoint) ||
            connector.endpoint != expectedEndpoint.trim()
        ) {
            onComplete(Result.failure(
                McpOAuthRefreshException(McpOAuthRefreshFailure.RESOURCE_BINDING_INVALID)
            ))
            return
        }

        val state = McpOAuthAuthStateStore.read(
            context.applicationContext,
            connectorId
        ).getOrElse {
            onComplete(Result.failure(
                McpOAuthRefreshException(
                    McpOAuthRefreshFailure.ENCRYPTED_STATE_UNAVAILABLE
                )
            ))
            return
        }

        val resource = state?.lastAuthorizationResponse?.request
            ?.additionalParameters?.get("resource")
        if (!isExactResourceBinding(expectedEndpoint, resource)) {
            onComplete(Result.failure(
                McpOAuthRefreshException(McpOAuthRefreshFailure.RESOURCE_BINDING_INVALID)
            ))
            return
        }

        McpOAuthTokenRefresher.withFreshToken(
            context = context.applicationContext,
            connectorId = connectorId,
            authorizationService = authorizationService,
            onComplete = onComplete
        )
    }

    companion object {
        internal fun isExactResourceBinding(
            expectedEndpoint: String,
            resource: String?
        ): Boolean =
            McpHttpClient.isSafeEndpoint(expectedEndpoint) &&
                resource != null &&
                resource == expectedEndpoint.trim()
    }
}
