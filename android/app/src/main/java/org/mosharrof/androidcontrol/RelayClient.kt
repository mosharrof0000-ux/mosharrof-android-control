package org.mosharrof.androidcontrol

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** HTTPS-only relay client with bounded responses and explicit job identifier validation. */
object RelayClient {
    private const val PREFS = "device_relay"
    private const val KEY_ALIAS = "mosharrof_android_relay_v1"
    private const val TOKEN_CIPHER = "AES/GCM/NoPadding"
    private const val MAX_RESPONSE_BYTES = 64 * 1024
    private const val MAX_COMMAND_ID_LENGTH = 128
    private const val HANDLED_IDS = "handled_command_ids"

    private val allowed = setOf(
        "termux.python_version",
        "termux.git_video_status",
        "termux.storage_report",
        "termux.system_info"
    )

    data class Job(val commandId: String, val operationId: String)

    fun pair(context: Context, endpoint: String, setupToken: String): String {
        val base = normalize(endpoint)
        require(setupToken.length >= 24) { "Setup token খুব ছোট; Cloudflare secret থেকে সম্পূর্ণ token দিন।" }
        val response = request(base + "/v1/device/pair/start", "POST", setupToken,
            JSONObject().put("client", "android-companion").toString())
        val code = response.optString("pairing_code")
        val token = response.optString("device_token")
        require(code.matches(Regex("[0-9]{6}")) && token.length >= 48) {
            "Relay pairing response অসম্পূর্ণ: " + response.optString("error", "invalid_response")
        }
        saveEndpoint(context, base)
        saveToken(context, token)
        return code
    }

    fun poll(context: Context): Job? {
        val endpoint = endpoint(context) ?: return null
        val token = token(context) ?: return null
        val response = request(endpoint + "/v1/device/jobs/next", "GET", token)
        if (!response.has("job") || response.isNull("job")) return null
        val job = response.optJSONObject("job")
            ?: error("Relay job-এর গঠন অবৈধ; কোনো কমান্ড চালানো হয়নি।")
        val parsed = parseJob(job)
        if (hasHandledCommand(context, parsed.commandId)) return null
        return parsed
    }

    fun postResult(context: Context, commandId: String, output: String, exitCode: Int) {
        require(isValidCommandId(commandId)) { "Relay command ID-এর বিন্যাস অবৈধ।" }
        val endpoint = endpoint(context) ?: error("Relay endpoint configured নয়; ফলাফল পাঠানো যায়নি।")
        val token = token(context) ?: error("Relay device token পাওয়া যায়নি; ফলাফল পাঠানো যায়নি।")
        request("$endpoint/v1/device/jobs/$commandId/result", "POST", token,
            JSONObject().put("output", output.take(10000)).put("exit_code", exitCode).toString())
    }

    fun hasHandledCommand(context: Context, commandId: String): Boolean {
        require(isValidCommandId(commandId)) { "Relay command ID-এর বিন্যাস অবৈধ।" }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(HANDLED_IDS, emptySet())
            .orEmpty()
            .contains(commandId)
    }

    fun markCommandHandled(context: Context, commandId: String) {
        require(isValidCommandId(commandId)) { "Relay command ID-এর বিন্যাস অবৈধ।" }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ids = prefs.getStringSet(HANDLED_IDS, emptySet()).orEmpty().toMutableSet()
        ids.add(commandId)
        while (ids.size > 100) ids.remove(ids.first())
        check(prefs.edit().putStringSet(HANDLED_IDS, ids).commit()) {
            "Relay command-এর স্থানীয় duplicate guard সংরক্ষণ করা যায়নি।"
        }
    }

    fun configured(context: Context): Boolean = endpoint(context) != null && token(context) != null

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry(KEY_ALIAS)
        }
    }

    internal fun normalize(value: String): String {
        val uri = URI(value.trim().trimEnd('/'))
        require(
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.query == null &&
                uri.fragment == null &&
                uri.host.none { it.isWhitespace() }
        ) { "Relay URL অবশ্যই credentials, query বা fragment ছাড়া HTTPS URL হতে হবে।" }
        return uri.toASCIIString().trimEnd('/')
    }

    internal fun isValidCommandId(value: String): Boolean =
        value.length in 1..MAX_COMMAND_ID_LENGTH &&
            value.matches(Regex("[A-Za-z0-9_-]+"))

    internal fun parseJob(job: JSONObject): Job {
        val id = job.optString("command_id")
        val operation = job.optString("operation_id")
        require(isValidCommandId(id)) {
            "Relay job-এর command_id অবৈধ; কোনো কমান্ড চালানো হয়নি।"
        }
        require(operation in allowed) {
            "Relay job-এর operation_id অনুমোদিত নয়; কোনো কমান্ড চালানো হয়নি।"
        }
        return Job(id, operation)
    }

    internal fun readLimited(stream: InputStream?, maxBytes: Int = MAX_RESPONSE_BYTES): String {
        if (stream == null) return ""
        require(maxBytes > 0)
        val output = ByteArrayOutputStream()
        stream.use { input ->
            val buffer = ByteArray(4096)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) {
                    throw IOException("Relay response নির্ধারিত আকারের সীমা অতিক্রম করেছে।")
                }
                output.write(buffer, 0, count)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    internal fun isRetryableRequest(method: String, url: String): Boolean =
        method == "GET" || (method == "POST" && url.endsWith("/result"))

    internal fun isRetryableStatus(statusCode: Int): Boolean =
        statusCode in setOf(408, 425, 429, 500, 502, 503, 504)

    internal fun retryDelayMs(attempt: Int): Long =
        (500L shl attempt.coerceIn(0, 2)).coerceAtMost(2000L)

    private class RelayHttpException(val statusCode: Int, message: String) : IOException(message)

    private fun request(url: String, method: String, bearer: String, body: String? = null): JSONObject {
        // Pairing creates a new device token and is intentionally never retried.
        // GET polling and result submission are idempotent and may use bounded retries.
        val retryable = isRetryableRequest(method, url)
        var attempt = 0
        while (true) {
            try {
                return requestOnce(url, method, bearer, body)
            } catch (error: IOException) {
                val retryableStatus = error !is RelayHttpException || isRetryableStatus(error.statusCode)
                if (!retryable || !retryableStatus || attempt >= 2) throw error
                val delayMs = retryDelayMs(attempt)
                try {
                    Thread.sleep(delayMs)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Relay retry interrupted.", interrupted)
                }
                attempt++
            }
        }
    }

    private fun requestOnce(url: String, method: String, bearer: String, body: String?): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("Authorization", "Bearer $bearer")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = readLimited(stream)
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (status !in 200..299) throw RelayHttpException(
                status,
                "Relay HTTP $status: " + json.optString("error", "unexpected_response").take(200)
            )
            return json
        } finally {
            connection.disconnect()
        }
    }

    private fun saveEndpoint(context: Context, endpoint: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("endpoint", endpoint).apply()
    }

    private fun endpoint(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("endpoint", null)

    private fun saveToken(context: Context, value: String) {
        val cipher = Cipher.getInstance(TOKEN_CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("token_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("token_data", Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    private fun token(context: Context): String? = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val iv = Base64.decode(prefs.getString("token_iv", null), Base64.NO_WRAP)
        val data = Base64.decode(prefs.getString("token_data", null), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TOKEN_CIPHER)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(data), Charsets.UTF_8)
    }.getOrNull()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
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
