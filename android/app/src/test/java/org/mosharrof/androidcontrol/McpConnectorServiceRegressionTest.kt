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

class McpConnectorServiceRegressionTest {

    private class FakeServer(
        private val legacy: Boolean = false,
        private val invalidSecondTool: Boolean = false,
        private val discoveryVersions: List<String> = listOf(McpProtocol.MODERN_VERSION),
        private val versionErrorSupported: List<String>? = null,
        private val discoveryErrorCode: Int? = null,
        private val paginatedTools: Boolean = false,
        private val repeatCursor: Boolean = false,
        private val toolResultType: String = "complete",
        private val taskGetStatuses: List<String> = listOf("working", "completed")
    ) {
        val toolCalls = AtomicInteger(0)
        val taskGetCalls = AtomicInteger(0)
        val methods = mutableListOf<String>()
        val toolCallMetadata = mutableListOf<Boolean>()
        val toolCallHeaders = mutableListOf<Map<String, String?>>()
        val toolCallParams = mutableListOf<JSONObject>()
        val toolListCursors = mutableListOf<String?>()

        fun connection(url: URL): HttpURLConnection =
            object : HttpURLConnection(url) {
                private val requestBytes = ByteArrayOutputStream()
                private var status = -1
                private var responseBody = ""
                private var responseType = "application/json"

                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy() = false

                override fun getOutputStream(): OutputStream = requestBytes

                private fun prepare() {
                    if (status >= 0) return

                    val request = JSONObject(
                        requestBytes.toString(Charsets.UTF_8.name())
                    )
                    val method = request.optString("method")
                    methods.add(method)

                    if (method == "tools/list") {
                        val params = request.optJSONObject("params")
                        val cursor = if (params != null && params.has("cursor") && !params.isNull("cursor")) {
                            params.opt("cursor") as? String
                                ?: error("Fake server received non-string cursor")
                        } else null
                        toolListCursors.add(cursor)
                    }

                    if (method == "tools/call") {
                        val params = request.optJSONObject("params")
                        if (params != null) toolCallParams.add(JSONObject(params.toString()))
                        val metadata = params?.optJSONObject("_meta")
                        toolCallMetadata.add(
                            metadata?.optString("io.modelcontextprotocol/protocolVersion") ==
                                McpProtocol.MODERN_VERSION
                        )
                        toolCallHeaders.add(
                            mapOf(
                                "protocol" to getRequestProperty("MCP-Protocol-Version"),
                                "method" to getRequestProperty("Mcp-Method"),
                                "name" to getRequestProperty("Mcp-Name")
                            )
                        )
                    }

                    val id = request.opt("id")

                    if (method == "server/discover" && discoveryErrorCode != null) {
                        status = 404
                        responseBody = JSONObject()
                            .put("jsonrpc", "2.0")
                            .put("id", id)
                            .put(
                                "error",
                                JSONObject()
                                    .put("code", discoveryErrorCode)
                                    .put("message", "Method not found")
                            )
                            .toString()
                        return
                    }

                    if (method == "server/discover" && versionErrorSupported != null) {
                        val supported = JSONArray()
                        versionErrorSupported.forEach { supported.put(it) }
                        status = 400
                        responseBody = JSONObject()
                            .put("jsonrpc", "2.0")
                            .put("id", id)
                            .put(
                                "error",
                                JSONObject()
                                    .put("code", -32022)
                                    .put("message", "Unsupported protocol version")
                                    .put("data", JSONObject().put("supported", supported))
                            )
                            .toString()
                        return
                    }

                    if (method == "server/discover" && legacy) {
                        status = 404
                        responseBody = ""
                        return
                    }

                    if (method.startsWith("notifications/")) {
                        status = 202
                        responseBody = ""
                        return
                    }

                    val result = when (method) {
                        "server/discover" -> JSONObject()
                            .put("resultType", "complete")
                            .put(
                                "supportedVersions",
                                JSONArray().apply { discoveryVersions.forEach { put(it) } }
                            )
                            .put("capabilities", JSONObject())
                            .put(
                                "_meta",
                                JSONObject().put(
                                    "io.modelcontextprotocol/serverInfo",
                                    JSONObject().put("name", "Fake MCP").put("version", "1")
                                )
                            )
                            .put("cacheScope", "public")
                            .put("ttlMs", 0)

                        "initialize" -> JSONObject()
                            .put("protocolVersion", "2025-11-25")
                            .put("capabilities", JSONObject())
                            .put(
                                "serverInfo",
                                JSONObject().put("name", "Fake Legacy MCP").put("version", "1")
                            )

                        "tools/list" -> {
                            val params = request.optJSONObject("params")
                            val cursor = if (params != null && params.has("cursor") && !params.isNull("cursor")) {
                                params.optString("cursor")
                            } else null
                            val tools = JSONArray()
                            if (paginatedTools) {
                                if (cursor == null) tools.put(tool("echo", false))
                                else tools.put(tool("second", false))
                            } else {
                                tools.put(tool("echo", false))
                                if (invalidSecondTool) tools.put(tool("invalid", true))
                            }
                            val page = JSONObject().put("tools", tools)
                            if (getRequestProperty("MCP-Protocol-Version") == McpProtocol.MODERN_VERSION) {
                                page.put("resultType", "complete")
                                    .put("ttlMs", 0)
                                    .put("cacheScope", "private")
                            }
                            if ((paginatedTools && cursor == null) || repeatCursor) {
                                page.put("nextCursor", "next")
                            }
                            page
                        }

                        "tasks/get" -> {
                            val index = taskGetCalls.getAndIncrement()
                            val taskStatus = taskGetStatuses.getOrElse(index) {
                                taskGetStatuses.lastOrNull() ?: "working"
                            }
                            JSONObject()
                                .put("resultType", "complete")
                                .put("task", taskSnapshot(taskStatus))
                        }

                        "tasks/update" -> JSONObject()
                            .put("resultType", "complete")
                            .put("task", taskSnapshot("working"))

                        "tasks/cancel" -> JSONObject()
                            .put("resultType", "complete")
                            .put("task", taskSnapshot("cancelled"))

                        "tools/call" -> {
                            toolCalls.incrementAndGet()
                            JSONObject()
                                .put("resultType", toolResultType)
                                .put(
                                    "content",
                                    JSONArray().put(
                                        JSONObject().put("type", "text").put("text", "ok")
                                    )
                                )
                                .apply {
                                    if (toolResultType == "input_required") {
                                        put(
                                            "inputRequests",
                                            JSONObject().put(
                                                "confirm",
                                                JSONObject()
                                                    .put("method", "elicitation/create")
                                                    .put("params", JSONObject().put("mode", "form"))
                                            )
                                        )
                                        put("requestState", "opaque-state-from-server")
                                    }
                                }
                        }

                        else -> JSONObject()
                    }

                    status = 200
                    responseBody = JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("id", id)
                        .put("result", result)
                        .toString()
                }
                private fun taskSnapshot(status: String): JSONObject {
                    val task = JSONObject()
                        .put("taskId", "task-1")
                        .put("status", status)
                        .put("ttlMs", 60_000)
                        .put("pollIntervalMs", 10)
                    if (status == "completed") {
                        task.put("result", JSONObject().put("content", JSONArray()))
                    }
                    if (status == "failed") {
                        task.put("error", JSONObject().put("code", -1).put("message", "failed"))
                    }
                    return task
                }

                private fun tool(name: String, invalid: Boolean): JSONObject {
                    val schema = if (invalid) {
                        JSONObject().put("type", "string")
                    } else {
                        JSONObject()
                            .put("type", "object")
                            .put(
                                "properties",
                                JSONObject().put(
                                    "text",
                                    JSONObject().put("type", "string")
                                )
                            )
                            .put("required", JSONArray().put("text"))
                    }

                    return JSONObject()
                        .put("name", name)
                        .put("description", "Test tool")
                        .put("inputSchema", schema)
                }

                override fun getResponseCode(): Int {
                    prepare()
                    return status
                }

                override fun getErrorStream(): InputStream =
                    ByteArrayInputStream(responseBody.toByteArray(Charsets.UTF_8))

                override fun getInputStream(): InputStream {
                    prepare()
                    return ByteArrayInputStream(responseBody.toByteArray(Charsets.UTF_8))
                }

                override fun getContentType(): String = responseType
                override fun getHeaderField(name: String?): String? = null
            }
    }

