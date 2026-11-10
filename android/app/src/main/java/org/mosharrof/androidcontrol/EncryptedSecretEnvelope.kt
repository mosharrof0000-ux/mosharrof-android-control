package org.mosharrof.androidcontrol

internal object EncryptedSecretEnvelope {

    data class Payload(
        val iv: ByteArray,
        val ciphertext: ByteArray
    )

    fun parseVersioned(
        value: String,
        expectedVersion: String,
        decode: (String) -> ByteArray
    ): Payload {
        val parts = value.split(".", limit = 3)
        require(parts.size == 3 && parts[0] == expectedVersion) {
            "সংরক্ষিত encrypted secret-এর format বা version সঠিক নয়।"
        }
        return decodePayload(parts[1], parts[2], decode)
    }

    fun parseUnversioned(
        value: String,
        decode: (String) -> ByteArray
    ): Payload {
        val parts = value.split(".", limit = 2)
        require(parts.size == 2) {
            "সংরক্ষিত encrypted secret-এর তথ্য সঠিক নয়।"
        }
        return decodePayload(parts[0], parts[1], decode)
    }

    private fun decodePayload(
        encodedIv: String,
        encodedCiphertext: String,
        decode: (String) -> ByteArray
    ): Payload {
        val iv: ByteArray
        val ciphertext: ByteArray

        try {
            iv = decode(encodedIv)
            ciphertext = decode(encodedCiphertext)
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException(
                "সংরক্ষিত encrypted secret-এর data পড়া যাচ্ছে না।",
                error
            )
        }

        require(iv.size == 12 && ciphertext.size >= 16) {
            "সংরক্ষিত encrypted secret-এর IV বা ciphertext অবৈধ।"
        }

        return Payload(iv, ciphertext)
    }
}
