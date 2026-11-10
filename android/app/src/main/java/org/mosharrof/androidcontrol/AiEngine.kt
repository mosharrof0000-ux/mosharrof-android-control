package org.mosharrof.androidcontrol

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Model-independent AI entry point.
 * API keys are encrypted with an Android Keystore AES-GCM key.
 * Network requests must use HTTPS. Redirects are disabled.
 */
internal data class LegacyAiProfileTarget(
    val id: String,
    val provider: AiProviderId,
    val endpoint: String?
)

internal fun legacyAiProfileTarget(
    provider: AiEngine.Provider,
    endpoint: String?
): LegacyAiProfileTarget = when (provider) {
    AiEngine.Provider.GEMINI -> LegacyAiProfileTarget(
        id = "legacy-gemini",
        provider = AiProviderId.GEMINI,
        endpoint = null
    )
    AiEngine.Provider.OPENAI_COMPATIBLE -> LegacyAiProfileTarget(
        id = "legacy-openai-compatible",
        provider = AiProviderId.CUSTOM,
        endpoint = run {
            val value = endpoint.orEmpty().trim()
            val uri = runCatching { URI(value) }.getOrElse {
                throw IllegalArgumentException("AI endpoint-এর ঠিকানা সঠিক নয়।")
            }
            require(
                uri.scheme.equals("https", ignoreCase = true) &&
                    !uri.host.isNullOrBlank() &&
                    uri.userInfo == null &&
                    uri.fragment == null &&
                    uri.query == null
            ) { "AI endpoint-এ নিরাপদ HTTPS ঠিকানা দিতে হবে।" }
            value
        }
    )
}

object AiEngine {
    enum class Provider { GEMINI, OPENAI_COMPATIBLE }

    private const val PREFS = "android_control_ai"
    private const val KEY_ALIAS = "android_control_ai_key_v1"
    private const val KEY_API = "encrypted_api_key"
    private const val MAX_API_KEY_CHARS = 8_192
    private const val MAX_MODEL_CHARS = 128
    private const val MAX_PROMPT_CHARS = 64 * 1024

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isConfigured(context: Context): Boolean {
        val p = prefs(context)
        return !p.getString(KEY_API, null).isNullOrBlank() &&
            !p.getString("model", null).isNullOrBlank() &&
            !p.getString("provider", null).isNullOrBlank()
    }

    fun saveConfig(
        context: Context,
        provider: Provider,
        model: String,
        endpoint: String,
        apiKey: String
    ) {
        require(model.isNotBlank()) { "AI মডেলের নাম লিখুন।" }
        require(apiKey.isNotBlank()) { "API key লিখুন।" }
        require(apiKey.length <= MAX_API_KEY_CHARS) { "API key নির্ধারিত আকারের চেয়ে বড়।" }
        require(model.trim().length <= MAX_MODEL_CHARS) { "AI মডেলের নাম অতিরিক্ত বড়।" }
        val safeEndpoint = if (provider == Provider.OPENAI_COMPATIBLE) {
            validateEndpoint(endpoint)
        } else {
            ""
        }

        val encrypted = encrypt(apiKey)
        prefs(context).edit()
            .putString("provider", provider.name)
            .putString("model", model.trim())
            .putString("endpoint", safeEndpoint)
            .putString(KEY_API, encrypted)
            .apply()
    }

    fun configurationSummary(context: Context): String {
        val p = prefs(context)
        if (!isConfigured(context)) return "AI সেটআপ করা হয়নি।"
        return "Provider: ${p.getString("provider", "অজানা")}\n" +
            "Model: ${p.getString("model", "অজানা")}\n" +
            "API key: এনক্রিপ্ট করে রাখা আছে"
    }

    private fun validateEndpoint(value: String): String {
        val endpoint = value.trim()
        val uri = runCatching { URI(endpoint) }.getOrElse {
            throw IllegalArgumentException("AI endpoint-এর ঠিকানা সঠিক নয়।")
        }
        require(
            uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.fragment == null &&
            uri.query == null
        ) {
            "AI endpoint-এ নিরাপদ HTTPS ঠিকানা দিতে হবে।"
        }
        return endpoint
    }

    fun saveProviderProfile(context: Context, id: String, provider: AiProviderId, model: String, apiKey: String, endpoint: String? = null): AiProviderProfileSummary =
        AiProviderProfileStore.save(context, id, provider, model, apiKey, endpoint)

    fun providerProfiles(context: Context): List<AiProviderProfileSummary> =
        AiProviderProfileStore.list(context)

    fun setActiveProviderProfile(context: Context, id: String) =
        AiProviderProfileStore.setActive(context, id)

    fun deleteProviderProfile(context: Context, id: String) =
        AiProviderProfileStore.delete(context, id)

