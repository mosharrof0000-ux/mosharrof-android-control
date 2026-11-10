package org.mosharrof.androidcontrol

/**
 * Provider-neutral contract for translating AI-native tool APIs into MOSHARROF's
 * canonical plugin call model. Adapters must never execute tools themselves.
 */
enum class AiProviderId {
    GEMINI, OPENAI, XAI, ANTHROPIC, MICROSOFT, CUSTOM
}

enum class CapabilitySupport { SUPPORTED, UNSUPPORTED, UNKNOWN }

enum class AiCapability {
    TOOL_CALLING, PARALLEL_TOOL_CALLS, STREAMING, CANCELLATION,
    JSON_SCHEMA, STRUCTURED_OUTPUTS, MODEL_DISCOVERY
}

data class AiCapabilityProfile(
    val provider: AiProviderId,
    val model: String,
    val apiVersion: String,
    val adapterVersion: String,
    val capabilities: Map<AiCapability, CapabilitySupport>
) {
    init {
        require(model.isNotBlank() && model.length <= 128)
        require(apiVersion.isNotBlank() && apiVersion.length <= 64)
        require(adapterVersion.isNotBlank() && adapterVersion.length <= 64)
    }

    fun supports(capability: AiCapability): Boolean =
        capabilities[capability] == CapabilitySupport.SUPPORTED
}

data class AiProviderConfig(
    val provider: AiProviderId,
    val model: String,
    val endpoint: String? = null,
    val credentialReference: String
) {
    init {
        require(model.isNotBlank() && model.length <= 128)
        require(credentialReference.isNotBlank() && credentialReference.length <= 128)
    }
}

data class CanonicalPluginDefinition(
    val id: String,
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any?>,
    val risk: ConnectorRegistry.Risk
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{1,79}")))
        require(name.isNotBlank() && name.length <= 128)
        require(description.isNotBlank() && description.length <= 4096)
        require(McpProtocol.validateInputSchema(inputSchema)) {
            "Plugin input schema is invalid or contains unsupported constraints."
        }
    }
}

data class CanonicalToolCall(
    val providerCallId: String,
    val toolName: String,
    val arguments: Map<String, Any?>
) {
    init {
        require(providerCallId.isNotBlank() && providerCallId.length <= 256)
        require(toolName.isNotBlank() && toolName.length <= 128)
    }
}

sealed class CanonicalAiResult {
    data class Text(val value: String) : CanonicalAiResult()
    data class ToolCalls(val calls: List<CanonicalToolCall>) : CanonicalAiResult() {
        init {
            require(calls.isNotEmpty() && calls.size <= 32)
            require(calls.map { it.providerCallId }.toSet().size == calls.size) {
                "Provider returned duplicate tool-call IDs."
            }
        }
    }
    data class Failure(val code: String, val safeMessage: String) : CanonicalAiResult() {
        init {
            require(code.matches(Regex("[A-Z0-9_]{1,64}")))
            require(safeMessage.isNotBlank() && safeMessage.length <= 512)
        }
    }
}

data class CanonicalToolResult(
    val providerCallId: String,
    val toolName: String,
    val content: String,
    val isError: Boolean = false
) {
    init {
        require(providerCallId.isNotBlank() && providerCallId.length <= 256)
        require(toolName.isNotBlank() && toolName.length <= 128)
        require(content.length <= 256 * 1024)
    }
}

/**
 * Pure translation boundary. Network, credentials, approvals and tool execution
 * remain in their existing dedicated components.
 */
interface AiProviderAdapter {
    val provider: AiProviderId
    val adapterVersion: String

    fun validateConfiguration(config: AiProviderConfig)
    fun capabilityProfile(model: String, apiVersion: String): AiCapabilityProfile
    fun encodeToolDefinitions(
        config: AiProviderConfig,
        tools: List<CanonicalPluginDefinition>
    ): Map<String, Any?>

    fun decodeResponse(payload: Map<String, Any?>): CanonicalAiResult

    fun encodeToolResults(
        config: AiProviderConfig,
        results: List<CanonicalToolResult>
    ): Map<String, Any?>
}

object AiProviderRegistry {
    private val adapters = linkedMapOf<AiProviderId, AiProviderAdapter>()

    @Synchronized
    fun register(adapter: AiProviderAdapter) {
        require(adapter.adapterVersion.isNotBlank() && adapter.adapterVersion.length <= 64)
        require(adapters.putIfAbsent(adapter.provider, adapter) == null) {
            "An adapter is already registered for ${adapter.provider}."
        }
    }

    @Synchronized
    @Synchronized
    fun registerBuiltIns() {
        if (AiProviderId.GEMINI !in adapters) register(GeminiFunctionCallingAdapter())
        if (AiProviderId.OPENAI !in adapters) register(OpenAiResponsesFunctionCallingAdapter())
    }

