package org.mosharrof.androidcontrol

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/**
 * MCP connection lifecycle and explicitly approved tool execution.
 */
class McpConnectorService(
    private val client: McpHttpClient,
    private val clientName: String = "MOSHARROF Android Control",
    private val clientVersion: String = "1.0",
    private val taskStore: McpTaskStore = InMemoryMcpTaskStore()
) {
    data class ConnectionInfo(
        val connectorId: String,
        val protocolVersion: String,
        val serverInfo: Map<String, Any?>,
        val discoveredTools: Int
    )

    private data class NegotiatedConnection(
        val version: String,
        val serverInfo: Map<String, Any?>
    )

    private val connected = linkedSetOf<String>()

    /** Connector IDs whose MCP sessions are owned by this service instance. */
    @Synchronized
    fun connectedConnectorIds(): Set<String> = connected.toSet()

    private companion object {
        const val MAX_TOOL_LIST_PAGES = 100
        const val MAX_DISCOVERED_TOOLS = 5000
        const val MAX_JSON_CONVERSION_DEPTH = 32
        const val MAX_MRTR_REQUEST_STATE_CHARS = 64 * 1024
    }

    @Synchronized
    fun connect(connectorId: String): Result<ConnectionInfo> = runCatching {
        val connector = ConnectorRegistry.connectorSnapshot()
            .firstOrNull { it.id == connectorId }
            ?: error("নিবন্ধিত Connector পাওয়া যায়নি।")

        require(connector.transport == ConnectorRegistry.Transport.MCP_STREAMABLE_HTTP) {
            "এই Connector MCP Streamable HTTP নয়।"
        }

        // A reconnect must invalidate previous tools and approval grants.
        ToolApprovalGrants.revokeConnector(connectorId)
        connected.remove(connectorId)
        // Retain task IDs as untrusted recovery candidates. Call tasks/get after
        // reconnect before presenting or acting on any recovered task.
        ConnectorRegistry.unregisterToolsForConnector(connectorId)

        try {
            val negotiated = negotiateProtocol()
            val version = negotiated.version

            var registered = 0
            var cursor: String? = null
            var pageCount = 0
            val seenCursors = mutableSetOf<String>()

            while (true) {
                require(pageCount < MAX_TOOL_LIST_PAGES) {
                    "MCP tools/list পৃষ্ঠা সীমা অতিক্রম করেছে।"
                }
                val listRequest = if (version == McpProtocol.MODERN_VERSION) {
                    McpProtocol.modernToolsListRequest(
                        id = UUID.randomUUID().toString(),
                        clientName = clientName,
                        clientVersion = clientVersion,
                        cursor = cursor
                    )
                } else {
                    McpProtocol.toolsListRequest(UUID.randomUUID().toString(), cursor)
                }

                val listResponse = client.post(listRequest)
                val listResult = listResponse.optJSONObject("result")
                    ?: error(
                        listResponse.optJSONObject("error")?.optString("message")
                            ?: "MCP tools/list ব্যর্থ হয়েছে।"
                    )

                if (version == McpProtocol.MODERN_VERSION) {
                    require(listResult.optString("resultType") == "complete") {
                        "Modern MCP tools/list resultType complete নয়।"
                    }
                    validateModernCacheHints(listResult, "tools/list")
                }

                val toolsArray = listResult.optJSONArray("tools")
                    ?: error("MCP tools/list response-এ tools array অনুপস্থিত।")

                for (index in 0 until toolsArray.length()) {
                    require(registered < MAX_DISCOVERED_TOOLS) {
                        "MCP Tool সংখ্যা নির্ধারিত সীমা অতিক্রম করেছে।"
                    }
                    val item = toolsArray.optJSONObject(index)
                        ?: error("MCP tools/list-এর Tool entry অবজেক্ট নয়।")
                    val name = item.optString("name").trim()
                    require(name.isNotBlank()) { "MCP Tool name অনুপস্থিত।" }

                    val description = item.optString("description", name)
                    val schemaObject = item.optJSONObject("inputSchema")
                        ?: error("MCP Tool inputSchema অনুপস্থিত: $name")

                    val inputSchema = schemaObject.toMap()
                    require(inputSchema["type"] == "object" && McpArgumentsValidator.supportsSchema(inputSchema)) {
                        "MCP Tool inputSchema-এ অসমর্থিত বা অবৈধ JSON Schema keyword আছে: $name"
                    }
                    val required = schemaObject.optJSONArray("required").toStringSet()

                    val safePart = name
                        .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                        .take(24)
                        .ifBlank { "tool" }

                    val nameHash = sha256(name).take(12)
                    val toolId = "${connectorId.take(40)}.$safePart.$nameHash"

                    val tool = ConnectorRegistry.Tool(
                        id = toolId,
                        connectorId = connectorId,
                        name = name,
                        description = description,
                        risk = ConnectorRegistry.Risk.WRITE,
                        requiredFields = required,
                        inputSchema = inputSchema
                    )

                    // A partial registration must fail the whole connection.
                    ConnectorRegistry.registerTool(tool).getOrThrow()
                    registered++
                }

                pageCount++
                val nextCursor = when {
                    !listResult.has("nextCursor") || listResult.isNull("nextCursor") -> null
                    else -> listResult.opt("nextCursor") as? String
                        ?: error("MCP tools/list nextCursor string নয়।")
                }
                if (nextCursor == null) break
                require(seenCursors.add(nextCursor)) {
                    "MCP tools/list একই nextCursor পুনরায় দিয়েছে; pagination থামানো হয়েছে।"
                }
                require(pageCount < MAX_TOOL_LIST_PAGES) {
                    "MCP tools/list পৃষ্ঠা সীমা অতিক্রম করেছে।"
                }
                cursor = nextCursor
            }

            ConnectorRegistry.updateConnectionState(
                connectorId,
                ConnectorRegistry.ConnectionState.CONNECTED
            ).getOrThrow()

            connected.add(connectorId)

            ConnectionInfo(
                connectorId = connectorId,
                protocolVersion = version,
                serverInfo = negotiated.serverInfo,
                discoveredTools = registered
            )
        } catch (failure: Throwable) {
            ToolApprovalGrants.revokeConnector(connectorId)
            connected.remove(connectorId)
            ConnectorRegistry.unregisterToolsForConnector(connectorId)
            runCatching {
                ConnectorRegistry.updateConnectionState(
                    connectorId,
                    ConnectorRegistry.ConnectionState.DISCONNECTED
                )
            }
            throw failure
        }
    }

    private fun negotiateProtocol(): NegotiatedConnection {
        client.selectProtocolVersion(McpProtocol.MODERN_VERSION)

        val discoveryResponse = try {
            client.post(
                McpProtocol.modernDiscoveryRequest(
                    id = UUID.randomUUID().toString(),
                    clientName = clientName,
                    clientVersion = clientVersion
                )
            )
        } catch (failure: McpHttpException) {
            // -32601 is a modern JSON-RPC Method Not Found response, not a legacy signal.
            val modernErrorCodes = setOf(-32601, -32020, -32021, -32022)
            if (
                failure.statusCode in setOf(400, 404) &&
                failure.rpcErrorCode !in modernErrorCodes
            ) {
                null
            } else if (failure.rpcErrorCode == -32022) {
                val supported = failure.rpcErrorData?.optJSONArray("supported")
                    ?: error("MCP -32022 error-এ data.supported অনুপস্থিত।")
                val serverVersions = (0 until supported.length())
                    .map { supported.optString(it) }
                    .toSet()
                val legacyVersion = listOf(
                    "2025-11-25", "2025-06-18", "2025-03-26"
                ).firstOrNull { it in serverVersions }
                    ?: error(
                        "MCP server-এর supported তালিকায় পরিচিত Legacy version নেই: " +
                            serverVersions.joinToString()
                    )
                return negotiateLegacyProtocol(legacyVersion)
            } else {
                throw failure
            }
        }

        val discoveryError = discoveryResponse?.optJSONObject("error")
        if (discoveryError != null) {
            error(
                "MCP server/discover JSON-RPC error " +
                    "${discoveryError.optInt("code")}: " +
                    discoveryError.optString("message")
            )
        }

        if (discoveryResponse != null) {
            val result = discoveryResponse.optJSONObject("result")
                ?: error(
                    discoveryError?.optString("message")
                        ?: "MCP server/discover response-এ result পাওয়া যায়নি।"
                )

            require(result.optString("resultType") == "complete") {
                "Modern MCP discovery resultType complete নয়।"
            }

            val supportedVersions = result.optJSONArray("supportedVersions")
                ?: error("Modern MCP discovery-এ supportedVersions অনুপস্থিত।")
            val serverVersions = (0 until supportedVersions.length())
                .map { supportedVersions.optString(it) }
                .toSet()
            if (McpProtocol.MODERN_VERSION !in serverVersions) {
                val legacyVersion = listOf(
                    "2025-11-25", "2025-06-18", "2025-03-26"
                ).firstOrNull { it in serverVersions }
                    ?: error(
                        "MCP server-এর supported তালিকায় পরিচিত protocol version নেই: " +
                            serverVersions.joinToString()
                    )
                return negotiateLegacyProtocol(legacyVersion)
            }

            require(result.optJSONObject("capabilities") != null) {
                "Modern MCP discovery-এ capabilities অনুপস্থিত।"
            }
            validateModernCacheHints(result, "server/discover")

            val serverInfo = result.optJSONObject("_meta")
                ?.optJSONObject("io.modelcontextprotocol/serverInfo")
                ?.toMap() ?: emptyMap()

            return NegotiatedConnection(
                version = McpProtocol.MODERN_VERSION,
                serverInfo = serverInfo
            )
        }

        return negotiateLegacyProtocol()
    }

    private fun validateModernCacheHints(result: JSONObject, operation: String) {
        require(result.optString("cacheScope") in setOf("public", "private")) {
            "Modern MCP $operation cacheScope অবৈধ বা অনুপস্থিত।"
        }
        val ttlValue = result.opt("ttlMs") as? Number
            ?: error("Modern MCP $operation ttlMs অবৈধ বা অনুপস্থিত।")
        val ttlMillis = runCatching {
            java.math.BigDecimal(ttlValue.toString()).longValueExact()
        }.getOrNull()
        require(ttlMillis != null && ttlMillis >= 0) {
            "Modern MCP $operation ttlMs অবশ্যই non-negative integer হতে হবে।"
        }
    }

    private fun negotiateLegacyProtocol(
        requestedVersion: String = "2025-11-25"
    ): NegotiatedConnection {
        require(requestedVersion in setOf("2025-03-26", "2025-06-18", "2025-11-25")) {
            "অসমর্থিত Legacy MCP protocol version."
        }
        client.selectProtocolVersion(requestedVersion)

        val response = client.post(
            McpProtocol.initializeRequest(
                id = UUID.randomUUID().toString(),
                clientName = clientName,
                clientVersion = clientVersion,
                protocolVersion = requestedVersion
            )
        )

        val result = response.optJSONObject("result")
            ?: error(
                response.optJSONObject("error")?.optString("message")
                    ?: "Legacy MCP initialize ব্যর্থ হয়েছে।"
            )

        val negotiatedVersion = result.optString("protocolVersion")
        require(
            negotiatedVersion in setOf(
                "2025-03-26",
                "2025-06-18",
                "2025-11-25"
            )
        ) {
            "Server অসমর্থিত Legacy MCP protocol version দিয়েছে।"
        }

        client.selectProtocolVersion(negotiatedVersion)
        client.post(McpProtocol.initializedNotification())

        return NegotiatedConnection(
            version = negotiatedVersion,
            serverInfo = result.optJSONObject("serverInfo")?.toMap()
                ?: emptyMap()
        )
    }

    /**
     * Called only after the user confirms the approval dialog.
     * READ_ONLY tools do not need a grant.
     */
    @Synchronized
    fun issueApprovalGrant(
        toolId: String,
        arguments: Map<String, Any?>
    ): Result<String?> = runCatching {
        val tool = ConnectorRegistry.toolSnapshot()
            .firstOrNull { it.id == toolId }
            ?: error("নিবন্ধিত Tool পাওয়া যায়নি।")

        require(tool.connectorId in connected) {
            "Connector এই Service session-এ সংযুক্ত নয়।"
        }

        require(McpArgumentsValidator.validate(tool.inputSchema, arguments, tool.requiredFields)) {
            "Tool input schema অনুযায়ী arguments অবৈধ অথবা required value অনুপস্থিত।"
        }
        val missing = tool.requiredFields.filterNot(arguments::containsKey)
        require(missing.isEmpty()) {
            "প্রয়োজনীয় input অনুপস্থিত: ${missing.joinToString(", ")}"
        }

        when (ConnectorRegistry.authorize(toolId)) {
            ConnectorRegistry.Decision.ALLOW_READ_ONLY -> null
            ConnectorRegistry.Decision.APPROVAL_REQUIRED ->
                ToolApprovalGrants.issue(tool.connectorId, toolId, arguments)
            ConnectorRegistry.Decision.DENY_CONNECTOR_UNAVAILABLE ->
                error("Connector সংযুক্ত নেই।")
            else -> error("নিরাপত্তা নীতিতে Approval Grant দেওয়া হয়নি।")
        }
    }

    /**
     * Non-read-only tools require a matching, unexpired, single-use grant.
     */
    @Synchronized
    fun callTool(
        toolId: String,
        arguments: Map<String, Any?>,
        approvalGrant: String? = null,
        inputResponses: Map<String, Any?>? = null,
        requestState: String? = null
    ): Result<String> = runCatching {
        val tool = ConnectorRegistry.toolSnapshot()
            .firstOrNull { it.id == toolId }
            ?: error("নিবন্ধিত Tool পাওয়া যায়নি।")

        require(tool.connectorId in connected) {
            "Connector এই Service session-এ সংযুক্ত নয়।"
        }

        require(McpArgumentsValidator.validate(tool.inputSchema, arguments, tool.requiredFields)) {
            "Tool input schema অনুযায়ী arguments অবৈধ অথবা required value অনুপস্থিত।"
        }
        val missing = tool.requiredFields.filterNot(arguments::containsKey)
        require(missing.isEmpty()) {
            "প্রয়োজনীয় input অনুপস্থিত: ${missing.joinToString(", ")}"
        }

        when (ConnectorRegistry.authorize(toolId)) {
            ConnectorRegistry.Decision.ALLOW_READ_ONLY -> Unit

            ConnectorRegistry.Decision.APPROVAL_REQUIRED -> {
                val token = approvalGrant
                    ?: error("এই Tool চালাতে একবারের Approval Grant প্রয়োজন।")

                require(
                    ToolApprovalGrants.consume(
                        token = token,
                        connectorId = tool.connectorId,
                        toolId = toolId,
                        arguments = arguments
                    )
                ) {
                    "Approval Grant অবৈধ, মেয়াদোত্তীর্ণ, ব্যবহৃত অথবা এই Input-এর জন্য নয়।"
                }
            }

            ConnectorRegistry.Decision.DENY_CONNECTOR_UNAVAILABLE ->
                error("Connector সংযুক্ত নেই।")

            else -> error("নিরাপত্তা নীতিতে Tool call অনুমোদিত হয়নি।")
        }

        val definition = McpProtocol.ToolDefinition(
            name = tool.name,
            description = tool.description,
            inputSchema = tool.inputSchema
        )

        val modernProtocol = clientProtocolVersion() == McpProtocol.MODERN_VERSION
        require(modernProtocol || (inputResponses == null && requestState == null)) {
            "MCP MRTR fields শুধু আধুনিক protocol-এ ব্যবহার করা যাবে।"
        }
        val request = if (modernProtocol) {
            McpProtocol.modernToolsCallRequest(
                id = UUID.randomUUID().toString(),
                toolName = tool.name,
                arguments = arguments,
                clientName = clientName,
                clientVersion = clientVersion,
                inputResponses = inputResponses,
                requestState = requestState
            )
        } else {
            McpProtocol.toolsCallRequest(
                id = UUID.randomUUID().toString(),
                toolName = tool.name,
                arguments = arguments
            )
        }

        val response = client.post(request, definition)

        if (response.has("error")) {
            val rpcError = response.optJSONObject("error")
            error(rpcError?.optString("message") ?: "MCP Tool call ব্যর্থ হয়েছে।")
        }

        val result = response.optJSONObject("result")
            ?: error("MCP Tool response-এ result পাওয়া যায়নি।")
        if (modernProtocol) {
            val resultType = result.optString("resultType")
            when (resultType) {
                "complete" -> Unit
                "input_required" -> {
                    val inputRequests = result.optJSONObject("inputRequests")
                        ?: error("MCP input_required response-এ inputRequests অনুপস্থিত।")
                    require(McpProtocol.validateInputRequests(inputRequests.toMap())) {
                        "MCP inputRequests-এর method, key বা params অবৈধ।"
                    }
                    if (result.has("requestState")) {
                        val state = result.opt("requestState")
                        require(state is String && state.length <= MAX_MRTR_REQUEST_STATE_CHARS) {
                            "MCP requestState string হতে হবে এবং নির্ধারিত আকারের মধ্যে থাকতে হবে।"
                        }
                    }
                }
                "task" ->
                    error("MCP Tasks extension এখনো সমর্থিত নয়; Tool execution থামানো হয়েছে।")
                else -> error("অসমর্থিত MCP resultType: $resultType")
            }
        }
        result.toString()
    }

    /**
     * Fetch one MCP Tasks snapshot. This is intentionally explicit and is not
     * advertised as a client capability until UI approval and durable recovery exist.
     */
    @Synchronized
    fun getTask(connectorId: String, taskId: String): Result<McpTaskProtocol.Snapshot> = runCatching {
        requireModernTaskConnection(connectorId)
        val response = client.post(
            McpProtocol.modernTasksGetRequest(
                id = UUID.randomUUID().toString(),
                taskId = taskId,
                clientName = clientName,
                clientVersion = clientVersion
            )
        )
        parseTaskResponse(connectorId, response, taskId)
    }

    /** Submit user-mediated MRTR answers for a task awaiting input. */
    @Synchronized
    fun updateTask(
        connectorId: String,
        taskId: String,
        inputResponses: Map<String, Any?>
    ): Result<McpTaskProtocol.Snapshot> = runCatching {
        requireModernTaskConnection(connectorId)
        require(inputResponses.isNotEmpty() && inputResponses.size <= 16) {
            "Task inputResponses খালি অথবা অতিরিক্ত বড়।"
        }
        val response = client.post(
            McpProtocol.modernTasksUpdateRequest(
                id = UUID.randomUUID().toString(),
                taskId = taskId,
                inputResponses = inputResponses,
                clientName = clientName,
                clientVersion = clientVersion
            )
        )
        parseTaskResponse(connectorId, response, taskId)
    }

    @Synchronized
    fun cancelTask(connectorId: String, taskId: String): Result<McpTaskProtocol.Snapshot> = runCatching {
        requireModernTaskConnection(connectorId)
        val response = client.post(
            McpProtocol.modernTasksCancelRequest(
                id = UUID.randomUUID().toString(),
                taskId = taskId,
                clientName = clientName,
                clientVersion = clientVersion
            )
        )
        parseTaskResponse(connectorId, response, taskId)
    }

    /** Pending task identifiers restored from durable local metadata after process restart. */
    @Synchronized
    fun pendingTasks(connectorId: String): Result<List<McpTaskRecord>> = runCatching {
        requireModernTaskConnection(connectorId)
        taskStore.list(connectorId)
    }

    /**
     * Bounded synchronous polling for callers already running off the UI thread.
     * It stops at terminal status, input_required, poll limit, or elapsed-time limit.
     */
    fun pollTask(
        connectorId: String,
        taskId: String,
        maxWaitMs: Long = 60_000L,
        maxPolls: Int = 20,
        sleeper: (Long) -> Unit = { Thread.sleep(it) }
    ): Result<McpTaskProtocol.Snapshot> = runCatching {
        require(maxWaitMs in 1L..300_000L) { "Task polling সময়সীমা ১–৩০০ সেকেন্ড হতে হবে।" }
        require(maxPolls in 1..100) { "Task polling সংখ্যা ১–১০০ হতে হবে।" }
        val startedAt = System.nanoTime()
        var snapshot = getTask(connectorId, taskId).getOrThrow()
        var polls = 1
        while (!snapshot.isTerminal &&
            snapshot.status != McpTaskProtocol.Status.INPUT_REQUIRED &&
            polls < maxPolls
        ) {
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            val remainingMs = maxWaitMs - elapsedMs
            if (remainingMs <= 0L) break
            val delayMs = minOf(snapshot.pollIntervalMs, remainingMs, 30_000L)
            sleeper(delayMs)
            if ((System.nanoTime() - startedAt) / 1_000_000L >= maxWaitMs) break
            snapshot = getTask(connectorId, taskId).getOrThrow()
            polls++
        }
        snapshot
    }

    private fun requireModernTaskConnection(connectorId: String) {
        require(connectorId in connected) { "Connector এই Service session-এ সংযুক্ত নয়।" }
        require(clientProtocolVersion() == McpProtocol.MODERN_VERSION) {
            "MCP Tasks শুধু আধুনিক protocol connection-এ ব্যবহার করা যাবে।"
        }
    }

    private fun parseTaskResponse(
        connectorId: String,
        response: JSONObject,
        expectedTaskId: String
    ): McpTaskProtocol.Snapshot {
        if (response.has("error")) {
            val rpcError = response.optJSONObject("error")
            error(rpcError?.optString("message") ?: "MCP Task operation ব্যর্থ হয়েছে।")
        }
        val result = response.optJSONObject("result")
            ?: error("MCP Task response-এ result অনুপস্থিত।")
        val task = result.optJSONObject("task") ?: result
        val snapshot = McpTaskProtocol.parseSnapshot(task.toMap())
            ?: error("MCP Task snapshot-এর status, TTL, poll interval অথবা payload অবৈধ।")
        require(snapshot.taskId == expectedTaskId) {
            "MCP Task response-এর taskId অনুরোধের সঙ্গে মেলেনি।"
        }
        taskStore.upsert(
            McpTaskRecord(
                connectorId = connectorId,
                taskId = snapshot.taskId,
                status = snapshot.status.name,
                ttlMs = snapshot.ttlMs,
                pollIntervalMs = snapshot.pollIntervalMs,
                observedAtEpochMs = System.currentTimeMillis()
            )
        )
        return snapshot
    }

    private fun clientProtocolVersion(): String {
        // The selected protocol is exposed by the client implementation.
        return client.selectedProtocolVersion()
    }

    @Synchronized
    fun disconnect(connectorId: String): Result<Unit> = runCatching {
        ToolApprovalGrants.revokeConnector(connectorId)
        connected.remove(connectorId)
        taskStore.removeConnector(connectorId)
        ConnectorRegistry.unregisterToolsForConnector(connectorId)

        ConnectorRegistry.updateConnectionState(
            connectorId,
            ConnectorRegistry.ConnectionState.DISCONNECTED
        ).getOrThrow()
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun JSONObject.toMap(depth: Int = 0): Map<String, Any?> {
        require(depth <= MAX_JSON_CONVERSION_DEPTH) {
            "MCP JSON object nesting limit অতিক্রম করেছে।"
        }
        val result = linkedMapOf<String, Any?>()
        val iterator = keys()

        while (iterator.hasNext()) {
            val key = iterator.next()
            val value = opt(key)

            result[key] = when (value) {
                is JSONObject -> value.toMap(depth + 1)
                is JSONArray -> value.toValueList(depth + 1)
                JSONObject.NULL -> null
                else -> value
            }
        }

        return result
    }

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()

        return buildSet {
            for (index in 0 until length()) {
                optString(index)
                    .takeIf { it.isNotBlank() }
                    ?.let(::add)
            }
        }
    }

    private fun JSONArray.toValueList(depth: Int): List<Any?> {
        require(depth <= MAX_JSON_CONVERSION_DEPTH) {
            "MCP JSON array nesting limit অতিক্রম করেছে।"
        }
        return (0 until length()).map { index ->
            when (val value = opt(index)) {
                is JSONObject -> value.toMap(depth + 1)
                is JSONArray -> value.toValueList(depth + 1)
                JSONObject.NULL -> null
                else -> value
            }
        }
    }
}
