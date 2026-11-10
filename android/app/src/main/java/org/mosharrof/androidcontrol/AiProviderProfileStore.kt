package org.mosharrof.androidcontrol

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Public metadata; credentials are deliberately excluded. */
data class AiProviderProfileSummary(
    val id: String,
    val provider: AiProviderId,
    val model: String,
    val endpoint: String?
)

/** Resolved only at request time. Its string representation never reveals the credential. */
class ResolvedAiProviderProfile(
    val summary: AiProviderProfileSummary,
    val config: AiProviderConfig,
    val apiKey: String
) {
    override fun toString(): String =
        "ResolvedAiProviderProfile(id=${summary.id}, provider=${summary.provider}, model=${summary.model}, apiKey=[REDACTED])"
}

internal interface AiProviderProfileStorage {
    fun get(key: String): String?
    fun put(key: String, value: String): Boolean
    fun remove(key: String): Boolean
    fun keys(): Set<String>
}

internal interface AiProviderProfileCipher {
    fun encrypt(profileId: String, plaintext: String): String
    fun decrypt(profileId: String, envelope: String): String
}

/**
 * Provider profile repository. Metadata and encrypted credentials are stored
 * under separate keys; profile metadata never contains an API key.
 */
internal class AiProviderProfileRepository(
    private val storage: AiProviderProfileStorage,
    private val cipher: AiProviderProfileCipher
) {
    companion object {
        private const val META_PREFIX = "profile_meta_"
        private const val SECRET_PREFIX = "profile_secret_"
        private const val ACTIVE_KEY = "active_profile"
        private const val MAX_KEY_CHARS = 8_192
        private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{1,63}")
        private val MODEL_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")
    }

    @Synchronized
    fun save(
        id: String,
        provider: AiProviderId,
        model: String,
        endpoint: String?,
        apiKey: String
    ): AiProviderProfileSummary {
        validateId(id)
        require(MODEL_PATTERN.matches(model)) { "AI model identifier is invalid." }
        require(apiKey.isNotBlank() && apiKey.length <= MAX_KEY_CHARS) {
            "AI credential is empty or exceeds the configured limit."
        }
        require(apiKey.none { it.isISOControl() }) { "AI credential contains control characters." }
        val safeEndpoint = validateEndpoint(provider, endpoint)
        val summary = AiProviderProfileSummary(id, provider, model, safeEndpoint)
        val metadata = JSONObject()
            .put("id", id)
            .put("provider", provider.name)
            .put("model", model)
            .put("endpoint", safeEndpoint ?: JSONObject.NULL)
            .toString()
        val encrypted = cipher.encrypt(id, apiKey)

        check(storage.put(SECRET_PREFIX + id, encrypted)) {
            "Encrypted AI credential could not be stored."
        }
        if (!storage.put(META_PREFIX + id, metadata)) {
            storage.remove(SECRET_PREFIX + id)
            error("AI provider profile metadata could not be stored.")
        }
        return summary
    }

    @Synchronized
    fun get(id: String): ResolvedAiProviderProfile? {
        validateId(id)
        val metadata = storage.get(META_PREFIX + id) ?: return null
        val summary = parseSummary(metadata)
        val encrypted = storage.get(SECRET_PREFIX + id)
            ?: throw IllegalStateException("Encrypted AI credential is missing for this profile.")
        val key = cipher.decrypt(id, encrypted)
        val config = AiProviderConfig(
            provider = summary.provider,
            model = summary.model,
            endpoint = summary.endpoint,
            credentialReference = id
        )
        return ResolvedAiProviderProfile(summary, config, key)
    }

    @Synchronized
    fun list(): List<AiProviderProfileSummary> =
        storage.keys().asSequence()
            .filter { it.startsWith(META_PREFIX) }
            .mapNotNull { key -> storage.get(key)?.let(::parseSummary) }
            .sortedBy { it.id }
            .toList()

    @Synchronized
    fun setActive(id: String) {
        validateId(id)
        require(storage.get(META_PREFIX + id) != null) { "AI provider profile does not exist." }
        check(storage.put(ACTIVE_KEY, id)) { "Active AI provider profile could not be saved." }
    }

    @Synchronized
    fun active(): ResolvedAiProviderProfile? =
        storage.get(ACTIVE_KEY)?.let(::get)

    @Synchronized
    fun delete(id: String) {
        validateId(id)
        storage.remove(SECRET_PREFIX + id)
        storage.remove(META_PREFIX + id)
        if (storage.get(ACTIVE_KEY) == id) storage.remove(ACTIVE_KEY)
    }

    private fun parseSummary(raw: String): AiProviderProfileSummary {
        val json = JSONObject(raw)
        val id = json.getString("id")
        validateId(id)
        val provider = AiProviderId.valueOf(json.getString("provider"))
        val model = json.getString("model")
        require(MODEL_PATTERN.matches(model)) { "Stored AI model identifier is invalid." }
        val endpoint = if (json.isNull("endpoint")) null else json.getString("endpoint")
        return AiProviderProfileSummary(id, provider, model, validateEndpoint(provider, endpoint))
    }

    private fun validateId(id: String) {
        require(ID_PATTERN.matches(id)) { "AI provider profile ID is invalid." }
    }

    private fun validateEndpoint(provider: AiProviderId, endpoint: String?): String? {
        if (provider != AiProviderId.CUSTOM) {
            require(endpoint == null) { "Official provider endpoints are fixed and cannot be overridden." }
            return null
        }
        val value = endpoint?.trim().orEmpty()
        val uri = runCatching { URI(value) }.getOrElse {
            throw IllegalArgumentException("Custom AI endpoint is invalid.")
        }
        require(
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.query == null &&
                uri.fragment == null
        ) { "Custom AI endpoint must be HTTPS without userinfo, query or fragment." }
        return value
    }
}

