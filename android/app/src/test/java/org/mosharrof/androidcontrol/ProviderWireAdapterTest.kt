package org.mosharrof.androidcontrol

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ProviderWireAdapterTest {
    @After fun cleanup() {
        AiProviderRegistry.clearForTests()
        ConnectorRegistry.clearForTests()
        ToolApprovalGrants.clearForTests()
    }

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

    @Test fun canonicalToolCallRejectsDeepOrOversizedProviderArguments() {
        var nested: Any? = "leaf"
        repeat(CanonicalArgumentBounds.MAX_DEPTH + 1) {
            nested = mapOf("child" to nested)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalToolCall("deep-call", "lookup", mapOf("root" to nested))
        }

        val wide = (1..CanonicalArgumentBounds.MAX_NODES + 1)
            .associate { "key$it" to it }
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalToolCall("wide-call", "lookup", wide)
        }
    }

    @Test fun responsesAdapterRejectsExcessivelyNestedJsonArguments() {
        val nestedJson = """{"x":""".repeat(0) + buildString {
            repeat(CanonicalArgumentBounds.MAX_DEPTH + 2) { append("""{"x":""") }
            append("\"leaf\"")
            repeat(CanonicalArgumentBounds.MAX_DEPTH + 2) { append("}") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenAiResponsesFunctionCallingAdapter().decodeResponse(mapOf(
                "output" to listOf(mapOf(
                    "type" to "function_call",
                    "call_id" to "deep-call",
                    "name" to "lookup",
                    "arguments" to nestedJson
                ))
            ))
        }
    }

    @Test fun adaptersRejectWrongProviderConfig() {
        assertThrows(IllegalArgumentException::class.java) {
            GeminiFunctionCallingAdapter().validateConfiguration(config(AiProviderId.OPENAI))
        }
    }

    @Test fun grokAdapterParsesChatCompletionToolCall() {
        val result = XaiChatCompletionsFunctionCallingAdapter().decodeResponse(mapOf(
            "choices" to listOf(mapOf("message" to mapOf(
                "tool_calls" to listOf(mapOf(
                    "id" to "grok-call-1",
                    "function" to mapOf(
                        "name" to "search",
                        "arguments" to "{\"q\":\"mosharrof\"}"
                    )
                ))
            )))
        )) as CanonicalAiResult.ToolCalls
        assertEquals("grok-call-1", result.calls.single().providerCallId)
        assertEquals("mosharrof", result.calls.single().arguments["q"])
    }

    @Test fun grokAdapterEncodesToolResultsWithCallId() {
        val result = XaiChatCompletionsFunctionCallingAdapter().encodeToolResults(
            config(AiProviderId.XAI),
            listOf(CanonicalToolResult("grok-call-1", "search", "ok"))
        )
        val messages = result["messages"] as List<*>
        val message = messages.single() as Map<*, *>
        assertEquals("grok-call-1", message["tool_call_id"])
    }

    @Test fun claudeAdapterParsesToolUseBlock() {
        val result = AnthropicMessagesToolUseAdapter().decodeResponse(mapOf(
            "stop_reason" to "tool_use",
            "content" to listOf(mapOf(
                "type" to "tool_use",
                "id" to "toolu_123",
                "name" to "read_file",
                "input" to mapOf("path" to "README.md")
            ))
        )) as CanonicalAiResult.ToolCalls
        assertEquals("toolu_123", result.calls.single().providerCallId)
        assertEquals("README.md", result.calls.single().arguments["path"])
    }

    @Test fun claudeAdapterEncodesToolResultErrorFlag() {
        val result = AnthropicMessagesToolUseAdapter().encodeToolResults(
            config(AiProviderId.ANTHROPIC),
            listOf(CanonicalToolResult("toolu_123", "read_file", "denied", true))
        )
        val content = result["content"] as List<*>
        val block = content.single() as Map<*, *>
        assertEquals("tool_result", block["type"])
        assertEquals(true, block["is_error"])
    }

    @Test fun allImplementedAdaptersRejectMismatchedProviderConfigs() {
        val adapters = listOf(
            GeminiFunctionCallingAdapter() to AiProviderId.GEMINI,
            OpenAiResponsesFunctionCallingAdapter() to AiProviderId.OPENAI,
            XaiChatCompletionsFunctionCallingAdapter() to AiProviderId.XAI,
            AnthropicMessagesToolUseAdapter() to AiProviderId.ANTHROPIC
        )
        adapters.forEach { (adapter, provider) ->
            val wrong = AiProviderId.values().first { it != provider }
            assertThrows(IllegalArgumentException::class.java) {
                adapter.validateConfiguration(config(wrong))
            }
        }
    }


    @Test fun vendorProfilesRequireKnownApiVersionAndKeepUnverifiedModelsUnknown() {
        val gemini = GeminiFunctionCallingAdapter().capabilityProfile("gemini-model", "v1beta")
        assertEquals(CapabilitySupport.UNKNOWN, gemini.capabilities[AiCapability.TOOL_CALLING])
        assertThrows(IllegalArgumentException::class.java) {
            OpenAiResponsesFunctionCallingAdapter().capabilityProfile("model", "v2")
        }
        assertThrows(IllegalArgumentException::class.java) {
            XaiResponsesFunctionCallingAdapter().capabilityProfile("model", "v2")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnthropicMessagesToolUseAdapter().capabilityProfile("model", "2023-06-01")
        }
    }


    private fun registerGuardTool(risk: ConnectorRegistry.Risk, connected: Boolean = true) {
        ConnectorRegistry.registerConnector(ConnectorRegistry.Connector(
            id = "test.connector",
            name = "Test Connector",
            transport = ConnectorRegistry.Transport.MCP_STREAMABLE_HTTP,
            endpoint = "https://example.com/mcp",
            state = if (connected) ConnectorRegistry.ConnectionState.CONNECTED
                    else ConnectorRegistry.ConnectionState.DISCONNECTED
        )).getOrThrow()
        ConnectorRegistry.registerTool(ConnectorRegistry.Tool(
            id = "test.tool",
            connectorId = "test.connector",
            name = "lookup",
            description = "Test lookup",
            risk = risk,
            requiredFields = setOf("q"),
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf("q" to mapOf("type" to "string")),
                "required" to listOf("q"),
                "additionalProperties" to false
            )
        )).getOrThrow()
    }

    @Test fun canonicalGuardAllowsOnlyRegisteredConnectedReadOnlyTools() {
        registerGuardTool(ConnectorRegistry.Risk.READ_ONLY)
        val decision = CanonicalToolCallGuard.inspect(
            CanonicalToolCall("call-1", "lookup", mapOf("q" to "hello"))
        )
        assertTrue(decision is CanonicalToolCallDecision.ReadOnlyAllowed)
    }

    @Test fun canonicalGuardRequiresApprovalForWriteTools() {
        registerGuardTool(ConnectorRegistry.Risk.WRITE)
        val decision = CanonicalToolCallGuard.inspect(
            CanonicalToolCall("call-2", "lookup", mapOf("q" to "hello"))
        )
        assertTrue(decision is CanonicalToolCallDecision.ApprovalRequired)
    }

    @Test fun canonicalGuardDeniesUnknownToolsAndInvalidArguments() {
        registerGuardTool(ConnectorRegistry.Risk.READ_ONLY)
        assertTrue(
            CanonicalToolCallGuard.inspect(
                CanonicalToolCall("call-3", "not_registered", emptyMap())
            ) is CanonicalToolCallDecision.Denied
        )
        assertTrue(
            CanonicalToolCallGuard.inspect(
                CanonicalToolCall("call-4", "lookup", mapOf("q" to 42))
            ) is CanonicalToolCallDecision.Denied
        )
    }

    @Test fun canonicalGuardDeniesDisconnectedConnectors() {
        registerGuardTool(ConnectorRegistry.Risk.READ_ONLY, connected = false)
        assertTrue(
            CanonicalToolCallGuard.inspect(
                CanonicalToolCall("call-5", "lookup", mapOf("q" to "hello"))
            ) is CanonicalToolCallDecision.Denied
        )
    }


    @Test fun grokResponsesAdapterBuildsAndParsesFunctionCalls() {
        val adapter = XaiResponsesFunctionCallingAdapter()
        val config = config(AiProviderId.XAI)
        val tools = adapter.encodeToolDefinitions(
            config,
            listOf(CanonicalPluginDefinition(
                "plugin.lookup", "lookup", "Lookup data",
                mapOf("type" to "object", "properties" to mapOf("q" to mapOf("type" to "string"))),
                ConnectorRegistry.Risk.READ_ONLY
            ))
        )["tools"] as List<*>
        val definition = tools.single() as Map<*, *>
        assertEquals("function", definition["type"])
        assertEquals("lookup", definition["name"])

        val parsed = adapter.decodeResponse(mapOf(
            "output" to listOf(mapOf(
                "type" to "function_call",
                "call_id" to "xai-call-1",
                "name" to "lookup",
                "arguments" to "{\"q\":\"hello\"}"
            ))
        )) as CanonicalAiResult.ToolCalls
        assertEquals("xai-call-1", parsed.calls.single().providerCallId)
        assertEquals("hello", parsed.calls.single().arguments["q"])

        val continuation = adapter.encodeToolResults(
            config,
            listOf(CanonicalToolResult("xai-call-1", "lookup", "found"))
        )["input"] as List<*>
        assertEquals("function_call_output", (continuation.single() as Map<*, *>)["type"])
    }

    @Test fun anthropicProfileUsesCurrentDocumentedApiVersion() {
        val profile = AnthropicMessagesToolUseAdapter()
            .capabilityProfile("claude-model", "2026-10-21")
        assertEquals("2026-10-21", profile.apiVersion)
        assertEquals(CapabilitySupport.UNKNOWN, profile.capabilities[AiCapability.TOOL_CALLING])
    }

    @Test fun geminiInteractionsAdapterBuildsCallsAndFunctionResults() {
        val adapter = GeminiInteractionsAdapter()
        val config = config(AiProviderId.GEMINI)
        val definitions = adapter.encodeToolDefinitions(
            config,
            listOf(CanonicalPluginDefinition(
                "plugin.lookup", "lookup", "Lookup data",
                mapOf("type" to "object", "properties" to mapOf("q" to mapOf("type" to "string"))),
                ConnectorRegistry.Risk.READ_ONLY
            ))
        )["tools"] as List<*>
        assertEquals("function", (definitions.single() as Map<*, *>)["type"])

        val parsed = adapter.decodeResponse(mapOf(
            "id" to "interaction-1",
            "steps" to listOf(mapOf(
                "type" to "function_call",
                "id" to "gemini-call-1",
                "name" to "lookup",
                "arguments" to mapOf("q" to "hello")
            ))
        )) as CanonicalAiResult.ToolCalls
        assertEquals("gemini-call-1", parsed.calls.single().providerCallId)
        assertEquals("hello", parsed.calls.single().arguments["q"])

        val resultInput = adapter.encodeToolResults(
            config,
            listOf(CanonicalToolResult("gemini-call-1", "lookup", "found"))
        )["input"] as List<*>
        val functionResult = resultInput.single() as Map<*, *>
        assertEquals("function_result", functionResult["type"])
        assertEquals("gemini-call-1", functionResult["call_id"])
    }

    @Test fun geminiInteractionsAdapterParsesModelOutputText() {
        val result = GeminiInteractionsAdapter().decodeResponse(mapOf(
            "steps" to listOf(mapOf(
                "type" to "model_output",
                "content" to listOf(mapOf("type" to "text", "text" to "Hello"))
            ))
        ))
        assertEquals(CanonicalAiResult.Text("Hello"), result)
    }
}
