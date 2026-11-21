package org.mosharrof.androidcontrol

import org.junit.Assert.*
import org.junit.Test

class AiProviderProfileRepositoryTest {
    private class MemoryStorage : AiProviderProfileStorage {
        val values = linkedMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String): Boolean {
            values[key] = value
            return true
        }
        override fun remove(key: String): Boolean {
            values.remove(key)
            return true
        }
        override fun keys(): Set<String> = values.keys.toSet()
    }

    private class TestCipher : AiProviderProfileCipher {
        override fun encrypt(profileId: String, plaintext: String) = "encrypted:$profileId:$plaintext"
        override fun decrypt(profileId: String, envelope: String): String {
            require(envelope.startsWith("encrypted:$profileId:"))
            return envelope.removePrefix("encrypted:$profileId:")
        }
    }

    private fun repository(storage: MemoryStorage = MemoryStorage()) =
        Triple(AiProviderProfileRepository(storage, TestCipher()), storage, TestCipher())

    @Test fun storesMultipleProviderProfilesWithoutPlaintextMetadata() {
        val (repo, storage) = repository()
        repo.save("gemini-main", AiProviderId.GEMINI, "gemini-model", null, "secret-g")
        repo.save("openai-main", AiProviderId.OPENAI, "gpt-model", null, "secret-o")
        assertEquals(2, repo.list().size)
        assertFalse(storage.get("profile_meta_gemini-main")!!.contains("secret-g"))
        assertFalse(storage.get("profile_meta_openai-main")!!.contains("secret-o"))
        assertEquals("secret-g", repo.get("gemini-main")!!.apiKey)
        assertEquals("secret-o", repo.get("openai-main")!!.apiKey)
    }

    @Test fun activeProfileCanBeSelectedAndDeleted() {
        val (repo) = repository()
        repo.save("gemini-main", AiProviderId.GEMINI, "gemini-model", null, "secret")
        repo.save("xai-main", AiProviderId.XAI, "grok-model", null, "secret-x")
        repo.setActive("xai-main")
        assertEquals("xai-main", repo.active()!!.summary.id)
        repo.delete("xai-main")
        assertNull(repo.active())
        assertNull(repo.get("xai-main"))
    }

    @Test fun customEndpointMustBeSafeHttps() {
        val (repo) = repository()
        assertThrows(IllegalArgumentException::class.java) {
            repo.save("custom-1", AiProviderId.CUSTOM, "model", "http://example.com", "secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            repo.save("custom-2", AiProviderId.CUSTOM, "model", "https://user@example.com", "secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            repo.save("custom-3", AiProviderId.CUSTOM, "model", "https://example.com?key=x", "secret")
        }
        assertEquals(
            "https://example.com/v1/chat",
            repo.save("custom-ok", AiProviderId.CUSTOM, "model", "https://example.com/v1/chat", "secret").endpoint
        )
    }

    @Test fun officialProviderCannotOverrideItsEndpoint() {
        val (repo) = repository()
        assertThrows(IllegalArgumentException::class.java) {
            repo.save("gemini-1", AiProviderId.GEMINI, "gemini-model", "https://attacker.example", "secret")
        }
    }

    @Test fun credentialAndIdentifiersAreBounded() {
        val (repo) = repository()
        assertThrows(IllegalArgumentException::class.java) {
            repo.save("../escape", AiProviderId.GEMINI, "gemini-model", null, "secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            repo.save("gemini-1", AiProviderId.GEMINI, "gemini-model", null, "x".repeat(8193))
        }
    }

    @Test fun credentialReplacementPreservesProfileMetadataAndActiveSelection() {
        val (repo, storage) = repository()
        repo.save("gemini-main", AiProviderId.GEMINI, "gemini-model", null, "old-secret")
        repo.setActive("gemini-main")

        val summary = repo.replaceCredential("gemini-main", "new-secret")

        assertEquals("gemini-main", summary.id)
        assertEquals("gemini-model", summary.model)
        assertEquals(AiProviderId.GEMINI, summary.provider)
        assertEquals("gemini-main", repo.active()!!.summary.id)
        assertEquals("new-secret", repo.get("gemini-main")!!.apiKey)
        assertFalse(storage.get("profile_secret_gemini-main")!!.contains("old-secret"))
    }

    @Test fun credentialReplacementRejectsMissingProfileAndInvalidKeysWithoutChangingExistingKey() {
        val (repo, storage) = repository()
        repo.save("gemini-main", AiProviderId.GEMINI, "gemini-model", null, "old-secret")

        assertThrows(IllegalArgumentException::class.java) {
            repo.replaceCredential("missing-profile", "new-secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            repo.replaceCredential("gemini-main", "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            repo.replaceCredential("gemini-main", "x".repeat(8193))
        }
        assertThrows(IllegalArgumentException::class.java) {
            repo.replaceCredential("gemini-main", "bad\nsecret")
        }
        assertEquals("old-secret", repo.get("gemini-main")!!.apiKey)
        assertTrue(storage.get("profile_secret_gemini-main")!!.contains("old-secret"))
    }

    @Test fun resolvedProfileNeverPrintsCredential() {
        val (repo) = repository()
        repo.save("gemini-main", AiProviderId.GEMINI, "gemini-model", null, "top-secret")
        assertFalse(repo.get("gemini-main")!!.toString().contains("top-secret"))
    }
}
