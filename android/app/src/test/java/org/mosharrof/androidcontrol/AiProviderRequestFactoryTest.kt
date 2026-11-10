package org.mosharrof.androidcontrol

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AiProviderRequestFactoryTest {
    @Before fun setup() {
        AiProviderRegistry.clearForTests()
        AiProviderRegistry.registerBuiltIns()
    }

    @After fun cleanup() {
        AiProviderRegistry.clearForTests()
    }

    private fun config(provider: AiProviderId) =
        AiProviderConfig(provider, "test-model", credentialReference = "keystore:test")

    private fun tools() = listOf(
        CanonicalPluginDefinition(
            "plugin.lookup",
            "lookup",
            "Look up information",
            mapOf(
                "type" to "object",
                "properties" to mapOf("query" to mapOf("type" to "string")),
                "required" to listOf("query")
            ),
            ConnectorRegistry.Risk.READ_ONLY
        )
    )

    @Test fun geminiUsesInteractionsEndpointAndHeaderWithoutUrlCredential() {
        val request = AiProviderRequestFactory.createInitialRequest(
            config(AiProviderId.GEMINI), "fake-gemini-key", "hello", tools()
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/interactions",
            request.url
        )
        assertEquals("fake-gemini-key", request.headers["x-goog-api-key"])
        assertFalse(request.url.contains("fake-gemini-key"))
        assertEquals(false, request.body["store"])
        assertEquals("test-model", request.body["model"])
    }

    @Test fun openAiUsesResponsesEndpointAndDisablesParallelCalls() {
        val request = AiProviderRequestFactory.createInitialRequest(
            config(AiProviderId.OPENAI), "fake-openai-key", "hello", tools()
        )
        assertEquals("https://api.openai.com/v1/responses", request.url)
        assertEquals("Bearer fake-openai-key", request.headers["Authorization"])
        assertEquals(false, request.body["parallel_tool_calls"])
        assertEquals(false, request.body["store"])
    }

    @Test fun xaiUsesResponsesEndpointRatherThanLegacyChatCompletions() {
        val request = AiProviderRequestFactory.createInitialRequest(
            config(AiProviderId.XAI), "fake-xai-key", "hello", tools()
        )
        assertEquals("https://api.x.ai/v1/responses", request.url)
        assertEquals("Bearer fake-xai-key", request.headers["Authorization"])
        assertFalse(request.url.contains("chat/completions"))
    }

    @Test fun anthropicUsesMessagesHeadersAndSingleToolTurn() {
        val request = AiProviderRequestFactory.createInitialRequest(
            config(AiProviderId.ANTHROPIC), "fake-anthropic-key", "hello", tools()
        )
        assertEquals("https://api.anthropic.com/v1/messages", request.url)
        assertEquals("fake-anthropic-key", request.headers["x-api-key"])
        assertEquals("2026-10-21", request.headers["anthropic-version"])
        assertEquals(4096, request.body["max_tokens"])
        assertEquals(
            true,
            (request.body["tool_choice"] as Map<*, *>)["disable_parallel_tool_use"]
        )
    }


    @Test fun customOpenAiCompatibleProfileUsesConfiguredHttpsChatCompletionsEndpoint() {
        val customConfig = AiProviderConfig(
            AiProviderId.CUSTOM,
            "custom-model",
            endpoint = "https://api.example.test/v1/chat/completions",
            credentialReference = "keystore:custom"
        )
        val request = AiProviderRequestFactory.createInitialRequest(
            customConfig, "fake-custom-key", "hello", tools()
        )
        assertEquals("https://api.example.test/v1/chat/completions", request.url)
        assertEquals("Bearer fake-custom-key", request.headers["Authorization"])
        assertEquals("custom-model", request.body["model"])
        assertEquals("auto", request.body["tool_choice"])
        assertEquals(false, request.body["parallel_tool_calls"])
        val messages = request.body["messages"] as List<*>
        assertEquals("hello", (messages.first() as Map<*, *>)["content"])
    }

    @Test fun customAdapterDecodesTextAndToolCallsAndEncodesResults() {
        val adapter = AiProviderRegistry.get(AiProviderId.CUSTOM)!!
        val customConfig = AiProviderConfig(
            AiProviderId.CUSTOM, "custom-model",
            endpoint = "https://api.example.test/v1/chat/completions",
            credentialReference = "keystore:custom"
        )
        assertEquals(
            CanonicalAiResult.Text("custom hello"),
            adapter.decodeResponse(mapOf("choices" to listOf(mapOf("message" to mapOf("content" to "custom hello")))))
        )
        val call = adapter.decodeResponse(mapOf("choices" to listOf(mapOf("message" to mapOf(
            "tool_calls" to listOf(mapOf(
                "id" to "call-1",
                "function" to mapOf("name" to "lookup", "arguments" to "{\\"query\\":\\"x\\"}")
            ))
        )))))
        assertTrue(call is CanonicalAiResult.ToolCalls)
        val encoded = adapter.encodeToolResults(customConfig, listOf(
            CanonicalToolResult("call-1", "lookup", "done")
        ))
        assertEquals("call-1", ((encoded["messages"] as List<*>).first() as Map<*, *>)["tool_call_id"])
    }

    @Test fun requestStringRedactsCredentialValues() {
        val secret = "never-print-this-key"
        val request = AiProviderRequestFactory.createInitialRequest(
            config(AiProviderId.OPENAI), secret, "hello", tools()
        )
        assertFalse(request.toString().contains(secret))
        assertTrue(request.toString().contains("Authorization"))
    }

    @Test fun rejectsEmptyOrOversizedPromptAndUnsafeCredential() {
        assertThrows(IllegalArgumentException::class.java) {
            AiProviderRequestFactory.createInitialRequest(
                config(AiProviderId.GEMINI), "key", " ", tools()
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AiProviderRequestFactory.createInitialRequest(
                config(AiProviderId.GEMINI), "key", "x".repeat(64 * 1024 + 1), tools()
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AiProviderRequestFactory.createInitialRequest(
                config(AiProviderId.GEMINI), "key\nInjected: yes", "hello", tools()
            )
        }
    }

    @Test fun rejectsUnregisteredAndUnsupportedProviders() {
        AiProviderRegistry.clearForTests()
        assertThrows(IllegalArgumentException::class.java) {
            AiProviderRequestFactory.createInitialRequest(
                config(AiProviderId.GEMINI), "key", "hello", emptyList()
            )
        }
        AiProviderRegistry.register(FakeMicrosoftAdapter())
        assertThrows(IllegalStateException::class.java) {
            AiProviderRequestFactory.createInitialRequest(
                config(AiProviderId.MICROSOFT), "key", "hello", emptyList()
            )
        }
    }

    private class FakeMicrosoftAdapter : AiProviderAdapter {
        override val provider = AiProviderId.MICROSOFT
        override val adapterVersion = "test"
        override fun validateConfiguration(config: AiProviderConfig) = Unit
        override fun capabilityProfile(model: String, apiVersion: String) =
            AiCapabilityProfile(provider, model, apiVersion, adapterVersion, emptyMap())
        override fun encodeToolDefinitions(
            config: AiProviderConfig,
            tools: List<CanonicalPluginDefinition>
        ) = mapOf("tools" to emptyList<Any>())
        override fun decodeResponse(payload: Map<String, Any?>) = CanonicalAiResult.Text("unused")
        override fun encodeToolResults(
            config: AiProviderConfig,
            results: List<CanonicalToolResult>
        ) = mapOf("input" to emptyList<Any>())
    }
}
