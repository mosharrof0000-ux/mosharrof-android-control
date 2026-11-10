package org.mosharrof.androidcontrol

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

class AiProviderRuntimeEndToEndTest {
    private class FakeMcpServer {
        val calls = AtomicInteger(0)
        @Volatile var failToolCall: Boolean = false

        fun connection(url: URL): HttpURLConnection = object : HttpURLConnection(url) {
            private val request = ByteArrayOutputStream()
            private var status = -1
            private var response = ""

            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun getOutputStream(): OutputStream = request

            private fun prepare() {
                if (status >= 0) return
                val json = JSONObject(request.toString(Charsets.UTF_8.name()))
                val id = json.opt("id")
                val result = when (json.optString("method")) {
                    "server/discover" -> JSONObject()
                        .put("resultType", "complete")
                        .put("supportedVersions", JSONArray().put(McpProtocol.MODERN_VERSION))
                        .put("capabilities", JSONObject())
                        .put("_meta", JSONObject().put(
                            "io.modelcontextprotocol/serverInfo",
                            JSONObject().put("name", "Runtime E2E Fake").put("version", "1")
                        ))
                        .put("cacheScope", "public")
                        .put("ttlMs", 0)
                    "tools/list" -> JSONObject()
                        .put("resultType", "complete")
                        .put("cacheScope", "private")
                        .put("ttlMs", 0)
                        .put("tools", JSONArray().put(
                            JSONObject()
                                .put("name", "lookup")
                                .put("description", "Lookup a value")
                                .put("inputSchema", JSONObject()
                                    .put("type", "object")
                                    .put("properties", JSONObject().put(
                                        "query", JSONObject().put("type", "string")
                                    ))
                                    .put("required", JSONArray().put("query"))
                                    .put("additionalProperties", false))
                        ))
                    "tools/call" -> {
                        calls.incrementAndGet()
                        if (failToolCall) {
                            status = 200
                            response = JSONObject()
                                .put("jsonrpc", "2.0")
                                .put("id", id)
                                .put("error", JSONObject()
                                    .put("code", -32000)
                                    .put("message", "simulated MCP execution failure"))
                                .toString()
                            return
                        }
                        JSONObject()
                            .put("resultType", "complete")
                            .put("content", JSONArray().put(
                                JSONObject().put("type", "text").put("text", "mcp-result")
                            ))
                    }
                    else -> error("Unexpected MCP method: ${json.optString("method")}")
                }
                status = 200
                response = JSONObject().put("jsonrpc", "2.0").put("id", id)
                    .put("result", result).toString()
            }

            override fun getResponseCode(): Int { prepare(); return status }
            override fun getInputStream(): InputStream {
                prepare()
                return ByteArrayInputStream(response.toByteArray(Charsets.UTF_8))
            }
            override fun getErrorStream(): InputStream =
                ByteArrayInputStream(response.toByteArray(Charsets.UTF_8))
            override fun getContentType(): String = "application/json"
            override fun getHeaderField(name: String?): String? = null
        }
    }

    @After fun cleanup() {
        ConnectorRegistry.clearForTests()
        ToolApprovalGrants.clearForTests()
        AiProviderRegistry.clearForTests()
    }

    private fun connectedService(server: FakeMcpServer, connectorId: String = "runtime.e2e"): McpConnectorService {
        ConnectorRegistry.registerConnector(
            ConnectorRegistry.Connector(
                id = connectorId,
                name = "Runtime E2E",
                transport = ConnectorRegistry.Transport.MCP_STREAMABLE_HTTP,
                endpoint = "https://example.com/mcp"
            )
        ).getOrThrow()
        val service = McpConnectorService(McpHttpClient(
            endpoint = "https://example.com/mcp",
            connectionFactory = server::connection
        ))
        service.connect(connectorId).getOrThrow()
        return service
    }

