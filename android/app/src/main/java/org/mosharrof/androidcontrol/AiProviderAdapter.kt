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
    fun registerBuiltIns() {
        if (AiProviderId.GEMINI !in adapters) register(GeminiInteractionsAdapter())
        if (AiProviderId.OPENAI !in adapters) register(OpenAiResponsesFunctionCallingAdapter())
        if (AiProviderId.XAI !in adapters) register(XaiResponsesFunctionCallingAdapter())
        if (AiProviderId.ANTHROPIC !in adapters) register(AnthropicMessagesToolUseAdapter())
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

/** Google Gemini Interactions API function-calling adapter. */
class GeminiInteractionsAdapter : AiProviderAdapter {
    override val provider = AiProviderId.GEMINI
    override val adapterVersion = "2"

    override fun validateConfiguration(config: AiProviderConfig) {
        require(config.provider == provider) { "Gemini adapter received another provider config." }
        require(config.endpoint == null) { "Official Gemini Interactions endpoint is fixed in this adapter version." }
    }

    override fun capabilityProfile(model: String, apiVersion: String): AiCapabilityProfile {
        require(apiVersion == "v1beta" || apiVersion == "v1") {
            "Unsupported Gemini Interactions API version."
        }
        return AiCapabilityProfile(
            provider, model, apiVersion, adapterVersion,
            mapOf(
                AiCapability.TOOL_CALLING to CapabilitySupport.UNKNOWN,
                AiCapability.PARALLEL_TOOL_CALLS to CapabilitySupport.UNKNOWN,
                AiCapability.STREAMING to CapabilitySupport.UNKNOWN,
                AiCapability.CANCELLATION to CapabilitySupport.UNKNOWN,
                AiCapability.JSON_SCHEMA to CapabilitySupport.UNKNOWN,
                AiCapability.STRUCTURED_OUTPUTS to CapabilitySupport.UNKNOWN,
                AiCapability.MODEL_DISCOVERY to CapabilitySupport.UNSUPPORTED
            )
        )
    }

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
                    "parameters" to tool.inputSchema
                )
            }
        )
    }

    override fun decodeResponse(payload: Map<String, Any?>): CanonicalAiResult {
        val steps = payload["steps"] as? List<*> ?: emptyList<Any?>()
        val calls = steps.mapNotNull { raw ->
            val step = raw as? Map<*, *> ?: return@mapNotNull null
            if (step["type"] != "function_call") return@mapNotNull null
            val id = step["id"] as? String ?: error("Gemini function_call ID is missing.")
            val name = step["name"] as? String ?: error("Gemini function_call name is missing.")
            val rawArguments = step["arguments"]
            val arguments = when (rawArguments) {
                is Map<*, *> -> {
                    require(rawArguments.keys.all { it is String })
                    rawArguments.entries.associate { it.key as String to it.value }
                }
                is String -> org.json.JSONObject(rawArguments).toStringMap()
                else -> error("Gemini function_call arguments must be an object.")
            }
            CanonicalToolCall(id, name, arguments)
        }
        if (calls.isNotEmpty()) return CanonicalAiResult.ToolCalls(calls)

        val text = steps.mapNotNull { raw ->
            val step = raw as? Map<*, *> ?: return@mapNotNull null
            if (step["type"] != "model_output") return@mapNotNull null
            val content = step["content"] as? List<*> ?: return@mapNotNull null
            content.mapNotNull { part ->
                val item = part as? Map<*, *> ?: return@mapNotNull null
                if (item["type"] == "text") item["text"] as? String else null
            }.joinToString("")
        }.joinToString("")
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
            "input" to results.map { result ->
                mapOf(
                    "type" to "function_result",
                    "name" to result.toolName,
                    "call_id" to result.providerCallId,
                    "result" to listOf(
                        mapOf(
                            "type" to "text",
                            "text" to if (result.isError) "ERROR: ${result.content}" else result.content
                        )
                    )
                )
            }
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

    override fun capabilityProfile(model: String, apiVersion: String): AiCapabilityProfile {
        require(apiVersion == "v1beta") { "Unsupported Gemini API version." }
        return AiCapabilityProfile(
            provider, model, apiVersion, adapterVersion,
            mapOf(
                AiCapability.TOOL_CALLING to CapabilitySupport.UNKNOWN,
                AiCapability.PARALLEL_TOOL_CALLS to CapabilitySupport.UNKNOWN,
                AiCapability.STREAMING to CapabilitySupport.UNKNOWN,
                AiCapability.CANCELLATION to CapabilitySupport.UNKNOWN,
                AiCapability.JSON_SCHEMA to CapabilitySupport.UNKNOWN,
                AiCapability.STRUCTURED_OUTPUTS to CapabilitySupport.UNKNOWN,
                AiCapability.MODEL_DISCOVERY to CapabilitySupport.UNSUPPORTED
            )
        )

    }

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

    override fun capabilityProfile(model: String, apiVersion: String): AiCapabilityProfile {
        require(apiVersion == "v1") { "Unsupported OpenAI Responses API version." }
        return AiCapabilityProfile(
            provider, model, apiVersion, adapterVersion,
            mapOf(
                AiCapability.TOOL_CALLING to CapabilitySupport.UNKNOWN,
                AiCapability.PARALLEL_TOOL_CALLS to CapabilitySupport.UNKNOWN,
                AiCapability.STREAMING to CapabilitySupport.UNKNOWN,
                AiCapability.CANCELLATION to CapabilitySupport.UNKNOWN,
                AiCapability.JSON_SCHEMA to CapabilitySupport.UNKNOWN,
                AiCapability.STRUCTURED_OUTPUTS to CapabilitySupport.UNKNOWN,
                AiCapability.MODEL_DISCOVERY to CapabilitySupport.UNSUPPORTED
            )
        )

    }

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


/** xAI Grok Responses API function-calling adapter. */
class XaiResponsesFunctionCallingAdapter : AiProviderAdapter {
    override val provider = AiProviderId.XAI
    override val adapterVersion = "2"

    override fun validateConfiguration(config: AiProviderConfig) {
        require(config.provider == provider) { "xAI adapter received another provider config." }
        require(config.endpoint == null) { "Official xAI Responses endpoint is fixed in this adapter version." }
    }

    override fun capabilityProfile(model: String, apiVersion: String): AiCapabilityProfile {
        require(apiVersion == "v1") { "Unsupported xAI Responses API version." }
        return AiCapabilityProfile(
            provider, model, apiVersion, adapterVersion,
            mapOf(
                AiCapability.TOOL_CALLING to CapabilitySupport.UNKNOWN,
                AiCapability.PARALLEL_TOOL_CALLS to CapabilitySupport.UNKNOWN,
                AiCapability.STREAMING to CapabilitySupport.UNKNOWN,
                AiCapability.CANCELLATION to CapabilitySupport.UNKNOWN,
                AiCapability.JSON_SCHEMA to CapabilitySupport.UNKNOWN,
                AiCapability.STRUCTURED_OUTPUTS to CapabilitySupport.UNKNOWN,
                AiCapability.MODEL_DISCOVERY to CapabilitySupport.UNSUPPORTED
            )
        )
    }

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
                ?: error("xAI function_call call_id is missing.")
            val name = item["name"] as? String
                ?: error("xAI function_call name is missing.")
            val arguments = item["arguments"] as? String
                ?: error("xAI function_call arguments are missing.")
            CanonicalToolCall(callId, name, org.json.JSONObject(arguments).toStringMap())
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
        else CanonicalAiResult.Failure("EMPTY_RESPONSE", "Grok returned no text or function call.")
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

/** xAI Grok Chat Completions-compatible custom function-calling adapter. */
class XaiChatCompletionsFunctionCallingAdapter : AiProviderAdapter {
    override val provider = AiProviderId.XAI
    override val adapterVersion = "1"

    override fun validateConfiguration(config: AiProviderConfig) {
        require(config.provider == provider)
        require(config.endpoint == null) { "xAI official endpoint is fixed in this adapter version." }
    }

    override fun capabilityProfile(model: String, apiVersion: String): AiCapabilityProfile {
        require(apiVersion == "v1") { "Unsupported xAI API version." }
        return AiCapabilityProfile(
            provider, model, apiVersion, adapterVersion,
            mapOf(
                AiCapability.TOOL_CALLING to CapabilitySupport.UNKNOWN,
                AiCapability.PARALLEL_TOOL_CALLS to CapabilitySupport.UNKNOWN,
                AiCapability.STREAMING to CapabilitySupport.UNKNOWN,
                AiCapability.CANCELLATION to CapabilitySupport.UNKNOWN,
                AiCapability.JSON_SCHEMA to CapabilitySupport.UNKNOWN,
                AiCapability.STRUCTURED_OUTPUTS to CapabilitySupport.UNKNOWN,
                AiCapability.MODEL_DISCOVERY to CapabilitySupport.UNSUPPORTED
            )
        )

    }

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
                    "function" to mapOf(
                        "name" to tool.name,
                        "description" to tool.description,
                        "parameters" to tool.inputSchema
                    )
                )
            },
            "parallel_tool_calls" to true
        )
    }

    override fun decodeResponse(payload: Map<String, Any?>): CanonicalAiResult {
        val choices = payload["choices"] as? List<*> ?: emptyList<Any?>()
        val message = (choices.firstOrNull() as? Map<*, *>)?.get("message") as? Map<*, *>
            ?: return CanonicalAiResult.Failure("EMPTY_RESPONSE", "Grok returned no message.")
        val toolCalls = message["tool_calls"] as? List<*> ?: emptyList<Any?>()
        val calls = toolCalls.map { raw ->
            val item = raw as? Map<*, *> ?: error("Grok tool call must be an object.")
            val id = item["id"] as? String ?: error("Grok tool call ID is missing.")
            val function = item["function"] as? Map<*, *>
                ?: error("Grok tool function is missing.")
            val name = function["name"] as? String ?: error("Grok tool name is missing.")
            val arguments = function["arguments"] as? String
                ?: error("Grok tool arguments are missing.")
            CanonicalToolCall(id, name, org.json.JSONObject(arguments).toStringMap())
        }
        if (calls.isNotEmpty()) return CanonicalAiResult.ToolCalls(calls)
        val text = message["content"] as? String
        return if (!text.isNullOrBlank()) CanonicalAiResult.Text(text)
        else CanonicalAiResult.Failure("EMPTY_RESPONSE", "Grok returned no text or function call.")
    }

    override fun encodeToolResults(
        config: AiProviderConfig,
        results: List<CanonicalToolResult>
    ): Map<String, Any?> {
        validateConfiguration(config)
        require(results.size in 1..32)
        return mapOf(
            "messages" to results.map { result ->
                mapOf(
                    "role" to "tool",
                    "tool_call_id" to result.providerCallId,
                    "content" to if (result.isError) "ERROR: ${result.content}" else result.content
                )
            }
        )
    }
}

