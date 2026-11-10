package org.mosharrof.androidcontrol
import org.json.JSONArray
import org.json.JSONObject

/** First-turn profile runtime; plugin tools remain disabled until approval continuation is integrated. */
object AiProviderRuntime {
    private const val MAX_JSON_DEPTH = 32
    private const val MAX_JSON_NODES = 2_048

    fun askText(profile: ResolvedAiProviderProfile, prompt: String): String {
        require(prompt.isNotBlank() && prompt.length <= AiProviderRequestFactory.MAX_PROMPT_CHARS) {
            "AI prompt is empty or exceeds the configured limit."
        }
        AiProviderRegistry.registerBuiltIns()
        val request = AiProviderRequestFactory.createInitialRequest(
            config = profile.config, apiKey = profile.apiKey, prompt = prompt, tools = emptyList()
        )
        val response = AiProviderHttpTransport().post(request)
        return when (val result = decodePayload(profile.config.provider, response.payload.toBoundedMap())) {
            is CanonicalAiResult.Text -> result.value
            is CanonicalAiResult.ToolCalls -> throw IllegalStateException(
                "AI returned plugin actions, but approval-driven continuation is not enabled yet. No plugin was executed."
            )
            is CanonicalAiResult.Failure -> throw IllegalStateException(result.safeMessage)
        }
    }

    internal fun decodePayload(provider: AiProviderId, payload: Map<String, Any?>): CanonicalAiResult {
        AiProviderRegistry.registerBuiltIns()
        val adapter = requireNotNull(AiProviderRegistry.get(provider)) {
            "No AI adapter is registered for the selected provider."
        }
        return adapter.decodeResponse(payload)
    }

    internal fun JSONObject.toBoundedMap(): Map<String, Any?> {
        var visited = 0
        fun convert(value: Any?, depth: Int): Any? {
            require(depth <= MAX_JSON_DEPTH) { "AI response nesting limit exceeded." }
            visited += 1
            require(visited <= MAX_JSON_NODES) { "AI response complexity limit exceeded." }
            return when (value) {
                is JSONObject -> {
                    val map = linkedMapOf<String, Any?>()
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        require(key.length <= 256) { "AI response key exceeds the configured limit." }
                        map[key] = convert(value.get(key), depth + 1)
                    }
                    map
                }
                is JSONArray -> (0 until value.length()).map { convert(value.get(it), depth + 1) }
                JSONObject.NULL -> null
                is String -> {
                    require(value.length <= 256 * 1024) { "AI response string exceeds the configured limit." }
                    value
                }
                is Number, is Boolean, null -> value
                else -> throw IllegalArgumentException("AI response contains an unsupported value.")
            }
        }
        @Suppress("UNCHECKED_CAST")
        return convert(this, 0) as Map<String, Any?>
    }
}
