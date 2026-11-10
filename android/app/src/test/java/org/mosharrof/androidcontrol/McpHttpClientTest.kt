package org.mosharrof.androidcontrol

import org.junit.Assert.*
import org.junit.Test

class McpHttpClientTest {
    @Test fun acceptsHttpsEndpoint() {
        assertTrue(McpHttpClient.isSafeEndpoint("https://example.com/mcp"))
    }

    @Test fun rejectsHttpEndpoint() {
        assertFalse(McpHttpClient.isSafeEndpoint("http://example.com/mcp"))
    }

    @Test fun rejectsEndpointWithUserInfo() {
        assertFalse(McpHttpClient.isSafeEndpoint("https://user:pass@example.com/mcp"))
    }

    @Test fun rejectsEndpointWithFragment() {
        assertFalse(McpHttpClient.isSafeEndpoint("https://example.com/mcp#secret"))
    }

    @Test fun extractsMultipleSseDataEvents() {
        val events = McpHttpClient.extractSseDataEvents(
            ": keepalive\nevent: message\ndata: {\"id\":\"7\"}\n\n" +
                "event: message\ndata: {\"id\":\ndata: \"8\"}\n\n"
        )
        assertEquals(2, events.size)
        assertEquals("{\"id\":\"7\"}", events[0])
        assertEquals("{\"id\":\n\"8\"}", events[1])
    }

