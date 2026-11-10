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
