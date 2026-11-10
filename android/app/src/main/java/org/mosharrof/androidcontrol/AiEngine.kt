package org.mosharrof.androidcontrol

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
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
object AiEngine {
    enum class Provider { GEMINI, OPENAI_COMPATIBLE }

    private const val PREFS = "android_control_ai"
    private const val KEY_ALIAS = "android_control_ai_key_v1"
    private const val KEY_API = "encrypted_api_key"
    private const val MAX_API_KEY_CHARS = 8_192
    private const val MAX_MODEL_CHARS = 128
    private const val MAX_PROMPT_CHARS = 64 * 1024
    private const val MAX_RESPONSE_BYTES = 256 * 1024

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
            uri.fragment == null
        ) {
            "AI endpoint-এ নিরাপদ HTTPS ঠিকানা দিতে হবে।"
        }
        return endpoint
    }

    fun ask(context: Context, prompt: String): String {
        require(prompt.isNotBlank()) { "AI-কে করার প্রশ্ন লিখুন।" }
        require(prompt.length <= MAX_PROMPT_CHARS) { "AI prompt ৬৪ KB সীমা অতিক্রম করেছে।" }
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

        val body = when (provider) {
            Provider.GEMINI -> JSONObject()
                .put("contents", JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                ))
            Provider.OPENAI_COMPATIBLE -> JSONObject()
                .put("model", model)
                .put("messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", prompt)
                ))
        }

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 60000
            doOutput = true
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (provider == Provider.GEMINI) {
                setRequestProperty("x-goog-api-key", apiKey)
            } else {
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
        }

        try {
            connection.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = readBoundedAiResponse(stream, MAX_RESPONSE_BYTES)
            if (code !in 200..299) {
                throw IllegalStateException(
                    "AI সার্ভার HTTP $code দিয়েছে। API key, model, quota ও endpoint পরীক্ষা করুন।"
                )
            }

            val json = JSONObject(raw)
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
        } finally {
            connection.disconnect()
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