/** Anthropic Messages API user-defined client tool-use adapter. */
class AnthropicMessagesToolUseAdapter : AiProviderAdapter {
    override val provider = AiProviderId.ANTHROPIC
    override val adapterVersion = "1"

    override fun validateConfiguration(config: AiProviderConfig) {
        require(config.provider == provider)
        require(config.endpoint == null) { "Anthropic official endpoint is fixed in this adapter version." }
    }

    override fun capabilityProfile(model: String, apiVersion: String): AiCapabilityProfile {
        require(apiVersion == "2026-10-21") { "Unsupported Anthropic Messages API version." }
        return AiCapabilityProfile(
            provider, model, apiVersion, adapterVersion,
            mapOf(
                AiCapability.TOOL_CALLING to CapabilitySupport.UNKNOWN,
                AiCapability.PARALLEL_TOOL_CALLS to CapabilitySupport.UNKNOWN,
                AiCapability.STREAMING to CapabilitySupport.UNKNOWN,
                AiCapability.CANCELLATION to CapabilitySupport.UNKNOWN,
                AiCapability.JSON_SCHEMA to CapabilitySupport.UNKNOWN,
                AiCapability.STRUCTURED_OUTPUTS to CapabilitySupport.UNKNOWN,
                AiCapability.MODEL_DISCOVERY to CapabilitySupport.UNSUPPORTED
            )
        )

    }

