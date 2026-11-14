package org.mosharrof.androidcontrol

import org.junit.Assert.*
import org.junit.Test

class McpSchemaFormTest {
    @Test
    fun buildsAndParsesFlatTypedFields() {
        val schema = mapOf<String, Any?>(
            "type" to "object",
            "properties" to mapOf(
                "title" to mapOf("type" to "string"),
                "count" to mapOf("type" to "integer"),
                "ratio" to mapOf("type" to "number"),
                "enabled" to mapOf("type" to "boolean"),
                "mode" to mapOf("type" to "string", "enum" to listOf("fast", "safe"))
            ),
            "required" to listOf("title", "count")
        )
        val fields = requireNotNull(McpSchemaForm.fields(schema))
        val parsed = McpSchemaForm.parse(
            fields,
            mapOf("title" to "hello", "count" to "7", "ratio" to "1.25"),
            mapOf("enabled" to true),
            mapOf("mode" to 1)
        ).getOrThrow()

        assertEquals("hello", parsed["title"])
        assertEquals(7L, parsed["count"])
        assertEquals(java.math.BigDecimal("1.25"), parsed["ratio"])
        assertEquals(true, parsed["enabled"])
        assertEquals("safe", parsed["mode"])
    }

    @Test
    fun omitsBlankOptionalFieldsAndUsesDefaults() {
        val schema = mapOf<String, Any?>(
            "type" to "object",
            "properties" to mapOf(
                "optional" to mapOf("type" to "string"),
                "limit" to mapOf("type" to "integer", "default" to 5)
            )
        )
        val fields = requireNotNull(McpSchemaForm.fields(schema))
        val parsed = McpSchemaForm.parse(fields, emptyMap(), emptyMap(), emptyMap()).getOrThrow()
        assertFalse(parsed.containsKey("optional"))
        assertEquals(5L, parsed["limit"])
    }

    @Test
    fun rejectsMissingRequiredAndInvalidNumericInput() {
        val schema = mapOf<String, Any?>(
            "type" to "object",
            "properties" to mapOf(
                "count" to mapOf("type" to "integer"),
                "ratio" to mapOf("type" to "number")
            ),
            "required" to listOf("count")
        )
        val fields = requireNotNull(McpSchemaForm.fields(schema))
        assertTrue(McpSchemaForm.parse(fields, emptyMap(), emptyMap(), emptyMap()).isFailure)
        assertTrue(
            McpSchemaForm.parse(
                fields, mapOf("count" to "2.5", "ratio" to "x"), emptyMap(), emptyMap()
            ).isFailure
        )
    }

    @Test
    fun complexOrOversizedSchemasFallBackInsteadOfBuildingUnsafeForm() {
        val nested = mapOf<String, Any?>(
            "type" to "object",
            "properties" to mapOf("child" to mapOf("type" to "string"))
        )
        assertNull(
            McpSchemaForm.fields(
                mapOf("type" to "object", "properties" to mapOf("nested" to nested))
            )
        )
        val tooMany = (1..33).associate { "field$it" to mapOf("type" to "string") }
        assertNull(McpSchemaForm.fields(mapOf("type" to "object", "properties" to tooMany)))
    }
}
