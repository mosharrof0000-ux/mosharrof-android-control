package org.mosharrof.androidcontrol

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.CancellationException

/**
 * MCP connection lifecycle and explicitly approved tool execution.
 */
class McpConnectorService(
    private val client: McpHttpClient,
    private val clientName: String = "MOSHARROF Android Control",
    private val clientVersion: String = "1.0",
    private val taskStore: McpTaskStore = InMemoryMcpTaskStore(),
    private val oauthMetadataFetcher: McpOAuthMetadataFetcher = McpOAuthMetadataFetcher()
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
    private val connecting = mutableSetOf<String>()

    /** Connector IDs whose MCP sessions are owned by this service instance. */
    @Synchronized
    fun connectedConnectorIds(): Set<String> = connected.toSet()

    /**
     * Discovers and validates OAuth protected-resource and authorization-server
     * metadata for a registered MCP endpoint. This is metadata-only: it does not
     * start authorization, attach credentials, or change connector state.
     */
    fun discoverOAuthMetadata(connectorId: String): Result<McpOAuthMetadataDiscovery> = runCatching {
        val connector = ConnectorRegistry.connectorSnapshot()
            .firstOrNull { it.id == connectorId }
            ?: error("নিবন্ধিত Connector পাওয়া যায়নি।")
        require(connector.transport == ConnectorRegistry.Transport.MCP_STREAMABLE_HTTP) {
            "এই Connector MCP Streamable HTTP নয়।"
        }
        oauthMetadataFetcher.discover(connector.endpoint)
            ?: error("OAuth metadata যাচাই করা যায়নি।")
    }

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


    /**
     * OAuth-safe connection lifecycle. Every discovery, initialize,
     * initialized notification, and tools/list page resolves its own token.
     * Tools are staged until every page has validated successfully.
     */
    internal fun connectAsync(
        connectorId: String,
        tokenProvider: McpAccessTokenResolver,
        ioExecutor: Executor,
        onComplete: (Result<ConnectionInfo>) -> Unit
    ) {
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finish(result: Result<ConnectionInfo>) {
            if (completed.compareAndSet(false, true)) {
                synchronized(this) { connecting.remove(connectorId) }
                onComplete(result)
            }
        }

        val connector = ConnectorRegistry.connectorSnapshot()
            .firstOrNull { it.id == connectorId }
        if (connector == null) {
            finish(Result.failure(IllegalArgumentException("নিবন্ধিত Connector পাওয়া যায়নি।")))
            return
        }
        if (connector.transport != ConnectorRegistry.Transport.MCP_STREAMABLE_HTTP) {
            finish(Result.failure(IllegalArgumentException("এই Connector MCP Streamable HTTP নয়।")))
            return
        }

        val alreadyConnecting = synchronized(this) {
            !connecting.add(connectorId)
        }
        if (alreadyConnecting) {
            finish(Result.failure(IllegalStateException("এই Connector-এর connection negotiation ইতিমধ্যে চলছে।")))
            return
        }
        synchronized(this) {
            connected.remove(connectorId)
            ToolApprovalGrants.revokeConnector(connectorId)
            ConnectorRegistry.unregisterToolsForConnector(connectorId)
        }

        val stagedTools = mutableListOf<ConnectorRegistry.Tool>()
        val seenNames = mutableSetOf<String>()
        val seenCursors = mutableSetOf<String>()
        var pageCount = 0
        var protocolVersion = McpProtocol.MODERN_VERSION
        var serverInfo: Map<String, Any?> = emptyMap()

        fun fail(failure: Throwable) {
            synchronized(this) {
                ToolApprovalGrants.revokeConnector(connectorId)
                connected.remove(connectorId)
                ConnectorRegistry.unregisterToolsForConnector(connectorId)
                runCatching {
                    ConnectorRegistry.updateConnectionState(
                        connectorId,
                        ConnectorRegistry.ConnectionState.DISCONNECTED
                    )
                }
            }
            finish(Result.failure(failure))
        }

        fun request(
            value: McpProtocol.Request,
            next: (Result<JSONObject>) -> Unit
        ) {
            client.postAsync(
                connectorId = connectorId,
                tokenProvider = tokenProvider,
                ioExecutor = ioExecutor,
                request = value
            ) { result -> next(result) }
        }

        fun completeConnection() {
            try {
                stagedTools.forEach { ConnectorRegistry.registerTool(it).getOrThrow() }
                ConnectorRegistry.updateConnectionState(
                    connectorId,
                    ConnectorRegistry.ConnectionState.CONNECTED
                ).getOrThrow()
                synchronized(this) { connected.add(connectorId) }
                finish(Result.success(ConnectionInfo(
                    connectorId = connectorId,
                    protocolVersion = protocolVersion,
                    serverInfo = serverInfo,
                    discoveredTools = stagedTools.size
                )))
            } catch (failure: Throwable) {
                fail(failure)
            }
        }

        lateinit var fetchPage: (String?) -> Unit
        fetchPage = page@{ cursor ->
            if (pageCount >= MAX_TOOL_LIST_PAGES) {
                fail(IllegalStateException("MCP tools/list পৃষ্ঠা সীমা অতিক্রম করেছে।"))
                return@page
            }
            val listRequest = if (protocolVersion == McpProtocol.MODERN_VERSION) {
                McpProtocol.modernToolsListRequest(
                    id = UUID.randomUUID().toString(),
                    clientName = clientName,
                    clientVersion = clientVersion,
                    cursor = cursor
                )
            } else {
                McpProtocol.toolsListRequest(UUID.randomUUID().toString(), cursor)
            }
            request(listRequest) { responseResult ->
                responseResult.fold(
                    onFailure = ::fail,
                    onSuccess = { response ->
                        try {
                            val listResult = response.optJSONObject("result")
                                ?: error(response.optJSONObject("error")?.optString("message")
                                    ?: "MCP tools/list ব্যর্থ হয়েছে।")
                            if (protocolVersion == McpProtocol.MODERN_VERSION) {
                                require(listResult.optString("resultType") == "complete") {
                                    "Modern MCP tools/list resultType complete নয়।"
                                }
                                validateModernCacheHints(listResult, "tools/list")
                            }
                            val toolsArray = listResult.optJSONArray("tools")
                                ?: error("MCP tools/list response-এ tools array অনুপস্থিত।")
                            for (index in 0 until toolsArray.length()) {
                                require(stagedTools.size < MAX_DISCOVERED_TOOLS) {
                                    "MCP Tool সংখ্যা নির্ধারিত সীমা অতিক্রম করেছে।"
                                }
                                val item = toolsArray.optJSONObject(index)
                                    ?: error("MCP tools/list-এর Tool entry অবজেক্ট নয়।")
                                val name = item.optString("name").trim()
                                require(name.isNotBlank() && seenNames.add(name)) {
                                    "MCP Tool name অনুপস্থিত অথবা duplicate।"
                                }
                                val description = item.optString("description", name)
                                val schemaObject = item.optJSONObject("inputSchema")
                                    ?: error("MCP Tool inputSchema অনুপস্থিত: $name")
                                val inputSchema = schemaObject.toMap()
                                require(inputSchema["type"] == "object" &&
                                    McpArgumentsValidator.supportsSchema(inputSchema)) {
                                    "MCP Tool inputSchema-এ অসমর্থিত বা অবৈধ JSON Schema keyword আছে: $name"
                                }
                                val required = schemaObject.optJSONArray("required").toStringSet()
                                val safePart = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                                    .take(24).ifBlank { "tool" }
                                val toolId = "${connectorId.take(40)}.$safePart.${sha256(name).take(12)}"
                                stagedTools += ConnectorRegistry.Tool(
                                    id = toolId,
                                    connectorId = connectorId,
                                    name = name,
                                    description = description,
                                    risk = ConnectorRegistry.Risk.WRITE,
                                    requiredFields = required,
                                    inputSchema = inputSchema
                                )
                            }
                            pageCount++
                            val nextCursor = when {
                                !listResult.has("nextCursor") || listResult.isNull("nextCursor") -> null
                                else -> listResult.opt("nextCursor") as? String
                                    ?: error("MCP tools/list nextCursor string নয়।")
                            }
                            if (nextCursor == null) {
                                completeConnection()
                            } else {
                                require(nextCursor.isNotBlank() && nextCursor.length <= 4096 &&
                                    seenCursors.add(nextCursor)) {
                                    "MCP tools/list cursor ফাঁকা, অতিরিক্ত বড় অথবা পুনরাবৃত্ত; pagination থামানো হয়েছে।"
                                }
                                require(pageCount < MAX_TOOL_LIST_PAGES) {
                                    "MCP tools/list পৃষ্ঠা সীমা অতিক্রম করেছে।"
                                }
                                fetchPage(nextCursor)
                            }
                        } catch (failure: Throwable) {
                            fail(failure)
                        }
                    }
                )
            }
        }

        fun beginLegacy(requestedVersion: String = "2025-11-25") {
            try {
                require(requestedVersion in setOf("2025-03-26", "2025-06-18", "2025-11-25")) {
                    "অসমর্থিত Legacy MCP protocol version."
                }
                protocolVersion = requestedVersion
                client.selectProtocolVersion(requestedVersion)
                request(McpProtocol.initializeRequest(
                    id = UUID.randomUUID().toString(),
                    clientName = clientName,
                    clientVersion = clientVersion,
                    protocolVersion = requestedVersion
                )) { initResult ->
                    initResult.fold(
                        onFailure = ::fail,
                        onSuccess = { response ->
                            try {
                                val result = response.optJSONObject("result")
                                    ?: error(response.optJSONObject("error")?.optString("message")
                                        ?: "Legacy MCP initialize ব্যর্থ হয়েছে।")
                                val negotiated = result.optString("protocolVersion")
                                require(negotiated in setOf("2025-03-26", "2025-06-18", "2025-11-25")) {
                                    "Server অসমর্থিত Legacy MCP protocol version দিয়েছে।"
                                }
                                protocolVersion = negotiated
                                client.selectProtocolVersion(negotiated)
                                serverInfo = result.optJSONObject("serverInfo")?.toMap() ?: emptyMap()
                                request(McpProtocol.initializedNotification()) { notificationResult ->
                                    notificationResult.fold(
                                        onFailure = ::fail,
                                        onSuccess = { fetchPage(null) }
                                    )
                                }
                            } catch (failure: Throwable) {
                                fail(failure)
                            }
                        }
                    )
                }
            } catch (failure: Throwable) {
                fail(failure)
            }
        }

        client.selectProtocolVersion(McpProtocol.MODERN_VERSION)
        request(McpProtocol.modernDiscoveryRequest(
            id = UUID.randomUUID().toString(),
            clientName = clientName,
            clientVersion = clientVersion
        )) { discoveryResult ->
            discoveryResult.fold(
                onFailure = { failure ->
                    val http = failure as? McpHttpException
                    val modernErrorCodes = setOf(-32601, -32020, -32021, -32022)
                    when {
                        http?.rpcErrorCode == -32022 -> {
                            val supported = http.rpcErrorData?.optJSONArray("supported")
                            val versions = supported?.let { array ->
                                (0 until array.length()).map { array.optString(it) }.toSet()
                            }.orEmpty()
                            val legacy = listOf("2025-11-25", "2025-06-18", "2025-03-26")
                                .firstOrNull { it in versions }
                            if (legacy == null) fail(IllegalStateException(
                                "MCP server-এর supported তালিকায় পরিচিত Legacy version নেই।"
                            )) else beginLegacy(legacy)
                        }
                        http?.statusCode in setOf(400, 404) &&
                            http?.rpcErrorCode !in modernErrorCodes -> beginLegacy()
                        else -> fail(failure)
                    }
                },
                onSuccess = { response ->
                    try {
                        val result = response.optJSONObject("result")
                            ?: error(response.optJSONObject("error")?.optString("message")
                                ?: "MCP server/discover response-এ result পাওয়া যায়নি।")
                        require(result.optString("resultType") == "complete") {
                            "Modern MCP discovery resultType complete নয়।"
                        }
                        val versions = result.optJSONArray("supportedVersions")
                            ?: error("Modern MCP discovery-এ supportedVersions অনুপস্থিত।")
                        val supported = (0 until versions.length()).map { versions.optString(it) }.toSet()
                        if (McpProtocol.MODERN_VERSION !in supported) {
                            val legacy = listOf("2025-11-25", "2025-06-18", "2025-03-26")
                                .firstOrNull { it in supported }
                                ?: error("MCP server-এর supported তালিকায় পরিচিত protocol version নেই।")
                            beginLegacy(legacy)
                            return@onSuccess
                        }
                        require(result.optJSONObject("capabilities") != null) {
                            "Modern MCP discovery-এ capabilities অনুপস্থিত।"
                        }
                        validateModernCacheHints(result, "server/discover")
                        protocolVersion = McpProtocol.MODERN_VERSION
                        serverInfo = result.optJSONObject("_meta")
                            ?.optJSONObject("io.modelcontextprotocol/serverInfo")
                            ?.toMap() ?: emptyMap()
                        fetchPage(null)
                    } catch (failure: Throwable) {
                        fail(failure)
                    }
                }
            )
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
                "task" -> {
                    val handle = McpTaskProtocol.parseHandle(result.toMap())
                        ?: error("MCP Task handle-এর taskId, status, TTL অথবা poll interval অবৈধ।")
                    taskStore.upsert(
                        McpTaskRecord(
                            connectorId = tool.connectorId,
                            taskId = handle.taskId,
                            status = handle.status.name,
                            ttlMs = handle.ttlMs,
                            pollIntervalMs = handle.pollIntervalMs,
                            observedAtEpochMs = System.currentTimeMillis()
                        )
                    )
                }
                else -> error("অসমর্থিত MCP resultType: $resultType")
            }
        }
        result.toString()
    }

    /**
     * OAuth-aware tool execution. All policy checks and the single-use approval
     * grant are consumed before the asynchronous transport is started.
     */
    @Synchronized
    internal fun callToolAsync(
        toolId: String,
        arguments: Map<String, Any?>,
        tokenProvider: McpAccessTokenResolver,
        ioExecutor: Executor,
        approvalGrant: String? = null,
        inputResponses: Map<String, Any?>? = null,
        requestState: String? = null,
        onComplete: (Result<String>) -> Unit
    ) {
        val prepared = runCatching {
            val tool = ConnectorRegistry.toolSnapshot()
                .firstOrNull { it.id == toolId }
                ?: error("নিবন্ধিত Tool পাওয়া যায়নি।")

            require(tool.connectorId in connected) {
                "Connector এই Service session-এ সংযুক্ত নয়।"
            }
            require(McpArgumentsValidator.validate(tool.inputSchema, arguments, tool.requiredFields)) {
                "Tool input schema অনুযায়ী arguments অবৈধ অথবা required value অনুপস্থিত।"
            }
            require(tool.requiredFields.all(arguments::containsKey)) {
                "প্রয়োজনীয় input অনুপস্থিত।"
            }

            when (ConnectorRegistry.authorize(toolId)) {
                ConnectorRegistry.Decision.ALLOW_READ_ONLY -> Unit
                ConnectorRegistry.Decision.APPROVAL_REQUIRED -> {
                    val token = approvalGrant
                        ?: error("এই Tool চালাতে একবারের Approval Grant প্রয়োজন।")
                    require(ToolApprovalGrants.consume(
                        token = token,
                        connectorId = tool.connectorId,
                        toolId = toolId,
                        arguments = arguments
                    )) {
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
            Triple(tool, definition, Pair(request, modernProtocol))
        }

        val (tool, definition, requestInfo) = prepared.getOrElse {
            onComplete(Result.failure(it))
            return
        }
        val (request, modernProtocol) = requestInfo

        client.postAsync(
            connectorId = tool.connectorId,
            tokenProvider = tokenProvider,
            ioExecutor = ioExecutor,
            request = request,
            toolDefinition = definition
        ) { responseResult ->
            val result = responseResult.mapCatching { response ->
                if (response.has("error")) {
                    val rpcError = response.optJSONObject("error")
                    error(rpcError?.optString("message") ?: "MCP Tool call ব্যর্থ হয়েছে।")
                }
                val resultObject = response.optJSONObject("result")
                    ?: error("MCP Tool response-এ result পাওয়া যায়নি।")
                if (modernProtocol) {
                    when (val resultType = resultObject.optString("resultType")) {
                        "complete" -> Unit
                        "input_required" -> {
                            val inputRequests = resultObject.optJSONObject("inputRequests")
                                ?: error("MCP input_required response-এ inputRequests অনুপস্থিত।")
                            require(McpProtocol.validateInputRequests(inputRequests.toMap())) {
                                "MCP inputRequests-এর method, key বা params অবৈধ।"
                            }
                            if (resultObject.has("requestState")) {
                                val state = resultObject.opt("requestState")
                                require(state is String && state.length <= MAX_MRTR_REQUEST_STATE_CHARS) {
                                    "MCP requestState string হতে হবে এবং নির্ধারিত আকারের মধ্যে থাকতে হবে।"
                                }
                            }
                        }
                        "task" -> {
                            val handle = McpTaskProtocol.parseHandle(resultObject.toMap())
                                ?: error("MCP Task handle-এর taskId, status, TTL অথবা poll interval অবৈধ।")
                            taskStore.upsert(
                                McpTaskRecord(
                                    connectorId = tool.connectorId,
                                    taskId = handle.taskId,
                                    status = handle.status.name,
                                    ttlMs = handle.ttlMs,
                                    pollIntervalMs = handle.pollIntervalMs,
                                    observedAtEpochMs = System.currentTimeMillis()
                                )
                            )
                        }
                        else -> error("অসমর্থিত MCP resultType: $resultType")
                    }
                }
                resultObject.toString()
            }
            onComplete(result)
        }
    }

    /** Fetch one MCP Tasks snapshot after a modern session has been negotiated. */
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
        require(McpTaskProtocol.validateInputResponsePayloads(inputResponses)) {
            "Task inputResponses-এর প্রতিটি request key-এর জন্য non-empty object payload প্রয়োজন।"
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

    /** OAuth-aware task operations; each request resolves a fresh bearer token. */
    @Synchronized
    internal fun getTaskAsync(
        connectorId: String,
        taskId: String,
        tokenProvider: McpAccessTokenResolver,
        ioExecutor: Executor,
        onComplete: (Result<McpTaskProtocol.Snapshot>) -> Unit
    ) {
        val request = runCatching {
            requireModernTaskConnection(connectorId)
            McpProtocol.modernTasksGetRequest(
                id = UUID.randomUUID().toString(),
                taskId = taskId,
                clientName = clientName,
                clientVersion = clientVersion
            )
        }.getOrElse {
            onComplete(Result.failure(it))
            return
        }
        postTaskRequestAsync(connectorId, taskId, request, tokenProvider, ioExecutor, onComplete)
    }

    @Synchronized
    internal fun updateTaskAsync(
        connectorId: String,
        taskId: String,
        inputResponses: Map<String, Any?>,
        tokenProvider: McpAccessTokenResolver,
        ioExecutor: Executor,
        onComplete: (Result<McpTaskProtocol.Snapshot>) -> Unit
    ) {
        val request = runCatching {
            requireModernTaskConnection(connectorId)
            require(McpTaskProtocol.validateInputResponsePayloads(inputResponses)) {
                "Task inputResponses-এর প্রতিটি request key-এর জন্য non-empty object payload প্রয়োজন।"
            }
            McpProtocol.modernTasksUpdateRequest(
                id = UUID.randomUUID().toString(),
                taskId = taskId,
                inputResponses = inputResponses,
                clientName = clientName,
                clientVersion = clientVersion
            )
        }.getOrElse {
            onComplete(Result.failure(it))
            return
        }
        postTaskRequestAsync(connectorId, taskId, request, tokenProvider, ioExecutor, onComplete)
    }

    @Synchronized
    internal fun cancelTaskAsync(
        connectorId: String,
        taskId: String,
        tokenProvider: McpAccessTokenResolver,
        ioExecutor: Executor,
        onComplete: (Result<McpTaskProtocol.Snapshot>) -> Unit
    ) {
        val request = runCatching {
            requireModernTaskConnection(connectorId)
            McpProtocol.modernTasksCancelRequest(
                id = UUID.randomUUID().toString(),
                taskId = taskId,
                clientName = clientName,
                clientVersion = clientVersion
            )
        }.getOrElse {
            onComplete(Result.failure(it))
            return
        }
        postTaskRequestAsync(connectorId, taskId, request, tokenProvider, ioExecutor, onComplete)
    }

    private fun postTaskRequestAsync(
        connectorId: String,
        taskId: String,
        request: McpProtocol.Request,
        tokenProvider: McpAccessTokenResolver,
        ioExecutor: Executor,
        onComplete: (Result<McpTaskProtocol.Snapshot>) -> Unit
    ) {
        client.postAsync(
            connectorId = connectorId,
            tokenProvider = tokenProvider,
            ioExecutor = ioExecutor,
            request = request
        ) { responseResult ->
            onComplete(responseResult.mapCatching { response ->
                parseTaskResponse(connectorId, response, taskId)
            })
        }
    }

    /** Pending task identifiers restored from durable local metadata after process restart. */
    @Synchronized
    fun pendingTasks(connectorId: String): Result<List<McpTaskRecord>> = runCatching {
        requireModernTaskConnection(connectorId)
        taskStore.list(connectorId)
    }

    /**
     * Revalidate every restored pending task against the connected server.
     * This performs network I/O and must be called off the UI thread.
     * A single stale/unknown task fails the refresh closed rather than trusting local state.
     */
    @Synchronized
    fun refreshPendingTasks(connectorId: String): Result<List<McpTaskProtocol.Snapshot>> = runCatching {
        requireModernTaskConnection(connectorId)
        val candidates = taskStore.list(connectorId)
        require(candidates.size <= 100) { "সংরক্ষিত MCP Task সংখ্যা সীমা অতিক্রম করেছে।" }
        candidates.map { candidate ->
            getTask(connectorId, candidate.taskId).getOrThrow()
        }
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
        sleeper: (Long) -> Unit = { Thread.sleep(it) },
        shouldCancel: () -> Boolean = { false },
        clockNanos: () -> Long = { System.nanoTime() }
    ): Result<McpTaskProtocol.Snapshot> = runCatching {
        require(maxWaitMs in 1L..300_000L) { "Task polling সময়সীমা ১–৩০০ সেকেন্ড হতে হবে।" }
        require(maxPolls in 1..100) { "Task polling সংখ্যা ১–১০০ হতে হবে।" }
        if (shouldCancel()) throw CancellationException("MCP Task polling বাতিল করা হয়েছে।")
        val startedAt = clockNanos()
        var snapshot = getTask(connectorId, taskId).getOrThrow()
        var polls = 1
        while (!snapshot.isTerminal &&
            snapshot.status != McpTaskProtocol.Status.INPUT_REQUIRED &&
            polls < maxPolls
        ) {
            val elapsedMs = (clockNanos() - startedAt) / 1_000_000L
            val remainingMs = maxWaitMs - elapsedMs
            if (remainingMs <= 0L) break
            if (shouldCancel()) throw CancellationException("MCP Task polling বাতিল করা হয়েছে।")
            // Never poll earlier than the server-requested interval. If the remaining
            // caller budget is shorter, wait only that budget and stop without another request.
            val delayMs = minOf(snapshot.pollIntervalMs, remainingMs)
            sleeper(delayMs)
            if (shouldCancel()) throw CancellationException("MCP Task polling বাতিল করা হয়েছে।")
            if ((clockNanos() - startedAt) / 1_000_000L >= maxWaitMs) break
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
