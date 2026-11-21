package org.mosharrof.androidcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiEngineMigrationTest {
    @Test fun mapsLegacyGeminiWithoutAnOverridableEndpoint() {
        val target = legacyAiProfileTarget(AiEngine.Provider.GEMINI, "https://ignored.example")
        assertEquals("legacy-gemini", target.id)
        assertEquals(AiProviderId.GEMINI, target.provider)
        assertNull(target.endpoint)
    }

    @Test fun mapsLegacyOpenAiCompatibleToCustomProfile() {
        val target = legacyAiProfileTarget(
            AiEngine.Provider.OPENAI_COMPATIBLE,
            "https://api.example.test/v1/chat/completions"
        )
        assertEquals("legacy-openai-compatible", target.id)
        assertEquals(AiProviderId.CUSTOM, target.provider)
        assertEquals("https://api.example.test/v1/chat/completions", target.endpoint)
    }

    @Test fun rejectsUnsafeLegacyCustomEndpointDuringMigration() {
        listOf(
            "http://api.example.test/v1/chat/completions",
            "https://user@api.example.test/v1/chat/completions",
            "https://api.example.test/v1/chat/completions?key=secret",
            "https://api.example.test/v1/chat/completions#fragment"
        ).forEach { endpoint ->
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                legacyAiProfileTarget(AiEngine.Provider.OPENAI_COMPATIBLE, endpoint)
            }
        }
    }

    @Test fun rejectsMissingLegacyCustomEndpoint() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            legacyAiProfileTarget(AiEngine.Provider.OPENAI_COMPATIBLE, null)
        }
    }
}