    private fun registerConnector() {
        ConnectorRegistry.registerConnector(
            ConnectorRegistry.Connector(
                id = "test.mcp",
                name = "Test MCP",
                transport = ConnectorRegistry.Transport.MCP_STREAMABLE_HTTP,
                endpoint = "https://example.com/mcp"
            )
        ).getOrThrow()
    }

    private fun service(server: FakeServer): McpConnectorService {
        val client = McpHttpClient(
            endpoint = "https://example.com/mcp",
            connectionFactory = server::connection
        )
        return McpConnectorService(client)
    }

    @After
    fun cleanup() {
        ConnectorRegistry.clearForTests()
    }

    @Test
    fun modernNegotiationRegistersTools() {
        registerConnector()
        val server = FakeServer()
        val result = service(server).connect("test.mcp").getOrThrow()

        assertEquals(McpProtocol.MODERN_VERSION, result.protocolVersion)
        assertEquals(1, result.discoveredTools)
        assertTrue(server.methods.contains("server/discover"))
        assertTrue(server.methods.contains("tools/list"))
    }

    @Test
    fun toolListPaginationFetchesEveryPageAndPreservesOpaqueCursor() {
        registerConnector()
        val server = FakeServer(paginatedTools = true)

        val result = service(server).connect("test.mcp").getOrThrow()

        assertEquals(2, result.discoveredTools)
        assertEquals(listOf(null, "next"), server.toolListCursors)
        assertEquals(
            setOf("echo", "second"),
            ConnectorRegistry.toolsFor("test.mcp").map { it.name }.toSet()
        )
    }

