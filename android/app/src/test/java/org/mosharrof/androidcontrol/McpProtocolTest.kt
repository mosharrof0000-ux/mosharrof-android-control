package org.mosharrof.androidcontrol

import org.junit.Assert.*
import org.junit.Test

class McpProtocolTest {
    @Test fun initializeUsesJsonRpcAndProtocolFields() {
        val request = McpProtocol.initializeRequest("1", "Mosharrof", "1.0", "test-version")
        assertEquals("2.0", request.jsonrpc)
        assertEquals("initialize", request.method)
        assertEquals("test-version", request.params["protocolVersion"])
        assertTrue(request.params.containsKey("clientInfo"))
    }

    @Test fun modernDiscoveryUsesCurrentProtocolVersion() {
        val request = McpProtocol.modernDiscoveryRequest("9", "Mosharrof", "1.0")
        assertEquals("server/discover", request.method)
        val meta = request.params["_meta"] as Map<*, *>
        assertEquals("2026-07-28", meta["io.modelcontextprotocol/protocolVersion"])
    }

    @Test fun modernToolsListCarriesClientMetadata() {
        val request = McpProtocol.modernToolsListRequest("10", "Mosharrof", "1.0")
        assertEquals("tools/list", request.method)
        assertTrue(request.params.containsKey("_meta"))
    }

    @Test fun modernToolsCallCarriesMetadataAndArguments() {
        val request = McpProtocol.modernToolsCallRequest(
            "11", "files.read", mapOf("path" to "notes.txt"), "Mosharrof", "1.0"
        )
        assertEquals("tools/call", request.method)
        assertEquals("files.read", request.params["name"])
        assertTrue(request.params.containsKey("_meta"))
    }

    @Test fun modernToolsListCarriesOpaqueCursor() {
        val request = McpProtocol.modernToolsListRequest(
            "12", "Mosharrof", "1.0", cursor = "opaque-next"
        )
        assertEquals("tools/list", request.method)
        assertEquals("opaque-next", request.params["cursor"])
        assertTrue(request.params.containsKey("_meta"))
    }

    @Test fun legacyToolsListCarriesOpaqueCursor() {
        val request = McpProtocol.toolsListRequest("13", cursor = "opaque-next")
        assertEquals("opaque-next", request.params["cursor"])
    }

    @Test fun initializedIsNotificationWithoutId() {
        val request = McpProtocol.initializedNotification()
        assertNull(request.id)
        assertEquals("notifications/initialized", request.method)
    }

    @Test fun toolsListRequestUsesExpectedMethod() {
        assertEquals("tools/list", McpProtocol.toolsListRequest("2").method)
    }

    @Test fun toolsCallCarriesNameAndArguments() {
        val request = McpProtocol.toolsCallRequest("3", "files.read", mapOf("path" to "notes.txt"))
        assertEquals("tools/call", request.method)
        assertEquals("files.read", request.params["name"])
        assertEquals("notes.txt", (request.params["arguments"] as Map<*, *>)["path"])
    }

    @Test fun rejectsUnsafeToolNames() {
        val tool = McpProtocol.ToolDefinition("../secret", inputSchema = mapOf("type" to "object"))
        assertFalse(McpProtocol.validateTool(tool))
    }

    @Test fun acceptsObjectInputSchema() {
        val tool = McpProtocol.ToolDefinition("files.read", inputSchema = mapOf("type" to "object"))
        assertTrue(McpProtocol.validateTool(tool))
    }