    @Test fun extractsUnterminatedFinalSseEvent() {
        val events = McpHttpClient.extractSseDataEvents(
            "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":\"9\"}"
        )
        assertEquals(1, events.size)
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":\"9\"}", events[0])
    }

    @Test fun mapsToolNameToMcpName() {
        assertEquals(
            "search",
            McpHttpClient.standardNameHeader("tools/call", mapOf("name" to "search"))
        )
    }

    @Test fun mapsPromptNameToMcpName() {
        assertEquals(
            "greeting",
            McpHttpClient.standardNameHeader("prompts/get", mapOf("name" to "greeting"))
        )
    }

    @Test fun mapsResourceUriToMcpName() {
        assertEquals(
            "file:///notes.txt",
            McpHttpClient.standardNameHeader(
                "resources/read",
                mapOf("uri" to "file:///notes.txt")
            )
        )
    }

    @Test fun unrelatedMethodDoesNotRequireMcpName() {
        assertNull(
            McpHttpClient.standardNameHeader("initialize", emptyMap())
        )
    }

    @Test fun rejectsMissingRequiredName() {
        try {
            McpHttpClient.standardNameHeader("tools/call", emptyMap())
            fail("Missing tool name must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected
        }
    }

    @Test fun encodesControlCharactersInName() {
        val original = "search\nInjected"
        val header = McpHttpClient.standardNameHeader(
            "tools/call", mapOf("name" to original)
        )!!
        val encoded = header.removePrefix("=?base64?").removeSuffix("?=")
        val decoded = String(
            java.util.Base64.getDecoder().decode(encoded), Charsets.UTF_8
        )
        assertEquals(original, decoded)
    }

    @Test fun encodesLeadingAndTrailingWhitespace() {
        val original = " padded "
        val header = McpHttpClient.standardNameHeader(
            "tools/call", mapOf("name" to original)
        )!!
        val encoded = header.removePrefix("=?base64?").removeSuffix("?=")
        assertEquals(
            original,
            String(java.util.Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        )
    }

    @Test fun encodesSentinelLookingName() {
        val original = "=?base64?literal?="
        val header = McpHttpClient.standardNameHeader(
            "tools/call", mapOf("name" to original)
        )!!
        val encoded = header.removePrefix("=?base64?").removeSuffix("?=")
        assertEquals(
            original,
            String(java.util.Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        )
    }

    @Test fun encodesUnicodeMcpName() {
        val original = "বাংলা"
        val header = McpHttpClient.standardNameHeader(
            "tools/call",
            mapOf("name" to original)
        )!!

        assertTrue(header.startsWith("=?base64?"))
        assertTrue(header.endsWith("?="))

        val encoded = header.removePrefix("=?base64?").removeSuffix("?=")
        val decoded = String(
            java.util.Base64.getDecoder().decode(encoded),
            Charsets.UTF_8
        )
        assertEquals(original, decoded)
    }


    @Test fun acceptsCompleteModernMetadata() {
        val params = mapOf(
            "_meta" to mapOf(
                "io.modelcontextprotocol/protocolVersion" to "2026-07-28",
                "io.modelcontextprotocol/clientCapabilities" to emptyMap<String, Any>(),
                "io.modelcontextprotocol/clientInfo" to mapOf(
                    "name" to "Mosharrof",
                    "version" to "1.0"
                )
            )
        )
        assertTrue(McpHttpClient.hasValidModernMetadata(params, "2026-07-28"))
    }

    @Test fun rejectsModernMetadataWithoutCapabilities() {
        val params = mapOf(
            "_meta" to mapOf(
                "io.modelcontextprotocol/protocolVersion" to "2026-07-28"
            )
        )
        assertFalse(McpHttpClient.hasValidModernMetadata(params, "2026-07-28"))
    }

    @Test fun rejectsModernMetadataWithWrongVersion() {
        val params = mapOf(
            "_meta" to mapOf(
                "io.modelcontextprotocol/protocolVersion" to "2025-11-25",
                "io.modelcontextprotocol/clientCapabilities" to emptyMap<String, Any>()
            )
        )
        assertFalse(McpHttpClient.hasValidModernMetadata(params, "2026-07-28"))
    }

    @Test fun acceptsModernMetadataWithoutOptionalClientInfo() {
        val params = mapOf(
            "_meta" to mapOf(
                "io.modelcontextprotocol/protocolVersion" to "2026-07-28",
                "io.modelcontextprotocol/clientCapabilities" to emptyMap<String, Any>()
            )
        )
        assertTrue(McpHttpClient.hasValidModernMetadata(params, "2026-07-28"))
    }


    @Test fun mapsAnnotatedStringToMcpParamHeader() {
        val tool = McpProtocol.ToolDefinition(
            name = "search",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "region" to mapOf("type" to "string", "x-mcp-header" to "Region")
                )
            )
        )
        assertEquals(
            mapOf("Mcp-Param-Region" to "Asia"),
            McpHttpClient.parameterHeaders(tool, mapOf("region" to "Asia"))
        )
    }

    @Test fun mapsNestedBooleanHeader() {
        val tool = McpProtocol.ToolDefinition(
            name = "search",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "options" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "enabled" to mapOf(
                                "type" to "boolean",
                                "x-mcp-header" to "Feature-Enabled"
                            )
                        )
                    )
                )
            )
        )
        assertEquals(
            mapOf("Mcp-Param-Feature-Enabled" to "true"),
            McpHttpClient.parameterHeaders(
                tool,
                mapOf("options" to mapOf("enabled" to true))
            )
        )
    }

    @Test fun omitsMissingAndNullAnnotatedArguments() {
        val tool = McpProtocol.ToolDefinition(
            name = "search",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "region" to mapOf("type" to "string", "x-mcp-header" to "Region")
                )
            )
        )
        assertTrue(McpHttpClient.parameterHeaders(tool, emptyMap()).isEmpty())
        assertTrue(
            McpHttpClient.parameterHeaders(tool, mapOf("region" to null)).isEmpty()
        )
    }

    @Test fun encodesUnsafeHeaderString() {
        val tool = McpProtocol.ToolDefinition(
            name = "search",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "region" to mapOf("type" to "string", "x-mcp-header" to "Region")
                )
            )
        )
        val value = "বাংলা"
        val header = McpHttpClient.parameterHeaders(
            tool, mapOf("region" to value)
        )["Mcp-Param-Region"]!!
        val encoded = header.removePrefix("=?base64?").removeSuffix("?=")
        assertEquals(
            value,
            String(java.util.Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        )
    }

    @Test fun encodesSentinelLookingHeaderString() {
        val tool = McpProtocol.ToolDefinition(
            name = "search",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "region" to mapOf("type" to "string", "x-mcp-header" to "Region")
                )
            )
        )
        val original = "=?base64?SGVsbG8=?="
        val header = McpHttpClient.parameterHeaders(
            tool, mapOf("region" to original)
        )["Mcp-Param-Region"]!!

        assertNotEquals(original, header)
        val encoded = header.removePrefix("=?base64?").removeSuffix("?=")
        assertEquals(
            original,
            String(java.util.Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        )
    }

    @Test fun acceptsMaximumSafeIntegerHeaderValue() {
        val tool = McpProtocol.ToolDefinition(
            name = "search",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "count" to mapOf("type" to "integer", "x-mcp-header" to "Count")
                )
            )
        )
        assertEquals(
            "9007199254740991",
            McpHttpClient.parameterHeaders(
                tool, mapOf("count" to 9_007_199_254_740_991L)
            )["Mcp-Param-Count"]
        )
    }

    @Test fun rejectsIntegerOutsideSafeRange() {
        val tool = McpProtocol.ToolDefinition(
            name = "search",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "count" to mapOf("type" to "integer", "x-mcp-header" to "Count")
                )
            )
        )
        try {
            McpHttpClient.parameterHeaders(
                tool, mapOf("count" to 9_007_199_254_740_992L)
            )
            fail("Unsafe integer must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected
        }
    }

    @Test fun rejectsWrongAnnotatedArgumentType() {
        val tool = McpProtocol.ToolDefinition(
            name = "search",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "region" to mapOf("type" to "string", "x-mcp-header" to "Region")
                )
            )
        )
        try {
            McpHttpClient.parameterHeaders(tool, mapOf("region" to 123))
            fail("Wrong argument type must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected
        }
    }


    @Test fun responseIdRequiresExactStringTypeAndValue() {
        assertTrue(
            McpHttpClient.hasMatchingResponseId(
                org.json.JSONObject("""{"id":"7"}"""),
                "7"
            )
        )
        assertFalse(
            McpHttpClient.hasMatchingResponseId(
                org.json.JSONObject("""{"id":7}"""),
                "7"
            )
        )
        assertFalse(
            McpHttpClient.hasMatchingResponseId(
                org.json.JSONObject("""{"id":"8"}"""),
                "7"
            )
        )
    }

    @Test fun sseSelectorRejectsNumericIdWhenStringIdExpected() {
        val events = listOf(
            """{"jsonrpc":"2.0","id":7,"result":{}}"""
        )
        try {
            McpHttpClient.selectSseResponsePayload(events, "7")
            fail("Numeric ID must not match a string request ID")
        } catch (_: java.io.IOException) {
            // Expected
        }
    }

    @Test fun sseSelectorChoosesOnlyExactMatchingStringId() {
        val wrong = """{"jsonrpc":"2.0","id":"8","result":{}}"""
        val matching = """{"jsonrpc":"2.0","id":"7","result":{}}"""
        assertEquals(
            matching,
            McpHttpClient.selectSseResponsePayload(listOf(wrong, matching), "7")
        )
    }
}
