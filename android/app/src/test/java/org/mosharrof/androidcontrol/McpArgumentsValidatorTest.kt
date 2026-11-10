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

}
