package org.mosharrof.androidcontrol

/**
 * A fully prepared HTTPS request. Header values can contain credentials, so
 * toString() deliberately redacts them and only reports header names.
 */
class AiProviderHttpRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: Map<String, Any?>
) {
    override fun toString(): String =
        "AiProviderHttpRequest(url=$url, headers=${headers.keys}, bodyFields=${body.keys})"
}

/**
 * Builds provider-specific first-turn requests from the canonical tool catalog.
 * No network access or credential persistence occurs in this class.
 */
object AiProviderRequestFactory {
    const val MAX_PROMPT_CHARS = 64 * 1024
    const val MAX_API_KEY_CHARS = 8_192
    const val MAX_TOOLS = 256

    fun createInitialRequest(
        config: AiProviderConfig,
        apiKey: String,
        prompt: String,
        tools: List<CanonicalPluginDefinition>
    ): AiProviderHttpRequest {
        require(prompt.isNotBlank() && prompt.length <= MAX_PROMPT_CHARS) {
            "AI prompt is empty or exceeds the 64 KiB limit."
        }
        require(apiKey.isNotBlank() && apiKey.length <= MAX_API_KEY_CHARS) {
            "AI credential is empty or exceeds the configured limit."
        }
        require(apiKey.none { it.isISOControl() }) {
            "AI credential contains invalid control characters."
        }
        require(tools.size <= MAX_TOOLS) { "Too many plugin tools were supplied." }

        val adapter = requireNotNull(AiProviderRegistry.get(config.provider)) {
            "No adapter is registered for ${config.provider}."
        }
        adapter.validateConfiguration(config)
        val apiVersion = when (config.provider) {
            AiProviderId.GEMINI -> "v1beta"
            AiProviderId.OPENAI, AiProviderId.XAI -> "v1"
            AiProviderId.ANTHROPIC -> "2026-10-21"
            AiProviderId.CUSTOM -> "v1"
            else -> error("This provider does not have an outbound model API.")
        }
        adapter.capabilityProfile(config.model, apiVersion)

        val toolDefinitions = adapter.encodeToolDefinitions(config, tools)["tools"]
            ?: error("Provider adapter did not produce a tools list.")

        val headers = linkedMapOf("Content-Type" to "application/json; charset=utf-8")
        val url: String
        val body: Map<String, Any?>

        when (config.provider) {
            AiProviderId.GEMINI -> {
                url = "https://generativelanguage.googleapis.com/$apiVersion/interactions"
                headers["x-goog-api-key"] = apiKey
                body = mapOf(
                    "model" to config.model,
                    "input" to prompt,
                    "tools" to toolDefinitions,
                    "store" to false
                )
            }
            AiProviderId.OPENAI -> {
                url = "https://api.openai.com/$apiVersion/responses"
                headers["Authorization"] = "Bearer $apiKey"
                body = mapOf(
                    "model" to config.model,
                    "input" to prompt,
                    "tools" to toolDefinitions,
                    "parallel_tool_calls" to false,
                    "store" to false
                )
            }
            AiProviderId.XAI -> {
                url = "https://api.x.ai/$apiVersion/responses"
                headers["Authorization"] = "Bearer $apiKey"
                body = mapOf(
                    "model" to config.model,
                    "input" to prompt,
                    "tools" to toolDefinitions,
                    "parallel_tool_calls" to false,
                    "store" to false
                )
            }
            AiProviderId.ANTHROPIC -> {
                url = "https://api.anthropic.com/v1/messages"
                headers["x-api-key"] = apiKey
                headers["anthropic-version"] = apiVersion
                body = mapOf(
                    "model" to config.model,
                    "max_tokens" to 4096,
                    "messages" to listOf(mapOf("role" to "user", "content" to prompt)),
                    "tools" to toolDefinitions,
                    "tool_choice" to mapOf(
                        "type" to "auto",
                        "disable_parallel_tool_use" to true
                    )
                )
            }
            AiProviderId.CUSTOM -> {
                url = config.endpoint ?: error("Custom HTTPS endpoint is required.")
                headers["Authorization"] = "Bearer $apiKey"
                body = mapOf(
                    "model" to config.model,
                    "messages" to listOf(mapOf("role" to "user", "content" to prompt)),
                    "tools" to toolDefinitions,
                    "tool_choice" to "auto",
                    "parallel_tool_calls" to false
                )
            }
            else -> error("This provider does not have an outbound model API.")
        }

        return AiProviderHttpRequest(url, headers.toMap(), body)
    }
}