    override fun encodeToolDefinitions(
        config: AiProviderConfig,
        tools: List<CanonicalPluginDefinition>
    ): Map<String, Any?> {
        validateConfiguration(config)
        require(tools.size <= 256)
        return mapOf(
            "tools" to tools.map { tool ->
                mapOf(
                    "name" to tool.name,
                    "description" to tool.description,
                    "input_schema" to tool.inputSchema
                )
            }
        )
    }

    override fun decodeResponse(payload: Map<String, Any?>): CanonicalAiResult {
        val content = payload["content"] as? List<*> ?: emptyList<Any?>()
        val calls = content.mapNotNull { raw ->
            val block = raw as? Map<*, *> ?: return@mapNotNull null
            if (block["type"] != "tool_use") return@mapNotNull null
            val id = block["id"] as? String ?: error("Claude tool_use ID is missing.")
            val name = block["name"] as? String ?: error("Claude tool_use name is missing.")
            val input = block["input"] as? Map<*, *>
                ?: error("Claude tool_use input must be an object.")
            require(input.keys.all { it is String })
            CanonicalToolCall(id, name, input.entries.associate { it.key as String to it.value })
        }
        if (calls.isNotEmpty()) return CanonicalAiResult.ToolCalls(calls)
        val text = content.mapNotNull { raw ->
            val block = raw as? Map<*, *> ?: return@mapNotNull null
            if (block["type"] == "text") block["text"] as? String else null
        }.joinToString("")
        return if (text.isNotBlank()) CanonicalAiResult.Text(text)
        else CanonicalAiResult.Failure("EMPTY_RESPONSE", "Claude returned no text or tool use.")
    }

