package org.mosharrof.androidcontrol

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import net.openid.appauth.AuthorizationService
import java.security.MessageDigest
import java.util.concurrent.Executor

internal object PluginDashboardSession {
    var connectorId: String? = null
    var service: McpConnectorService? = null
}

class PluginDashboardPage(
    private val activity: Activity,
    private val worker: Executor,
    private val onStatus: (String) -> Unit,
    private val onStartOAuthAuthorization: (String, String, net.openid.appauth.AuthorizationRequest) -> Unit
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
    private lateinit var oauthMetadataView: TextView
    private lateinit var oauthClientMetadataUrlInput: EditText
    private lateinit var oauthScopesInput: EditText
    private var oauthDiscoveryInProgress = false
    private var oauthRequestPreparationInProgress = false
    @Volatile private var lastOAuthDiscovery: McpOAuthMetadataDiscovery? = null
    @Volatile private var lastOAuthConnectorId: String? = null

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
        button(parent, "OAuth Metadata যাচাই (শুধু GET)") {
            discoverOAuthMetadata()
        }
        oauthMetadataView = TextView(activity).apply {
            text = "OAuth metadata এখনো যাচাই করা হয়নি।"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(8, 8, 8, 12)
        }
        parent.addView(oauthMetadataView, matchParams())
        oauthClientMetadataUrlInput = EditText(activity).apply {
            hint = "OAuth Client Metadata URL (CIMD)"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        parent.addView(oauthClientMetadataUrlInput, matchParams())
        oauthScopesInput = EditText(activity).apply {
            hint = "OAuth scopes (space-separated; server-required)"
            setSingleLine(true)
        }
        parent.addView(oauthScopesInput, matchParams())
        button(parent, "Encrypted PKCE Request প্রস্তুত (Browser নয়)") {
            prepareOAuthAuthorizationRequest()
        }
        button(parent, "প্রস্তুত OAuth Request দিয়ে Browser Login শুরু করুন") {
            startPreparedOAuthAuthorization()
        }
        button(parent, "Remote OAuth Token Revocation (নিশ্চিতকরণসহ)") {
            confirmOAuthTokenRevocation()
        }
        button(parent, "সংরক্ষিত MCP Task পুনরুদ্ধার / যাচাই") {
            loadPendingTasks()
        }
        button(parent, "স্থানীয় Token ও OAuth Session মুছুন") {
            val id = PluginDashboardSession.connectorId
                ?: connectorIdFor(endpointInput.text.toString().trim())
            if (id == null) {
                onStatus("সঠিক HTTPS endpoint দিন।")
            } else {
                // Local logout only: remove every locally encrypted credential/request.
                // Remote refresh-token revocation is a separate network operation.
                val results = listOf(
                    PluginSecretStore.deleteToken(activity, id),
                    McpOAuthAuthStateStore.delete(activity, id),
                    McpOAuthPendingRequestStore.delete(activity, id)
                )
                val failure = results.firstOrNull { it.isFailure }?.exceptionOrNull()
                if (failure == null) {
                    tokenInput.text.clear()
                    onStatus("স্থানীয় Token, OAuth state ও pending PKCE request মুছে ফেলা হয়েছে। Remote revocation করা হয়নি।")
                } else {
                    onStatus("সব স্থানীয় credential মুছে ফেলা নিশ্চিত করা যায়নি: ${safeUiErrorMessage(failure)}")
                }
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

    /**
     * RFC 7009 revocation is a remote network operation. It runs only after
     * an explicit button tap and a second confirmation; local credentials are
     * retained unless the server returns HTTP 200.
     */
    private fun confirmOAuthTokenRevocation() {
        val connectorId = PluginDashboardSession.connectorId
            ?: connectorIdFor(endpointInput.text.toString().trim())
        if (connectorId == null) {
            onStatus("OAuth revocation-এর জন্য সঠিক HTTPS MCP endpoint দিন।")
            return
        }
        val discovery = lastOAuthDiscovery
        if (discovery == null || lastOAuthConnectorId != connectorId) {
            onStatus("আগে এই Connector-এর OAuth metadata পুনরায় যাচাই করুন।")
            return
        }
        val revocationEndpoint = McpOAuthMetadata
            .validatedRevocationEndpoint(discovery.authorizationServerMetadata)
        if (revocationEndpoint == null) {
            onStatus("Authorization Server নিরাপদ revocation_endpoint প্রকাশ করেনি; কোনো token বা local state পরিবর্তন করা হয়নি।")
            return
        }

        AlertDialog.Builder(activity)
            .setTitle("OAuth Token প্রত্যাহার করবেন?")
            .setMessage("Authorization Server-এ HTTPS POST পাঠিয়ে OAuth token revoke করা হবে। HTTP 200 নিশ্চিত না হওয়া পর্যন্ত স্থানীয় OAuth credentials মুছবে না।")
            .setNegativeButton("বাতিল", null)
            .setPositiveButton("Remote Token Revocation করুন") { _, _ ->
                worker.execute {
                    var remoteRevocationConfirmed = false
                    runCatching {
                        val authState = McpOAuthAuthStateStore
                            .read(activity, connectorId).getOrThrow()
                            ?: error("সংরক্ষিত OAuth session পাওয়া যায়নি।")
                        val clientId = authState.lastAuthorizationResponse
                            ?.request?.clientId
                            ?.takeIf { it.isNotBlank() }
                            ?: error("OAuth session থেকে validated public client ID পাওয়া যায়নি।")
                        val refreshToken = authState.refreshToken
                        val accessToken = authState.accessToken
                        val token = refreshToken ?: accessToken
                            ?: error("Revocation-এর জন্য কোনো OAuth token পাওয়া যায়নি।")
                        val hint = if (refreshToken != null) "refresh_token" else "access_token"

                        McpOAuthTokenRevocation.revoke(
                            revocationEndpoint = revocationEndpoint,
                            clientId = clientId,
                            token = token,
                            tokenTypeHint = hint
                        ).getOrThrow()
                        remoteRevocationConfirmed = true

                        McpOAuthAuthStateStore.delete(activity, connectorId).getOrThrow()
                        McpOAuthPendingRequestStore.delete(activity, connectorId).getOrThrow()
                    }.onSuccess {
                        activity.runOnUiThread {
                            tokenInput.text.clear()
                            onStatus("Authorization Server HTTP 200 দিয়ে OAuth token revocation নিশ্চিত করেছে; স্থানীয় OAuth state ও pending request মুছে ফেলা হয়েছে।")
                        }
                    }.onFailure { error ->
                        activity.runOnUiThread {
                            val message = if (remoteRevocationConfirmed) {
                                "Remote token revocation সফল, তবে স্থানীয় OAuth cleanup সম্পূর্ণ হয়নি: "
                            } else {
                                "OAuth revocation ব্যর্থ; স্থানীয় OAuth credentials পরিবর্তন করা হয়নি: "
                            }
                            onStatus(message + safeUiErrorMessage(error))
                        }
                    }
                }
            }
            .show()
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
                val oauthState = McpOAuthAuthStateStore.read(activity, id).getOrThrow()
                val taskPreferences = activity.getSharedPreferences("mosharrof_mcp_tasks", Activity.MODE_PRIVATE)
                val service = if (oauthState != null && enteredToken.isBlank()) {
                    val authorizationService = AuthorizationService(activity)
                    val resolver = McpOAuthAccessTokenProvider(activity, authorizationService)
                    val oauthClient = McpHttpClient(endpoint = endpoint, allowSynchronousRequests = false)
                    val oauthConnectorService = McpConnectorService(
                        client = oauthClient,
                        taskStore = SharedPreferencesMcpTaskStore(taskPreferences)
                    )
                    oauthConnectorService.connectAsync(
                        connectorId = id,
                        tokenProvider = resolver,
                        ioExecutor = worker
                    ) { result ->
                        authorizationService.dispose()
                        result.onSuccess { info ->
                            PluginDashboardSession.connectorId = id
                            PluginDashboardSession.service = oauthConnectorService
                            activity.runOnUiThread {
                                tokenInput.text.clear()
                                onStatus("OAuth-সহ MCP সংযুক্ত। আবিষ্কৃত Tool: ${info.discoveredTools}")
                                showTools(info.connectorId)
                            }
                        }.onFailure { error ->
                            activity.runOnUiThread {
                                onStatus("OAuth MCP সংযোগ ব্যর্থ: ${safeUiErrorMessage(error)}")
                            }
                        }
                    }
                    null
                } else {
                    val token = if (enteredToken.isNotBlank()) {
                        enteredToken
                    } else {
                        PluginSecretStore.readToken(activity, id)
                    }
                    val client = McpHttpClient(endpoint = endpoint, bearerToken = token)
                    val manualService = McpConnectorService(
                        client = client,
                        taskStore = SharedPreferencesMcpTaskStore(taskPreferences)
                    )
                    val info = manualService.connect(id).getOrThrow()
                    PluginDashboardSession.connectorId = id
                    PluginDashboardSession.service = manualService
                    info
                }
                service
            }.onSuccess { info ->
                if (info != null) {
                    activity.runOnUiThread {
                        tokenInput.text.clear()
                        onStatus("MCP সংযুক্ত। আবিষ্কৃত Tool: ${info.discoveredTools}")
                        showTools(info.connectorId)
                    }
                }
            }.onFailure { error ->
                activity.runOnUiThread {
                    onStatus("MCP সংযোগ ব্যর্থ: ${safeUiErrorMessage(error)}")
                }
            }
        }
    }

    /**
     * User-initiated, metadata-only OAuth discovery. This performs bounded HTTPS
     * GET requests; it never opens a browser or starts authorization/token flows.
     */
    private fun discoverOAuthMetadata() {
        val endpoint = endpointInput.text.toString().trim()
        val connectorId = connectorIdFor(endpoint)
        if (connectorId == null) {
            onStatus("OAuth discovery-এর জন্য সঠিক HTTPS MCP endpoint দিন।")
            return
        }
        if (oauthDiscoveryInProgress) {
            onStatus("OAuth metadata যাচাই ইতিমধ্যে চলছে।")
            return
        }
        oauthDiscoveryInProgress = true
        oauthMetadataView.text = "OAuth metadata যাচাই চলছে…"
        val existingService = PluginDashboardSession.service.takeIf {
            PluginDashboardSession.connectorId == connectorId
        }
        worker.execute {
            runCatching {
                val existing = ConnectorRegistry.connectorSnapshot().firstOrNull { it.id == connectorId }
                if (existing == null) {
                    ConnectorRegistry.registerConnector(
                        ConnectorRegistry.Connector(
                            id = connectorId,
                            name = "MCP Connector",
                            transport = ConnectorRegistry.Transport.MCP_STREAMABLE_HTTP,
                            endpoint = endpoint,
                            state = ConnectorRegistry.ConnectionState.CONFIGURED
                        )
                    ).getOrThrow()
                } else {
                    require(existing.endpoint == endpoint &&
                        existing.transport == ConnectorRegistry.Transport.MCP_STREAMABLE_HTTP) {
                        "নিবন্ধিত Connector endpoint বা transport মিলছে না।"
                    }
                }
                val discoveryService = existingService ?: McpConnectorService(
                    client = McpHttpClient(endpoint = endpoint)
                )
                discoveryService.discoverOAuthMetadata(connectorId).getOrThrow()
            }.onSuccess { discovery ->
                val issuer = discovery.authorizationServerIssuer
                val authorizationEndpoint =
                    discovery.authorizationServerMetadata["authorization_endpoint"] as? String
                val tokenEndpoint =
                    discovery.authorizationServerMetadata["token_endpoint"] as? String
                activity.runOnUiThread {
                    oauthDiscoveryInProgress = false
                    lastOAuthDiscovery = discovery
                    lastOAuthConnectorId = connectorId
                    oauthMetadataView.text = buildString {
                        append("OAuth metadata যাচাইকৃত\n")
                        append("Authorization Server: ").append(issuer)
                        append("\nAuthorization endpoint: ")
                            .append(authorizationEndpoint ?: "অনুপস্থিত")
                        append("\nToken endpoint: ")
                            .append(tokenEndpoint ?: "অনুপস্থিত")
                        append("\n\nএটি শুধু metadata discovery। কোনো login, browser redirect, ")
                        append("PKCE, token exchange বা credential সংরক্ষণ করা হয়নি।")
                    }
                    onStatus("OAuth metadata যাচাই সফল; authorization শুরু হয়নি।")
                }
            }.onFailure { failure ->
                activity.runOnUiThread {
                    oauthDiscoveryInProgress = false
                    lastOAuthDiscovery = null
                    lastOAuthConnectorId = null
                    oauthMetadataView.text = "OAuth metadata যাচাই ব্যর্থ।"
                    onStatus("OAuth metadata যাচাই ব্যর্থ: " + safeUiErrorMessage(failure))
                }
            }
        }
    }
    /**
     * Builds and encrypts an OAuth Authorization Code + S256 PKCE request.
     * This deliberately stops before browser launch or any token network call.
     */
    private fun prepareOAuthAuthorizationRequest() {
        val endpoint = endpointInput.text.toString().trim()
        val connectorId = connectorIdFor(endpoint)
        if (connectorId == null) {
            onStatus("OAuth request তৈরির আগে সঠিক HTTPS MCP endpoint দিন।")
            return
        }
        if (oauthRequestPreparationInProgress) {
            onStatus("OAuth PKCE request প্রস্তুত করা ইতিমধ্যে চলছে।")
            return
        }
        val discovery = lastOAuthDiscovery
        if (discovery == null || lastOAuthConnectorId != connectorId) {
            onStatus("আগে এই Connector-এর OAuth metadata যাচাই করুন।")
            return
        }
        val clientMetadataUrl = oauthClientMetadataUrlInput.text.toString().trim()
        val scopes = oauthScopesInput.text.toString().trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (clientMetadataUrl.isBlank()) {
            onStatus("OAuth Client Metadata URL (CIMD) দিন।")
            return
        }
        oauthRequestPreparationInProgress = true
        onStatus("CIMD যাচাই ও encrypted PKCE request প্রস্তুত হচ্ছে…")
        worker.execute {
            runCatching {
                val clientMetadata = McpOAuthMetadataFetcher()
                    .fetchClientMetadata(
                        clientMetadataUrl,
                        McpOAuthAuthorizationRequestFactory.REDIRECT_URI
                    ) ?: error("CIMD metadata HTTPS fetch বা validation ব্যর্থ।")
                val request = McpOAuthAuthorizationRequestFactory.build(
                    issuer = discovery.authorizationServerIssuer,
                    authorizationServerMetadata = discovery.authorizationServerMetadata,
                    clientMetadataUrl = clientMetadataUrl,
                    clientMetadata = clientMetadata,
                    resourceUrl = endpoint.trim(),
                    resourceMetadata = discovery.resourceMetadata,
                    scopes = scopes
                )
                McpOAuthPendingRequestStore.save(activity, connectorId, request).getOrThrow()
            }.onSuccess {
                activity.runOnUiThread {
                    oauthRequestPreparationInProgress = false
                    onStatus("S256 PKCE Authorization Request encrypted storage-এ প্রস্তুত। Browser বা token exchange শুরু হয়নি.")
                }
            }.onFailure { error ->
                activity.runOnUiThread {
                    oauthRequestPreparationInProgress = false
                    onStatus("OAuth request প্রস্তুত হয়নি: " + safeUiErrorMessage(error))
                }
            }
        }
    }

    /**
     * Launches only after an explicit in-app user tap. The authorization request
     * must already exist in encrypted storage and is re-read immediately before use.
     */
    private fun startPreparedOAuthAuthorization() {
        val endpoint = endpointInput.text.toString().trim()
        val connectorId = connectorIdFor(endpoint)
        if (connectorId == null) {
            onStatus("OAuth Browser Login-এর জন্য সঠিক HTTPS MCP endpoint দিন।")
            return
        }
        worker.execute {
            val pending = McpOAuthPendingRequestStore
                .read(activity, connectorId)
                .getOrElse {
                    activity.runOnUiThread {
                        onStatus("Encrypted OAuth request পড়া যায়নি; আগে PKCE request প্রস্তুত করুন।")
                    }
                    return@execute
                }
            if (pending == null) {
                activity.runOnUiThread {
                    onStatus("Encrypted pending OAuth request নেই; আগে PKCE request প্রস্তুত করুন।")
                }
                return@execute
            }
            activity.runOnUiThread {
                onStartOAuthAuthorization(connectorId, endpoint, pending)
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
                        onStatus("Disconnect ব্যর্থ: ${safeUiErrorMessage(error)}")
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
        val tool = ConnectorRegistry.toolSnapshot().firstOrNull { it.id == toolId }
        val fields = tool?.let { McpSchemaForm.fields(it.inputSchema) }
        if (tool != null && !fields.isNullOrEmpty()) {
            requestSchemaToolCall(toolId, toolName, risk, tool.inputSchema, tool.requiredFields, fields)
        } else {
            requestJsonToolCall(toolId, toolName, risk)
        }
    }

    private fun requestSchemaToolCall(
        toolId: String,
        toolName: String,
        risk: String,
        schema: Map<String, Any?>,
        requiredFields: Collection<String>,
        fields: List<McpSchemaForm.Field>
    ) {
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 8, 20, 8)
        }
        val textInputs = linkedMapOf<String, EditText>()
        val booleanInputs = linkedMapOf<String, CheckBox>()
        val enumInputs = linkedMapOf<String, Spinner>()
        fields.forEach { field ->
            note(form, field.name + if (field.required) " · প্রয়োজনীয়" else " · ঐচ্ছিক")
            when {
                field.enumValues.isNotEmpty() -> {
                    val spinner = Spinner(activity)
                    val labels = field.enumValues.map { it?.toString() ?: "null" }
                    spinner.adapter = ArrayAdapter(
                        activity,
                        android.R.layout.simple_spinner_item,
                        labels
                    ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                    if (field.hasDefault) {
                        val selected = field.enumValues.indexOf(field.defaultValue)
                        if (selected >= 0) spinner.setSelection(selected)
                    }
                    enumInputs[field.name] = spinner
                    form.addView(spinner, matchParams())
                }
                field.type == "boolean" -> {
                    val check = CheckBox(activity).apply {
                        text = field.name
                        isChecked = field.defaultValue as? Boolean ?: false
                    }
                    booleanInputs[field.name] = check
                    form.addView(check, matchParams())
                }
                else -> {
                    val edit = EditText(activity).apply {
                        hint = field.name
                        setSingleLine(true)
                        if (field.hasDefault) setText(field.defaultValue?.toString().orEmpty())
                        inputType = when (field.type) {
                            "integer" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                            "number" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
                            else -> InputType.TYPE_CLASS_TEXT
                        }
                    }
                    textInputs[field.name] = edit
                    form.addView(edit, matchParams())
                }
            }
        }

        AlertDialog.Builder(activity)
            .setTitle("Tool অনুমোদন")
            .setMessage("Tool: $toolName\\nRisk: $risk\\n\\nSchema অনুযায়ী তথ্য দিন। চালালে এই call-এর জন্য স্পষ্ট অনুমোদন দেওয়া হবে।")
            .setView(form)
            .setNegativeButton("বাতিল", null)
            .setPositiveButton("অনুমোদন করে চালান") { _, _ ->
                val textValues = textInputs.mapValues { it.value.text.toString() }
                val boolValues = booleanInputs.mapValues { it.value.isChecked }
                val enumIndices = enumInputs.mapValues { it.value.selectedItemPosition }
                val args = McpSchemaForm.parse(fields, textValues, boolValues, enumIndices)
                    .getOrElse {
                        onStatus("Form input সঠিক নয়: ${safeUiErrorMessage(it)}")
                        return@setPositiveButton
                    }
                if (!McpArgumentsValidator.validate(schema, args, requiredFields)) {
                    onStatus("Form-এর তথ্য JSON Schema অনুযায়ী বৈধ নয়।")
                    return@setPositiveButton
                }
                executeApprovedToolCall(toolId, toolName, args)
            }
            .show()
    }

    /** Creates a short-lived AppAuth resolver only for connectors with encrypted OAuth state. */
    private fun oauthResolverFor(connectorId: String): Pair<AuthorizationService, McpAccessTokenResolver>? {
        val state = McpOAuthAuthStateStore.read(activity, connectorId).getOrThrow() ?: return null
        require(state.accessToken != null || state.refreshToken != null) { "OAuth session has no usable token state." }
        val authorizationService = AuthorizationService(activity)
        return authorizationService to McpOAuthAccessTokenProvider(activity, authorizationService)
    }

    private fun callToolWithTransport(
        service: McpConnectorService,
        connectorId: String,
        toolId: String,
        arguments: Map<String, Any?>,
        approvalGrant: String?,
        inputResponses: Map<String, Any?>? = null,
        requestState: String? = null,
        onComplete: (Result<String>) -> Unit
    ) {
        val oauth = runCatching { oauthResolverFor(connectorId) }.getOrElse { onComplete(Result.failure(it)); return }
        if (oauth == null) {
            onComplete(service.callTool(toolId, arguments, approvalGrant, inputResponses, requestState))
            return
        }
        val (authorizationService, resolver) = oauth
        service.callToolAsync(
            toolId = toolId,
            arguments = arguments,
            approvalGrant = approvalGrant,
            inputResponses = inputResponses,
            requestState = requestState,
            tokenProvider = resolver,
            ioExecutor = worker
        ) { result ->
            authorizationService.dispose()
            onComplete(result)
        }
    }

    private fun executeApprovedToolCall(toolId: String, toolName: String, args: Map<String, Any?>) {
        val service = PluginDashboardSession.service
        val connectorId = PluginDashboardSession.connectorId
        if (service == null || connectorId == null) { onStatus("Connector session সক্রিয় নেই।"); return }
        worker.execute {
            val grant = service.issueApprovalGrant(toolId, args).getOrElse { error ->
                activity.runOnUiThread { onStatus("অনুমোদন তৈরি হয়নি: ${safeUiErrorMessage(error)}") }
                return@execute
            }
            callToolWithTransport(service, connectorId, toolId, args, grant) { result ->
                result.onSuccess { output -> activity.runOnUiThread { handleToolOutput(toolId, toolName, args, output, 0) } }
                    .onFailure { error -> activity.runOnUiThread { onStatus("Tool call ব্যর্থ: ${safeUiErrorMessage(error)}") } }
            }
        }
    }

    private fun requestJsonToolCall(toolId: String, toolName: String, risk: String) {
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
                    onStatus("Input JSON সঠিক নয়: ${safeUiErrorMessage(it)}")
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
                            onStatus("অনুমোদন তৈরি হয়নি: ${safeUiErrorMessage(error)}")
                        }
                        return@execute
                    }

                    callToolWithTransport(service, PluginDashboardSession.connectorId ?: return@execute, toolId, args, grant) { result ->
                        result.onSuccess { output -> activity.runOnUiThread {
                            handleToolOutput(toolId, toolName, args, output, 0)
                        } }.onFailure { error -> activity.runOnUiThread {
                            onStatus("Tool call ব্যর্থ: ${safeUiErrorMessage(error)}")
                        } }
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
        if (result.optString("resultType") == "task") {
            val handle = McpTaskProtocol.parseHandle(result.toMap())
            if (handle == null) {
                resultView.text = "অবৈধ MCP Task handle; নিরাপদে থামানো হয়েছে।"
                onStatus("MCP Task handle যাচাই ব্যর্থ।")
                return
            }
            resultView.text = "MCP Task শুরু হয়েছে: ${handle.taskId}\nStatus: ${handle.status}"
            onStatus("MCP Task শুরু হয়েছে; সার্ভার থেকে বর্তমান অবস্থা যাচাই করা হচ্ছে।")
            refreshTask(handle.taskId)
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

        if (requests.length() == 1) {
            val requestKey = requests.keys().asSequence().firstOrNull() ?: return
            val request = requests.optJSONObject(requestKey) ?: return
            val params = request.optJSONObject("params")
            if (request.optString("method") == "elicitation/create" &&
                params != null && params.optString("mode") == "form"
            ) {
                val requestedSchema = params.optJSONObject("requestedSchema")
                val schema = requestedSchema?.toMap()
                val fields = schema?.let { McpSchemaForm.fields(it) }
                if (schema != null && !fields.isNullOrEmpty()) {
                    showElicitationForm(
                        toolId, toolName, arguments, requestKey, params.optString("message"),
                        schema, fields, requestState, round
                    )
                    return
                }
            }
        }

        val externalUrl = buildList {
            val iterator = requests.keys()
            while (iterator.hasNext()) {
                val request = requests.optJSONObject(iterator.next()) ?: continue
                if (request.optString("method") != "elicitation/create") continue
                val params = request.optJSONObject("params") ?: continue
                if (params.optString("mode") != "url") continue
                McpProtocol.validateElicitationUrl(params.optString("url"))?.let(::add)
            }
        }.firstOrNull()
        val hasUnsafeUrlMode = run {
            val iterator = requests.keys()
            var unsafe = false
            while (iterator.hasNext()) {
                val request = requests.optJSONObject(iterator.next()) ?: continue
                val params = request.optJSONObject("params") ?: continue
                if (request.optString("method") == "elicitation/create" &&
                    params.optString("mode") == "url" &&
                    McpProtocol.validateElicitationUrl(params.optString("url")) == null
                ) unsafe = true
            }
            unsafe
        }
        val responseInput = EditText(activity).apply {
            hint = """{"request-key":{"action":"accept","content":{}}}"""
            setText("{}")
            minLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val dialogBuilder = AlertDialog.Builder(activity)
            .setTitle("MCP অতিরিক্ত তথ্য চায় (${round + 1}/$MAX_MRTR_ROUNDS)")
            .setMessage(
                "সার্ভারের অনুরোধ (বিশ্বাসহীন ডেটা—পড়ে যাচাই করুন):\\n" +
                    requests.toString(2).take(MAX_MRTR_DISPLAY_CHARS) +
                    (if (externalUrl != null) "\\n\\nURL mode: লিংকটি বাইরের ব্রাউজারে খুলতে পারবেন।" else "") +
                    (if (hasUnsafeUrlMode) "\\nঅবৈধ বা অনিরাপদ URL mode লিংক ব্লক করা হয়েছে।" else "") +
                    "\\n\\nনিচে inputResponses JSON লিখুন। সংবেদনশীল তথ্য দেবেন না।"
            )
            .setView(responseInput)
            .setNegativeButton("বাতিল", null)
            .setPositiveButton("উত্তর পাঠান") { _, _ ->
                val responses = runCatching {
                    JSONObject(responseInput.text.toString()).toMap()
                }.getOrElse {
                    onStatus("inputResponses JSON সঠিক নয়: ${safeUiErrorMessage(it)}")
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
                            onStatus("MRTR retry অনুমোদন তৈরি হয়নি: ${safeUiErrorMessage(error)}")
                        }
                        return@execute
                    }
                    callToolWithTransport(service, PluginDashboardSession.connectorId ?: return@execute, toolId, arguments, grant, responses, requestState) { result ->
                        result.onSuccess { nextOutput -> activity.runOnUiThread {
                            handleToolOutput(toolId, toolName, arguments, nextOutput, round + 1)
                        } }.onFailure { error -> activity.runOnUiThread {
                            onStatus("MCP MRTR retry ব্যর্থ: ${safeUiErrorMessage(error)}")
                        } }
                    }
                }
            }
        if (externalUrl != null) {
            dialogBuilder.setNeutralButton("ব্রাউজারে খুলুন", null)
        }
        val dialog = dialogBuilder.create()
        if (externalUrl != null) {
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    runCatching {
                        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(externalUrl)))
                    }.onSuccess {
                        onStatus("URL mode লিংক বাইরের ব্রাউজারে খোলা হয়েছে; ফিরে এসে উত্তর যাচাই করুন।")
                    }.onFailure {
                        onStatus("ব্রাউজার খোলা যায়নি: ${safeUiErrorMessage(it)}")
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showElicitationForm(
        toolId: String,
        toolName: String,
        arguments: Map<String, Any?>,
        requestKey: String,
        serverMessage: String,
        schema: Map<String, Any?>,
        fields: List<McpSchemaForm.Field>,
        requestState: String?,
        round: Int
    ) {
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 8, 20, 8)
        }
        val textInputs = linkedMapOf<String, EditText>()
        val booleanInputs = linkedMapOf<String, CheckBox>()
        val enumInputs = linkedMapOf<String, Spinner>()
        fields.forEach { field ->
            note(form, field.name + if (field.required) " · প্রয়োজনীয়" else " · ঐচ্ছিক")
            when {
                field.enumValues.isNotEmpty() -> {
                    val spinner = Spinner(activity)
                    val labels = field.enumValues.map { it?.toString() ?: "null" }
                    spinner.adapter = ArrayAdapter(
                        activity, android.R.layout.simple_spinner_item, labels
                    ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                    if (field.hasDefault) {
                        val selected = field.enumValues.indexOf(field.defaultValue)
                        if (selected >= 0) spinner.setSelection(selected)
                    }
                    enumInputs[field.name] = spinner
                    form.addView(spinner, matchParams())
                }
                field.type == "boolean" -> {
                    val check = CheckBox(activity).apply {
                        text = field.name
                        isChecked = field.defaultValue as? Boolean ?: false
                    }
                    booleanInputs[field.name] = check
                    form.addView(check, matchParams())
                }
                else -> {
                    val edit = EditText(activity).apply {
                        hint = field.name
                        setSingleLine(true)
                        if (field.hasDefault) setText(field.defaultValue?.toString().orEmpty())
                        inputType = when (field.type) {
                            "integer" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                            "number" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
                            else -> InputType.TYPE_CLASS_TEXT
                        }
                    }
                    textInputs[field.name] = edit
                    form.addView(edit, matchParams())
                }
            }
        }
        val action = Spinner(activity).apply {
            adapter = ArrayAdapter(
                activity, android.R.layout.simple_spinner_item, listOf("accept", "decline", "cancel")
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        note(form, "আপনার সিদ্ধান্ত")
        form.addView(action, matchParams())

        AlertDialog.Builder(activity)
            .setTitle("MCP নিরাপদ তথ্য-অনুরোধ (${round + 1}/$MAX_MRTR_ROUNDS)")
            .setMessage(serverMessage.take(1_000) + "\\n\\nসার্ভারের অনুরোধ যাচাই করে সিদ্ধান্ত নিন।")
            .setView(form)
            .setNegativeButton("বাতিল", null)
            .setPositiveButton("উত্তর পাঠান") { _, _ ->
                val selectedAction = action.selectedItem?.toString() ?: "cancel"
                val response = if (selectedAction == "accept") {
                    val content = McpSchemaForm.parse(
                        fields,
                        textInputs.mapValues { it.value.text.toString() },
                        booleanInputs.mapValues { it.value.isChecked },
                        enumInputs.mapValues { it.value.selectedItemPosition }
                    ).getOrElse {
                        onStatus("Elicitation form input সঠিক নয়: ${safeUiErrorMessage(it)}")
                        return@setPositiveButton
                    }
                    val required = (schema["required"] as? List<*>)?.filterIsInstance<String>().orEmpty()
                    if (!McpArgumentsValidator.validate(schema, content, required)) {
                        onStatus("Elicitation form-এর তথ্য schema অনুযায়ী বৈধ নয়।")
                        return@setPositiveButton
                    }
                    mapOf("action" to "accept", "content" to content)
                } else {
                    mapOf("action" to selectedAction)
                }
                val service = PluginDashboardSession.service
                if (service == null) {
                    onStatus("Connector session সক্রিয় নেই; MRTR বন্ধ করা হয়েছে।")
                    return@setPositiveButton
                }
                worker.execute {
                    val grant = service.issueApprovalGrant(toolId, arguments).getOrElse { error ->
                        activity.runOnUiThread {
                            onStatus("MRTR retry অনুমোদন তৈরি হয়নি: ${safeUiErrorMessage(error)}")
                        }
                        return@execute
                    }
                    callToolWithTransport(service, PluginDashboardSession.connectorId ?: return@execute, toolId, arguments, grant, mapOf(requestKey to response), requestState) { result ->
                        result.onSuccess { nextOutput -> activity.runOnUiThread {
                            handleToolOutput(toolId, toolName, arguments, nextOutput, round + 1)
                        } }.onFailure { error -> activity.runOnUiThread {
                            onStatus("MCP MRTR retry ব্যর্থ: ${safeUiErrorMessage(error)}")
                        } }
                    }
                }
            }
            .show()
    }

    /** Refresh task state only on explicit user action; no hidden background polling. */
    private fun getTaskWithTransport(service: McpConnectorService, connectorId: String, taskId: String, callback: (Result<McpTaskProtocol.Snapshot>) -> Unit) {
        val oauth = runCatching { oauthResolverFor(connectorId) }.getOrElse { callback(Result.failure(it)); return }
        if (oauth == null) { callback(service.getTask(connectorId, taskId)); return }
        val (authorizationService, resolver) = oauth
        service.getTaskAsync(connectorId, taskId, resolver, worker) { result ->
            authorizationService.dispose()
            callback(result)
        }
    }

    private fun updateTaskWithTransport(service: McpConnectorService, connectorId: String, taskId: String, responses: Map<String, Any?>, callback: (Result<McpTaskProtocol.Snapshot>) -> Unit) {
        val oauth = runCatching { oauthResolverFor(connectorId) }.getOrElse { callback(Result.failure(it)); return }
        if (oauth == null) { callback(service.updateTask(connectorId, taskId, responses)); return }
        val (authorizationService, resolver) = oauth
        service.updateTaskAsync(connectorId, taskId, responses, resolver, worker) { result ->
            authorizationService.dispose()
            callback(result)
        }
    }

    private fun cancelTaskWithTransport(service: McpConnectorService, connectorId: String, taskId: String, callback: (Result<McpTaskProtocol.Snapshot>) -> Unit) {
        val oauth = runCatching { oauthResolverFor(connectorId) }.getOrElse { callback(Result.failure(it)); return }
        if (oauth == null) { callback(service.cancelTask(connectorId, taskId)); return }
        val (authorizationService, resolver) = oauth
        service.cancelTaskAsync(connectorId, taskId, resolver, worker) { result ->
            authorizationService.dispose()
            callback(result)
        }
    }

    private fun refreshTask(taskId: String) {
        val service = PluginDashboardSession.service
        val connectorId = PluginDashboardSession.connectorId
        if (service == null || connectorId == null) {
            onStatus("MCP session নেই; Task status যাচাই করা যায়নি।")
            return
        }
        worker.execute {
            getTaskWithTransport(service, connectorId, taskId) { result ->
                result.onSuccess { snapshot -> activity.runOnUiThread { showTaskSnapshot(snapshot) } }
                    .onFailure { error -> activity.runOnUiThread {
                        onStatus("MCP Task status পাওয়া যায়নি: ${safeUiErrorMessage(error)}")
                    } }
            }
        }
    }

    private fun loadPendingTasks() {
        val service = PluginDashboardSession.service
        val connectorId = PluginDashboardSession.connectorId
        if (service == null || connectorId == null) {
            onStatus("আগে MCP Connector সংযুক্ত করুন।")
            return
        }
        worker.execute {
            service.pendingTasks(connectorId)
                .onSuccess { candidates ->
                    activity.runOnUiThread {
                        if (candidates.isEmpty()) {
                            onStatus("এই Connector-এর কোনো সংরক্ষিত pending Task নেই।")
                            return@runOnUiThread
                        }
                        val labels = candidates.map {
                            "${it.taskId} · local candidate status: ${it.status}"
                        }.toTypedArray()
                        AlertDialog.Builder(activity)
                            .setTitle("পুনরুদ্ধারের Task candidate")
                            .setItems(labels) { _, index ->
                                // Never trust persisted status; fetch the selected task remotely.
                                refreshTask(candidates[index].taskId)
                            }
                            .setNegativeButton("বন্ধ", null)
                            .show()
                        onStatus("${candidates.size}টি local Task candidate পাওয়া গেছে; নির্বাচন করলে server থেকে যাচাই হবে।")
                    }
                }
                .onFailure { error ->
                    activity.runOnUiThread {
                        onStatus("Task recovery তালিকা পাওয়া যায়নি: ${safeUiErrorMessage(error)}")
                    }
                }
        }
    }

    private fun showTaskSnapshot(snapshot: McpTaskProtocol.Snapshot) {
        val statusLabel = snapshot.status.name
        val details = buildString {
            append("Task ID: ${snapshot.taskId}\nStatus: $statusLabel")
            snapshot.statusMessage?.let { append("\n\nসার্ভার বার্তা: ${it.take(1_000)}") }
            snapshot.result?.let { append("\n\nResult:\n${JSONObject(it).toString(2).take(4_000)}") }
            snapshot.error?.let { append("\n\nError:\n${JSONObject(it).toString(2).take(2_000)}") }
            snapshot.inputRequests?.let {
                append("\n\nInput requests (বিশ্বাসহীন সার্ভার ডেটা):\n")
                append(JSONObject(it).toString(2).take(3_000))
            }
        }
        resultView.text = details
        val builder = AlertDialog.Builder(activity)
            .setTitle("MCP Task · $statusLabel")
            .setMessage(details)
            .setNegativeButton("বন্ধ", null)
        when (snapshot.status) {
            McpTaskProtocol.Status.WORKING -> {
                builder.setNeutralButton("অবস্থা Refresh", null)
                builder.setPositiveButton("Task বাতিল", null)
            }
            McpTaskProtocol.Status.INPUT_REQUIRED -> {
                builder.setNeutralButton("উত্তর দিন", null)
                builder.setPositiveButton("Task বাতিল", null)
            }
            else -> Unit
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            if (snapshot.status == McpTaskProtocol.Status.WORKING) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    dialog.dismiss()
                    refreshTask(snapshot.taskId)
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    dialog.dismiss()
                    confirmTaskCancellation(snapshot.taskId)
                }
            } else if (snapshot.status == McpTaskProtocol.Status.INPUT_REQUIRED) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    dialog.dismiss()
                    requestTaskInput(snapshot)
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    dialog.dismiss()
                    confirmTaskCancellation(snapshot.taskId)
                }
            }
        }
        dialog.show()
    }

    private fun requestTaskInput(snapshot: McpTaskProtocol.Snapshot) {
        val requests = snapshot.inputRequests ?: run {
            onStatus("Task input_required হলেও inputRequests অনুপস্থিত।")
            return
        }
        val input = EditText(activity).apply {
            hint = """{"request-key":{"action":"accept","content":{}}}"""
            setText("{}")
            minLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        AlertDialog.Builder(activity)
            .setTitle("MCP Task-এর অতিরিক্ত তথ্য")
            .setMessage("সার্ভারের অনুরোধ (বিশ্বাসহীন ডেটা—যাচাই করুন):\\n" + JSONObject(requests).toString(2).take(3_000) + "\\n\\nশুধু প্রয়োজনীয় inputResponses JSON দিন। পাঠানোর আগে আপনার স্পষ্ট অনুমতি লাগবে।")
            .setView(input)
            .setNegativeButton("বাতিল", null)
            .setPositiveButton("যাচাই করে উত্তর পাঠান") { _, _ ->
                val responses = runCatching { JSONObject(input.text.toString()).toMap() }.getOrElse {
                    onStatus("Task input JSON সঠিক নয়: ${safeUiErrorMessage(it)}")
                    return@setPositiveButton
                }
                if (!McpTaskProtocol.validateInputResponses(requests, responses)) {
                    onStatus("Task inputResponses-এর key ও object payload সার্ভারের request-এর সঙ্গে মিলতে হবে।")
                    return@setPositiveButton
                }
                val service = PluginDashboardSession.service
                val connectorId = PluginDashboardSession.connectorId
                if (service == null || connectorId == null) {
                    onStatus("MCP session নেই; Task input পাঠানো হয়নি।")
                    return@setPositiveButton
                }
                worker.execute {
                    updateTaskWithTransport(service, connectorId, snapshot.taskId, responses) { result ->
                        result.onSuccess { next -> activity.runOnUiThread { showTaskSnapshot(next) } }
                            .onFailure { error -> activity.runOnUiThread {
                                onStatus("Task input পাঠানো ব্যর্থ: ${safeUiErrorMessage(error)}")
                            } }
                    }
                }
            }
            .show()
    }

    private fun confirmTaskCancellation(taskId: String) {
        AlertDialog.Builder(activity)
            .setTitle("MCP Task বাতিল করবেন?")
            .setMessage("এই অনুরোধটি MCP সার্ভারে পাঠানো হবে।")
            .setNegativeButton("ফিরে যান", null)
            .setPositiveButton("Task বাতিল করুন") { _, _ ->
                val service = PluginDashboardSession.service
                val connectorId = PluginDashboardSession.connectorId
                if (service == null || connectorId == null) {
                    onStatus("MCP session নেই; Task বাতিল করা হয়নি।")
                    return@setPositiveButton
                }
                worker.execute {
                    cancelTaskWithTransport(service, connectorId, taskId) { result ->
                        result.onSuccess { snapshot -> activity.runOnUiThread { showTaskSnapshot(snapshot) } }
                            .onFailure { error -> activity.runOnUiThread {
                                onStatus("Task বাতিল ব্যর্থ: ${safeUiErrorMessage(error)}")
                            } }
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
