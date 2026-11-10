package org.mosharrof.androidcontrol

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ProviderWireAdapterTest {
    @After fun cleanup() = AiProviderRegistry.clearForTests()

    private fun config(provider: AiProviderId) =
        AiProviderConfig(provider, "model-test", credentialReference = "keystore:test")

    @Test fun geminiAdapterBuildsFunctionDeclarationEnvelope() {
        val adapter = GeminiFunctionCallingAdapter()
        val result = adapter.encodeToolDefinitions(
            config(AiProviderId.GEMINI),
            listOf(CanonicalPluginDefinition(
                "plugin.search", "search", "Search items",
                mapOf("type" to "object", "properties" to mapOf("query" to mapOf("type" to "string"))),
                ConnectorRegistry.Risk.READ_ONLY
            ))
        )
        val tools = result["tools"] as List<*>
        val declarations = (tools[0] as Map<*, *>)["functionDeclarations"] as List<*>
        assertEquals("search", (declarations[0] as Map<*, *>)["name"])
    }

    @Test fun geminiAdapterParsesFunctionCallsAndPreservesNativeId() {
        val adapter = GeminiFunctionCallingAdapter()
        val result = adapter.decodeResponse(mapOf(
            "candidates" to listOf(mapOf(
                "content" to mapOf("parts" to listOf(mapOf(
                    "functionCall" to mapOf(
                        "id" to "gem-call-42",
                        "name" to "search",
                        "args" to mapOf("query" to "hello")
                    )
                )))
            ))
        )) as CanonicalAiResult.ToolCalls
        assertEquals("gem-call-42", result.calls.single().providerCallId)
        assertEquals("hello", result.calls.single().arguments["query"])
    }

    @Test fun geminiAdapterEncodesFunctionResponse() {
        val adapter = GeminiFunctionCallingAdapter()
        val result = adapter.encodeToolResults(
            config(AiProviderId.GEMINI),
            listOf(CanonicalToolResult("gem-call-42", "search", "found"))
        )
        val parts = result["parts"] as List<*>
        val functionResponse = (parts.single() as Map<*, *>)["functionResponse"] as Map<*, *>
        assertEquals("gem-call-42", functionResponse["id"])
    }

    @Test fun openAiAdapterUsesResponsesFunctionToolShape() {
        val adapter = OpenAiResponsesFunctionCallingAdapter()
        val result = adapter.encodeToolDefinitions(
            config(AiProviderId.OPENAI),
            listOf(CanonicalPluginDefinition(
                "plugin.read", "read_item", "Read item",
                mapOf("type" to "object", "properties" to emptyMap<String, Any?>()),
                ConnectorRegistry.Risk.READ_ONLY
            ))
        )
        val tools = result["tools"] as List<*>
        val tool = tools.single() as Map<*, *>
        assertEquals("function", tool["type"])
        assertEquals("read_item", tool["name"])
        assertEquals(false, tool["strict"])
    }

    @Test fun openAiAdapterParsesResponsesFunctionCall() {
        val adapter = OpenAiResponsesFunctionCallingAdapter()
        val result = adapter.decodeResponse(mapOf(
            "output" to listOf(mapOf(
                "type" to "function_call",
                "call_id" to "call_abc",
                "name" to "lookup",
                "arguments" to "{\"term\":\"hello\",\"limit\":2}"
            ))
        )) as CanonicalAiResult.ToolCalls
        assertEquals("call_abc", result.calls.single().providerCallId)
        assertEquals(2, result.calls.single().arguments["limit"])
    }

    @Test fun openAiAdapterEncodesFunctionCallOutputWithCallId() {
        val adapter = OpenAiResponsesFunctionCallingAdapter()
        val result = adapter.encodeToolResults(
            config(AiProviderId.OPENAI),
            listOf(CanonicalToolResult("call_abc", "lookup", "done"))
        )
        val input = result["input"] as List<*>
        val output = input.single() as Map<*, *>
        assertEquals("function_call_output", output["type"])
        assertEquals("call_abc", output["call_id"])
    }

    @Test fun adaptersRejectWrongProviderConfig() {
        assertThrows(IllegalArgumentException::class.java) {
            GeminiFunctionCallingAdapter().validateConfiguration(config(AiProviderId.OPENAI))
        }
    }
}
