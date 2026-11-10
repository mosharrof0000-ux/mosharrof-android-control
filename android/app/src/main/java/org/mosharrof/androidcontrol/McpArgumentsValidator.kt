package org.mosharrof.androidcontrol

import java.math.BigDecimal

object McpArgumentsValidator {

    private const val MAX_ARGUMENT_DEPTH = 32
    private const val MAX_ARGUMENT_NODES = 2_048
    private class ValidationBudget(var visited: Int = 0)

    fun validate(
        schema: Map<String, Any?>,
        arguments: Map<String, Any?>,
        requiredFields: Collection<String>
    ): Boolean {
        if (schema["type"] != "object" || !supportsSchema(schema)) return false

        if (requiredFields.any {
            !arguments.containsKey(it) || arguments[it] == null
        }) return false

        return validateValue(schema, arguments, 0, ValidationBudget())
    }


    /**
     * Validates the schema vocabulary before a remote tool is registered.
     * Unknown keywords are rejected: silently ignoring a JSON Schema assertion
     * could make the app approve arguments the server considers invalid.
     */
    fun supportsSchema(schema: Map<String, Any?>): Boolean =
        validateSchemaNode(schema, 0, ValidationBudget())

    private val supportedSchemaKeys = setOf(
        "$" + "schema", "$" + "id", "$" + "comment",
        "title", "description", "default", "examples", "deprecated", "readOnly", "writeOnly",
        "type", "const", "enum", "properties", "required", "additionalProperties", "items",
        "minItems", "maxItems", "minProperties", "maxProperties", "minLength", "maxLength",
        "pattern", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf"
    )

    private fun schemaInteger(schema: Map<*, *>, key: String): Int? {
        if (!schema.containsKey(key)) return null
        val number = decimal(schema[key]) ?: return null
        return runCatching { number.intValueExact() }.getOrNull()
    }

    private fun validateSchemaNode(
        schema: Map<*, *>,
        depth: Int,
        budget: ValidationBudget
    ): Boolean {
        if (depth > MAX_ARGUMENT_DEPTH || ++budget.visited > MAX_ARGUMENT_NODES) return false
        if (schema.keys.any { it !is String || it !in supportedSchemaKeys }) return false

        when (val type = schema["type"]) {
            null -> Unit
            is String -> if (type !in setOf("object", "array", "string", "boolean", "integer", "number", "null")) return false
            is List<*> -> {
                if (type.isEmpty() || type.size > 7 ||
                    type.any { it !is String || it !in setOf("object", "array", "string", "boolean", "integer", "number", "null") } ||
                    type.distinct().size != type.size
                ) return false
            }
            else -> return false
        }

        if (schema.containsKey("enum")) {
            val values = schema["enum"] as? List<*> ?: return false
            if (values.isEmpty() || values.size > 256) return false
        }

        if (schema.containsKey("required")) {
            val required = schema["required"] as? List<*> ?: return false
            if (required.size > 256 || required.any { it !is String || it.isBlank() || it.length > 256 }) return false
            if (required.distinct().size != required.size) return false
        }

        if (schema.containsKey("properties")) {
            val properties = schema["properties"] as? Map<*, *> ?: return false
            if (properties.size > MAX_ARGUMENT_NODES) return false
            for ((name, child) in properties) {
                if (name !is String || name.isBlank() || name.length > 256) return false
                if (child !is Map<*, *> || !validateSchemaNode(child, depth + 1, budget)) return false
            }
        }

        when (val additional = schema["additionalProperties"]) {
            null, is Boolean -> Unit
            is Map<*, *> -> if (!validateSchemaNode(additional, depth + 1, budget)) return false
            else -> return false
        }

        if (schema.containsKey("items")) {
            val items = schema["items"] as? Map<*, *> ?: return false
            if (!validateSchemaNode(items, depth + 1, budget)) return false
        }

        for (key in listOf("minItems", "maxItems", "minProperties", "maxProperties", "minLength", "maxLength")) {
            if (schema.containsKey(key)) {
                val value = schemaInteger(schema, key) ?: return false
                if (value < 0 || value > 1_000_000) return false
            }
        }
        val minItems = schemaInteger(schema, "minItems")
        val maxItems = schemaInteger(schema, "maxItems")
        val minProperties = schemaInteger(schema, "minProperties")
        val maxProperties = schemaInteger(schema, "maxProperties")
        val minLength = schemaInteger(schema, "minLength")
        val maxLength = schemaInteger(schema, "maxLength")
        if (minItems != null && maxItems != null && minItems > maxItems) return false
        if (minProperties != null && maxProperties != null && minProperties > maxProperties) return false
        if (minLength != null && maxLength != null && minLength > maxLength) return false

        if (schema.containsKey("pattern")) {
            val pattern = schema["pattern"] as? String ?: return false
            if (pattern.length > 2_048 || runCatching { Regex(pattern) }.isFailure) return false
        }

        for (key in listOf("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum")) {
            if (schema.containsKey(key) && decimal(schema[key]) == null) return false
        }
        if (schema.containsKey("multipleOf")) {
            val divisor = decimal(schema["multipleOf"]) ?: return false
            if (divisor <= BigDecimal.ZERO) return false
        }
        for (key in listOf("$" + "schema", "$" + "id", "$" + "comment", "title", "description")) {
            if (schema.containsKey(key) && (schema[key] !is String || (schema[key] as String).length > 4_096)) return false
        }
        for (key in listOf("deprecated", "readOnly", "writeOnly")) {
            if (schema.containsKey(key) && schema[key] !is Boolean) return false
        }
        if (schema.containsKey("examples") && (schema["examples"] !is List<*> || (schema["examples"] as List<*>).size > 16)) return false
        return true
    }