    private fun profile() : ResolvedAiProviderProfile {
        val config = AiProviderConfig(
            AiProviderId.GEMINI, "test-model", credentialReference = "runtime-e2e-profile"
        )
        return ResolvedAiProviderProfile(
            AiProviderProfileSummary("runtime-e2e-profile", AiProviderId.GEMINI, "test-model", null),
            config,
            "fake-key"
        )
    }

    private fun functionCall() = mapOf<String, Any?>(
        "steps" to listOf(mapOf(
            "type" to "function_call",
            "id" to "provider-call-e2e",
            "name" to "lookup",
            "arguments" to mapOf("query" to "hello")
        ))
    )

    private fun finalText() = mapOf<String, Any?>(
        "steps" to listOf(mapOf(
            "type" to "model_output",
            "content" to listOf(mapOf("type" to "text", "text" to "final answer"))
        ))
    )

    @Test fun approvedWriteFlowsThroughApprovalMcpAndProviderContinuation() {
        val server = FakeMcpServer()
        val service = connectedService(server)
        val requests = mutableListOf<AiProviderHttpRequest>()
        var approvals = 0
        val responses = ArrayDeque(listOf(functionCall(), finalText()))

        val answer = AiProviderRuntime.runConversation(
            profile = profile(),
            prompt = "look this up",
            service = service,
            approve = { plan ->
                approvals++
                assertEquals("lookup", plan.call.toolName)
                assertTrue(plan.approvalRequired)
                true
            },
            post = { request ->
                requests += request
                if (requests.size == 1) {
                    val tools = request.body["tools"] as List<*>
                    assertEquals("lookup", (tools.single() as Map<*, *>)["name"])
                } else {
                    val input = request.body["input"] as List<*>
                    assertTrue(input.any { (it as? Map<*, *>)?.get("type") == "function_result" })
                }
                responses.removeFirst()
            }
        )

        assertEquals("final answer", answer)
        assertEquals(1, approvals)
        assertEquals(1, server.calls.get())
        assertEquals(2, requests.size)
    }

    @Test fun declinedWriteIsNotExecutedButProviderReceivesDenialAndContinues() {
        val server = FakeMcpServer()
        val service = connectedService(server)
        val requests = mutableListOf<AiProviderHttpRequest>()
        var approvals = 0
        val responses = ArrayDeque(listOf(functionCall(), finalText()))

        val answer = AiProviderRuntime.runConversation(
            profile = profile(),
            prompt = "look this up",
            service = service,
            approve = {
                approvals++
                false
            },
            post = { request ->
                requests += request
                if (requests.size == 2) {
                    val input = request.body["input"] as List<*>
                    val result = input.filterIsInstance<Map<*, *>>()
                        .first { it["type"] == "function_result" }
                    assertTrue(result.toString().contains("declined"))
                }
                responses.removeFirst()
            }
        )

        assertEquals("final answer", answer)
        assertEquals(1, approvals)
        assertEquals(0, server.calls.get())
        assertEquals(2, requests.size)
    }

    @Test fun approvalCallbackExceptionFailsClosedAndDoesNotExecuteTool() {
        val server = FakeMcpServer()
        val service = connectedService(server)
        val requests = mutableListOf<AiProviderHttpRequest>()
        val responses = ArrayDeque(listOf(functionCall(), finalText()))

        val answer = AiProviderRuntime.runConversation(
            profile = profile(),
            prompt = "look this up",
            service = service,
            approve = { throw IllegalStateException("simulated approval UI failure") },
            post = { request ->
                requests += request
                if (requests.size == 2) {
                    val input = request.body["input"] as List<*>
                    val result = input.filterIsInstance<Map<*, *>>()
                        .first { it["type"] == "function_result" }
                    assertTrue(result.toString().contains("declined"))
                }
                responses.removeFirst()
            }
        )

        assertEquals("final answer", answer)
        assertEquals(0, server.calls.get())
        assertEquals(2, requests.size)
    }