    fun get(provider: AiProviderId): AiProviderAdapter? = adapters[provider]

    @Synchronized
    fun providers(): Set<AiProviderId> = adapters.keys.toSet()

    @Synchronized
    fun clearForTests() {
        adapters.clear()
    }
}

/** Stable projection from the existing connector registry; no execution authority. */
object CanonicalPluginCatalog {
    fun snapshot(): List<CanonicalPluginDefinition> =
        ConnectorRegistry.toolSnapshot().map { tool ->
            CanonicalPluginDefinition(
                id = tool.id,
                name = tool.name,
                description = tool.description,
                inputSchema = tool.inputSchema,
                risk = tool.risk
            )
        }
}

/** Google Gemini generateContent function-calling wire adapter. */
class GeminiFunctionCallingAdapter : AiProviderAdapter {
    override val provider = AiProviderId.GEMINI
    override val adapterVersion = "1"

    override fun validateConfiguration(config: AiProviderConfig) {
        require(config.provider == provider) { "Gemini adapter received another provider config." }
        require(config.endpoint == null) { "Gemini endpoint override is not supported by this adapter version." }
    }

    override fun capabilityProfile(model: String, apiVersion: String) =
        AiCapabilityProfile(
            provider, model, apiVersion, adapterVersion,
            mapOf(
                AiCapability.TOOL_CALLING to CapabilitySupport.SUPPORTED,
                AiCapability.PARALLEL_TOOL_CALLS to CapabilitySupport.SUPPORTED,
                AiCapability.STREAMING to CapabilitySupport.UNKNOWN,
                AiCapability.CANCELLATION to CapabilitySupport.UNKNOWN,
                AiCapability.JSON_SCHEMA to CapabilitySupport.UNKNOWN,
                AiCapability.STRUCTURED_OUTPUTS to CapabilitySupport.UNKNOWN,
                AiCapability.MODEL_DISCOVERY to CapabilitySupport.UNSUPPORTED
            )
        )

    override fun encodeToolDefinitions(
        config: AiProviderConfig,
        tools: List<CanonicalPluginDefinition>
    ): Map<String, Any?> {
        validateConfiguration(config)
        require(tools.size <= 256)
        return mapOf(
            "tools" to listOf(
                mapOf("functionDeclarations" to tools.map { tool ->
                    mapOf(
                        "name" to tool.name,
                        "description" to tool.description,
                        "parameters" to tool.inputSchema
                    )
                })
            )
        )
    }

    override fun decodeResponse(payload: Map<String, Any?>): CanonicalAiResult {
        val candidates = payload["candidates"] as? List<*> ?: emptyList<Any?>()
        val content = (candidates.firstOrNull() as? Map<*, *>)
            ?.get("content") as? Map<*, *>
        val parts = content?.get("parts") as? List<*> ?: emptyList<Any?>()
        val calls = parts.mapIndexedNotNull { index, raw ->
            val functionCall = (raw as? Map<*, *>)?.get("functionCall") as? Map<*, *>
                ?: return@mapIndexedNotNull null
            val name = functionCall["name"] as? String
                ?: throw IllegalArgumentException("Gemini functionCall name is missing.")
            val args = functionCall["args"] as? Map<*, *>
                ?: throw IllegalArgumentException("Gemini functionCall args must be an object.")
            require(args.keys.all { it is String })
            CanonicalToolCall(
                providerCallId = (functionCall["id"] as? String)?.takeIf { it.isNotBlank() } ?: "local_gemini_$name-$index",
                toolName = name,
                arguments = args.entries.associate { it.key as String to it.value }
            )
        }
        if (calls.isNotEmpty()) return CanonicalAiResult.ToolCalls(calls)
        val text = parts.mapNotNull { (it as? Map<*, *>)?.get("text") as? String }
            .joinToString("")
        return if (text.isNotBlank()) CanonicalAiResult.Text(text)
        else CanonicalAiResult.Failure("EMPTY_RESPONSE", "Gemini returned no text or function call.")
    }

    override fun encodeToolResults(
        config: AiProviderConfig,
        results: List<CanonicalToolResult>
    ): Map<String, Any?> {
        validateConfiguration(config)
        require(results.size in 1..32)
        return mapOf(
            "parts" to results.map { result ->
                mapOf(
                    "functionResponse" to mapOf(
                        "name" to result.toolName,
                        "id" to result.providerCallId.takeUnless { it.startsWith("local_gemini_") },
                        "response" to mapOf(
                            "result" to result.content,
                            "isError" to result.isError
                        )
                    )
                )
            }
        )
    }
}