    @Test
    fun repeatedToolListCursorFailsAndRollsBackAllTools() {
        registerConnector()
        val server = FakeServer(paginatedTools = true, repeatCursor = true)

        val result = service(server).connect("test.mcp")

        assertTrue(result.isFailure)
        assertEquals(listOf(null, "next"), server.toolListCursors)
        assertTrue(ConnectorRegistry.toolsFor("test.mcp").isEmpty())
    }

    @Test
    fun inputRequiredResultIsReturnedForMrtrHandling() {
        registerConnector()
        val server = FakeServer(toolResultType = "input_required")
        val connectorService = service(server)
        connectorService.connect("test.mcp").getOrThrow()
        val tool = ConnectorRegistry.toolsFor("test.mcp").single()
        val arguments = mapOf<String, Any?>("text" to "hello")
        val grant = connectorService.issueApprovalGrant(tool.id, arguments).getOrThrow()
            ?: error("Approval Grant পাওয়া যায়নি।")

        val result = connectorService.callTool(tool.id, arguments, grant).getOrThrow()
        val payload = JSONObject(result)

        assertEquals("input_required", payload.optString("resultType"))
        assertTrue(payload.optJSONObject("inputRequests")?.has("confirm") == true)
        assertEquals("opaque-state-from-server", payload.optString("requestState"))
        assertEquals(1, server.toolCalls.get())
    }

    @Test
    fun mrtrRetryCarriesResponsesAndOpaqueStateWithoutChangingOriginalArguments() {
        registerConnector()
        val server = FakeServer(toolResultType = "input_required")
        val connectorService = service(server)
        connectorService.connect("test.mcp").getOrThrow()
        val tool = ConnectorRegistry.toolsFor("test.mcp").single()
        val arguments = mapOf<String, Any?>("text" to "hello")
        val firstGrant = connectorService.issueApprovalGrant(tool.id, arguments).getOrThrow()
            ?: error("Approval Grant পাওয়া যায়নি।")
        connectorService.callTool(tool.id, arguments, firstGrant).getOrThrow()

        val responses = mapOf<String, Any?>(
            "confirm" to mapOf("action" to "accept", "content" to mapOf("ok" to true))
        )
        val secondGrant = connectorService.issueApprovalGrant(tool.id, arguments).getOrThrow()
            ?: error("দ্বিতীয় Approval Grant পাওয়া যায়নি।")
        connectorService.callTool(
            toolId = tool.id,
            arguments = arguments,
            approvalGrant = secondGrant,
            inputResponses = responses,
            requestState = "opaque-state-from-server"
        ).getOrThrow()

        val retryParams = server.toolCallParams.last()
        assertEquals(
            "accept",
            retryParams.optJSONObject("inputResponses")
                ?.optJSONObject("confirm")
                ?.optString("action")
        )
        assertEquals("opaque-state-from-server", retryParams.optString("requestState"))
        assertEquals("hello", retryParams.optJSONObject("arguments")?.optString("text"))
        assertEquals(2, server.toolCalls.get())
    }

