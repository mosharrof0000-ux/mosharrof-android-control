package org.mosharrof.androidcontrol

import android.content.Context
import net.openid.appauth.AuthState
import java.security.MessageDigest

/**
 * Persists AppAuth state (including refresh-token state) only through the
 * AndroidKeyStore-backed encrypted plugin secret store.
 *
 * This class deliberately does not start authorization, exchange tokens, or
 * attach credentials to MCP requests. Callers must validate the authorization
 * response and token endpoint result before persisting the updated AuthState.
 */
internal object McpOAuthAuthStateStore {
    private const val MAX_SERIALIZED_STATE_CHARS = 8192
    private val connectorIdPattern = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{1,79}")

    fun save(
        context: Context,
        connectorId: String,
        authState: AuthState
    ): Result<Unit> = runCatching {
        require(connectorIdPattern.matches(connectorId)) {
            "Connector ID-এর বিন্যাস সঠিক নয়।"
        }
        val serialized = authState.jsonSerializeString()
        require(serialized.isNotBlank() && serialized.length <= MAX_SERIALIZED_STATE_CHARS) {
            "OAuth state নির্ধারিত আকারের বাইরে।"
        }
        // Validate the serialization before replacing the previously stored state.
        AuthState.jsonDeserialize(serialized)
        PluginSecretStore.saveToken(context, storageId(connectorId), serialized)
            .getOrThrow()
    }

    fun read(context: Context, connectorId: String): Result<AuthState?> = runCatching {
        require(connectorIdPattern.matches(connectorId)) {
            "Connector ID-এর বিন্যাস সঠিক নয়।"
        }
        val serialized = PluginSecretStore.readToken(context, storageId(connectorId))
            ?: return@runCatching null
        require(serialized.isNotBlank() && serialized.length <= MAX_SERIALIZED_STATE_CHARS) {
            "সংরক্ষিত OAuth state-এর আকার অবৈধ।"
        }
        AuthState.jsonDeserialize(serialized)
    }

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
        return "oauth.$digest"
    }
}
