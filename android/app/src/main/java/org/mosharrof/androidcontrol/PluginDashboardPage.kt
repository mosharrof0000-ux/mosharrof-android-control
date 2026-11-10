package org.mosharrof.androidcontrol

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.Executor

private object PluginDashboardSession {
    var connectorId: String? = null
    var service: McpConnectorService? = null
}

class PluginDashboardPage(
    private val activity: Activity,
    private val worker: Executor,
    private val onStatus: (String) -> Unit
) {
    private companion object {
        const val MAX_MRTR_ROUNDS = 10
        const val MAX_MRTR_DISPLAY_CHARS = 4_000
        const val MAX_JSON_INPUT_DEPTH = 32
    }

    private lateinit var endpointInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var toolsContainer: LinearLayout
    private lateinit var resultView: TextView

    fun render(parent: LinearLayout) {
        val prefs = activity.getSharedPreferences("plugin_dashboard_v1", Activity.MODE_PRIVATE)

        heading(parent, "MCP Connector")
        note(parent, "শুধু HTTPS endpoint গ্রহণ করা হবে। Token encrypted storage-এ রাখা হবে; রিপোর্টে Token দেখানো হবে না।")

        endpointInput = EditText(activity).apply {
            hint = "HTTPS MCP endpoint"
            setSingleLine(true)
            setText(prefs.getString("endpoint", ""))
        }
        parent.addView(endpointInput, matchParams())

        tokenInput = EditText(activity).apply {
            hint = "Bearer Token (ঐচ্ছিক)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        parent.addView(tokenInput, matchParams())

        button(parent, "Token সংরক্ষণ ও Connect") {
            connect()
        }
        button(parent, "Disconnect") {
            disconnect()
        }
        button(parent, "সংরক্ষিত Token মুছুন") {
            val id = PluginDashboardSession.connectorId
                ?: connectorIdFor(endpointInput.text.toString().trim())
            if (id == null) {
                onStatus("সঠিক HTTPS endpoint দিন।")
            } else {
                PluginSecretStore.deleteToken(activity, id)
                    .onSuccess { onStatus("সংরক্ষিত Token মুছে ফেলা হয়েছে।") }
                    .onFailure { onStatus("Token মোছা যায়নি: ${it.message}") }
            }
        }

        heading(parent, "আবিষ্কৃত Tools")
        toolsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        parent.addView(toolsContainer, matchParams())

        resultView = TextView(activity).apply {
            text = "এখনো কোনো Tool call করা হয়নি।"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(8, 12, 8, 12)
        }
        parent.addView(resultView, matchParams())

        val currentId = PluginDashboardSession.connectorId
        if (currentId != null &&
            PluginDashboardSession.service != null &&
            ConnectorRegistry.connectorSnapshot().any {
                it.id == currentId && it.state == ConnectorRegistry.ConnectionState.CONNECTED
            }
        ) {
            showTools(currentId)
            onStatus("Plugin Connector সংযুক্ত আছে।")
        } else {
            note(parent, "সংযোগ বিচ্ছিন্ন। Endpoint দিয়ে Connect করুন।")
        }
    }

    private fun connect() {
        val endpoint = endpointInput.text.toString().trim()
        val id = connectorIdFor(endpoint)
        if (id == null) {
            onStatus("শুধু সঠিক HTTPS MCP endpoint গ্রহণযোগ্য।")
            return
        }

        val enteredToken = tokenInput.text.toString()
        val prefs = activity.getSharedPreferences("plugin_dashboard_v1", Activity.MODE_PRIVATE)
        prefs.edit().putString("endpoint", endpoint).apply()

        worker.execute {
            runCatching {
                val existing = ConnectorRegistry.connectorSnapshot().firstOrNull { it.id == id }
                if (existing == null) {
                    ConnectorRegistry.registerConnector(
                        ConnectorRegistry.Connector(
                            id = id,
                            name = "MCP Connector",
                            transport = ConnectorRegistry.Transport.MCP_STREAMABLE_HTTP,
                            endpoint = endpoint,
                            state = ConnectorRegistry.ConnectionState.CONFIGURED
                        )
                    ).getOrThrow()
                } else {
                    require(existing.endpoint == endpoint) {
                        "Connector endpoint-এর সঙ্গে নিবন্ধিত endpoint মিলছে না।"
                    }
                }

                if (enteredToken.isNotBlank()) {
                    PluginSecretStore.saveToken(activity, id, enteredToken).getOrThrow()
                }
                val token = if (enteredToken.isNotBlank()) {
                    enteredToken
                } else {
                    PluginSecretStore.readToken(activity, id)
                }

                val client = McpHttpClient(
                    endpoint = endpoint,
                    bearerToken = token
                )
                val service = McpConnectorService(client)
                val info = service.connect(id).getOrThrow()
                PluginDashboardSession.connectorId = id
                PluginDashboardSession.service = service
                info
            }.onSuccess { info ->
                activity.runOnUiThread {
                    tokenInput.text.clear()
                    onStatus("MCP সংযুক্ত। আবিষ্কৃত Tool: ${info.discoveredTools}")
                    showTools(info.connectorId)
                }
            }.onFailure { error ->
                activity.runOnUiThread {
                    onStatus("MCP সংযোগ ব্যর্থ: ${error.message ?: "অজানা সমস্যা"}")
                }
            }
        }
    }

    private fun disconnect() {
        val id = PluginDashboardSession.connectorId
        val service = PluginDashboardSession.service
        if (id == null || service == null) {
            onStatus("কোনো সক্রিয় Plugin session নেই।")
            return
        }
        worker.execute {
            service.disconnect(id)
                .onSuccess {
                    PluginDashboardSession.service = null
                    PluginDashboardSession.connectorId = null
                    activity.runOnUiThread {
                        toolsContainer.removeAllViews()
                        onStatus("MCP Connector বিচ্ছিন্ন হয়েছে।")
                    }
                }
                .onFailure { error ->
                    activity.runOnUiThread {
                        onStatus("Disconnect ব্যর্থ: ${error.message}")
                    }
                }
        }
    }

    private fun showTools(connectorId: String) {
        if (!::toolsContainer.isInitialized) return
        toolsContainer.removeAllViews()
        val tools = ConnectorRegistry.toolsFor(connectorId)
        if (tools.isEmpty()) {
            note(toolsContainer, "কোনো Tool পাওয়া যায়নি।")
            return
        }

        tools.forEach { tool ->
            val riskLabel = when (tool.risk) {
                ConnectorRegistry.Risk.READ_ONLY -> "READ_ONLY"
                ConnectorRegistry.Risk.WRITE -> "WRITE"
                ConnectorRegistry.Risk.EXTERNAL_SIDE_EFFECT -> "EXTERNAL_SIDE_EFFECT"
                ConnectorRegistry.Risk.DESTRUCTIVE -> "DESTRUCTIVE"
            }
            note(toolsContainer, "${tool.name} · $riskLabel\n${tool.description}")
            button(toolsContainer, "Tool চালান: ${tool.name}") {
                requestToolCall(tool.id, tool.name, riskLabel)
            }
        }
    }

    private fun requestToolCall(toolId: String, toolName: String, risk: String) {
        val input = EditText(activity).apply {
            hint = """{"key":"value"}"""
            setText("{}")
            minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }

        AlertDialog.Builder(activity)
            .setTitle("Tool অনুমোদন")
            .setMessage(
                "Tool: $toolName\nRisk: $risk\n\nInput JSON যাচাই করুন। " +
                    "চালান বাটনে চাপলে এই call-এর জন্য স্পষ্ট অনুমোদন দেওয়া হবে।"
            )
            .setView(input)
            .setNegativeButton("বাতিল", null)
            .setPositiveButton("অনুমোদন করে চালান") { _, _ ->
                val args = runCatching {
                    JSONObject(input.text.toString()).toMap()
                }.getOrElse {
                    onStatus("Input JSON সঠিক নয়: ${it.message}")
                    return@setPositiveButton
                }

                val service = PluginDashboardSession.service
                if (service == null) {
                    onStatus("Connector session সক্রিয় নেই।")
                    return@setPositiveButton
                }

                worker.execute {
                    val grant = service.issueApprovalGrant(
                        toolId = toolId,
                        arguments = args
                    ).getOrElse { error ->
                        activity.runOnUiThread {
                            onStatus("অনুমোদন তৈরি হয়নি: ${error.message}")
                        }
                        return@execute
                    }

                    service.callTool(
                        toolId = toolId,
                        arguments = args,
                        approvalGrant = grant
                    ).onSuccess { output ->
                        activity.runOnUiThread {
                            handleToolOutput(
                                toolId = toolId,
                                toolName = toolName,
                                arguments = args,
                                output = output,
                                round = 0
                            )
                        }
                    }.onFailure { error ->
                        activity.runOnUiThread {
                            onStatus("Tool call ব্যর্থ: ${error.message}")
                        }
                    }
                }
            }
            .show()
    }

    /**
     * Drives modern MCP Multi Round-Trip Requests with an explicit user decision
     * for every response round. Server-provided request text is displayed as data,
     * never interpreted as instructions by the app.
     */
    private fun handleToolOutput(
        toolId: String,
        toolName: String,
        arguments: Map<String, Any?>,
        output: String,
        round: Int
    ) {
        val result = runCatching { JSONObject(output) }.getOrElse {
            resultView.text = output
            onStatus("Tool call সম্পন্ন।")
            return
        }
        if (result.optString("resultType") != "input_required") {
            resultView.text = output
            onStatus("Tool call সম্পন্ন।")
            return
        }

        if (round >= MAX_MRTR_ROUNDS) {
            resultView.text = output
            onStatus("MCP input round-এর সর্বোচ্চ সীমা পূর্ণ হয়েছে; নিরাপদে থামানো হয়েছে।")
            return
        }

        val requests = result.optJSONObject("inputRequests")
        if (requests == null || requests.length() == 0) {
            onStatus("MCP input_required response-এ বৈধ inputRequests নেই।")
            return
        }
        val requestState = if (result.has("requestState") && !result.isNull("requestState")) {
            result.opt("requestState") as? String
                ?: run {
                    onStatus("MCP requestState string নয়; round-trip থামানো হয়েছে।")
                    return
                }
        } else null

        val responseInput = EditText(activity).apply {
            hint = """{"request-key":{"action":"accept","content":{}}}"""
            setText("{}")
            minLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        AlertDialog.Builder(activity)
            .setTitle("MCP অতিরিক্ত তথ্য চায় (${round + 1}/$MAX_MRTR_ROUNDS)")
            .setMessage(
                "সার্ভারের অনুরোধ (বিশ্বাসহীন ডেটা—পড়ে যাচাই করুন):\n" +
                    requests.toString(2).take(MAX_MRTR_DISPLAY_CHARS) +
                    "\n\nনিচে inputResponses JSON লিখুন। সংবেদনশীল তথ্য দেবেন না।"
            )
            .setView(responseInput)
            .setNegativeButton("বাতিল", null)
            .setPositiveButton("উত্তর পাঠান") { _, _ ->
                val responses = runCatching {
                    JSONObject(responseInput.text.toString()).toMap()
                }.getOrElse {
                    onStatus("inputResponses JSON সঠিক নয়: ${it.message}")
                    return@setPositiveButton
                }
                val expectedKeys = buildSet {
                    val iterator = requests.keys()
                    while (iterator.hasNext()) add(iterator.next())
                }
                if (responses.keys != expectedKeys) {
                    onStatus("inputResponses-এর key অবশ্যই সার্ভারের প্রতিটি input request-এর key-এর সঙ্গে মিলতে হবে।")
                    return@setPositiveButton
                }

                val service = PluginDashboardSession.service
                if (service == null) {
                    onStatus("Connector session সক্রিয় নেই; MRTR বন্ধ করা হয়েছে।")
                    return@setPositiveButton
                }
                worker.execute {
                    val grant = service.issueApprovalGrant(toolId, arguments).getOrElse { error ->
                        activity.runOnUiThread {
                            onStatus("MRTR retry অনুমোদন তৈরি হয়নি: ${error.message}")
                        }
                        return@execute
                    }
                    service.callTool(
                        toolId = toolId,
                        arguments = arguments,
                        approvalGrant = grant,
                        inputResponses = responses,
                        requestState = requestState
                    ).onSuccess { nextOutput ->
                        activity.runOnUiThread {
                            handleToolOutput(
                                toolId = toolId,
                                toolName = toolName,
                                arguments = arguments,
                                output = nextOutput,
                                round = round + 1
                            )
                        }
                    }.onFailure { error ->
                        activity.runOnUiThread {
                            onStatus("MCP MRTR retry ব্যর্থ: ${error.message}")
                        }
                    }
                }
            }
            .show()
    }

    private fun connectorIdFor(endpoint: String): String? {
        val uri = runCatching { java.net.URI(endpoint) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", true) ||
            uri.host.isNullOrBlank() ||
            uri.userInfo != null ||
            uri.fragment != null
        ) return null

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(endpoint.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
        return "mcp.$digest"
    }

    private fun JSONObject.toMap(depth: Int = 0): Map<String, Any?> {
        require(depth <= MAX_JSON_INPUT_DEPTH) { "JSON input nesting limit অতিক্রম করেছে।" }
        val result = linkedMapOf<String, Any?>()
        val iterator = keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            result[key] = jsonValueToKotlin(opt(key), depth + 1)
        }
        return result
    }

    private fun jsonValueToKotlin(value: Any?, depth: Int): Any? {
        require(depth <= MAX_JSON_INPUT_DEPTH) { "JSON input nesting limit অতিক্রম করেছে।" }
        return when (value) {
            is JSONObject -> value.toMap(depth)
            is JSONArray -> (0 until value.length()).map { index ->
                jsonValueToKotlin(value.opt(index), depth + 1)
            }
            JSONObject.NULL -> null
            else -> value
        }
    }

    private fun heading(parent: LinearLayout, value: String) {
        parent.addView(TextView(activity).apply {
            text = value
            textSize = 16f
            setTextColor(Color.rgb(5, 83, 72))
            setPadding(4, 14, 4, 8)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, matchParams())
    }

    private fun note(parent: LinearLayout, value: String) {
        parent.addView(TextView(activity).apply {
            text = value
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(10, 10, 10, 10)
        }, matchParams())
    }

    private fun button(parent: LinearLayout, label: String, action: () -> Unit) {
        parent.addView(Button(activity).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 6
        })
    }

    private fun matchParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
}