    @Test
    fun taskGetUpdateAndCancelUseModernTaskEndpoints() {
        registerConnector()
        val server = FakeServer()
        val connectorService = service(server)
        connectorService.connect("test.mcp").getOrThrow()

        val fetched = connectorService.getTask("test.mcp", "task-1").getOrThrow()
        assertEquals(McpTaskProtocol.Status.WORKING, fetched.status)
        val updated = connectorService.updateTask(
            "test.mcp",
            "task-1",
            mapOf("confirm" to mapOf("action" to "accept"))
        ).getOrThrow()
        assertEquals(McpTaskProtocol.Status.WORKING, updated.status)
        val cancelled = connectorService.cancelTask("test.mcp", "task-1").getOrThrow()
        assertEquals(McpTaskProtocol.Status.CANCELLED, cancelled.status)
        assertTrue(server.methods.containsAll(listOf("tasks/get", "tasks/update", "tasks/cancel")))
    }

    @Test
    fun taskOperationsRejectSnapshotForDifferentTaskId() {
        registerConnector()
        val server = FakeServer()
        val connectorService = service(server)
        connectorService.connect("test.mcp").getOrThrow()

        assertTrue(connectorService.getTask("test.mcp", "task-2").isFailure)
        assertTrue(
            connectorService.updateTask(
                "test.mcp",
                "task-2",
                mapOf("confirm" to mapOf("action" to "accept"))
            ).isFailure
        )
        assertTrue(connectorService.cancelTask("test.mcp", "task-2").isFailure)
    }

    @Test
    fun taskPollingStopsOnTerminalStateAndRespectsInjectedDelay() {
        registerConnector()
        val server = FakeServer(taskGetStatuses = listOf("working", "completed"))
        val connectorService = service(server)
        connectorService.connect("test.mcp").getOrThrow()
        val delays = mutableListOf<Long>()

        val snapshot = connectorService.pollTask(
            connectorId = "test.mcp",
            taskId = "task-1",
            maxWaitMs = 1_000,
            maxPolls = 5,
            sleeper = { delays.add(it) }
        ).getOrThrow()

        assertEquals(McpTaskProtocol.Status.COMPLETED, snapshot.status)
        assertEquals(listOf(10L), delays)
        assertEquals(2, server.taskGetCalls.get())
    }

    @Test
    fun taskPollingCountsInitialSnapshotAgainstPollLimit() {
        registerConnector()
        val server = FakeServer(taskGetStatuses = listOf("working", "completed"))
        val connectorService = service(server)
        connectorService.connect("test.mcp").getOrThrow()
        val delays = mutableListOf<Long>()

        val snapshot = connectorService.pollTask(
            connectorId = "test.mcp",
            taskId = "task-1",
            maxWaitMs = 1_000,
            maxPolls = 1,
            sleeper = { delays.add(it) }
        ).getOrThrow()

        assertEquals(McpTaskProtocol.Status.WORKING, snapshot.status)
        assertTrue(delays.isEmpty())
        assertEquals(1, server.taskGetCalls.get())
    }

    @Test
    fun taskPollingRejectsUnboundedArgumentsBeforeNetworkCalls() {
        registerConnector()
        val server = FakeServer()
        val connectorService = service(server)
        connectorService.connect("test.mcp").getOrThrow()

        assertTrue(connectorService.pollTask("test.mcp", "task-1", maxWaitMs = 300_001).isFailure)
        assertTrue(connectorService.pollTask("test.mcp", "task-1", maxPolls = 101).isFailure)
        assertEquals(0, server.taskGetCalls.get())
    }

