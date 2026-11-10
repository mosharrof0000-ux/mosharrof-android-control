package org.mosharrof.androidcontrol
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderRuntimeTest {
    @After fun cleanup() { AiProviderRegistry.clearForTests() }

    @Test fun decodesGeminiInteractionsText() {
        assertEquals(CanonicalAiResult.Text("hello gemini"),
            AiProviderRuntime.decodePayload(AiProviderId.GEMINI, mapOf("steps" to listOf(
                mapOf("type" to "model_output", "content" to listOf(mapOf("type" to "text", "text" to "hello gemini")))
            ))))
    }

    @Test fun decodesOpenAiResponsesText() {
        assertEquals(CanonicalAiResult.Text("hello openai"),
            AiProviderRuntime.decodePayload(AiProviderId.OPENAI, mapOf("output" to listOf(
                mapOf("type" to "message", "content" to listOf(mapOf("type" to "output_text", "text" to "hello openai")))
            ))))
    }

    @Test fun decodesXaiResponsesText() {
        assertEquals(CanonicalAiResult.Text("hello grok"),
            AiProviderRuntime.decodePayload(AiProviderId.XAI, mapOf("output" to listOf(
                mapOf("type" to "message", "content" to listOf(mapOf("type" to "output_text", "text" to "hello grok")))
            ))))
    }

    @Test fun decodesAnthropicMessagesText() {
        assertEquals(CanonicalAiResult.Text("hello claude"),
            AiProviderRuntime.decodePayload(AiProviderId.ANTHROPIC, mapOf("content" to listOf(
                mapOf("type" to "text", "text" to "hello claude")
            ))))
    }

    @Test fun builtInAdaptersKeepUnverifiedCapabilitiesUnknown() {
        AiProviderRegistry.registerBuiltIns()
        assertEquals(setOf(AiProviderId.GEMINI, AiProviderId.OPENAI, AiProviderId.XAI, AiProviderId.ANTHROPIC),
            AiProviderRegistry.providers())
        assertTrue(AiProviderRegistry.get(AiProviderId.GEMINI)!!
            .capabilityProfile("model-test", "v1beta").capabilities[AiCapability.TOOL_CALLING] == CapabilitySupport.UNKNOWN)
    }
}
