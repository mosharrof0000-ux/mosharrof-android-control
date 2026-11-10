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
        assertEquals(setOf(AiProviderId.GEMINI, AiProviderId.OPENAI, AiProviderId.XAI, AiProviderId.ANTHROPIC, AiProviderId.CUSTOM),
            AiProviderRegistry.providers())
        assertTrue(AiProviderRegistry.get(AiProviderId.GEMINI)!!
            .capabilityProfile("model-test", "v1beta").capabilities[AiCapability.TOOL_CALLING] == CapabilitySupport.UNKNOWN)
    }

    @Test fun toolCallWithoutConnectedMcpSessionFailsClosedWithoutContinuation() {
        val config = AiProviderConfig(
            AiProviderId.GEMINI, "test-model", credentialReference = "test-profile"
        )
        val profile = ResolvedAiProviderProfile(
            AiProviderProfileSummary("test-profile", AiProviderId.GEMINI, "test-model", null),
            config,
            "fake-key"
        )
        var requests = 0
        var approvalPrompted = false
        val error = runCatching {
            AiProviderRuntime.runConversation(
                profile = profile,
                prompt = "run a plugin",
                service = null,
                approve = { approvalPrompted = true; true },
                post = { request ->
                    requests++
                    assertEquals(emptyList<Any>(), request.body["tools"])
                    mapOf(
                        "steps" to listOf(
                            mapOf(
                                "type" to "function_call",
                                "id" to "provider-call-1",
                                "name" to "unknown_tool",
                                "arguments" to emptyMap<String, Any?>()
                            )
                        )
                    )
                }
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals(1, requests)
        assertTrue(!approvalPrompted)
    }

    @Test fun cancellationBeforeFirstProviderRequestPreventsNetworkWork() {
        val config = AiProviderConfig(AiProviderId.GEMINI, "test-model", credentialReference = "test-profile")
        val profile = ResolvedAiProviderProfile(
            AiProviderProfileSummary("test-profile", AiProviderId.GEMINI, "test-model", null),
            config,
            "fake-key"
        )
        val cancellation = AiProviderCancellation()
        cancellation.cancel()
        var requests = 0
        val failure = runCatching {
            AiProviderRuntime.runConversation(
                profile = profile,
                prompt = "hello",
                service = null,
                approve = { false },
                cancellation = cancellation,
                post = { requests++; emptyMap() }
            )
        }.exceptionOrNull()
        assertTrue(failure is java.util.concurrent.CancellationException)
        assertEquals(0, requests)
    }

    @Test fun boundedConversationReturnsProviderTextWithoutToolExecution() {
        val config = AiProviderConfig(
            AiProviderId.GEMINI, "test-model", credentialReference = "test-profile"
        )
        val profile = ResolvedAiProviderProfile(
            AiProviderProfileSummary("test-profile", AiProviderId.GEMINI, "test-model", null),
            config,
            "fake-key"
        )
        val answer = AiProviderRuntime.runConversation(
            profile = profile,
            prompt = "hello",
            service = null,
            approve = { false },
            post = {
                mapOf(
                    "steps" to listOf(
                        mapOf(
                            "type" to "model_output",
                            "content" to listOf(mapOf("type" to "text", "text" to "hello from fake provider"))
                        )
                    )
                )
            }
        )
        assertEquals("hello from fake provider", answer)
    }
}
