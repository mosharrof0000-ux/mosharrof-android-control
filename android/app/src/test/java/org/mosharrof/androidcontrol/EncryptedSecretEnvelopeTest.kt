package org.mosharrof.androidcontrol

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EncryptedSecretEnvelopeTest {

    private val decoder: (String) -> ByteArray = {
        Base64.getDecoder().decode(it)
    }

    private val validIv = Base64.getEncoder().encodeToString(ByteArray(12))
    private val validCiphertext =
        Base64.getEncoder().encodeToString(ByteArray(16))

    @Test
    fun parsesValidVersionedEnvelope() {
        val result = EncryptedSecretEnvelope.parseVersioned(
            "v1.$validIv.$validCiphertext", "v1", decoder
        )
        assertEquals(12, result.iv.size)
        assertEquals(16, result.ciphertext.size)
    }

    @Test
    fun rejectsUnsupportedVersion() {
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedSecretEnvelope.parseVersioned(
                "v2.$validIv.$validCiphertext", "v1", decoder
            )
        }
    }

    @Test
    fun rejectsMalformedVersionedEnvelope() {
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedSecretEnvelope.parseVersioned("v1.$validIv", "v1", decoder)
        }
    }

    @Test
    fun convertsInvalidBase64ToSafeError() {
        assertThrows(IllegalStateException::class.java) {
            EncryptedSecretEnvelope.parseVersioned("v1.%%%.$validCiphertext", "v1", decoder)
        }
    }

    @Test
    fun rejectsInvalidIvLength() {
        val shortIv = Base64.getEncoder().encodeToString(ByteArray(11))
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedSecretEnvelope.parseVersioned(
                "v1.$shortIv.$validCiphertext", "v1", decoder
            )
        }
    }

    @Test
    fun rejectsTruncatedCiphertext() {
        val shortCiphertext =
            Base64.getEncoder().encodeToString(ByteArray(15))
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedSecretEnvelope.parseVersioned(
                "v1.$validIv.$shortCiphertext", "v1", decoder
            )
        }
    }

    @Test
    fun rejectsOversizedEnvelopeBeforeDecoding() {
        var decoderCalled = false
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedSecretEnvelope.parseVersioned(
                "v1." + "x".repeat(48_000),
                "v1"
            ) {
                decoderCalled = true
                decoder(it)
            }
        }
        assertEquals(false, decoderCalled)
    }

    @Test
    fun parsesValidUnversionedEnvelope() {
        val result = EncryptedSecretEnvelope.parseUnversioned(
            "$validIv.$validCiphertext", decoder
        )
        assertEquals(12, result.iv.size)
        assertEquals(16, result.ciphertext.size)
    }

    @Test
    fun rejectsMalformedUnversionedEnvelope() {
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedSecretEnvelope.parseUnversioned(validIv, decoder)
        }
    }
}
