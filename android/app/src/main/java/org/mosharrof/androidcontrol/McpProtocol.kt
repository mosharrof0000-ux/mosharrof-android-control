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
        clientVersion: String,
        cursor: String? = null
    ): Request {
        require(id.isNotBlank())
        val params = linkedMapOf<String, Any?>(
            "_meta" to modernMetadata(clientName, clientVersion)
        )
        if (cursor != null) params["cursor"] = cursor
        return Request(id = id, method = "tools/list", params = params)
    }

    fun modernToolsCallRequest(
        id: String,
        toolName: String,
        arguments: Map<String, Any?> = emptyMap(),
        clientName: String,
        clientVersion: String,
        inputResponses: Map<String, Any?>? = null,
        requestState: String? = null
    ): Request {
        require(id.isNotBlank())
        require(isSafeName(toolName))
        require(inputResponses == null || inputResponses.keys.all { it.isNotBlank() }) {
            "MRTR inputResponses-এর key খালি হতে পারবে না।"
        }
        val params = linkedMapOf<String, Any?>(
            "name" to toolName,
            "arguments" to arguments,
            "_meta" to modernMetadata(clientName, clientVersion)
        )
        // requestState is opaque server-owned state: preserve it byte-for-byte.
        if (inputResponses != null) params["inputResponses"] = inputResponses
        if (requestState != null) params["requestState"] = requestState
        return Request(id = id, method = "tools/call", params = params)
    }

    fun initializedNotification(): Request =
        Request(id = null, method = "notifications/initialized")

    fun toolsListRequest(id: String, cursor: String? = null): Request {
        require(id.isNotBlank())
        val params = if (cursor == null) emptyMap() else mapOf("cursor" to cursor)
        return Request(id = id, method = "tools/list", params = params)
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

    /**
     * Validates the bounded server-to-client requests carried by a modern MRTR result.
     * Their params remain opaque data for the matching handler/UI.
     */
    fun validateInputRequests(inputRequests: Map<String, Any?>): Boolean {
        if (inputRequests.isEmpty() || inputRequests.size > 16) return false
        val supportedMethods = setOf(
            "elicitation/create",
            "sampling/createMessage",
            "roots/list"
        )
        return inputRequests.all { (key, rawRequest) ->
            if (key.isBlank() || key.length > 128) return@all false
            val request = rawRequest as? Map<*, *> ?: return@all false
            val method = request["method"] as? String ?: return@all false
            method in supportedMethods && request["params"] is Map<*, *>
        }
    }

    fun validateInputSchema(schema: Map<String, Any?>): Boolean {
        if (schema["type"] != "object") return false

        val usedHeaders = mutableSetOf<String>()
        var visitedSchemas = 0
        val maxSchemaDepth = 32
        val maxSchemaNodes = 2048
        val supportedKeywords = setOf(
            "\$schema", "\$id", "title", "description", "default", "examples",
            "deprecated", "readOnly", "writeOnly", "format",
            "type", "const", "enum", "properties", "required", "additionalProperties",
            "minProperties", "maxProperties", "minLength", "maxLength", "pattern",
            "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf",
            "minItems", "maxItems", "items", "x-mcp-header"
        )
        val supportedTypes = setOf(
            "object", "array", "string", "boolean", "integer", "number", "null"
        )

        fun schemaNumber(value: Any?): java.math.BigDecimal? {
            if (value !is Number) return null
            return runCatching { java.math.BigDecimal(value.toString()) }.getOrNull()
        }

        fun validNonNegativeInteger(value: Any?): Boolean {
            val number = schemaNumber(value) ?: return false
            return number.stripTrailingZeros().scale() <= 0 &&
                number >= java.math.BigDecimal.ZERO &&
                number <= java.math.BigDecimal.valueOf(Int.MAX_VALUE.toLong())
        }

        fun visitSchema(
            current: Map<*, *>,
            depth: Int,
            allowHeaderHere: Boolean,
            allowHeadersInProperties: Boolean
        ): Boolean {
            if (depth > maxSchemaDepth || ++visitedSchemas > maxSchemaNodes) return false
            if (current.keys.any { it !is String || it !in supportedKeywords }) return false

            for (key in listOf("\$schema", "\$id", "title", "description", "format")) {
                if (current.containsKey(key) && current[key] !is String) return false
            }
            for (key in listOf("deprecated", "readOnly", "writeOnly")) {
                if (current.containsKey(key) && current[key] !is Boolean) return false
            }
            if (current.containsKey("examples") && current["examples"] !is List<*>) return false

            val types = when (val declared = current["type"]) {
                null -> emptyList()
                is String -> listOf(declared)
                is List<*> -> {
                    if (declared.isEmpty() || declared.any { it !is String }) return false
                    val names = declared.filterIsInstance<String>()
                    if (names.size != names.toSet().size) return false
                    names
                }
                else -> return false
            }
            if (types.any { it !in supportedTypes }) return false

            if (current.containsKey("enum")) {
                val values = current["enum"] as? List<*> ?: return false
                if (values.isEmpty()) return false
            }

            val properties = if (current.containsKey("properties")) {
                current["properties"] as? Map<*, *> ?: return false
            } else null
            if (properties?.keys?.any { it !is String } == true) return false
            if (properties != null && current["type"] != "object") return false

            if (current.containsKey("required")) {
                val required = current["required"] as? List<*> ?: return false
                if (required.any { it !is String || it.isBlank() }) return false
                if (required.size != required.toSet().size) return false
                if (properties != null && required.any { it !in properties.keys }) return false
            }

            for (key in listOf(
                "minProperties", "maxProperties", "minLength", "maxLength", "minItems", "maxItems"
            )) {
                if (current.containsKey(key) && !validNonNegativeInteger(current[key])) return false
            }
            val minMaxPairs = listOf(
                "minProperties" to "maxProperties",
                "minLength" to "maxLength",
                "minItems" to "maxItems"
            )
            for ((minKey, maxKey) in minMaxPairs) {
                if (current.containsKey(minKey) && current.containsKey(maxKey)) {
                    val min = schemaNumber(current[minKey]) ?: return false
                    val max = schemaNumber(current[maxKey]) ?: return false
                    if (min > max) return false
                }
            }

            for (key in listOf("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum")) {
                if (current.containsKey(key) && schemaNumber(current[key]) == null) return false
            }
            if (current.containsKey("multipleOf")) {
                val divisor = schemaNumber(current["multipleOf"]) ?: return false
                if (divisor <= java.math.BigDecimal.ZERO) return false
            }
            if (current.containsKey("pattern")) {
                val pattern = current["pattern"] as? String ?: return false
                if (runCatching { Regex(pattern) }.isFailure) return false
            }

            if (current.containsKey("x-mcp-header")) {
                if (!allowHeaderHere) return false
                val header = current["x-mcp-header"] as? String ?: return false
                if (!Regex("[!#$%&'*+.^_\\x60|~0-9A-Za-z-]+").matches(header)) return false
                val type = current["type"] as? String ?: return false
                if (type !in setOf("string", "integer", "boolean")) return false
                if (!usedHeaders.add(header.lowercase(java.util.Locale.ROOT))) return false
            }

            if (properties != null) {
                for ((_, rawChild) in properties) {
                    val child = rawChild as? Map<*, *> ?: return false
                    if (!visitSchema(
                        child,
                        depth + 1,
                        allowHeadersInProperties,
                        allowHeadersInProperties
                    )) return false
                }
            }

            if (current.containsKey("items")) {
                val items = current["items"] as? Map<*, *> ?: return false
                if (!visitSchema(items, depth + 1, false, false)) return false
            }

            if (current.containsKey("additionalProperties")) {
                when (val additional = current["additionalProperties"]) {
                    is Boolean -> Unit
                    is Map<*, *> -> if (!visitSchema(additional, depth + 1, false, false)) return false
                    else -> return false
                }
            }

            return true
        }

        return visitSchema(schema, 0, false, true)
    }
    fun validateTool(tool: ToolDefinition): Boolean {
        if (!isSafeName(tool.name)) return false
        return validateInputSchema(tool.inputSchema)
    }

    private fun isSafeName(value: String): Boolean =
        value.length in 2..128 &&
            value.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._:-]*"))
}