    private fun decimal(value: Any?): BigDecimal? {
        if (value !is Number) return null
        return runCatching {
            when (value) {
                is Double -> if (!value.isFinite()) null else BigDecimal(value.toString())
                is Float -> if (!value.isFinite()) null else BigDecimal(value.toString())
                else -> BigDecimal(value.toString())
            }
        }.getOrNull()
    }

    private fun validInteger(value: Any?): Boolean {
        val number = decimal(value) ?: return false
        return number.stripTrailingZeros().scale() <= 0
    }

    private fun validBound(schema: Map<*, *>, key: String): BigDecimal? {
        if (!schema.containsKey(key)) return null
        return decimal(schema[key])
    }

    private fun validateValue(
        schema: Map<*, *>,
        value: Any?,
        depth: Int,
        budget: ValidationBudget
    ): Boolean {
        if (depth > MAX_ARGUMENT_DEPTH || ++budget.visited > MAX_ARGUMENT_NODES) return false
        if (schema.containsKey("const") && schema["const"] != value) return false

        if (schema.containsKey("enum")) {
            val values = schema["enum"] as? List<*> ?: return false
            if (values.none { it == value }) return false
        }

        val types = when (val declared = schema["type"]) {
            null -> emptyList()
            is String -> listOf(declared)
            is List<*> -> {
                if (declared.isEmpty() || declared.any { it !is String }) return false
                declared.filterIsInstance<String>()
            }
            else -> return false
        }

        if (types.any { it !in setOf("object", "array", "string", "boolean", "integer", "number", "null") }) {
            return false
        }
        if (types.isNotEmpty() && types.none { matchesType(it, value) }) return false

        if (value == null) return types.isEmpty() || "null" in types

        when (value) {
            is String -> {
                val length = value.codePointCount(0, value.length)
                val min = schema["minLength"]?.let { decimal(it)?.intValueExact() ?: return false }
                val max = schema["maxLength"]?.let { decimal(it)?.intValueExact() ?: return false }
                if (min != null && min < 0 || max != null && max < 0) return false
                if (min != null && length < min) return false
                if (max != null && length > max) return false

                if (schema.containsKey("pattern")) {
                    val pattern = schema["pattern"] as? String ?: return false
                    val regex = runCatching { Regex(pattern) }.getOrNull() ?: return false
                    if (!regex.containsMatchIn(value)) return false
                }
            }

            is Number -> {
                val number = decimal(value) ?: return false
                val minimum = validBound(schema, "minimum")
                val maximum = validBound(schema, "maximum")
                val exclusiveMinimum = validBound(schema, "exclusiveMinimum")
                val exclusiveMaximum = validBound(schema, "exclusiveMaximum")

                if (schema.containsKey("minimum") && minimum == null) return false
                if (schema.containsKey("maximum") && maximum == null) return false
                if (schema.containsKey("exclusiveMinimum") && exclusiveMinimum == null) return false
                if (schema.containsKey("exclusiveMaximum") && exclusiveMaximum == null) return false

                if (minimum != null && number < minimum) return false
                if (maximum != null && number > maximum) return false
                if (exclusiveMinimum != null && number <= exclusiveMinimum) return false
                if (exclusiveMaximum != null && number >= exclusiveMaximum) return false

                if (schema.containsKey("multipleOf")) {
                    val divisor = validBound(schema, "multipleOf") ?: return false
                    if (divisor <= BigDecimal.ZERO) return false
                    if (number.remainder(divisor).compareTo(BigDecimal.ZERO) != 0) return false
                }
            }

            is Map<*, *> -> {
                if (value.keys.any { it !is String }) return false

                val required = schema["required"]
                if (required != null) {
                    val names = required as? List<*> ?: return false
                    if (names.any { it !is String }) return false
                    if (names.distinct().size != names.size) return false
                    if (names.any { !value.containsKey(it) || value[it] == null }) return false
                }

                val propertyMap = when (val properties = schema["properties"]) {
                    null -> emptyMap<Any?, Any?>()
                    is Map<*, *> -> properties
                    else -> return false
                }

                val min = schema["minProperties"]?.let { decimal(it)?.intValueExact() ?: return false }
                val max = schema["maxProperties"]?.let { decimal(it)?.intValueExact() ?: return false }
                if (min != null && min < 0 || max != null && max < 0) return false
                if (min != null && value.size < min) return false
                if (max != null && value.size > max) return false

                for ((key, childValue) in value) {
                    if (propertyMap.containsKey(key)) {
                        val child = propertyMap[key] as? Map<*, *> ?: return false
                        if (!validateValue(child, childValue, depth + 1, budget)) return false
                    } else {
                        when (val additional = schema["additionalProperties"]) {
                            false -> return false
                            is Map<*, *> -> if (!validateValue(additional, childValue, depth + 1, budget)) return false
                        }
                    }
                }
            }

            is List<*> -> {
                val min = schema["minItems"]?.let { decimal(it)?.intValueExact() ?: return false }
                val max = schema["maxItems"]?.let { decimal(it)?.intValueExact() ?: return false }
                if (min != null && min < 0 || max != null && max < 0) return false
                if (min != null && value.size < min) return false
                if (max != null && value.size > max) return false

                if (schema.containsKey("items")) {
                    val itemSchema = schema["items"] as? Map<*, *> ?: return false
                    if (value.any { !validateValue(itemSchema, it, depth + 1, budget) }) return false
                }
            }
        }

        return true
    }

    private fun matchesType(type: String, value: Any?): Boolean = when (type) {
        "object" -> value is Map<*, *>
        "array" -> value is List<*>
        "string" -> value is String
        "boolean" -> value is Boolean
        "integer" -> validInteger(value)
        "number" -> decimal(value) != null
        "null" -> value == null
        else -> false
    }
}
