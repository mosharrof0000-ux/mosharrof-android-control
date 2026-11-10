package org.mosharrof.androidcontrol

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AiToolCallOrchestratorTest {
    @After fun cleanup() {
        ConnectorRegistry.clearForTests()
        ToolApprovalGrants.clearForTests()
    }

    private fun registerTool(risk: ConnectorRegistry.Risk, connected: Boolean = true) {
        ConnectorRegistry.registerConnector(
            ConnectorRegistry.Connector(
                id = "orchestrator.connector",
                name = "Orchestrator test connector",
                transport = ConnectorRegistry.Transport.MCP_STREAMABLE_HTTP,
                endpoint = "https://example.com/mcp",
                state = if (connected) ConnectorRegistry.ConnectionState.CONNECTED
                    else ConnectorRegistry.ConnectionState.DISCONNECTED
            )
        ).getOrThrow()
        ConnectorRegistry.registerTool(
            ConnectorRegistry.Tool(
                id = "orchestrator.tool",
                connectorId = "orchestrator.connector",
                name = "lookup",
                description = "Lookup test data",
                risk = risk,
                requiredFields = setOf("query"),
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to mapOf("query" to mapOf("type" to "string")),
                    "required" to listOf("query"),
                    "additionalProperties" to false
                )
            )
        ).getOrThrow()
    }

    private fun call() = CanonicalToolCall(
        providerCallId = "provider-call-1",
        toolName = "lookup",
        arguments = mapOf("query" to "hello")
    )

    @Test fun readOnlyCallExecutesWithoutGrantAndPreservesProviderCallId() {
        registerTool(ConnectorRegistry.Risk.READ_ONLY)
        val plan = AiToolCallOrchestrator.plan(call()) as AiToolCallPlan.Ready
        assertFalse(plan.approvalRequired)

        val result = AiToolCallOrchestrator.execute(plan) { toolId, arguments, grant ->
            assertEquals("orchestrator.tool", toolId)
            assertEquals("hello", arguments["query"])
            assertNull(grant)
            Result.success("found")
        } as AiToolCallExecution.Completed

        assertEquals("provider-call-1", result.result.providerCallId)
        assertEquals("found", result.result.content)
        assertFalse(result.result.isError)
    }

    @Test fun writeCallWaitsForExplicitApprovalGrant() {
        registerTool(ConnectorRegistry.Risk.WRITE)
        val plan = AiToolCallOrchestrator.plan(call()) as AiToolCallPlan.Ready
        assertTrue(plan.approvalRequired)
        var invoked = false

        val result = AiToolCallOrchestrator.execute(plan) { _, _, _ ->
            invoked = true
            Result.success("should not run")
        }

        assertTrue(result is AiToolCallExecution.ApprovalPending)
        assertFalse(invoked)
    }

    @Test fun writeCallPassesOnlySuppliedGrantToAuthorizedExecutor() {
        registerTool(ConnectorRegistry.Risk.WRITE)
        val plan = AiToolCallOrchestrator.plan(call()) as AiToolCallPlan.Ready

        val result = AiToolCallOrchestrator.execute(plan, "grant-from-approved-ui") { _, _, grant ->
            assertEquals("grant-from-approved-ui", grant)
            Result.success("written")
        } as AiToolCallExecution.Completed

        assertEquals("written", result.result.content)
    }

    @Test fun disconnectedConnectorIsDeniedBeforeExecution() {
        registerTool(ConnectorRegistry.Risk.READ_ONLY, connected = false)
        val result = AiToolCallOrchestrator.plan(call())
        assertTrue(result is AiToolCallPlan.Denied)
    }

    @Test fun schemaMismatchIsDeniedBeforeExecution() {
        registerTool(ConnectorRegistry.Risk.READ_ONLY)
        val invalid = call().copy(arguments = mapOf("query" to 42))
        assertTrue(AiToolCallOrchestrator.plan(invalid) is AiToolCallPlan.Denied)
    }

    @Test fun permissionIsRecheckedImmediatelyBeforeExecution() {
        registerTool(ConnectorRegistry.Risk.READ_ONLY)
        val plan = AiToolCallOrchestrator.plan(call()) as AiToolCallPlan.Ready
        ConnectorRegistry.updateConnectionState(
            "orchestrator.connector",
            ConnectorRegistry.ConnectionState.DISCONNECTED
        ).getOrThrow()
        var invoked = false

        val result = AiToolCallOrchestrator.execute(plan) { _, _, _ ->
            invoked = true
            Result.success("unexpected")
        }

        assertTrue(result is AiToolCallExecution.Denied)
        assertFalse(invoked)
    }

    @Test fun readOnlyCallRejectsUnexpectedApprovalGrant() {
        registerTool(ConnectorRegistry.Risk.READ_ONLY)
        val plan = AiToolCallOrchestrator.plan(call()) as AiToolCallPlan.Ready
        val result = AiToolCallOrchestrator.execute(plan, "unexpected") { _, _, _ ->
            fail("Executor must not be invoked")
            Result.success("")
        }
        assertTrue(result is AiToolCallExecution.Denied)
    }

    @Test fun executorFailureBecomesBoundedErrorResult() {
        registerTool(ConnectorRegistry.Risk.READ_ONLY)
        val plan = AiToolCallOrchestrator.plan(call()) as AiToolCallPlan.Ready
        val result = AiToolCallOrchestrator.execute(plan) { _, _, _ ->
            Result.failure(IllegalStateException("temporary failure\nretry later"))
        } as AiToolCallExecution.Completed

        assertTrue(result.result.isError)
        assertEquals("temporary failure retry later", result.result.content)
    }

    @Test fun successfulOutputIsBounded() {
        registerTool(ConnectorRegistry.Risk.READ_ONLY)
        val plan = AiToolCallOrchestrator.plan(call()) as AiToolCallPlan.Ready
        val result = AiToolCallOrchestrator.execute(plan) { _, _, _ ->
            Result.success("x".repeat(70 * 1024))
        } as AiToolCallExecution.Completed
        assertEquals(64 * 1024, result.result.content.length)
    }
}
