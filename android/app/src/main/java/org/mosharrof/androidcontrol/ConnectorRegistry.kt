package org.mosharrof.androidcontrol

import java.net.URI

/**
 * Safe registry for external software connectors and their tools.
 * This registry does not execute network requests or store credentials.
 */
object ConnectorRegistry {

    enum class Transport {
        HTTPS_API,
        MCP_STREAMABLE_HTTP
    }

    enum class Risk {
        READ_ONLY,
        WRITE,
        EXTERNAL_SIDE_EFFECT,
        DESTRUCTIVE
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONFIGURED,
        CONNECTED,
        DISABLED
    }

    data class Connector(
        val id: String,
        val name: String,
        val transport: Transport,
        val endpoint: String,
        val state: ConnectionState = ConnectionState.DISCONNECTED
    )

    data class Tool(
        val id: String,
        val connectorId: String,
        val name: String,
        val description: String,
        val risk: Risk,
        val requiredFields: Set<String> = emptySet(),
        val inputSchema: Map<String, Any?> = mapOf("type" to "object")
    )

    enum class Decision {
        ALLOW_READ_ONLY,
        APPROVAL_REQUIRED,
        APPROVED,
        DENY_NOT_REGISTERED,
        DENY_CONNECTOR_UNAVAILABLE,
        DENY_APPROVAL_MISSING
    }

    private val connectors = linkedMapOf<String, Connector>()
    private val tools = linkedMapOf<String, Tool>()

    private val safeId = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{1,79}")

    private fun validateId(id: String, label: String) {
        require(safeId.matches(id)) { "$label ID-এর বিন্যাস সঠিক নয়।" }
    }

    private fun validateEndpoint(endpoint: String) {
        val uri = runCatching { URI(endpoint.trim()) }.getOrElse {
            throw IllegalArgumentException("Connector endpoint সঠিক নয়।")
        }
        require(
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.fragment == null
        ) {
            "Connector-এর জন্য নিরাপদ HTTPS endpoint প্রয়োজন।"
        }
    }

    @Synchronized
    fun registerConnector(connector: Connector): Result<Unit> = runCatching {
        validateId(connector.id, "Connector")
        require(connector.name.isNotBlank()) { "Connector-এর নাম খালি।" }
        validateEndpoint(connector.endpoint)
        require(connector.id !in connectors) {
            "এই Connector আগে থেকেই নিবন্ধিত: ${connector.id}"
        }
        connectors[connector.id] = connector.copy(
            endpoint = connector.endpoint.trim()
        )
    }

    private fun deepCopySchemaValue(value: Any?): Any? =
        when (value) {
            is Map<*, *> -> LinkedHashMap<String, Any?>().also { copy ->
                value.forEach { (key, nested) ->
                    require(key is String) {
                        "Schema object-এর key অবশ্যই String হতে হবে।"
                    }
                    copy[key] = deepCopySchemaValue(nested)
                }
            }
            is List<*> -> value.map(::deepCopySchemaValue)
            null, is String, is Number, is Boolean -> value
            else -> throw IllegalArgumentException(
                "Schema-তে অসমর্থিত value type: ${value::class.java.name}"
            )
        }

    private fun defensiveToolCopy(tool: Tool): Tool =
        tool.copy(
            requiredFields = tool.requiredFields.toSet(),
            inputSchema = deepCopySchemaValue(tool.inputSchema)
                as Map<String, Any?>
        )

    private fun validateRequiredSchema(tool: Tool) {
        val declaredRequired = when (val raw = tool.inputSchema["required"]) {
            null -> emptySet()
            is List<*> -> {
                require(raw.all { it is String && it.isNotBlank() }) {
                    "Schema-এর required তালিকায় অবৈধ field আছে।"
                }
                val names = raw.filterIsInstance<String>()
                require(names.size == names.toSet().size) {
                    "Schema-এর required তালিকায় duplicate field আছে।"
                }
                names.toSet()
            }
            else -> throw IllegalArgumentException(
                "Schema-এর required অবশ্যই একটি array হতে হবে।"
            )
        }

        require(declaredRequired == tool.requiredFields) {
            "requiredFields ও inputSchema.required-এর মধ্যে অমিল আছে।"
        }

        if (declaredRequired.isNotEmpty()) {
            val properties = tool.inputSchema["properties"]
            require(properties is Map<*, *> &&
                declaredRequired.all { it in properties.keys }) {
                "প্রতিটি required field properties-এ ঘোষণা করতে হবে।"
            }
        }
    }

    @Synchronized
    fun registerTool(tool: Tool): Result<Unit> = runCatching {
        validateId(tool.id, "Tool")
        validateId(tool.connectorId, "Connector")
        require(tool.name.isNotBlank()) { "Tool-এর নাম খালি।" }
        require(tool.description.isNotBlank()) { "Tool-এর বিবরণ খালি।" }
        require(tool.requiredFields.none { it.isBlank() }) {
            "Tool-এর input field খালি হতে পারবে না।"
        }
        val safeTool = defensiveToolCopy(tool)
        validateRequiredSchema(safeTool)
        require(McpProtocol.validateInputSchema(safeTool.inputSchema)) {
            "Tool-এর inputSchema অথবা x-mcp-header annotation অবৈধ।"
        }
        require(tool.connectorId in connectors) {
            "Tool নিবন্ধনের আগে Connector নিবন্ধন করতে হবে।"
        }
        require(tool.id !in tools) { "এই Tool আগে থেকেই নিবন্ধিত: ${tool.id}" }
        tools[tool.id] = safeTool
    }

    @Synchronized
    fun unregisterToolsForConnector(connectorId: String): Int {
        require(connectorId.isNotBlank()) { "Connector ID খালি হতে পারবে না।" }

        val toolIds = tools.values
            .filter { it.connectorId == connectorId }
            .map { it.id }

        toolIds.forEach { tools.remove(it) }
        return toolIds.size
    }

    @Synchronized
    fun updateConnectionState(
        connectorId: String,
        state: ConnectionState
    ): Result<Unit> = runCatching {
        val current = requireNotNull(connectors[connectorId]) {
            "Connector পাওয়া যায়নি: $connectorId"
        }
        connectors[connectorId] = current.copy(state = state)
    }

    @Synchronized
    fun connectorSnapshot(): List<Connector> = connectors.values.toList()

    @Synchronized
    fun toolSnapshot(): List<Tool> =
        tools.values.map(::defensiveToolCopy)

    @Synchronized
    fun toolsFor(connectorId: String): List<Tool> =
        tools.values
            .filter { it.connectorId == connectorId }
            .map(::defensiveToolCopy)

    /**
     * Checks whether a tool may proceed.
     * This is a policy gate, not an execution function.
     */
    @Synchronized
    fun authorize(toolId: String): Decision {
        val tool = tools[toolId] ?: return Decision.DENY_NOT_REGISTERED
        val connector = connectors[tool.connectorId]
            ?: return Decision.DENY_NOT_REGISTERED

        if (connector.state != ConnectionState.CONNECTED) {
            return Decision.DENY_CONNECTOR_UNAVAILABLE
        }

        return if (tool.risk == Risk.READ_ONLY) {
            Decision.ALLOW_READ_ONLY
        } else {
            Decision.APPROVAL_REQUIRED
        }
    }

    @Synchronized
    fun clearForTests() {
        tools.clear()
        connectors.clear()
    }
}
