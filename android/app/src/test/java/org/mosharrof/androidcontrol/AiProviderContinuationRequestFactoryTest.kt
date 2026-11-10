package org.mosharrof.androidcontrol

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AiProviderContinuationRequestFactoryTest {
    @Before fun setup() {
        AiProviderRegistry.clearForTests()
        AiProviderRegistry.registerBuiltIns()
    }

    @After fun cleanup() {
        AiProviderRegistry.clearForTests()
    }

    private fun config(provider: AiProviderId) = when (provider) {
        AiProviderId.CUSTOM -> AiProviderConfig(
            provider, "test-model", "https://api.example.test/v1/chat/completions", "keystore:test"
        )
        else -> AiProviderConfig(provider, "test-model", credentialReference = "keystore:test")
    }

    private fun tool() = CanonicalPluginDefinition(
        id = "plugin.lookup",
        name = "lookup",
        description = "Look up information",
        inputSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("query" to mapOf("type" to "string")),
            "required" to listOf("query")
        ),
        risk = ConnectorRegistry.Risk.READ_ONLY
    )

    private fun result(id: String, name: String = "lookup") =
        listOf(CanonicalToolResult(id, name, "result-value"))

    @Test fun geminiReplaysExactStepsAndAppendsFunctionResultStatelessly() {
        val thought = mapOf("type" to "thought", "signature" to "opaque-signature")
        val call = mapOf(
            "type" to "function_call", "id" to "gem-call", "name" to "lookup",
            "arguments" to mapOf("query" to "x")
        )
        val previous = mapOf<String, Any?>("steps" to listOf(thought, call))
        val request = AiProviderContinuationRequestFactory.create(
            config(AiProviderId.GEMINI), "fake-key", "find x", previous,
            result("gem-call"), listOf(tool())
        )
        val input = request.body["input"] as List<*>
        assertEquals("user_input", (input[0] as Map<*, *>)["type"])
        assertEquals(thought, input[1])
        assertEquals(call, input[2])
        assertEquals("function_result", (input[3] as Map<*, *>)["type"])
        assertEquals(false, request.body["store"])
        assertEquals("fake-key", request.headers["x-goog-api-key"])
    }

    @Test fun openAiReplaysPriorOutputAndAddsFunctionCallOutputWithoutServerState() {
        val reasoning = mapOf("type" to "reasoning", "encrypted_content" to "opaque")
        val call = mapOf(
            "type" to "function_call", "call_id" to "oa-call", "name" to "lookup",
            "arguments" to "{\"query\":\"x\"}"
        )
        val previous = mapOf<String, Any?>("output" to listOf(reasoning, call))
        val request = AiProviderContinuationRequestFactory.create(
            config(AiProviderId.OPENAI), "fake-key", "find x", previous,
            result("oa-call"), listOf(tool())
        )
        val input = request.body["input"] as List<*>
        assertEquals(reasoning, input[1])
        assertEquals(call, input[2])
        assertEquals("function_call_output", (input[3] as Map<*, *>)["type"])
        assertEquals(false, request.body["store"])
        assertEquals(false, request.body["parallel_tool_calls"])
    }

    @Test fun xaiReplaysEncryptedReasoningOutputForStatelessContinuation() {
        val reasoning = mapOf("type" to "reasoning", "encrypted_content" to "opaque-xai")
        val call = mapOf(
            "type" to "function_call", "call_id" to "xai-call", "name" to "lookup",
            "arguments" to "{\"query\":\"x\"}"
        )
        val request = AiProviderContinuationRequestFactory.create(
            config(AiProviderId.XAI), "fake-key", "find x",
            mapOf("output" to listOf(reasoning, call)), result("xai-call"), listOf(tool())
        )
        val input = request.body["input"] as List<*>
        assertEquals(reasoning, input[1])
        assertEquals(call, input[2])
        assertEquals("function_call_output", (input[3] as Map<*, *>)["type"])
        assertEquals(false, request.body["store"])
    }

    @Test fun anthropicAddsAssistantToolUseThenUserToolResult() {
        val assistantContent = listOf(
            mapOf("type" to "text", "text" to "I'll look that up."),
            mapOf("type" to "tool_use", "id" to "claude-call", "name" to "lookup", "input" to mapOf("query" to "x"))
        )
        val request = AiProviderContinuationRequestFactory.create(
            config(AiProviderId.ANTHROPIC), "fake-key", "find x",
            mapOf("content" to assistantContent), result("claude-call"), listOf(tool())
        )
        val messages = request.body["messages"] as List<*>
        assertEquals("user", (messages[0] as Map<*, *>)["role"])
        assertEquals("assistant", (messages[1] as Map<*, *>)["role"])
        assertEquals(assistantContent, (messages[1] as Map<*, *>)["content"])
        val resultMessage = messages[2] as Map<*, *>
        assertEquals("user", resultMessage["role"])
        val content = resultMessage["content"] as List<*>
        assertEquals("tool_result", (content[0] as Map<*, *>)["type"])
        assertEquals("claude-call", (content[0] as Map<*, *>)["tool_use_id"])
    }

    @Test fun customOpenAiCompatibleAddsAssistantToolCallAndToolMessage() {
        val assistantMessage = mapOf(
            "role" to "assistant",
            "tool_calls" to listOf(
                mapOf(
                    "id" to "custom-call",
                    "type" to "function",
                    "function" to mapOf("name" to "lookup", "arguments" to "{\"query\":\"x\"}")
                )
            )
        )
        val previous = mapOf<String, Any?>("choices" to listOf(mapOf("message" to assistantMessage)))
        val request = AiProviderContinuationRequestFactory.create(
            config(AiProviderId.CUSTOM), "fake-key", "find x", previous,
            result("custom-call"), listOf(tool())
        )
        val messages = request.body["messages"] as List<*>
        assertEquals(assistantMessage, messages[1])
        assertEquals("tool", (messages[2] as Map<*, *>)["role"])
        assertEquals("custom-call", (messages[2] as Map<*, *>)["tool_call_id"])
    }

    @Test fun rejectsMissingOrMismatchedToolResultsBeforeBuildingRequest() {
        val previous = mapOf<String, Any?>(
            "output" to listOf(
                mapOf(
                    "type" to "function_call", "call_id" to "expected-call",
                    "name" to "lookup", "arguments" to "{\"query\":\"x\"}"
                )
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            AiProviderContinuationRequestFactory.create(
                config(AiProviderId.OPENAI), "fake-key", "find x", previous,
                result("different-call"), listOf(tool())
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AiProviderContinuationRequestFactory.create(
                config(AiProviderId.OPENAI), "fake-key", "find x", previous,
                result("expected-call", "other-tool"), listOf(tool())
            )
        }
    }

    @Test fun rejectsOversizedHistory() {
        val call = mapOf(
            "type" to "function_call", "call_id" to "oa-call", "name" to "lookup",
            "arguments" to "{\"query\":\"x\"}"
        )
        val history = List(2_049) { mapOf("type" to "message", "content" to emptyList<Any>()) } + call
        assertThrows(IllegalArgumentException::class.java) {
            AiProviderContinuationRequestFactory.create(
                config(AiProviderId.OPENAI), "fake-key", "find x",
                mapOf("output" to history), result("oa-call"), listOf(tool())
            )
        }
    }
}