    @Test fun mcpExecutionFailureIsReturnedToProviderAndConversationContinues() {
        val server = FakeMcpServer().apply { failToolCall = true }
        val service = connectedService(server)
        val requests = mutableListOf<AiProviderHttpRequest>()
        val responses = ArrayDeque(listOf(functionCall(), finalText()))

        val answer = AiProviderRuntime.runConversation(
            profile = profile(),
            prompt = "look this up",
            service = service,
            approve = { true },
            post = { request ->
                requests += request
                if (requests.size == 2) {
                    val input = request.body["input"] as List<*>
                    val result = input.filterIsInstance<Map<*, *>>()
                        .first { it["type"] == "function_result" }
                    val resultContent = (result["result"] as List<*>)
                        .filterIsInstance<Map<*, *>>()
                        .first()["text"] as String
                    assertTrue(resultContent.startsWith("ERROR: "))
                    assertTrue(resultContent.contains("simulated MCP execution failure"))
                }
                responses.removeFirst()
            }
        )

        assertEquals("final answer", answer)
        assertEquals(1, server.calls.get())
        assertEquals(2, requests.size)
    }

    @Test fun unknownToolIsDeniedAndNeverSentToMcp() {
        val server = FakeMcpServer()
        val service = connectedService(server)
        val requests = mutableListOf<AiProviderHttpRequest>()
        val unknownCall = mapOf<String, Any?>(
            "steps" to listOf(mapOf(
                "type" to "function_call",
                "id" to "provider-call-unknown",
                "name" to "delete_everything",
                "arguments" to emptyMap<String, Any?>()
            ))
        )
        val responses = ArrayDeque(listOf(unknownCall, finalText()))

        val answer = AiProviderRuntime.runConversation(
            profile = profile(),
            prompt = "run a plugin",
            service = service,
            approve = { error("Unknown tool must not request approval") },
            post = { request ->
                requests += request
                if (requests.size == 2) {
                    val input = request.body["input"] as List<*>
                    val result = input.filterIsInstance<Map<*, *>>()
                        .first { it["type"] == "function_result" }
                    val resultContent = (result["result"] as List<*>)
                        .filterIsInstance<Map<*, *>>()
                        .first()["text"] as String
                    assertTrue(resultContent.startsWith("ERROR: "))
                }
                responses.removeFirst()
            }
        )

        assertEquals("final answer", answer)
        assertEquals(0, server.calls.get())
        assertEquals(2, requests.size)
    }