    override fun encodeToolResults(
        config: AiProviderConfig,
        results: List<CanonicalToolResult>
    ): Map<String, Any?> {
        validateConfiguration(config)
        require(results.size in 1..32)
        return mapOf(
            "role" to "user",
            "content" to results.map { result ->
                buildMap<String, Any?> {
                    put("type", "tool_result")
                    put("tool_use_id", result.providerCallId)
                    put("content", result.content)
                    if (result.isError) put("is_error", true)
                }
            }
        )
    }
}


sealed class CanonicalToolCallDecision {
    data class ReadOnlyAllowed(val toolId: String, val call: CanonicalToolCall) : CanonicalToolCallDecision()
    data class ApprovalRequired(val toolId: String, val call: CanonicalToolCall) : CanonicalToolCallDecision()
    data class Denied(val reason: String) : CanonicalToolCallDecision()
}

/**
 * Resolves untrusted provider tool calls against registered MOSHARROF tools.
 * This is a policy/validation gate only; it never issues grants or executes tools.
 */
object CanonicalToolCallGuard {
    fun inspect(call: CanonicalToolCall): CanonicalToolCallDecision {
        val matches = ConnectorRegistry.toolSnapshot().filter { it.name == call.toolName }
        if (matches.size != 1) {
            return CanonicalToolCallDecision.Denied(
                if (matches.isEmpty()) "Tool is not registered." else "Tool name is ambiguous across connectors."
            )
        }
        val tool = matches.single()
        val required = (tool.inputSchema["required"] as? List<*>)
            ?.mapNotNull { it as? String }
            ?: emptyList()
        if (!McpArgumentsValidator.validate(tool.inputSchema, call.arguments, required)) {
            return CanonicalToolCallDecision.Denied("Tool arguments do not match the registered schema.")
        }
        return when (ConnectorRegistry.authorize(tool.id)) {
            ConnectorRegistry.Decision.ALLOW_READ_ONLY ->
                CanonicalToolCallDecision.ReadOnlyAllowed(tool.id, call)
            ConnectorRegistry.Decision.APPROVAL_REQUIRED ->
                CanonicalToolCallDecision.ApprovalRequired(tool.id, call)
            else -> CanonicalToolCallDecision.Denied("Connector or tool is unavailable under current policy.")
        }
    }
}


