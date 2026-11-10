package org.mosharrof.androidcontrol

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConnectorRegistryTest {

    @Before
    fun setUp() {
        ConnectorRegistry.clearForTests()
    }

    @After
    fun tearDown() {
        ConnectorRegistry.clearForTests()
    }

    private fun connector(
        state: ConnectorRegistry.ConnectionState =
            ConnectorRegistry.ConnectionState.CONNECTED,
        endpoint: String = "https://api.example.com/tools"
    ) = ConnectorRegistry.Connector(
        id = "example.api",
        name = "Example API",
        transport = ConnectorRegistry.Transport.HTTPS_API,
        endpoint = endpoint,
        state = state
    )

    private fun tool(
        id: String = "example.read",
        risk: ConnectorRegistry.Risk = ConnectorRegistry.Risk.READ_ONLY,
        inputSchema: Map<String, Any?> = mapOf("type" to "object")
    ) = ConnectorRegistry.Tool(
        id = id,
        connectorId = "example.api",
        name = "Example tool",
        description = "Test tool",
        risk = risk,
        inputSchema = inputSchema
    )

    @Test
    fun rejectsToolWithInvalidHeaderSchema() {
        ConnectorRegistry.registerConnector(connector())
        val invalidSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "value" to mapOf("type" to "number", "x-mcp-header" to "Amount")
            )
        )
        assertFalse(ConnectorRegistry.registerTool(tool(inputSchema = invalidSchema)).isSuccess)
    }

    @Test
    fun acceptsAndStoresValidToolSchema() {
        ConnectorRegistry.registerConnector(connector())
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "region" to mapOf("type" to "string", "x-mcp-header" to "Region")
            )
        )
        assertTrue(ConnectorRegistry.registerTool(tool(inputSchema = schema)).isSuccess)
        assertEquals(schema, ConnectorRegistry.toolSnapshot().single().inputSchema)
    }

    @Test
    fun acceptsHttpsConnector() {
        assertTrue(ConnectorRegistry.registerConnector(connector()).isSuccess)
    }

    @Test
    fun rejectsInsecureHttpEndpoint() {
        assertFalse(
            ConnectorRegistry.registerConnector(
                connector(endpoint = "http://api.example.com/tools")
            ).isSuccess
        )
    }

    @Test
    fun rejectsDuplicateConnector() {
        ConnectorRegistry.registerConnector(connector())
        assertFalse(ConnectorRegistry.registerConnector(connector()).isSuccess)
    }

    @Test
    fun toolRequiresRegisteredConnector() {
        assertFalse(ConnectorRegistry.registerTool(tool()).isSuccess)
    }

    @Test
    fun readOnlyToolAllowedOnlyWhenConnected() {
        ConnectorRegistry.registerConnector(
            connector(ConnectorRegistry.ConnectionState.CONFIGURED)
        )
        ConnectorRegistry.registerTool(tool())
        assertEquals(
            ConnectorRegistry.Decision.DENY_CONNECTOR_UNAVAILABLE,
            ConnectorRegistry.authorize("example.read")
        )
    }

    @Test
    fun connectedReadOnlyToolCanRunWithoutWriteApproval() {
        ConnectorRegistry.registerConnector(connector())
        ConnectorRegistry.registerTool(tool())
        assertEquals(
            ConnectorRegistry.Decision.ALLOW_READ_ONLY,
            ConnectorRegistry.authorize("example.read")
        )
    }

    @Test
    fun writeToolRequiresExplicitApproval() {
        ConnectorRegistry.registerConnector(connector())
        ConnectorRegistry.registerTool(
            tool(risk = ConnectorRegistry.Risk.WRITE)
        )
        assertEquals(
            ConnectorRegistry.Decision.APPROVAL_REQUIRED,
            ConnectorRegistry.authorize("example.read")
        )
        assertEquals(
            ConnectorRegistry.Decision.APPROVAL_REQUIRED,
            ConnectorRegistry.authorize("example.read")
        )
    }

    @Test
    fun externalSideEffectAndDestructiveToolsRequireApproval() {
        ConnectorRegistry.registerConnector(connector())

        val risks = listOf(
            ConnectorRegistry.Risk.EXTERNAL_SIDE_EFFECT,
            ConnectorRegistry.Risk.DESTRUCTIVE
        )

        risks.forEach { risk ->
            val id = "example.${risk.name.lowercase()}"
            assertTrue(
                "Tool registration failed for $risk",
                ConnectorRegistry.registerTool(tool(id = id, risk = risk)).isSuccess
            )
            assertEquals(
                "$risk must require explicit approval",
                ConnectorRegistry.Decision.APPROVAL_REQUIRED,
                ConnectorRegistry.authorize(id)
            )
        }
    }

    @Test
    fun unregisterToolsForConnectorRemovesOnlyMatchingTools() {
        val first = connector()
        val second = connector(
            endpoint = "https://other.example.com/tools"
        ).copy(id = "other.api", name = "Other API")

        assertTrue(ConnectorRegistry.registerConnector(first).isSuccess)
        assertTrue(ConnectorRegistry.registerConnector(second).isSuccess)

        assertTrue(ConnectorRegistry.registerTool(tool(id = "example.read")).isSuccess)
        assertTrue(ConnectorRegistry.registerTool(
            tool(id = "example.write").copy(
                risk = ConnectorRegistry.Risk.WRITE
            )
        ).isSuccess)

        assertTrue(ConnectorRegistry.registerTool(
            tool(id = "other.read").copy(connectorId = second.id)
        ).isSuccess)

        val removed = ConnectorRegistry.unregisterToolsForConnector(first.id)

        assertEquals(2, removed)
        assertEquals(listOf("other.read"), ConnectorRegistry.toolSnapshot().map { it.id })
        assertEquals(0, ConnectorRegistry.unregisterToolsForConnector(first.id))
    }

    @Test
    fun rejectsRequiredFieldsMismatch() {
        ConnectorRegistry.registerConnector(connector())
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf("region" to mapOf("type" to "string")),
            "required" to listOf("region")
        )
        val candidate = tool(inputSchema = schema).copy(requiredFields = emptySet())
        assertFalse(ConnectorRegistry.registerTool(candidate).isSuccess)
    }

    @Test
    fun rejectsRequiredFieldMissingFromProperties() {
        ConnectorRegistry.registerConnector(connector())
        val schema = mapOf(
            "type" to "object",
            "properties" to emptyMap<String, Any?>(),
            "required" to listOf("missing")
        )
        val candidate = tool(inputSchema = schema).copy(
            requiredFields = setOf("missing")
        )
        assertFalse(ConnectorRegistry.registerTool(candidate).isSuccess)
    }

    @Test
    fun rejectsDuplicateToolId() {
        ConnectorRegistry.registerConnector(connector())
        assertTrue(ConnectorRegistry.registerTool(tool()).isSuccess)
        assertFalse(ConnectorRegistry.registerTool(tool()).isSuccess)
    }

    @Test
    fun schemaIsDeepCopiedOnRegistrationAndSnapshot() {
        ConnectorRegistry.registerConnector(connector())

        val nested = mutableMapOf<String, Any?>("type" to "string")
        val properties = mutableMapOf<String, Any?>("region" to nested)
        val schema = mutableMapOf<String, Any?>(
            "type" to "object",
            "properties" to properties
        )

        assertTrue(ConnectorRegistry.registerTool(tool(inputSchema = schema)).isSuccess)

        nested["type"] = "number"
        assertEquals(
            "string",
            (((ConnectorRegistry.toolSnapshot().single().inputSchema["properties"]
                as Map<*, *>)["region"] as Map<*, *>)["type"])
        )

        val snapshot = ConnectorRegistry.toolSnapshot().single()
        val snapshotProperties =
            snapshot.inputSchema["properties"] as MutableMap<String, Any?>
        val snapshotRegion = snapshotProperties["region"] as MutableMap<String, Any?>
        snapshotRegion["type"] = "boolean"

        assertEquals(
            "string",
            (((ConnectorRegistry.toolSnapshot().single().inputSchema["properties"]
                as Map<*, *>)["region"] as Map<*, *>)["type"])
        )
    }

    @Test
    fun unknownToolIsDenied() {
        assertEquals(
            ConnectorRegistry.Decision.DENY_NOT_REGISTERED,
            ConnectorRegistry.authorize("unknown.tool")
        )
    }
}
