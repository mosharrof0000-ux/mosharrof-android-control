package org.mosharrof.androidcontrol

import java.math.BigDecimal

/** Safe, bounded model for flat native MCP input forms. Complex schemas fall back to JSON input. */
internal object McpSchemaForm {
    private const val MAX_FIELDS = 32

    data class Field(
        val name: String,
        val type: String,
        val required: Boolean,
        val enumValues: List<Any?> = emptyList(),
        val defaultValue: Any? = null,
        val hasDefault: Boolean = false
    )

    fun fields(schema: Map<String, Any?>): List<Field>? {
        if (schema["type"] != "object") return null
        val properties = schema["properties"] as? Map<*, *> ?: return null
        if (properties.size > MAX_FIELDS) return null
        val required = (schema["required"] as? List<*>)?.mapNotNull { it as? String }?.toSet()
            ?: emptySet()
        if (schema["required"] != null &&
            ((schema["required"] as? List<*>)?.size != required.size)
        ) return null

        val fields = mutableListOf<Field>()
        for ((rawName, rawDefinition) in properties) {
            val name = rawName as? String ?: return null
            if (name.isBlank() || name.length > 128) return null
            val definition = rawDefinition as? Map<*, *> ?: return null
            val type = definition["type"] as? String ?: return null
            if (type !in setOf("string", "number", "integer", "boolean")) return null
            val enumValues = when {
                definition.containsKey("const") -> listOf(definition["const"])
                definition.containsKey("enum") -> definition["enum"] as? List<*> ?: return null
                else -> emptyList()
            }
            if (definition.containsKey("enum") && enumValues.isEmpty()) return null
            fields += Field(
                name = name,
                type = type,
                required = name in required,
                enumValues = enumValues,
                defaultValue = definition["default"],
                hasDefault = definition.containsKey("default")
            )
        }
        return fields
    }

    fun parse(
        fields: List<Field>,
        textValues: Map<String, String>,
        booleanValues: Map<String, Boolean>,
        enumIndices: Map<String, Int>
    ): Result<Map<String, Any?>> = runCatching {
        require(fields.size <= MAX_FIELDS) { "Native form field limit অতিক্রম করেছে।" }
        val result = linkedMapOf<String, Any?>()
        for (field in fields) {
            val value: Any? = when {
                field.enumValues.isNotEmpty() -> {
                    val index = enumIndices[field.name] ?: 0
                    require(index in field.enumValues.indices) { "Enum selection অবৈধ: ${field.name}" }
                    field.enumValues[index]
                }
                field.type == "boolean" -> booleanValues[field.name] ?: (field.defaultValue as? Boolean ?: false)
                field.type == "string" -> {
                    val raw = textValues[field.name].orEmpty()
                    when {
                        raw.isNotEmpty() -> raw
                        field.hasDefault -> field.defaultValue
                        field.required -> error("প্রয়োজনীয় input অনুপস্থিত: ${field.name}")
                        else -> continue
                    }
                }
                field.type == "integer" -> {
                    val raw = textValues[field.name].orEmpty()
                    if (raw.isBlank() && !field.required && !field.hasDefault) continue
                    val number = (raw.ifBlank { field.defaultValue?.toString().orEmpty() })
                        .toBigDecimalOrNull() ?: error("পূর্ণসংখ্যা সঠিক নয়: ${field.name}")
                    number.toBigIntegerExact().longValueExact()
                }
                field.type == "number" -> {
                    val raw = textValues[field.name].orEmpty()
                    if (raw.isBlank() && !field.required && !field.hasDefault) continue
                    (raw.ifBlank { field.defaultValue?.toString().orEmpty() })
                        .toBigDecimalOrNull() ?: error("সংখ্যা সঠিক নয়: ${field.name}")
                }
                else -> error("অসমর্থিত field type: ${field.type}")
            }
            result[field.name] = value
        }
        result
    }
}
