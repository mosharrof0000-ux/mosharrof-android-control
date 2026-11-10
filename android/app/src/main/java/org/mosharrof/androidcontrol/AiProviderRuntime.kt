package org.mosharrof.androidcontrol

import org.json.JSONArray
import org.json.JSONObject

/**
 * Bounded provider conversation runner. Tool execution is possible only when a
 * connected MCP service is supplied; every non-read-only call must pass the
 * caller's explicit approval callback before a one-time grant is issued.
 */
object AiProviderRuntime {
    private const val MAX_JSON_DEPTH = 32
    private const val MAX_JSON_NODES = 2_048
    const val MAX_CONTINUATION_ROUNDS = 8
    const val MAX_TOTAL_TOOL_CALLS = 32

    fun askText(profile: ResolvedAiProviderProfile, prompt: String): String =
        askText(profile, prompt, service = null, approve = { false })

    fun askText(
        profile: ResolvedAiProviderProfile,
        prompt: String,
        service: McpConnectorService?,
        approve: (AiToolCallPlan.Ready) -> Boolean
    ): String = runConversation(
        profile = profile,
        prompt = prompt,
        service = service,
        approve = approve,
        post = { request -> AiProviderHttpTransport().post(request).payload.toBoundedMap() }
    )

    internal fun runConversation(
        profile: ResolvedAiProviderProfile,
        prompt: String,
        service: McpConnectorService?,
        approve: (AiToolCallPlan.Ready) -> Boolean,
        post: (AiProviderHttpRequest) -> Map<String, Any?>
    ): String {
        require(prompt.isNotBlank() && prompt.length <= AiProviderRequestFactory.MAX_PROMPT_CHARS) {
            "AI prompt is empty or exceeds the configured limit."
        }
        AiProviderRegistry.registerBuiltIns()

        val connectedIds = ConnectorRegistry.connectorSnapshot()
            .filter { it.state == ConnectorRegistry.ConnectionState.CONNECTED }
            .map { it.id }
            .toSet()
        val tools = if (service == null) emptyList() else {
            CanonicalPluginCatalog.snapshot().filter { definition ->
                ConnectorRegistry.toolSnapshot().any { tool ->
                    tool.name == definition.name &&
                        tool.id == definition.id &&
                        tool.connectorId in connectedIds
                }
            }
        }
        val request = AiProviderRequestFactory.createInitialRequest(
            config = profile.config,
            apiKey = profile.apiKey,
            prompt = prompt,
            tools = tools
        )
        var payload = post(request)
        var totalCalls = 0
        var continuationRounds = 0

        while (true) {
            when (val result = decodePayload(profile.config.provider, payload)) {
                is CanonicalAiResult.Text -> return result.value
                is CanonicalAiResult.Failure -> throw IllegalStateException(result.safeMessage)
                is CanonicalAiResult.ToolCalls -> {
                    require(service != null && tools.isNotEmpty()) {
                        "AI returned plugin actions, but no connected MCP tool session is available. No plugin was executed."
                    }
                    totalCalls += result.calls.size
                    require(totalCalls <= MAX_TOTAL_TOOL_CALLS) {
                        "AI tool-call limit reached; remaining calls were not executed."
                    }
                    require(continuationRounds < MAX_CONTINUATION_ROUNDS) {
                        "AI continuation limit reached; no further plugin calls were executed."
                    }

                    val plans = AiToolCallOrchestrator.planAll(result.calls).getOrThrow()
                    val toolResults = plans.map { plan ->
                        when (plan) {
                            is AiToolCallPlan.Denied -> CanonicalToolResult(
                                providerCallId = plan.call.providerCallId,
                                toolName = plan.call.toolName,
                                content = plan.reason.take(64 * 1024),
                                isError = true
                            )
                            is AiToolCallPlan.Ready -> {
                                if (plan.approvalRequired && !runCatching { approve(plan) }.getOrDefault(false)) {
                                    CanonicalToolResult(
                                        providerCallId = plan.call.providerCallId,
                                        toolName = plan.call.toolName,
                                        content = "User declined this tool call. It was not executed.",
                                        isError = true
                                    )
                                } else {
                                    val grant = if (plan.approvalRequired) {
                                        service.issueApprovalGrant(plan.toolId, plan.call.arguments)
                                            .getOrThrow()
                                            ?: error("Approval grant was not issued for an approval-required tool.")
                                    } else null
                                    when (val execution = AiToolCallOrchestrator.executeWithMcpService(
                                        plan = plan,
                                        service = service,
                                        approvalGrant = grant
                                    )) {
                                        is AiToolCallExecution.Completed -> execution.result
                                        is AiToolCallExecution.ApprovalPending -> CanonicalToolResult(
                                            plan.call.providerCallId,
                                            plan.call.toolName,
                                            "Approval was not provided. The tool was not executed.",
                                            isError = true
                                        )
                                        is AiToolCallExecution.Denied -> CanonicalToolResult(
                                            plan.call.providerCallId,
                                            plan.call.toolName,
                                            execution.reason.take(64 * 1024),
                                            isError = true
                                        )
                                    }
                                }
                            }
                        }
                    }

                    val continuation = AiProviderContinuationRequestFactory.create(
                        config = profile.config,
                        apiKey = profile.apiKey,
                        prompt = prompt,
                        previousResponse = payload,
                        results = toolResults,
                        tools = tools
                    )
                    payload = post(continuation)
                    continuationRounds++
                }
            }
        }
    }

    internal fun decodePayload(provider: AiProviderId, payload: Map<String, Any?>): CanonicalAiResult {
        AiProviderRegistry.registerBuiltIns()
        val adapter = requireNotNull(AiProviderRegistry.get(provider)) {
            "No AI adapter is registered for the selected provider."
        }
        return adapter.decodeResponse(payload)
    }

    internal fun JSONObject.toBoundedMap(): Map<String, Any?> {
        var visited = 0
        fun convert(value: Any?, depth: Int): Any? {
            require(depth <= MAX_JSON_DEPTH) { "AI response nesting limit exceeded." }
            visited += 1
            require(visited <= MAX_JSON_NODES) { "AI response complexity limit exceeded." }
            return when (value) {
                is JSONObject -> {
                    val map = linkedMapOf<String, Any?>()
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        require(key.length <= 256) { "AI response key exceeds the configured limit." }
                        map[key] = convert(value.get(key), depth + 1)
                    }
                    map
                }
                is JSONArray -> (0 until value.length()).map { convert(value.get(it), depth + 1) }
                JSONObject.NULL -> null
                is String -> {
                    require(value.length <= 256 * 1024) { "AI response string exceeds the configured limit." }
                    value
                }
                is Number, is Boolean, null -> value
                else -> throw IllegalArgumentException("AI response contains an unsupported value.")
            }
        }
        @Suppress("UNCHECKED_CAST")
        return convert(this, 0) as Map<String, Any?>
    }
}
