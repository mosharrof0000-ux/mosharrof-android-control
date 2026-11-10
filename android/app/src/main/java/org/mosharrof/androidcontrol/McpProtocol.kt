package org.mosharrof.androidcontrol

/**
 * MCP JSON-RPC message models and safe request builders.
 * Network transport and JSON encoding are intentionally separate.
 */
object McpProtocol {
    data class Request(
        val jsonrpc: String = "2.0",
        val id: String? = null,
        val method: String,
        val params: Map<String, Any?> = emptyMap()
    )

    data class RpcError(
        val code: Int,
        val message: String,
        val data: Any? = null
    )

    data class Response(
        val jsonrpc: String = "2.0",
        val id: String,
        val result: Map<String, Any?>? = null,
        val error: RpcError? = null
    )

    data class ToolDefinition(
        val name: String,
        val description: String = "",
        val inputSchema: Map<String, Any?>
    )

    fun initializeRequest(
        id: String,
        clientName: String,
        clientVersion: String,
        protocolVersion: String
    ): Request {
        require(id.isNotBlank())
        require(clientName.isNotBlank())
        require(clientVersion.isNotBlank())
        require(protocolVersion.isNotBlank())
        return Request(
            id = id,
            method = "initialize",
            params = mapOf(
                "protocolVersion" to protocolVersion,
                "capabilities" to emptyMap<String, Any>(),
                "clientInfo" to mapOf(
                    "name" to clientName,
                    "version" to clientVersion
                )
            )
        )
    }

    const val MODERN_VERSION = "2026-07-28"

    private fun modernMetadata(clientName: String, clientVersion: String): Map<String, Any?> {
        require(clientName.isNotBlank())
        require(clientVersion.isNotBlank())
        return mapOf(
            "io.modelcontextprotocol/protocolVersion" to MODERN_VERSION,
            "io.modelcontextprotocol/clientInfo" to mapOf(
                "name" to clientName,
                "version" to clientVersion
            ),
            "io.modelcontextprotocol/clientCapabilities" to emptyMap<String, Any>()
        )
    }

    fun modernDiscoveryRequest(
        id: String,
        clientName: String,
        clientVersion: String
    ): Request {
        require(id.isNotBlank())
        return Request(
            id = id,
            method = "server/discover",
            params = mapOf("_meta" to modernMetadata(clientName, clientVersion))
        )
    }

    fun modernToolsListRequest(
        id: String,
        clientName: String,
        clientVersion: String
    ): Request {
        require(id.isNotBlank())
        return Request(
            id = id,
            method = "tools/list",
            params = mapOf("_meta" to modernMetadata(clientName, clientVersion))
        )
    }

    fun modernToolsCallRequest(
        id: String,
        toolName: String,
        arguments: Map<String, Any?> = emptyMap(),
        clientName: String,
        clientVersion: String
    ): Request {
        require(id.isNotBlank())
        require(isSafeName(toolName))
        return Request(
            id = id,
            method = "tools/call",
            params = mapOf(
                "name" to toolName,
                "arguments" to arguments,
                "_meta" to modernMetadata(clientName, clientVersion)
            )
        )
    }

    fun initializedNotification(): Request =
        Request(id = null, method = "notifications/initialized")

    fun toolsListRequest(id: String): Request {
        require(id.isNotBlank())
        return Request(id = id, method = "tools/list")
    }

    fun toolsCallRequest(
        id: String,
        toolName: String,
        arguments: Map<String, Any?> = emptyMap()
    ): Request {
        require(id.isNotBlank())
        require(isSafeName(toolName))
        return Request(
            id = id,
            method = "tools/call",
            params = mapOf("name" to toolName, "arguments" to arguments)
        )
    }

    fun validateResponse(response: Response, expectedId: String): Boolean {
        if (response.jsonrpc != "2.0" || response.id != expectedId) return false
        if ((response.result == null) == (response.error == null)) return false
        return true
    }

    fun validateInputSchema(schema: Map<String, Any?>): Boolean {
        if (schema["type"] != "object") return false

        val usedHeaders = mutableSetOf<String>()

        fun containsHeaderAnnotation(value: Any?): Boolean = when (value) {
            is Map<*, *> ->
                value.containsKey("x-mcp-header") ||
                    value.values.any(::containsHeaderAnnotation)
            is Iterable<*> -> value.any(::containsHeaderAnnotation)
            is Array<*> -> value.any(::containsHeaderAnnotation)
            else -> false
        }

        fun visitObject(current: Map<*, *>): Boolean {
            for ((key, value) in current) {
                if (key != "properties" && containsHeaderAnnotation(value)) {
                    return false
                }
            }

            val rawProperties = current["properties"]
            if (rawProperties == null) return true
            val properties = rawProperties as? Map<*, *> ?: return false

            for ((_, rawProperty) in properties) {
                val property = rawProperty as? Map<*, *> ?: return false

                for ((key, value) in property) {
                    if (key != "properties" &&
                        key != "x-mcp-header" &&
                        containsHeaderAnnotation(value)
                    ) return false
                }

                if (property.containsKey("x-mcp-header")) {
                    val header = property["x-mcp-header"] as? String
                        ?: return false
                    if (!Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+").matches(header)) {
                        return false
                    }

                    val type = property["type"] as? String ?: return false
                    if (type !in setOf("string", "integer", "boolean")) {
                        return false
                    }
                    if (!usedHeaders.add(header.lowercase(java.util.Locale.ROOT))) {
                        return false
                    }
                }

                if (property.containsKey("properties") &&
                    property["type"] != "object"
                ) return false

                if (property["type"] == "object" &&
                    !visitObject(property)
                ) return false
            }
            return true
        }

        return !schema.containsKey("x-mcp-header") && visitObject(schema)
    }

    fun validateTool(tool: ToolDefinition): Boolean {
        if (!isSafeName(tool.name)) return false
        return validateInputSchema(tool.inputSchema)
    }

    private fun isSafeName(value: String): Boolean =
        value.length in 2..128 &&
            value.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._:-]*"))
}