enum class AiProviderIntegrationDirection { OUTBOUND_MODEL_API, INBOUND_MCP_CLIENT }
enum class AiProviderImplementationStatus {
    TRANSLATION_ONLY, INBOUND_BRIDGE_PENDING, NOT_IMPLEMENTED
}

data class AiProviderCatalogEntry(
    val provider: AiProviderId,
    val displayName: String,
    val direction: AiProviderIntegrationDirection,
    val status: AiProviderImplementationStatus,
    val officialDocumentation: List<String>,
    val adapterRegistered: Boolean
)

/** Machine-readable provider inventory; status deliberately distinguishes translation from live support. */
object AiProviderCatalog {
    private data class Definition(
        val provider: AiProviderId,
        val name: String,
        val direction: AiProviderIntegrationDirection,
        val status: AiProviderImplementationStatus,
        val docs: List<String>
    )

    private val definitions = listOf(
        Definition(
            AiProviderId.GEMINI, "Google Gemini",
            AiProviderIntegrationDirection.OUTBOUND_MODEL_API,
            AiProviderImplementationStatus.TRANSLATION_ONLY,
            listOf("https://ai.google.dev/gemini-api/docs/interactions-overview", "https://ai.google.dev/gemini-api/docs/function-calling")
        ),
        Definition(
            AiProviderId.OPENAI, "OpenAI",
            AiProviderIntegrationDirection.OUTBOUND_MODEL_API,
            AiProviderImplementationStatus.TRANSLATION_ONLY,
            listOf("https://developers.openai.com/api/docs/guides/function-calling")
        ),
        Definition(
            AiProviderId.XAI, "xAI Grok",
            AiProviderIntegrationDirection.OUTBOUND_MODEL_API,
            AiProviderImplementationStatus.TRANSLATION_ONLY,
            listOf("https://docs.x.ai/developers/rest-api-reference/inference/responses", "https://docs.x.ai/developers/tools/function-calling")
        ),
        Definition(
            AiProviderId.ANTHROPIC, "Anthropic Claude",
            AiProviderIntegrationDirection.OUTBOUND_MODEL_API,
            AiProviderImplementationStatus.TRANSLATION_ONLY,
            listOf(
                "https://platform.claude.com/docs/en/agents-and-tools/tool-use/how-tool-use-works",
                "https://platform.claude.com/docs/en/agents-and-tools/tool-use/handle-tool-calls",
                "https://platform.claude.com/docs/en/api/versioning"
            )
        ),
        Definition(
            AiProviderId.MICROSOFT, "Microsoft 365 Copilot / Copilot Studio",
            AiProviderIntegrationDirection.INBOUND_MCP_CLIENT,
            AiProviderImplementationStatus.INBOUND_BRIDGE_PENDING,
            listOf(
                "https://learn.microsoft.com/en-us/microsoft-365/copilot/extensibility/overview-plugins",
                "https://learn.microsoft.com/en-us/microsoft-copilot-studio/add-tools-custom-agent"
            )
        )
    )

    fun snapshot(): List<AiProviderCatalogEntry> = definitions.map { definition ->
        AiProviderCatalogEntry(
            provider = definition.provider,
            displayName = definition.name,
            direction = definition.direction,
            status = definition.status,
            officialDocumentation = definition.docs.toList(),
            adapterRegistered = AiProviderRegistry.get(definition.provider) != null
        )
    }
}
