package org.mosharrof.androidcontrol

import android.content.Context
import net.openid.appauth.AuthorizationRequest
import java.security.MessageDigest

/**
 * Stores the original AppAuth authorization request before browser hand-off.
 *
 * The request contains the original OAuth state and PKCE verifier, so it must
 * only be persisted through the AndroidKeyStore-backed encrypted secret store.
 * Callback handling must compare against this original request before deleting
 * it or exchanging an authorization code.
 */
internal object McpOAuthPendingRequestStore {
    private const val MAX_SERIALIZED_REQUEST_CHARS = 8192
    private val connectorIdPattern = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{1,79}")

    fun save(
        context: Context,
        connectorId: String,
        request: AuthorizationRequest
    ): Result<Unit> = runCatching {
        require(connectorIdPattern.matches(connectorId)) {
            "Connector ID-এর বিন্যাস সঠিক নয়।"
        }
        require(request.state.isNotBlank() && request.state.length <= 512) {
            "OAuth request state অনুপস্থিত বা অবৈধ।"
        }
        require(request.codeVerifier != null &&
            request.codeVerifier.matches(Regex("[A-Za-z0-9._~-]{43,128}")) &&
            request.codeChallengeMethod == AuthorizationRequest.CODE_CHALLENGE_METHOD_S256
        ) {
            "OAuth request-এর S256 PKCE verifier অবৈধ।"
        }
        val serialized = request.jsonSerializeString()
        require(serialized.isNotBlank() && serialized.length <= MAX_SERIALIZED_REQUEST_CHARS) {
            "OAuth pending request নির্ধারিত আকারের বাইরে।"
        }
        // Confirm that the serialized request remains parseable before replacing it.
        AuthorizationRequest.jsonDeserialize(serialized)
        PluginSecretStore.saveToken(context, storageId(connectorId), serialized)
            .getOrThrow()
    }

    fun read(context: Context, connectorId: String): Result<AuthorizationRequest?> = runCatching {
        require(connectorIdPattern.matches(connectorId)) {
            "Connector ID-এর বিন্যাস সঠিক নয়।"
        }
        val serialized = PluginSecretStore.readToken(context, storageId(connectorId))
            ?: return@runCatching null
        require(serialized.isNotBlank() && serialized.length <= MAX_SERIALIZED_REQUEST_CHARS) {
            "সংরক্ষিত OAuth pending request-এর আকার অবৈধ।"
        }
        AuthorizationRequest.jsonDeserialize(serialized)
    }

    /**
     * Delete only after the callback has been checked against the original
     * request. Invalid or unsolicited callbacks must not consume this record.
     */
    fun delete(context: Context, connectorId: String): Result<Unit> = runCatching {
        require(connectorIdPattern.matches(connectorId)) {
            "Connector ID-এর বিন্যাস সঠিক নয়।"
        }
        PluginSecretStore.deleteToken(context, storageId(connectorId)).getOrThrow()
    }

    internal fun storageId(connectorId: String): String {
        require(connectorIdPattern.matches(connectorId)) {
            "Connector ID-এর বিন্যাস সঠিক নয়।"
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(connectorId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "oauth.pending.$digest"
    }
}
