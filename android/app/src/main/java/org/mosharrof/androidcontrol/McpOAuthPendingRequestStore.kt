package org.mosharrof.androidcontrol

import android.content.Context
import net.openid.appauth.AuthorizationRequest
import org.json.JSONObject
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
    internal data class BoundAuthorizationRequest(
        val request: AuthorizationRequest,
        val expectedIssuer: String,
        val issuerRequired: Boolean
    )

    private const val MAX_SERIALIZED_REQUEST_CHARS = 8192
    private const val MAX_SERIALIZED_BINDING_CHARS = 2048
    private val connectorIdPattern = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{1,79}")

    fun save(
        context: Context,
        connectorId: String,
        request: AuthorizationRequest
    ): Result<Unit> = runCatching {
        require(connectorIdPattern.matches(connectorId)) {
            "Connector ID-এর বিন্যাস সঠিক নয়।"
        }
        val requestState = request.state
        require(!requestState.isNullOrBlank() && requestState.length <= 512) {
            "OAuth request state অনুপস্থিত বা অবৈধ।"
        }
        val verifier = request.codeVerifier
        require(!verifier.isNullOrBlank() &&
            verifier.matches(Regex("[A-Za-z0-9._~-]{43,128}")) &&
            request.codeVerifierChallengeMethod == AuthorizationRequest.CODE_CHALLENGE_METHOD_S256
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

    /**
     * Saves the issuer selected from validated authorization-server metadata
     * beside the PKCE request. Both records use the encrypted secret store.
     */
    fun saveBound(
        context: Context,
        connectorId: String,
        request: AuthorizationRequest,
        expectedIssuer: String,
        issuerRequired: Boolean
    ): Result<Unit> {
        val requestSaved = save(context, connectorId, request)
        if (requestSaved.isFailure) return requestSaved
        return runCatching {
            require(McpOAuthMetadata.validateAuthorizationIssuer(
                expectedIssuer, expectedIssuer, issuerRequired = true
            )) { "OAuth authorization-server issuer অবৈধ।" }
            val binding = JSONObject()
                .put("expected_issuer", expectedIssuer)
                .put("issuer_required", issuerRequired)
                .toString()
            require(binding.length <= MAX_SERIALIZED_BINDING_CHARS)
            PluginSecretStore.saveToken(context, issuerStorageId(connectorId), binding)
                .getOrThrow()
        }.onFailure {
            runCatching { delete(context, connectorId) }
        }
    }

    fun readBound(
        context: Context,
        connectorId: String
    ): Result<BoundAuthorizationRequest?> = runCatching {
        val request = read(context, connectorId).getOrThrow()
            ?: return@runCatching null
        val serialized = PluginSecretStore.readToken(context, issuerStorageId(connectorId))
            ?: error("OAuth pending issuer binding পাওয়া যায়নি।")
        require(serialized.isNotBlank() && serialized.length <= MAX_SERIALIZED_BINDING_CHARS)
        val json = JSONObject(serialized)
        val issuer = json.optString("expected_issuer").takeIf { it.isNotBlank() }
            ?: error("OAuth pending issuer binding অবৈধ।")
        val issuerRequired = json.opt("issuer_required") as? Boolean
            ?: error("OAuth pending issuer capability অবৈধ।")
        require(McpOAuthMetadata.validateAuthorizationIssuer(
            issuer, issuer, issuerRequired = true
        )) { "OAuth pending issuer identifier অবৈধ।" }
        BoundAuthorizationRequest(request, issuer, issuerRequired)
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
        PluginSecretStore.deleteToken(context, issuerStorageId(connectorId)).getOrThrow()
    }

    internal fun issuerStorageId(connectorId: String): String {
        require(connectorIdPattern.matches(connectorId)) {
            "Connector ID-এর বিন্যাস সঠিক নয়।"
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(connectorId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "oauth.pending.issuer.$digest"
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