    @Test
    fun taskOperationsRejectDisconnectedConnectorAndEmptyAnswers() {
        registerConnector()
        val server = FakeServer()
        val connectorService = service(server)

        assertTrue(connectorService.getTask("test.mcp", "task-1").isFailure)
        connectorService.connect("test.mcp").getOrThrow()
        assertTrue(connectorService.updateTask("test.mcp", "task-1", emptyMap()).isFailure)
        connectorService.disconnect("test.mcp").getOrThrow()
        assertTrue(connectorService.cancelTask("test.mcp", "task-1").isFailure)
    }

    @Test
    fun modernMethodNotFoundDoesNotTriggerLegacyFallback() {
        registerConnector()
        val server = FakeServer(discoveryErrorCode = -32601)

        val failure = service(server).connect("test.mcp")

        assertTrue("Modern method-not-found must remain a connection error", failure.isFailure)
        assertEquals(listOf("server/discover"), server.methods)
        assertFalse("Do not send legacy initialize after modern -32601", server.methods.contains("initialize"))
    }

    @Test
    fun discoveryAdvertisedLegacyVersionUsesInitializeHandshake() {
        registerConnector()
        val server = FakeServer(discoveryVersions = listOf("2025-11-25"))

        val result = service(server).connect("test.mcp").getOrThrow()

        assertEquals("2025-11-25", result.protocolVersion)
        assertTrue(server.methods.contains("initialize"))
        assertTrue(server.methods.contains("notifications/initialized"))
        assertTrue(server.methods.contains("tools/list"))
    }

    @Test
    fun unsupportedProtocolVersionErrorSelectsSupportedLegacyVersion() {
        registerConnector()
        val server = FakeServer(
            versionErrorSupported = listOf("2025-11-25")
        )

        val result = service(server).connect("test.mcp").getOrThrow()

        assertEquals("2025-11-25", result.protocolVersion)
        assertTrue(server.methods.contains("initialize"))
        assertTrue(server.methods.contains("notifications/initialized"))
        assertTrue(server.methods.contains("tools/list"))
    }

    @Test
    fun legacyFallbackNegotiatesAndRegistersTools() {
        registerConnector()
        val server = FakeServer(legacy = true)
        val result = service(server).connect("test.mcp").getOrThrow()

        assertEquals("2025-11-25", result.protocolVersion)
        assertEquals(1, result.discoveredTools)
        assertTrue(server.methods.contains("server/discover"))
        assertTrue(server.methods.contains("initialize"))
        assertTrue(server.methods.contains("notifications/initialized"))
        assertTrue(server.methods.contains("tools/list"))
    }

    @Test
    fun failedToolRegistrationRollsBackPartialTools() {
        registerConnector()
        val server = FakeServer(invalidSecondTool = true)

        val failure = service(server).connect("test.mcp")

        assertTrue("Expected connection failure", failure.isFailure)
        assertTrue(ConnectorRegistry.toolsFor("test.mcp").isEmpty())
    }