    /** Explicit, idempotent migration; the legacy encrypted preference is preserved. */
    fun migrateLegacyConfigToProfile(context: Context): Boolean {
        if (AiProviderProfileStore.active(context) != null || !isConfigured(context)) return false
        val p = prefs(context)
        val legacyProvider = runCatching {
            Provider.valueOf(p.getString("provider", "") ?: "")
        }.getOrNull() ?: return false
        val model = p.getString("model", null) ?: return false
        val key = decrypt(p.getString(KEY_API, null) ?: return false)
        val target = legacyAiProfileTarget(legacyProvider, p.getString("endpoint", null))
        AiProviderProfileStore.save(context, target.id, target.provider, model, key, target.endpoint)
        AiProviderProfileStore.setActive(context, target.id)
        return true
    }

    fun ask(context: Context, prompt: String): String {
        require(prompt.isNotBlank()) { "AI-কে করার প্রশ্ন লিখুন।" }
        require(prompt.length <= MAX_PROMPT_CHARS) { "AI prompt ৬৪ KB সীমা অতিক্রম করেছে।" }
        AiProviderProfileStore.active(context)?.let { profile ->
            return AiProviderRuntime.askText(profile, prompt)
        }
        val p = prefs(context)
        val provider = runCatching {
            Provider.valueOf(p.getString("provider", "") ?: "")
        }.getOrElse { throw IllegalStateException("AI Provider নির্বাচন ও সংরক্ষণ করুন।") }
        val model = p.getString("model", null)
            ?: throw IllegalStateException("AI মডেলের নাম সংরক্ষিত নেই।")
        val apiKey = decrypt(
            p.getString(KEY_API, null)
                ?: throw IllegalStateException("AI API key সংরক্ষিত নেই।")
        )

        val url = when (provider) {
            Provider.GEMINI -> {
                require(model.matches(Regex("[A-Za-z0-9._-]+"))) {
                    "Gemini মডেলের নামের বিন্যাস সঠিক নয়।"
                }
                "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
            }
            Provider.OPENAI_COMPATIBLE -> {
                p.getString("endpoint", null)
                    ?.let { validateEndpoint(it) }
                    ?: throw IllegalStateException("HTTPS endpoint সংরক্ষণ করুন।")
            }
        }

        val headers = linkedMapOf(
            "Content-Type" to "application/json; charset=utf-8"
        )
        if (provider == Provider.GEMINI) {
            headers["x-goog-api-key"] = apiKey
        } else {
            headers["Authorization"] = "Bearer $apiKey"
        }
        val request = AiProviderHttpRequest(
            url = url,
            headers = headers,
            body = when (provider) {
                Provider.GEMINI -> mapOf(
                    "contents" to listOf(
                        mapOf("parts" to listOf(mapOf("text" to prompt)))
                    )
                )
                Provider.OPENAI_COMPATIBLE -> mapOf(
                    "model" to model,
                    "messages" to listOf(mapOf("role" to "user", "content" to prompt))
                )
            }
        )

        val json = try {
            AiProviderHttpTransport().post(request).payload
        } catch (error: AiProviderHttpException) {
            throw IllegalStateException(
                "AI সার্ভার HTTP ${error.statusCode} দিয়েছে। API key, model, quota ও endpoint পরীক্ষা করুন。",
                error
            )
        }
        return when (provider) {
            Provider.GEMINI -> {
                val candidates = json.optJSONArray("candidates")
                val parts = candidates?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")
                parts?.optJSONObject(0)?.optString("text")
                    ?.takeIf { it.isNotBlank() }
                    ?: "AI উত্তর দেয়নি। Safety block বা response format পরীক্ষা করুন।"
            }
            Provider.OPENAI_COMPATIBLE -> {
                json.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content")
                    ?.takeIf { it.isNotBlank() }
                    ?: "AI উত্তর পাওয়া যায়নি। Provider response format পরীক্ষা করুন।"
            }
        }
    }

    fun askWithMcpApproval(
        context: Context,
        prompt: String,
        service: McpConnectorService?,
        approve: (AiToolCallPlan.Ready) -> Boolean
    ): String {
        val profile = AiProviderProfileStore.active(context)
        return if (profile != null) {
            AiProviderRuntime.askText(profile, prompt, service, approve)
        } else {
            // Legacy profiles do not yet have provider-neutral tool-call support.
            ask(context, prompt)
        }
    }

    internal fun cryptoSelfTest(): Boolean {
        val sample = "android-control-keystore-test"
        return decrypt(encrypt(sample)) == sample
    }

    private fun getSecretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
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

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val data = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        return "$iv.$data"
    }

    private fun decrypt(value: String): String {
        val pieces = value.split(".", limit = 2)
        require(pieces.size == 2) { "সংরক্ষিত AI key-এর তথ্য সঠিক নয়।" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getSecretKey(),
            GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP))
        )
        return String(
            cipher.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP)),
            Charsets.UTF_8
        )
    }
}

/** Reads an AI response with a strict byte cap, including HTTP error bodies. */
internal fun readBoundedAiResponse(input: InputStream?, maxBytes: Int = 256 * 1024): String {
    require(maxBytes in 1..(1024 * 1024)) { "AI response byte limit is invalid." }
    if (input == null) return ""
    val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
    input.use { stream ->
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "AI response size limit exceeded." }
            output.write(buffer, 0, count)
        }
    }
    return String(output.toByteArray(), Charsets.UTF_8)
}

