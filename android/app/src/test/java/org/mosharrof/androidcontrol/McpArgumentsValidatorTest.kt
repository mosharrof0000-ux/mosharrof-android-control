package org.mosharrof.androidcontrol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpArgumentsValidatorTest {

    private val schema = mapOf<String, Any?>(
        "type" to "object",
        "properties" to mapOf(
            "text" to mapOf("type" to "string"),
            "count" to mapOf("type" to "integer"),
            "enabled" to mapOf("type" to "boolean"),
            "meta" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "label" to mapOf("type" to "string")
                ),
                "required" to listOf("label")
            ),
            "items" to mapOf(
                "type" to "array",
                "items" to mapOf("type" to "string")
            )
        ),
        "required" to listOf("text"),
        "additionalProperties" to false
    )

    @Test
    fun acceptsValidArguments() {
        assertTrue(
            McpArgumentsValidator.validate(
                schema,
                mapOf(
                    "text" to "hello",
                    "count" to 3,
                    "enabled" to true,
                    "meta" to mapOf("label" to "test"),
                    "items" to listOf("a", "b")
                ),
                listOf("text")
            )
        )
    }

    @Test
    fun rejectsMissingRequiredArgument() {
        assertFalse(McpArgumentsValidator.validate(schema, emptyMap(), listOf("text")))
    }

    @Test
    fun rejectsNullRequiredArgument() {
        assertFalse(
            McpArgumentsValidator.validate(
                schema, mapOf("text" to null), listOf("text")
            )
        )
    }

    @Test
    fun acceptsNullForRequiredPropertyWhenNullTypeIsDeclared() {
        val nullableSchema = mapOf<String, Any?>(
            "type" to "object",
            "properties" to mapOf(
                "maybe" to mapOf("type" to listOf("string", "null"))
            ),
            "required" to listOf("maybe"),
            "additionalProperties" to false
        )

        assertTrue(McpArgumentsValidator.validate(nullableSchema, mapOf("maybe" to null), listOf("maybe")))
    }

    @Test
    fun acceptsNullForNestedRequiredPropertyWhenNullTypeIsDeclared() {
        val nullableNestedSchema = mapOf<String, Any?>(
            "type" to "object",
            "properties" to mapOf(
                "nested" to mapOf(
                    "type" to "object",
                    "properties" to mapOf("maybe" to mapOf("type" to "null")),
                    "required" to listOf("maybe")
                )
            ),
            "required" to listOf("nested"),
            "additionalProperties" to false
        )

        assertTrue(
            McpArgumentsValidator.validate(
                nullableNestedSchema,
                mapOf("nested" to mapOf("maybe" to null)),
                listOf("nested")
            )
        )
    }

    @Test
    fun rejectsWrongPrimitiveType() {
        assertFalse(
            McpArgumentsValidator.validate(
                schema, mapOf("text" to 123), listOf("text")
            )
        )
    }

    @Test
    fun rejectsWrongNestedPropertyType() {
        assertFalse(
            McpArgumentsValidator.validate(
                schema,
                mapOf("text" to "ok", "meta" to mapOf("label" to 42)),
                listOf("text")
            )
        )
    }

    @Test
    fun rejectsWrongArrayItemType() {
        assertFalse(
            McpArgumentsValidator.validate(
                schema,
                mapOf("text" to "ok", "items" to listOf("valid", 99)),
                listOf("text")
            )
        )
    }

    @Test
    fun rejectsUndeclaredPropertyWhenAdditionalPropertiesFalse() {
        assertFalse(
            McpArgumentsValidator.validate(
                schema,
                mapOf("text" to "ok", "unexpected" to "value"),
                listOf("text")
            )
        )
    }

    @Test
    fun rejectsMissingNestedRequiredProperty() {
        assertFalse(
            McpArgumentsValidator.validate(
                schema,
                mapOf("text" to "ok", "meta" to emptyMap<String, Any?>()),
                listOf("text")
            )
        )
    }


    @Test
    fun acceptsLargeExactLongInteger() {
        val integerSchema = mapOf<String, Any?>(
            "type" to "object",
            "properties" to mapOf("id" to mapOf("type" to "integer"))
        )
        assertTrue(
            McpArgumentsValidator.validate(
                integerSchema,
                mapOf("id" to Long.MAX_VALUE),
                emptyList()
            )
        )
    }

    @Test
    fun rejectsFractionalInteger() {
        val integerSchema = mapOf<String, Any?>(
            "type" to "object",
            "properties" to mapOf("id" to mapOf("type" to "integer"))
        )
        assertFalse(
            McpArgumentsValidator.validate(
                integerSchema,
                mapOf("id" to 12.5),
                emptyList()
            )
        )
    }

    @Test
    fun rejectsInvalidNumericConstraint() {
        val invalidSchema = mapOf<String, Any?>(
            "type" to "object",
            "properties" to mapOf(
                "count" to mapOf("type" to "number", "minimum" to "not-a-number")
            )
        )
        assertFalse(
            McpArgumentsValidator.validate(
                invalidSchema,
                mapOf("count" to 5),
                emptyList()
            )
        )
    }

    @Test
    fun enforcesExclusiveNumericBoundary() {
        val boundedSchema = mapOf<String, Any?>(
            "type" to "object",
            "properties" to mapOf(
                "count" to mapOf("type" to "number", "exclusiveMinimum" to 5)
            )
        )
        assertFalse(
            McpArgumentsValidator.validate(
                boundedSchema, mapOf("count" to 5), emptyList()
            )
        )
        assertTrue(
            McpArgumentsValidator.validate(
                boundedSchema, mapOf("count" to 6), emptyList()
            )
        )
    }

    @Test
    fun rejectsNullSchemaForDeclaredProperty() {
        val invalidSchema = mapOf<String, Any?>(
            "type" to "object",
            "properties" to mapOf("text" to null)
        )
        assertFalse(
            McpArgumentsValidator.validate(
                invalidSchema, mapOf("text" to "hello"), emptyList()
            )
        )
    }


    @Test
    fun rejectsArgumentNestingBeyondSafetyLimit() {
        var nestedSchema: Map<String, Any?> = mapOf("type" to "string")
        var nestedValue: Any? = "leaf"
        repeat(40) {
            nestedSchema = mapOf(
                "type" to "object",
                "properties" to mapOf("child" to nestedSchema),
                "required" to listOf("child")
            )
            nestedValue = mapOf("child" to nestedValue)
        }
        val rootSchema = mapOf<String, Any?>(
            "type" to "object",
            "properties" to mapOf("root" to nestedSchema)
        )
        assertFalse(
            McpArgumentsValidator.validate(
                rootSchema,
                mapOf("root" to nestedValue),
                emptyList()
            )
        )
    }


    @Test
    fun rejectsUnsupportedSchemaAssertionsAtEveryDepth() {
        val unsupportedRoot = schema + (
            "patternProperties" to mapOf(".*" to mapOf("type" to "string"))
        )
        assertFalse(McpArgumentsValidator.supportsSchema(unsupportedRoot))
        assertFalse(
            McpArgumentsValidator.validate(
                unsupportedRoot,
                mapOf("text" to "hello"),
                listOf("text")
            )
        )

        val unsupportedNested = mapOf<String, Any?>(
            "type" to "object",
            "properties" to mapOf(
                "value" to mapOf(
                    "type" to "string",
                    "patternProperties" to mapOf(".*" to mapOf("type" to "string"))
                )
            )
        )
        assertFalse(McpArgumentsValidator.supportsSchema(unsupportedNested))
    }

    @Test
    fun acceptsProtocolSupportedFormatAndMcpHeaderAnnotations() {
        val schema = mapOf<String, Any?>(
            "type" to "object",
            "properties" to mapOf(
                "region" to mapOf(
                    "type" to "string",
                    "format" to "email",
                    "x-mcp-header" to "Region"
                )
            ),
            "required" to listOf("region")
        )

        assertTrue(McpProtocol.validateInputSchema(schema))
        assertTrue(McpArgumentsValidator.supportsSchema(schema))
        assertTrue(McpArgumentsValidator.validate(schema, mapOf("region" to "a@example.com"), listOf("region")))
    }

    @Test
    fun rejectsInvalidMcpHeaderPlacementAndNames() {
        val rootHeader = mapOf<String, Any?>(
            "type" to "object",
            "x-mcp-header" to "Region",
            "properties" to emptyMap<String, Any?>()
        )
        val invalidHeaderName = mapOf<String, Any?>(
            "type" to "object",
            "properties" to mapOf(
                "region" to mapOf("type" to "string", "x-mcp-header" to "Bad Header")
            )
        )

        assertFalse(McpArgumentsValidator.supportsSchema(rootHeader))
        assertFalse(McpArgumentsValidator.supportsSchema(invalidHeaderName))
    }

    @Test
    fun rejectsNullValuesForPresentSchemaKeywords() {
        val nullType = mapOf<String, Any?>("type" to null)
        val nullAdditionalProperties = mapOf<String, Any?>(
            "type" to "object",
            "additionalProperties" to null
        )
        assertFalse(McpArgumentsValidator.supportsSchema(nullType))
        assertFalse(McpArgumentsValidator.supportsSchema(nullAdditionalProperties))
    }

    @Test
    fun rejectsMalformedSupportedConstraintsAndAcceptsSupportedAnnotations() {
        val malformed = mapOf<String, Any?>(
            "type" to "object",
            "properties" to mapOf("value" to mapOf("type" to "string", "pattern" to "["))
        )
        assertFalse(McpArgumentsValidator.supportsSchema(malformed))

        val supported = mapOf<String, Any?>(
            "type" to "object",
            "title" to "Lookup input",
            "description" to "Safe lookup",
            "properties" to mapOf(
                "value" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 20)
            ),
            "required" to listOf("value"),
            "additionalProperties" to false
        )
        assertTrue(McpArgumentsValidator.supportsSchema(supported))
        assertTrue(McpArgumentsValidator.validate(supported, mapOf("value" to "ok"), listOf("value")))
    }

}
