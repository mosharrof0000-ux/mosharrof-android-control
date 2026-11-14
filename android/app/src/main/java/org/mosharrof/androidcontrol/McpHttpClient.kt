package org.mosharrof.androidcontrol

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HTTPS Streamable HTTP transport for MCP.
 * Does not execute tools or bypass ConnectorRegistry authorization.
 */
internal class McpHttpException(
    val statusCode: Int,
    val rpcErrorCode: Int?,
    message: String,
    val rpcErrorData: JSONObject? = null
) : IOException(message)

class McpHttpClient(
    private val endpoint: String,
    @Volatile private var protocolVersion: String = McpProtocol.MODERN_VERSION,
    private val bearerToken: String? = null,
    private val connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    },
    private val allowSynchronousRequests: Boolean = true
) {
    @Volatile private var sessionId: String? = null

    init {
        require(isSafeEndpoint(endpoint)) { "শুধু নিরাপদ HTTPS MCP endpoint গ্রহণযোগ্য।" }
        require(
            protocolVersion == McpProtocol.MODERN_VERSION ||
            protocolVersion in LEGACY_VERSIONS
        ) { "অসমর্থিত MCP protocol version: $protocolVersion" }
        require(bearerToken == null || isValidBearerToken(bearerToken)) {
            "অবৈধ Bearer Token"
        }
    }

    @Synchronized
    internal fun selectedProtocolVersion(): String = protocolVersion

    internal fun selectProtocolVersion(version: String) {
        require(
            version == McpProtocol.MODERN_VERSION ||
            version in LEGACY_VERSIONS
        ) { "অসমর্থিত MCP protocol version: $version" }

        if (protocolVersion != version) {
            sessionId = null
            protocolVersion = version
        }
    }

    /**
     * Callback-based OAuth request entry point. Token resolution and HTTP work
     * are both dispatched off the caller thread. A token is resolved for this
     * request only; it is never retained by this client instance.
     */
    internal fun postAsync(
        connectorId: String,
        tokenProvider: McpAccessTokenResolver,
        ioExecutor: Executor,
        request: McpProtocol.Request,
        toolDefinition: McpProtocol.ToolDefinition? = null,
        onComplete: (Result<JSONObject>) -> Unit
    ) {
        val completed = AtomicBoolean(false)
        fun complete(result: Result<JSONObject>) {
            if (completed.compareAndSet(false, true)) onComplete(result)
        }

        try {
            ioExecutor.execute {
                try {
                    tokenProvider.resolve(connectorId, endpoint) { tokenResult ->
                        tokenResult.fold(
                            onSuccess = { token ->
                                try {
                                    ioExecutor.execute {
                                        complete(runCatching {
                                            postWithBearer(request, toolDefinition, token.value)
                                        })
                                    }
                                } catch (failure: Throwable) {
                                    complete(Result.failure(failure))
                                }
                            },
                            onFailure = { failure -> complete(Result.failure(failure)) }
                        )
                    }
                } catch (failure: Throwable) {
                    complete(Result.failure(failure))
                }
            }
        } catch (failure: Throwable) {
            complete(Result.failure(failure))
        }
    }

    /** Retains the static/manual-token API for non-OAuth connectors. */
    fun post(
        request: McpProtocol.Request,
        toolDefinition: McpProtocol.ToolDefinition? = null
    ): JSONObject {
        check(allowSynchronousRequests) {
            "এই OAuth Connector-এ synchronous/static-token transport বন্ধ; per-request async token resolution বাধ্যতামূলক।"
        }
        return postWithBearer(request, toolDefinition, bearerToken)
    }

    @Synchronized
    private fun postWithBearer(
        request: McpProtocol.Request,
        toolDefinition: McpProtocol.ToolDefinition?,
        requestBearerToken: String?
    ): JSONObject {
        require(requestBearerToken == null || isValidBearerToken(requestBearerToken)) {
            "অবৈধ Bearer Token"
        }
        require(request.jsonrpc == "2.0")
        require(request.method.matches(Regex("[a-zA-Z0-9/_-]{2,100}")))
        if (request.id == null) {
            require(request.method.startsWith("notifications/"))
        }

        val modern = protocolVersion == McpProtocol.MODERN_VERSION
        if (modern && request.id != null) {
            require(hasValidModernMetadata(request.params, protocolVersion)) {
                "আধুনিক MCP অনুরোধে বৈধ protocolVersion ও clientCapabilities প্রয়োজন।"
            }
        }

        require(toolDefinition == null || request.method == "tools/call") {
            "Tool definition শুধু tools/call-এর জন্য গ্রহণযোগ্য।"
        }
        val parameterHeaders = if (
            modern && request.method == "tools/call" && toolDefinition != null
        ) {
            require(McpProtocol.validateTool(toolDefinition)) {
                "Tool inputSchema অবৈধ।"
            }
            require(request.params["name"] == toolDefinition.name) {
                "Tool definition ও request-এর নাম মিলছে না।"
            }
            val rawArguments = request.params["arguments"]
            val arguments = when (rawArguments) {
                null -> emptyMap()
                is Map<*, *> -> rawArguments.entries.associate { (key, value) ->
                    (key as? String ?: throw IllegalArgumentException("অবৈধ argument key")) to value
                }
                else -> throw IllegalArgumentException("Tool arguments অবশ্যই object হতে হবে।")
            }
            parameterHeaders(toolDefinition, arguments)
        } else {
            emptyMap()
        }

        val uri = URI(endpoint.trim())
        val connection = connectionFactory(URL(uri.toASCIIString()))
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json, text/event-stream")
            connection.setRequestProperty("MCP-Protocol-Version", protocolVersion)
            requestBearerToken?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            sessionId?.let { connection.setRequestProperty("Mcp-Session-Id", it) }

            if (modern) {
                connection.setRequestProperty("Mcp-Method", request.method)
                standardNameHeader(request.method, request.params)?.let {
                    connection.setRequestProperty("Mcp-Name", it)
                }
                parameterHeaders.forEach { (name, value) ->
                    connection.setRequestProperty(name, value)
                }
            }

            val message = linkedMapOf<String, Any?>(
                "jsonrpc" to request.jsonrpc,
                "method" to request.method,
                "params" to request.params
            )
            if (request.id != null) message["id"] = request.id
            val body = JSONObject(message).toString()
                .toByteArray(StandardCharsets.UTF_8)

            require(body.size <= MAX_BODY_BYTES) { "MCP অনুরোধ ২৫৬ KB সীমা অতিক্রম করেছে।" }
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }

            val status = connection.responseCode
            if (status !in 200..299) {
                val errorStream = connection.errorStream
                val detail = readLimited(errorStream, MAX_RESPONSE_BYTES)
                val rpcError = runCatching {
                JSONObject(detail).optJSONObject("error")
            }.getOrNull()
            val rpcCode = (rpcError?.opt("code") as? Number)?.toInt()
            // Keep only the protocol-version hint needed for safe negotiation.
            // Arbitrary server error bodies can echo Authorization headers or tokens.
            val rpcData = if (rpcCode == -32022) {
                rpcError?.optJSONObject("data")?.optJSONArray("supported")?.let { raw ->
                    val supported = org.json.JSONArray()
                    for (index in 0 until raw.length()) {
                        val version = raw.opt(index) as? String ?: continue
                        if (version.length <= 64) supported.put(version)
                    }
                    JSONObject().put("supported", supported)
                }
            } else {
                null
            }
            throw McpHttpException(
                status,
                rpcCode,
                "MCP HTTP $status request failed",
                rpcData
            )
            }

            if (request.id == null) {
                require(status == 202) { "MCP notification-এর জন্য HTTP 202 প্রত্যাশিত।" }
                return JSONObject()
            }

            connection.getHeaderField("Mcp-Session-Id")?.let {
                require(it.isNotBlank() && it.all { ch -> ch.code in 0x21..0x7E })
                if (!modern) sessionId = it
            }

            val contentType = connection.contentType.orEmpty()
                .substringBefore(";").trim().lowercase()
            val responseText = readLimited(connection.inputStream, MAX_RESPONSE_BYTES)

            val response = when (contentType) {
                "application/json" -> JSONObject(responseText)
                "text/event-stream" -> parseSseResponse(responseText, request.id!!)
                else -> throw IOException("অপরিচিত MCP response content type")
            }

            require(response.optString("jsonrpc") == "2.0") {
                "MCP response JSON-RPC 2.0 নয়।"
            }
            require(hasMatchingResponseId(response, request.id!!)) {
                "MCP response ID অনুরোধের ID-এর সঙ্গে মেলেনি।"
            }
            require(response.has("result") xor response.has("error")) {
                "MCP response-এ result অথবা error—একটি থাকতে হবে।"
            }

            // Preserve the modern wire discriminator here; the service layer decides
            // whether this app can safely consume the returned result type.
            if (modern && response.has("result")) {
                val resultObject = response.optJSONObject("result")
                    ?: throw IOException("আধুনিক MCP result অবজেক্ট নয়।")
                val resultType = resultObject.optString("resultType", "")
                if (resultType.isBlank()) {
                    throw IOException("আধুনিক MCP result-এ resultType অনুপস্থিত।")
                }
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val MAX_BODY_BYTES = 256 * 1024
        private const val MAX_RESPONSE_BYTES = 256 * 1024
        private val LEGACY_VERSIONS = setOf(
            "2025-03-26",
            "2025-06-18",
            "2025-11-25"
        )

        internal fun hasValidModernMetadata(
            params: Map<String, Any?>,
            expectedVersion: String
        ): Boolean {
            val metadata = params["_meta"] as? Map<*, *> ?: return false

            if (metadata["io.modelcontextprotocol/protocolVersion"] != expectedVersion) {
                return false
            }

            if (metadata["io.modelcontextprotocol/clientCapabilities"] !is Map<*, *>) {
                return false
            }

            val clientInfo = metadata["io.modelcontextprotocol/clientInfo"]
            if (clientInfo != null) {
                val info = clientInfo as? Map<*, *> ?: return false
                if ((info["name"] as? String).isNullOrBlank()) return false
                if ((info["version"] as? String).isNullOrBlank()) return false
            }

            return true
        }

        internal fun isValidBearerToken(value: String): Boolean =
            value.isNotBlank() && !value.contains("\r") && !value.contains("\n")

        internal fun isSafeEndpoint(value: String): Boolean = runCatching {
            val uri = URI(value.trim())
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.fragment == null &&
                uri.host.none { it.isWhitespace() }
        }.getOrDefault(false)

        internal fun extractSseDataEvents(text: String): List<String> {
        val events = mutableListOf<String>()
        val dataLines = mutableListOf<String>()

        fun finishEvent() {
            if (dataLines.isNotEmpty()) {
                events.add(dataLines.joinToString("\n"))
                dataLines.clear()
            }
        }

        for (line in text.split("\n")) {
            val clean = line.removeSuffix("\r")
            if (clean.isEmpty()) {
                finishEvent()
            } else if (clean.startsWith(":")) {
                continue
            } else if (clean.startsWith("data:")) {
                dataLines.add(clean.substring(5).removePrefix(" "))
            }
        }
        finishEvent()
        return events
    }

    internal fun parameterHeaders(
        tool: McpProtocol.ToolDefinition,
        arguments: Map<String, Any?>
    ): Map<String, String> {
        require(McpProtocol.validateTool(tool)) {
            "Tool inputSchema অবৈধ।"
        }

        val headers = linkedMapOf<String, String>()

        fun valueAtPath(path: List<String>): Any? {
            var current: Any? = arguments
            for (part in path) {
                current = (current as? Map<*, *>)?.get(part) ?: return null
            }
            return current
        }

        fun visit(schema: Map<*, *>, path: List<String>) {
            val properties = schema["properties"] as? Map<*, *> ?: return

            for ((rawName, rawChild) in properties) {
                val name = rawName as? String
                    ?: throw IllegalArgumentException("Schema property name অবৈধ।")
                val child = rawChild as? Map<*, *>
                    ?: throw IllegalArgumentException("Schema property অবৈধ।")
                val childPath = path + name
                val annotation = child["x-mcp-header"] as? String

                if (annotation != null) {
                    val value = valueAtPath(childPath)
                    if (value != null) {
                        val rendered = when (child["type"]) {
                            "string" -> value as? String
                                ?: throw IllegalArgumentException("$name অবশ্যই string হতে হবে।")
                            "integer" -> when (value) {
                                is Byte, is Short, is Int, is Long -> {
                                    val number = (value as Number).toLong()
                                    require(number in -9_007_199_254_740_991L..9_007_199_254_740_991L) {
                                        "$name নিরাপদ integer সীমার বাইরে।"
                                    }
                                    number.toString()
                                }
                                else -> throw IllegalArgumentException("$name অবশ্যই integer হতে হবে।")
                            }
                            "boolean" -> (value as? Boolean)?.toString()
                                ?: throw IllegalArgumentException("$name অবশ্যই boolean হতে হবে।")
                            else -> throw IllegalArgumentException("অসমর্থিত Header parameter type।")
                        }

                        headers["Mcp-Param-$annotation"] = encodeHeaderValue(rendered)
                    }
                }

                if (child["type"] == "object") {
                    visit(child, childPath)
                }
            }
        }

        visit(tool.inputSchema, emptyList())
        return headers
    }

    private fun encodeHeaderValue(value: String): String {
        val needsEncoding = value.isEmpty() ||
            value.startsWith(" ") ||
            value.endsWith(" ") ||
            value.any { it.code !in 0x20..0x7E } ||
            (value.startsWith("=?base64?") && value.endsWith("?="))

        if (!needsEncoding) return value

        val encoded = java.util.Base64.getEncoder()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
        return "=?base64?$encoded?="
    }

    internal fun standardNameHeader(
        method: String,
        params: Map<String, Any?>
    ): String? {
        val value = when (method) {
            "tools/call", "prompts/get" -> params["name"] as? String
            "resources/read" -> params["uri"] as? String
            "tasks/get", "tasks/update", "tasks/cancel" -> params["taskId"] as? String
            else -> return null
        } ?: throw IllegalArgumentException("$method-এর জন্য Mcp-Name প্রয়োজন।")

        require(value.isNotEmpty()) { "Mcp-Name খালি হতে পারবে না।" }

        val needsEncoding =
            value.startsWith(" ") ||
            value.endsWith(" ") ||
            value.any { it.code !in 0x20..0x7E } ||
            (value.startsWith("=?base64?") && value.endsWith("?="))

        if (!needsEncoding) return value

        val encoded = java.util.Base64.getEncoder()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
        return "=?base64?$encoded?="
    }

    internal fun hasMatchingResponseId(response: JSONObject, expectedId: String): Boolean =
        response.opt("id") == expectedId

    internal fun selectSseResponsePayload(
        events: List<String>,
        expectedId: String
    ): String {
        for (event in events) {
            val parsed = runCatching { JSONObject(event) }.getOrNull() ?: continue
            if (hasMatchingResponseId(parsed, expectedId) &&
                parsed.optString("jsonrpc") == "2.0" &&
                (parsed.has("result") xor parsed.has("error"))
            ) {
                return event
            }
        }
        throw IOException("SSE stream-এ অনুরোধের ID-মেলা বৈধ response পাওয়া যায়নি।")
    }

    internal fun parseSseResponse(text: String, expectedId: String): JSONObject {
        val payload = selectSseResponsePayload(
            extractSseDataEvents(text),
            expectedId
        )
        return JSONObject(payload)
    }

    private fun readLimited(stream: InputStream?, maxBytes: Int): String {
            if (stream == null) return ""
            val output = ByteArrayOutputStream()
            stream.use { input ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) {
                        throw IOException("MCP সার্ভারের উত্তর ২৫৬ KB সীমা অতিক্রম করেছে।")
                    }
                    output.write(buffer, 0, count)
                }
            }
            return String(output.toByteArray(), StandardCharsets.UTF_8)
        }
    }
}