/** Android SharedPreferences + Android Keystore implementation. */
object AiProviderProfileStore {
    private const val PREFS = "android_control_ai_profiles_v1"
    private const val KEY_ALIAS = "mosharrof_ai_profiles_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun repository(context: Context): AiProviderProfileRepository {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val storage = object : AiProviderProfileStorage {
            override fun get(key: String): String? = prefs.getString(key, null)
            override fun put(key: String, value: String): Boolean =
                prefs.edit().putString(key, value).commit()
            override fun remove(key: String): Boolean = prefs.edit().remove(key).commit()
            override fun keys(): Set<String> = prefs.all.keys
        }
        val cipher = object : AiProviderProfileCipher {
            override fun encrypt(profileId: String, plaintext: String): String {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
                cipher.updateAAD(profileId.toByteArray(Charsets.UTF_8))
                val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
                return listOf(
                    "v1",
                    Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
                    Base64.encodeToString(encrypted, Base64.NO_WRAP)
                ).joinToString(".")
            }

            override fun decrypt(profileId: String, envelope: String): String {
                val payload = EncryptedSecretEnvelope.parseVersioned(envelope, "v1") {
                    Base64.decode(it, Base64.NO_WRAP)
                }
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    getExistingKey(),
                    GCMParameterSpec(128, payload.iv)
                )
                cipher.updateAAD(profileId.toByteArray(Charsets.UTF_8))
                return String(cipher.doFinal(payload.ciphertext), Charsets.UTF_8)
            }
        }
        return AiProviderProfileRepository(storage, cipher)
    }

    fun save(
        context: Context,
        id: String,
        provider: AiProviderId,
        model: String,
        apiKey: String,
        endpoint: String? = null
    ): AiProviderProfileSummary =
        repository(context).save(id, provider, model, endpoint, apiKey)

    fun get(context: Context, id: String): ResolvedAiProviderProfile? =
        repository(context).get(id)

    fun list(context: Context): List<AiProviderProfileSummary> =
        repository(context).list()

    fun setActive(context: Context, id: String) =
        repository(context).setActive(id)

    fun active(context: Context): ResolvedAiProviderProfile? =
        repository(context).active()

    fun delete(context: Context, id: String) =
        repository(context).delete(id)

    private fun getExistingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        check(store.containsAlias(KEY_ALIAS)) { "AI profile encryption key is unavailable." }
        return store.getKey(KEY_ALIAS, null) as? SecretKey
            ?: error("AI profile encryption key is unusable.")
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        check(!store.containsAlias(KEY_ALIAS)) { "AI profile encryption key alias is unusable." }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