    @Test
    fun writeToolRequiresApprovalAndDisconnectRemovesTools() {
        registerConnector()
        val server = FakeServer()
        val service = service(server)
        val connection = service.connect("test.mcp").getOrThrow()
        val tool = ConnectorRegistry.toolsFor("test.mcp").single()

        val denied = service.callTool(
            tool.id,
            mapOf("text" to "hello")
        )

        assertTrue("WRITE tool must require explicit approval", denied.isFailure)
        assertEquals(0, server.toolCalls.get())

        val approvedArguments = mapOf("text" to "hello")
        val grant = service.issueApprovalGrant(
            tool.id,
            approvedArguments
        ).getOrThrow() ?: error("WRITE Tool-এর Grant পাওয়া যায়নি।")

        val alteredCall = service.callTool(
            tool.id,
            mapOf("text" to "tampered"),
            approvalGrant = grant
        )
        assertTrue("Grant must reject changed arguments", alteredCall.isFailure)
        assertEquals(0, server.toolCalls.get())

        val approvedResult = service.callTool(
            tool.id,
            approvedArguments,
            approvalGrant = service.issueApprovalGrant(
                tool.id,
                approvedArguments
            ).getOrThrow()
        ).getOrThrow()

        assertTrue(approvedResult.contains("ok"))
            assertEquals(1, server.toolCalls.get())

            assertEquals(true, server.toolCallMetadata.last())
            val headers = server.toolCallHeaders.last()
            assertEquals(McpProtocol.MODERN_VERSION, headers["protocol"])
            assertEquals("tools/call", headers["method"])
            assertEquals("echo", headers["name"])

        service.disconnect("test.mcp")
        assertTrue(ConnectorRegistry.toolsFor("test.mcp").isEmpty())

        val staleCall = service.callTool(
            tool.id,
            mapOf("text" to "again")
        )
        assertTrue("Disconnected tool must not execute", staleCall.isFailure)
        assertEquals(1, server.toolCalls.get())
    }

    @Test
    fun disconnectReconnectRevokesOldGrantAndAllowsFreshGrant() {
        registerConnector()
        val server = FakeServer()
        val connectorService = service(server)

        connectorService.connect("test.mcp").getOrThrow()
        val firstTool = ConnectorRegistry.toolsFor("test.mcp").single()
        assertEquals("echo", firstTool.name)

        val oldArguments = mapOf("text" to "before reconnect")
        val oldGrant = connectorService.issueApprovalGrant(
            firstTool.id,
            oldArguments
        ).getOrThrow() ?: error("পুরোনো Grant পাওয়া যায়নি।")

        connectorService.disconnect("test.mcp").getOrThrow()
        assertTrue(ConnectorRegistry.toolsFor("test.mcp").isEmpty())

        val reconnected = connectorService.connect("test.mcp").getOrThrow()
        assertEquals(McpProtocol.MODERN_VERSION, reconnected.protocolVersion)

        val freshTools = ConnectorRegistry.toolsFor("test.mcp")
        assertEquals(1, freshTools.size)
        assertEquals("echo", freshTools.single().name)

        val staleCall = connectorService.callTool(
            freshTools.single().id,
            oldArguments,
            approvalGrant = oldGrant
        )
        assertTrue("Old grant must be rejected after reconnect", staleCall.isFailure)
        assertEquals(0, server.toolCalls.get())

        val reconnectArguments = mapOf("text" to "reconnected")
        val reconnectGrant = connectorService.issueApprovalGrant(
            freshTools.single().id,
            reconnectArguments
        ).getOrThrow() ?: error("Reconnect Tool-এর নতুন Grant পাওয়া যায়নি।")

        val result = connectorService.callTool(
            freshTools.single().id,
            reconnectArguments,
            approvalGrant = reconnectGrant
        ).getOrThrow()

        assertTrue(result.contains("ok"))
        assertEquals(1, server.toolCalls.get())
        assertEquals(2, server.methods.count { it == "server/discover" })
        assertEquals(2, server.methods.count { it == "tools/list" })
    }

    @Test
    fun invalidArgumentsAreRejectedBeforeGrantOrRemoteCall() {
        registerConnector()
        val server = FakeServer()
        val service = service(server)
        service.connect("test.mcp").getOrThrow()
        val tool = ConnectorRegistry.toolsFor("test.mcp").single()

        val missingArguments = emptyMap<String, Any?>()
        val invalidTypeArguments = mapOf<String, Any?>("text" to 123)

        assertTrue(
            "Missing required input must not receive a grant",
            service.issueApprovalGrant(tool.id, missingArguments).isFailure
        )
        assertTrue(
            "Invalid input type must not receive a grant",
            service.issueApprovalGrant(tool.id, invalidTypeArguments).isFailure
        )
        assertTrue(
            "Missing required input must not execute",
            service.callTool(tool.id, missingArguments).isFailure
        )
        assertTrue(
            "Invalid input type must not execute",
            service.callTool(tool.id, invalidTypeArguments).isFailure
        )
        assertEquals("Invalid arguments must never reach the server", 0, server.toolCalls.get())
    }

}
