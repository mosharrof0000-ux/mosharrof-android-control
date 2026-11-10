package org.mosharrof.androidcontrol

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Plugin-only encrypted secret storage.
 * Tokens are encrypted with AndroidKeyStore AES-GCM.
 * Plaintext secrets are never written to SharedPreferences.
 */
object PluginSecretStore {

    private const val PREFS = "android_control_plugin_secrets_v1"
    private const val KEY_ALIAS = "mosharrof_plugin_secret_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val VERSION = "v1"
    private const val MAX_TOKEN_LENGTH = 8192

    private val lock = Any()
    private val connectorIdPattern =
        Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{1,79}")

    fun saveToken(
        context: Context,
        connectorId: String,
        token: String
    ): Result<Unit> = runCatching {
        validateConnectorId(connectorId)
        require(token.isNotBlank()) { "Token খালি হতে পারবে না।" }
        require(token.length <= MAX_TOKEN_LENGTH) {
            "Token নির্ধারিত আকারের চেয়ে বড়।"
        }

        synchronized(lock) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(connectorId.toByteArray(Charsets.UTF_8))

            val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv

            val storedValue = listOf(
                VERSION,
                Base64.encodeToString(iv, Base64.NO_WRAP),
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
            ).joinToString(".")

            val saved = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(storageKey(connectorId), storedValue)
                .commit()

            check(saved) { "Encrypted token সংরক্ষণ করা যায়নি।" }
        }
    }

    fun readToken(context: Context, connectorId: String): String? {
        validateConnectorId(connectorId)

        return synchronized(lock) {
            val storedValue = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(storageKey(connectorId), null)
                ?: return@synchronized null

            val payload = EncryptedSecretEnvelope.parseVersioned(
                storedValue,
                VERSION
            ) { Base64.decode(it, Base64.NO_WRAP) }
            val iv = payload.iv
            val encrypted = payload.ciphertext

            val cipher = Cipher.getInstance(TRANSFORMATION)
            try {
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    getExistingKey(),
                    GCMParameterSpec(128, iv)
                )
                cipher.updateAAD(connectorId.toByteArray(Charsets.UTF_8))
                String(cipher.doFinal(encrypted), Charsets.UTF_8)
            } catch (error: android.security.keystore.KeyPermanentlyInvalidatedException) {
                throw IllegalStateException(
                    "Plugin encryption key স্থায়ীভাবে invalid হয়েছে। নতুন token সংরক্ষণ করুন; পুরোনো encrypted token মুছে ফেলা হয়নি।",
                    error
                )
            } catch (error: javax.crypto.AEADBadTagException) {
                throw IllegalStateException(
                    "Plugin token যাচাই করা যায়নি। Encrypted data বা encryption key-এ সমস্যা থাকতে পারে। নতুন token সংরক্ষণ করুন; পুরোনো data অক্ষত আছে।",
                    error
                )
            }
        }
    }

    fun hasToken(context: Context, connectorId: String): Boolean {
        validateConnectorId(connectorId)
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .contains(storageKey(connectorId))
    }

    fun deleteToken(
        context: Context,
        connectorId: String
    ): Result<Unit> = runCatching {
        validateConnectorId(connectorId)
        val removed = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(storageKey(connectorId))
            .commit()

        check(removed) { "Plugin token মুছে ফেলা যায়নি।" }
    }

    private fun storageKey(connectorId: String): String =
        "encrypted_token_$connectorId"

    private fun validateConnectorId(connectorId: String) {
        require(connectorIdPattern.matches(connectorId)) {
            "Connector ID-এর বিন্যাস সঠিক নয়।"
        }
    }

    private fun getExistingKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
        }
        check(keyStore.containsAlias(KEY_ALIAS)) {
            "Plugin token-এর encryption key পাওয়া যায়নি। Token আবার সংরক্ষণ করুন।"
        }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw IllegalStateException(
                "Plugin token-এর encryption key ব্যবহারযোগ্য নয়। Token আবার সংরক্ষণ করুন।"
            )
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
        }

        val aliasExists = keyStore.containsAlias(KEY_ALIAS)
        val existingKey = if (aliasExists) {
            keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        } else null

        return KeyAliasPolicy.resolve(
            aliasExists,
            existingKey,
            "Plugin encryption key ব্যবহারযোগ্য নয়। পুরোনো token অক্ষত আছে।"
        ) {
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
            generator.generateKey()
        }
    }
}