    @Test fun acceptsDeclaredStringHeader() {
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "region" to mapOf("type" to "string", "x-mcp-header" to "Region")
            )
        )
        assertTrue(McpProtocol.validateInputSchema(schema))
    }

    @Test fun acceptsNestedObjectHeader() {
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "options" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "enabled" to mapOf("type" to "boolean", "x-mcp-header" to "Feature-Enabled")
                    )
                )
            )
        )
        assertTrue(McpProtocol.validateInputSchema(schema))
    }

    @Test fun rejectsUnsupportedHeaderPropertyType() {
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "amount" to mapOf("type" to "number", "x-mcp-header" to "Amount")
            )
        )
        assertFalse(McpProtocol.validateInputSchema(schema))
    }

    @Test fun rejectsDuplicateHeaderNamesIgnoringCase() {
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "first" to mapOf("type" to "string", "x-mcp-header" to "Region"),
                "second" to mapOf("type" to "integer", "x-mcp-header" to "region")
            )
        )
        assertFalse(McpProtocol.validateInputSchema(schema))
    }

    @Test fun rejectsInvalidHeaderToken() {
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "value" to mapOf("type" to "string", "x-mcp-header" to "Bad Header")
            )
        )
        assertFalse(McpProtocol.validateInputSchema(schema))
    }

    @Test fun rejectsHeaderAnnotationsOutsideStaticProperties() {
        val invalidSchemas = listOf(
            mapOf(
                "type" to "object",
                "items" to mapOf("x-mcp-header" to "Bad")
            ),
            mapOf(
                "type" to "object",
                "oneOf" to listOf(
                    mapOf("x-mcp-header" to "Bad")
                )
            ),
            mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "value" to mapOf(
                        "type" to "string",
                        "\$ref" to mapOf("x-mcp-header" to "Bad")
                    )
                )
            )
        )
        invalidSchemas.forEach {
            assertFalse(McpProtocol.validateInputSchema(it))
        }
    }

    @Test fun rejectsUnsupportedSchemaAssertionsInsteadOfIgnoringThem() {
        val schemas = listOf(
            mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "value" to mapOf(
                        "type" to "string",
                        "oneOf" to listOf(mapOf("const" to "a"), mapOf("const" to "b"))
                    )
                )
            ),
            mapOf(
                "type" to "object",
                "properties" to mapOf("value" to mapOf("type" to "string", "patternProperties" to emptyMap<String, Any>()))
            ),
            mapOf(
                "type" to "object",
                "properties" to mapOf("items" to mapOf("type" to "array", "uniqueItems" to true))
            ),
            mapOf(
                "type" to "object",
                "properties" to mapOf("value" to mapOf("\$ref" to "#/definitions/value"))
            )
        )
        schemas.forEach { assertFalse(McpProtocol.validateInputSchema(it)) }
    }

    @Test fun rejectsMalformedAdditionalPropertiesConstraint() {
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf("value" to mapOf("type" to "string")),
            "additionalProperties" to "false"
        )
        assertFalse(McpProtocol.validateInputSchema(schema))
    }

    @Test fun rejectsSchemaNestingBeyondSupportedDepth() {
        var nested: Map<String, Any?> = mapOf("type" to "string")
        repeat(40) {
            nested = mapOf(
                "type" to "object",
                "properties" to mapOf("child" to nested)
            )
        }
        val root = mapOf(
            "type" to "object",
            "properties" to mapOf("root" to nested)
        )
        assertFalse(McpProtocol.validateInputSchema(root))
    }

    @Test fun responseMustMatchRequestAndContainExactlyOneOutcome() {
        assertTrue(McpProtocol.validateResponse(
            McpProtocol.Response(id = "4", result = mapOf("ok" to true)), "4"
        ))
        assertFalse(McpProtocol.validateResponse(
            McpProtocol.Response(id = "wrong", result = mapOf("ok" to true)), "4"
        ))
        assertFalse(McpProtocol.validateResponse(
            McpProtocol.Response(id = "4"), "4"
        ))
    }

    @Test fun rejectsBlankInitializeFields() {
        try {
            McpProtocol.initializeRequest("", "Mosharrof", "1.0", "test")
            fail("Blank request id should be rejected")
        } catch (_: IllegalArgumentException) {
            assertTrue(true)
        }
    }
}
