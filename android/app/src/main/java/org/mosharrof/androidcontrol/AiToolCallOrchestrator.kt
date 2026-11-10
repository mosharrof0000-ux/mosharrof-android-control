package org.mosharrof.androidcontrol

/**
 * Coordinates the policy boundary between provider-generated tool calls and the
 * existing connector runtime. It deliberately does not display UI, issue grants,
 * or execute arbitrary provider-supplied code.
 */
sealed class AiToolCallPlan {
    data class Ready(
        val call: CanonicalToolCall,
        val toolId: String,
        val approvalRequired: Boolean
    ) : AiToolCallPlan()

    data class Denied(val call: CanonicalToolCall, val reason: String) : AiToolCallPlan()
}

sealed class AiToolCallExecution {
    data class Completed(val result: CanonicalToolResult) : AiToolCallExecution()
    data class ApprovalPending(val call: CanonicalToolCall, val toolId: String) : AiToolCallExecution()
    data class Denied(val call: CanonicalToolCall, val reason: String) : AiToolCallExecution()
}

/**
 * One-call-at-a-time execution prevents an unapproved or failed call in a
 * provider's parallel batch from causing partial side effects.
 */
object AiToolCallOrchestrator {
    const val MAX_CALLS_PER_RESPONSE = 32

    fun planAll(calls: List<CanonicalToolCall>): Result<List<AiToolCallPlan>> = runCatching {
        require(calls.isNotEmpty() && calls.size <= MAX_CALLS_PER_RESPONSE) {
            "Provider tool-call count is outside the supported limit."
        }
        require(calls.map { it.providerCallId }.toSet().size == calls.size) {
            "Provider returned duplicate tool-call IDs."
        }
        calls.map(::plan)
    }

    fun plan(call: CanonicalToolCall): AiToolCallPlan =
        when (val decision = CanonicalToolCallGuard.inspect(call)) {
            is CanonicalToolCallDecision.ReadOnlyAllowed ->
                AiToolCallPlan.Ready(call, decision.toolId, approvalRequired = false)
            is CanonicalToolCallDecision.ApprovalRequired ->
                AiToolCallPlan.Ready(call, decision.toolId, approvalRequired = true)
            is CanonicalToolCallDecision.Denied ->
                AiToolCallPlan.Denied(call, decision.reason)
        }

    /**
     * Rechecks the registry and schema immediately before execution. A grant is
     * supplied only by the UI flow after explicit user approval; this method
     * never creates one.
     */
    fun execute(
        plan: AiToolCallPlan.Ready,
        approvalGrant: String? = null,
        executor: (toolId: String, arguments: Map<String, Any?>, approvalGrant: String?) -> Result<String>
    ): AiToolCallExecution {
        val refreshed = plan(plan.call)
        if (refreshed !is AiToolCallPlan.Ready || refreshed.toolId != plan.toolId) {
            val reason = (refreshed as? AiToolCallPlan.Denied)?.reason
                ?: "Tool permission or connector state changed; prepare the call again."
            return AiToolCallExecution.Denied(plan.call, reason)
        }
        if (refreshed.approvalRequired && approvalGrant.isNullOrBlank()) {
            return AiToolCallExecution.ApprovalPending(plan.call, plan.toolId)
        }
        if (!refreshed.approvalRequired && approvalGrant != null) {
            return AiToolCallExecution.Denied(
                plan.call,
                "A read-only tool must not receive an approval grant."
            )
        }

        val result = runCatching {
            executor(plan.toolId, plan.call.arguments, approvalGrant).getOrThrow()
        }
        return result.fold(
            onSuccess = { output ->
                val safeOutput = output.take(MAX_RESULT_CHARS)
                AiToolCallExecution.Completed(
                    CanonicalToolResult(
                        providerCallId = plan.call.providerCallId,
                        toolName = plan.call.toolName,
                        content = safeOutput
                    )
                )
            },
            onFailure = { failure ->
                AiToolCallExecution.Completed(
                    CanonicalToolResult(
                        providerCallId = plan.call.providerCallId,
                        toolName = plan.call.toolName,
                        content = safeFailureMessage(failure),
                        isError = true
                    )
                )
            }
        )
    }

    private fun safeFailureMessage(failure: Throwable): String =
        (failure.message ?: "Plugin execution failed.")
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .take(MAX_RESULT_CHARS)

    private const val MAX_RESULT_CHARS = 64 * 1024
}