    @Test fun continuationRoundLimitStopsFurtherToolExecution() {
        val server = FakeMcpServer()
        val service = connectedService(server)
        var requests = 0

        val failure = runCatching {
            AiProviderRuntime.runConversation(
                profile = profile(),
                prompt = "keep using the tool",
                service = service,
                approve = { true },
                post = {
                    requests++
                    functionCall()
                }
            )
        }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("continuation limit"))
        assertEquals(AiProviderRuntime.MAX_CONTINUATION_ROUNDS + 1, requests)
        assertEquals(AiProviderRuntime.MAX_CONTINUATION_ROUNDS, server.calls.get())
    }


    private fun profile(provider: AiProviderId): ResolvedAiProviderProfile {
        val config = AiProviderConfig(
            provider, "test-model", credentialReference = "runtime-e2e-${provider.name.lowercase()}"
        )
        return ResolvedAiProviderProfile(
            AiProviderProfileSummary(config.credentialReference, provider, "test-model", null),
            config,
            "fake-key"
        )
    }

    private fun providerToolCall(provider: AiProviderId): Map<String, Any?> {
        val callId = "provider-call-${provider.name.lowercase()}"
        return when (provider) {
            AiProviderId.GEMINI -> mapOf("steps" to listOf(mapOf(
                "type" to "function_call", "id" to callId, "name" to "lookup",
                "arguments" to mapOf("query" to "hello")
            )))
            AiProviderId.OPENAI, AiProviderId.XAI -> mapOf("output" to listOf(mapOf(
                "type" to "function_call", "call_id" to callId, "name" to "lookup",
                "arguments" to """{"query":"hello"}"""
            )))
            AiProviderId.ANTHROPIC -> mapOf("content" to listOf(mapOf(
                "type" to "tool_use", "id" to callId, "name" to "lookup",
                "input" to mapOf("query" to "hello")
            )))
            else -> error("No outbound tool-call test fixture for $provider")
        }
    }

    private fun providerFinalText(provider: AiProviderId): Map<String, Any?> = when (provider) {
        AiProviderId.GEMINI -> mapOf("steps" to listOf(mapOf(
            "type" to "model_output",
            "content" to listOf(mapOf("type" to "text", "text" to "final answer"))
        )))
        AiProviderId.OPENAI, AiProviderId.XAI -> mapOf("output" to listOf(mapOf(
            "type" to "message",
            "content" to listOf(mapOf("type" to "output_text", "text" to "final answer"))
        )))
        AiProviderId.ANTHROPIC -> mapOf("content" to listOf(mapOf(
            "type" to "text", "text" to "final answer"
        )))
        else -> error("No outbound text test fixture for $provider")
    }

    @Test fun allBuiltInOutboundProvidersCompleteApprovedMcpToolRoundTrip() {
        listOf(
            AiProviderId.GEMINI,
            AiProviderId.OPENAI,
            AiProviderId.XAI,
            AiProviderId.ANTHROPIC
        ).forEach { provider ->
            // Each iteration must start with an isolated registry; otherwise the
            // same fake tool name from the previous connector is correctly denied
            // as ambiguous by the production fail-closed guard.
            ConnectorRegistry.clearForTests()
            ToolApprovalGrants.clearForTests()
            val server = FakeMcpServer()
            val service = connectedService(server, "runtime.e2e.${provider.name.lowercase()}")
            val requests = mutableListOf<AiProviderHttpRequest>()
            var approvals = 0
            val responses = ArrayDeque(listOf(
                providerToolCall(provider),
                providerFinalText(provider)
            ))

            val answer = AiProviderRuntime.runConversation(
                profile = profile(provider),
                prompt = "look this up",
                service = service,
                approve = { plan ->
                    approvals++
                    assertEquals("lookup", plan.call.toolName)
                    assertTrue(plan.approvalRequired)
                    true
                },
                post = { request ->
                    requests += request
                    val toolDefinitions = request.body["tools"] as? List<*>
                    assertTrue("$provider must advertise the MCP tool", !toolDefinitions.isNullOrEmpty())
                    if (requests.size == 2) {
                        when (provider) {
                            AiProviderId.GEMINI, AiProviderId.OPENAI, AiProviderId.XAI -> {
                                val input = requests.last().body["input"] as List<*>
                                val expectedType = if (provider == AiProviderId.GEMINI) "function_result" else "function_call_output"
                                assertTrue("$provider continuation must include the tool result",
                                    input.any { (it as? Map<*, *>)?.get("type") == expectedType })
                            }
                            AiProviderId.ANTHROPIC -> {
                                val messages = requests.last().body["messages"] as List<*>
                                val resultMessage = messages.last() as Map<*, *>
                                assertEquals("user", resultMessage["role"])
                                val content = resultMessage["content"] as List<*>
                                assertTrue(content.any { (it as? Map<*, *>)?.get("type") == "tool_result" })
                            }
                            else -> error("Unexpected provider $provider")
                        }
                    }
                    responses.removeFirst()
                }
            )

            assertEquals("$provider final response", "final answer", answer)
            assertEquals("$provider approval count", 1, approvals)
            assertEquals("$provider MCP execution count", 1, server.calls.get())
            assertEquals("$provider HTTP request count", 2, requests.size)
        }
    }

}