/** OpenAI Responses API function-calling wire adapter (not Chat Completions). */
class OpenAiResponsesFunctionCallingAdapter : AiProviderAdapter {
    override val provider = AiProviderId.OPENAI
    override val adapterVersion = "1"

    override fun validateConfiguration(config: AiProviderConfig) {
        require(config.provider == provider) { "OpenAI adapter received another provider config." }
        require(config.endpoint == null) { "Official OpenAI Responses endpoint is fixed in this adapter version." }
    }

    override fun capabilityProfile(model: String, apiVersion: String) =
        AiCapabilityProfile(
            provider, model, apiVersion, adapterVersion,
            mapOf(
                AiCapability.TOOL_CALLING to CapabilitySupport.SUPPORTED,
                AiCapability.PARALLEL_TOOL_CALLS to CapabilitySupport.SUPPORTED,
                AiCapability.STREAMING to CapabilitySupport.UNKNOWN,
                AiCapability.CANCELLATION to CapabilitySupport.UNKNOWN,
                AiCapability.JSON_SCHEMA to CapabilitySupport.UNKNOWN,
                AiCapability.STRUCTURED_OUTPUTS to CapabilitySupport.UNKNOWN,
                AiCapability.MODEL_DISCOVERY to CapabilitySupport.UNSUPPORTED
            )
        )

    override fun encodeToolDefinitions(
        config: AiProviderConfig,
        tools: List<CanonicalPluginDefinition>
    ): Map<String, Any?> {
        validateConfiguration(config)
        require(tools.size <= 256)
        return mapOf(
            "tools" to tools.map { tool ->
                mapOf(
                    "type" to "function",
                    "name" to tool.name,
                    "description" to tool.description,
                    "parameters" to tool.inputSchema,
                    "strict" to false
                )
            }
        )
    }

    override fun decodeResponse(payload: Map<String, Any?>): CanonicalAiResult {
        val output = payload["output"] as? List<*> ?: emptyList<Any?>()
        val calls = output.mapNotNull { raw ->
            val item = raw as? Map<*, *> ?: return@mapNotNull null
            if (item["type"] != "function_call") return@mapNotNull null
            val callId = item["call_id"] as? String
                ?: throw IllegalArgumentException("OpenAI function_call call_id is missing.")
            val name = item["name"] as? String
                ?: throw IllegalArgumentException("OpenAI function_call name is missing.")
            val rawArguments = item["arguments"] as? String
                ?: throw IllegalArgumentException("OpenAI function_call arguments are missing.")
            val parsed = org.json.JSONObject(rawArguments)
            CanonicalToolCall(callId, name, parsed.toStringMap())
        }
        if (calls.isNotEmpty()) return CanonicalAiResult.ToolCalls(calls)

        val text = output.mapNotNull { raw ->
            val item = raw as? Map<*, *> ?: return@mapNotNull null
            if (item["type"] != "message") return@mapNotNull null
            val content = item["content"] as? List<*> ?: return@mapNotNull null
            content.mapNotNull { part ->
                val entry = part as? Map<*, *> ?: return@mapNotNull null
                if (entry["type"] == "output_text") entry["text"] as? String else null
            }.joinToString("")
        }.joinToString("")
        return if (text.isNotBlank()) CanonicalAiResult.Text(text)
        else CanonicalAiResult.Failure("EMPTY_RESPONSE", "OpenAI returned no text or function call.")
    }

    override fun encodeToolResults(
        config: AiProviderConfig,
        results: List<CanonicalToolResult>
    ): Map<String, Any?> {
        validateConfiguration(config)
        require(results.size in 1..32)
        return mapOf(
            "input" to results.map { result ->
                mapOf(
                    "type" to "function_call_output",
                    "call_id" to result.providerCallId,
                    "output" to if (result.isError) "ERROR: ${result.content}" else result.content
                )
            }
        )
    }
}

private fun org.json.JSONObject.toStringMap(): Map<String, Any?> {
    val result = linkedMapOf<String, Any?>()
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val value = opt(key)
        result[key] = when (value) {
            org.json.JSONObject.NULL -> null
            is org.json.JSONObject -> value.toStringMap()
            is org.json.JSONArray -> value.toAnyList()
            else -> value
        }
    }
    return result
}

private fun org.json.JSONArray.toAnyList(): List<Any?> =
    (0 until length()).map { index ->
        when (val value = opt(index)) {
            org.json.JSONObject.NULL -> null
            is org.json.JSONObject -> value.toStringMap()
            is org.json.JSONArray -> value.toAnyList()
            else -> value
        }
    }

// JSON conversion helpers above intentionally preserve nested maps and arrays.
