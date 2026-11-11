package org.mosharrof.androidcontrol

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object AndroidKeystoreDiagnostic {

    private const val ALIAS =
        "mosharrof_android_control_keystore_diagnostic_v1"

    fun run(): List<String> {
        val results = mutableListOf<String>()

        try {
            val store = KeyStore.getInstance("AndroidKeyStore")
            store.load(null)

            // Only this diagnostic module's dedicated alias is affected.
            if (store.containsAlias(ALIAS)) {
                store.deleteEntry(ALIAS)
            }

            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )

            generator.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or
                        KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(
                        KeyProperties.ENCRYPTION_PADDING_NONE
                    )
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )

            val key = generator.generateKey()
            results.add("PASS: Diagnostic AES key তৈরি")

            val sample = "mosharrof-keystore-diagnostic"
            val encryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
            encryptCipher.init(Cipher.ENCRYPT_MODE, key)

            val encrypted = encryptCipher.doFinal(
                sample.toByteArray(Charsets.UTF_8)
            )
            val iv = encryptCipher.iv
            results.add("PASS: AES-GCM encryption")

            val reloadedStore = KeyStore.getInstance("AndroidKeyStore")
            reloadedStore.load(null)

            check(reloadedStore.containsAlias(ALIAS)) {
                "Diagnostic key পুনরায় পাওয়া যায়নি"
            }

            val reloadedKey = reloadedStore.getKey(ALIAS, null) as? SecretKey
                ?: error("Diagnostic key ব্যবহারযোগ্য নয়")

            val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
            decryptCipher.init(
                Cipher.DECRYPT_MODE,
                reloadedKey,
                GCMParameterSpec(128, iv)
            )

            val decrypted = String(
                decryptCipher.doFinal(encrypted),
                Charsets.UTF_8
            )

            check(decrypted == sample) {
                "AES-GCM round-trip মিলেনি"
            }

            results.add("PASS: Keystore key reload ও decryption")
        } catch (error: Exception) {
            results.add(
                "FAIL: Android Keystore — " +
                    safeUiErrorMessage(error)
            )
        } finally {
            runCatching {
                val cleanupStore = KeyStore.getInstance("AndroidKeyStore")
                cleanupStore.load(null)
                if (cleanupStore.containsAlias(ALIAS)) {
                    cleanupStore.deleteEntry(ALIAS)
                }
            }.onFailure {
                results.add(
                    "WARNING: Diagnostic alias পরিষ্কার করা যায়নি"
                )
            }
        }

        return results
    }
}
