package org.mosharrof.androidcontrol

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** HTTPS-only relay client. Device credentials are encrypted with an Android Keystore AES key. */
object RelayClient {
    private const val PREFS = "device_relay"
    private const val KEY_ALIAS = "mosharrof_android_relay_v1"
    private const val TOKEN_CIPHER = "AES/GCM/NoPadding"
    private val allowed = setOf("termux.python_version", "termux.git_video_status", "termux.storage_report", "termux.system_info")
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
        val job = response.optJSONObject("job") ?: return null
        val id = job.optString("command_id")
        val operation = job.optString("operation_id")
        if (id.isBlank() || operation !in allowed) return null
        return Job(id, operation)
    }

    fun postResult(context: Context, commandId: String, output: String, exitCode: Int) {
        val endpoint = endpoint(context) ?: return
        val token = token(context) ?: return
        request("$endpoint/v1/device/jobs/$commandId/result", "POST", token,
            JSONObject().put("output", output.take(10000)).put("exit_code", exitCode).toString())
    }

    fun configured(context: Context): Boolean = endpoint(context) != null && token(context) != null
    fun clear(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply() }

    private fun normalize(value: String): String {
        val parsed = URL(value.trim().trimEnd('/'))
        require(parsed.protocol == "https" && parsed.host.isNotBlank()) { "Relay URL অবশ্যই HTTPS হতে হবে।" }
        return parsed.toString().trimEnd('/')
    }

    private fun request(url: String, method: String, bearer: String, body: String? = null): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
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
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
        if (status !in 200..299) throw IllegalStateException(
            "Relay HTTP $status: " + json.optString("error", text.take(300))
        )
        return json
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
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
}
