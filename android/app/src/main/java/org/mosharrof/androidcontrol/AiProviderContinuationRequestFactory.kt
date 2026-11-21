package org.mosharrof.androidcontrol

/**
 * Builds stateless provider continuation requests after a guarded tool batch.
 *
 * The previous model output is replayed verbatim where the provider requires
 * client-managed history. This preserves provider-specific reasoning/signature
 * items and prevents relying on server-side response storage.
 */
object AiProviderContinuationRequestFactory {
    private const val MAX_HISTORY_ITEMS = 2_048

    fun create(
        config: AiProviderConfig,
        apiKey: String,
        prompt: String,
        previousResponse: Map<String, Any?>,
        results: List<CanonicalToolResult>,
        tools: List<CanonicalPluginDefinition>
    ): AiProviderHttpRequest {
        require(prompt.isNotBlank() && prompt.length <= AiProviderRequestFactory.MAX_PROMPT_CHARS) {
            "AI prompt is empty or exceeds the configured limit."
        }
        require(apiKey.isNotBlank() && apiKey.length <= AiProviderRequestFactory.MAX_API_KEY_CHARS) {
            "AI credential is empty or exceeds the configured limit."
        }
        require(apiKey.none { it.isISOControl() }) {
            "AI credential contains invalid control characters."
        }
        require(tools.size <= AiProviderRequestFactory.MAX_TOOLS) {
            "Too many plugin tools were supplied."
        }
        require(results.isNotEmpty() && results.size <= 32) {
            "Tool result count is outside the configured limit."
        }

        AiProviderRegistry.registerBuiltIns()
        val adapter = requireNotNull(AiProviderRegistry.get(config.provider)) {
            "No adapter is registered for ${config.provider}."
        }
        adapter.validateConfiguration(config)
        val apiVersion = when (config.provider) {
            AiProviderId.GEMINI -> "v1beta"
            AiProviderId.OPENAI, AiProviderId.XAI, AiProviderId.CUSTOM -> "v1"
            AiProviderId.ANTHROPIC -> "2026-10-21"
            else -> error("This provider does not have an outbound model API.")
        }
        adapter.capabilityProfile(config.model, apiVersion)

        val expectedCalls = adapter.decodeResponse(previousResponse) as? CanonicalAiResult.ToolCalls
            ?: throw IllegalArgumentException("Previous provider response does not contain tool calls.")
        require(expectedCalls.calls.size == results.size) {
            "Every provider tool call must have exactly one result."
        }
        val resultsById = results.associateBy { it.providerCallId }
        require(resultsById.size == results.size) { "Duplicate tool result IDs are not allowed." }
        expectedCalls.calls.forEach { call ->
            val result = resultsById[call.providerCallId]
                ?: throw IllegalArgumentException("A provider tool call is missing its result.")
            require(result.toolName == call.toolName) {
                "Tool result name does not match the provider tool call."
            }
        }
        require(results.all { result -> expectedCalls.calls.any { it.providerCallId == result.providerCallId } }) {
            "A tool result does not belong to the previous provider response."
        }

        val toolDefinitions = adapter.encodeToolDefinitions(config, tools)["tools"]
            ?: error("Provider adapter did not produce a tools list.")
        val encodedResults = adapter.encodeToolResults(config, results)
        val headers = linkedMapOf("Content-Type" to "application/json; charset=utf-8")
        val url: String
        val body: Map<String, Any?>

        when (config.provider) {
            AiProviderId.GEMINI -> {
                val previousSteps = previousResponse["steps"] as? List<*>
                    ?: throw IllegalArgumentException("Gemini continuation is missing previous steps.")
                require(previousSteps.isNotEmpty() && previousSteps.size <= MAX_HISTORY_ITEMS) {
                    "Gemini continuation history is empty or exceeds the configured limit."
                }
                require(previousSteps.all { it is Map<*, *> }) {
                    "Gemini continuation contains an invalid history step."
                }
                val resultSteps = encodedResults["input"] as? List<*>
                    ?: error("Gemini adapter did not encode function results.")
                url = "https://generativelanguage.googleapis.com/$apiVersion/interactions"
                headers["x-goog-api-key"] = apiKey
                body = mapOf(
                    "model" to config.model,
                    "input" to buildList<Any?> {
                        add(
                            mapOf(
                                "type" to "user_input",
                                "content" to listOf(mapOf("type" to "text", "text" to prompt))
                            )
                        )
                        addAll(previousSteps)
                        addAll(resultSteps)
                    },
                    "tools" to toolDefinitions,
                    "store" to false
                )
            }
            AiProviderId.OPENAI, AiProviderId.XAI -> {
                val previousOutput = previousResponse["output"] as? List<*>
                    ?: throw IllegalArgumentException("Responses API continuation is missing previous output.")
                require(previousOutput.isNotEmpty() && previousOutput.size <= MAX_HISTORY_ITEMS) {
                    "Responses API history is empty or exceeds the configured limit."
                }
                require(previousOutput.all { it is Map<*, *> }) {
                    "Responses API continuation contains an invalid output item."
                }
                val resultItems = encodedResults["input"] as? List<*>
                    ?: error("Responses adapter did not encode function results.")
                url = if (config.provider == AiProviderId.OPENAI) {
                    "https://api.openai.com/$apiVersion/responses"
                } else {
                    "https://api.x.ai/$apiVersion/responses"
                }
                headers["Authorization"] = "Bearer $apiKey"
                body = buildMap<String, Any?> {
                    put("model", config.model)
                    put("input", buildList<Any?> {
                        add(mapOf("role" to "user", "content" to prompt))
                        addAll(previousOutput)
                        addAll(resultItems)
                    })
                    put("tools", toolDefinitions)
                    put("parallel_tool_calls", false)
                    put("store", false)
                    if (config.provider == AiProviderId.XAI) {
                        put("include", listOf("reasoning.encrypted_content"))
                    }
                }
            }
            AiProviderId.ANTHROPIC -> {
                val previousContent = previousResponse["content"] as? List<*>
                    ?: throw IllegalArgumentException("Anthropic continuation is missing assistant content.")
                require(previousContent.isNotEmpty() && previousContent.size <= MAX_HISTORY_ITEMS) {
                    "Anthropic assistant content is empty or exceeds the configured limit."
                }
                require(previousContent.all { it is Map<*, *> }) {
                    "Anthropic continuation contains an invalid content block."
                }
                val resultMessage = encodedResults
                url = "https://api.anthropic.com/v1/messages"
                headers["x-api-key"] = apiKey
                headers["anthropic-version"] = apiVersion
                body = mapOf(
                    "model" to config.model,
                    "max_tokens" to 4096,
                    "messages" to listOf(
                        mapOf("role" to "user", "content" to prompt),
                        mapOf("role" to "assistant", "content" to previousContent),
                        resultMessage
                    ),
                    "tools" to toolDefinitions,
                    "tool_choice" to mapOf(
                        "type" to "auto",
                        "disable_parallel_tool_use" to true
                    )
                )
            }
            AiProviderId.CUSTOM -> {
                val choices = previousResponse["choices"] as? List<*>
                    ?: throw IllegalArgumentException("Custom provider continuation is missing choices.")
                val assistantMessage = (choices.firstOrNull() as? Map<*, *>)?.get("message") as? Map<*, *>
                    ?: throw IllegalArgumentException("Custom provider continuation is missing the assistant message.")
                val rawCalls = assistantMessage["tool_calls"] as? List<*>
                require(!rawCalls.isNullOrEmpty() && rawCalls.size <= 32) {
                    "Custom provider assistant tool calls are empty or exceed the configured limit."
                }
                url = config.endpoint ?: error("Custom HTTPS endpoint is required.")
                headers["Authorization"] = "Bearer $apiKey"
                val resultMessages = encodedResults["messages"] as? List<*>
                    ?: error("Custom adapter did not encode tool result messages.")
                body = mapOf(
                    "model" to config.model,
                    "messages" to buildList<Any?> {
                        add(mapOf("role" to "user", "content" to prompt))
                        add(assistantMessage)
                        addAll(resultMessages)
                    },
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
