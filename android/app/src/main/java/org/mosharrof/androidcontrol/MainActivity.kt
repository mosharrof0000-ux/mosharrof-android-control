package org.mosharrof.androidcontrol

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.app.ActivityManager
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.os.StatFs
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import java.security.MessageDigest
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var report: TextView
    private lateinit var status: TextView
    private lateinit var pageTitle: TextView
    private lateinit var pageHost: LinearLayout
    private var selectedTree: DocumentFile? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val folderRequestCode = 4102
    private val maxFiles = 5000
    private val maxDepth = 12
    private lateinit var relayUrl: EditText
    private lateinit var setupToken: EditText
    private lateinit var aiModel: EditText
    private lateinit var aiEndpoint: EditText
    private lateinit var aiKey: EditText
    private lateinit var aiPrompt: EditText

    private var aiModelDraft = ""
    private var aiEndpointDraft = ""
    private var aiPromptDraft = ""
    private var relayUrlDraft = ""
    private val relayHandler = Handler(Looper.getMainLooper())
    @Volatile private var relayPolling = false
    @Volatile private var activityVisible = false
    @Volatile private var relayApprovalPending = false
    @Volatile private var lastRelayCommand = ""
    private val relayTick: Runnable = object : Runnable {
        override fun run() {
            if (!relayPolling || !activityVisible || isFinishing || relayApprovalPending) return
            worker.execute {
                if (!relayApprovalPending) {
                    runCatching {
                        RelayClient.retryPendingResult(this@MainActivity)
                        RelayClient.poll(this@MainActivity)
                    }.onSuccess { job ->
                        if (job != null && job.commandId != lastRelayCommand && !relayApprovalPending) {
                            lastRelayCommand = job.commandId
                            relayApprovalPending = true
                            runOnUiThread { requestRelayJobApproval(job) }
                        }
                    }.onFailure { error ->
                        runOnUiThread { status.text = "Relay polling সমস্যা: ${error.message}" }
                    }
                }
                if (relayPolling && activityVisible && !relayApprovalPending) {
                    relayHandler.postDelayed(this@MainActivity.relayTick, 3000)
                }
            }
        }
    }

    private val teal = Color.rgb(8, 124, 104)
    private val tealDark = Color.rgb(5, 83, 72)
    private val canvasColor = Color.rgb(245, 249, 247)
    private val cardColor = Color.WHITE
    private val mutedColor = Color.rgb(100, 119, 112)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        report = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(35, 55, 48))
            setPadding(16, 14, 16, 14)
        }
        status = TextView(this).apply {
            text = "ডিভাইস প্রস্তুত"
            textSize = 13f
            setTextColor(tealDark)
            setPadding(14, 12, 14, 12)
            background = roundedBackground(Color.rgb(226, 243, 237), 14)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(canvasColor)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 22, 20, 14)
            setBackgroundColor(tealDark)
        }
        header.addView(TextView(this).apply {
            text = "MOSHARROF  /  ANDROID"
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(Color.rgb(181, 229, 213))
            typeface = Typeface.DEFAULT_BOLD
        })
        header.addView(TextView(this).apply {
            text = "Android Control"
            textSize = 25f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, 5, 0, 3)
        })
        header.addView(TextView(this).apply {
            text = "আপনার ডিভাইস, এক জায়গায় নিয়ন্ত্রণ"
            textSize = 13f
            setTextColor(Color.rgb(218, 239, 231))
        })
        root.addView(header)

        val statusWrap = LinearLayout(this).apply {
            setPadding(16, 12, 16, 8)
            addView(status, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(statusWrap)

        pageTitle = TextView(this).apply {
            textSize = 20f
            setTextColor(tealDark)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(20, 10, 20, 12)
        }
        root.addView(pageTitle)

        val scroll = ScrollView(this)
        pageHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 0, 16, 20)
        }
        scroll.addView(pageHost)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(6, 7, 6, 7)
            setBackgroundColor(Color.WHITE)
            elevation = 8f
        }
        addNavButton(nav, "হোম") { showHome() }
        addNavButton(nav, "স্ক্রিন") { showScreenPage() }
        addNavButton(nav, "ফাইল") { showFilesPage() }
        addNavButton(nav, "টুলস") { showToolsPage() }
        addNavButton(nav, "MCP") { showMcpPage() }
        root.addView(nav)

        setContentView(root)
        showHome()
        refreshDiagnostics()
    }

    private fun roundedBackground(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }
    }

    private fun addNavButton(parent: LinearLayout, label: String, action: () -> Unit) {
        val button = TextView(this).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(tealDark)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(10, 12, 10, 12)
            setOnClickListener { action() }
        }
        parent.addView(button, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun showPage(title: String, content: LinearLayout.() -> Unit) {
        pageTitle.text = title
        if (::aiModel.isInitialized) aiModelDraft = aiModel.text.toString()
        if (::aiEndpoint.isInitialized) aiEndpointDraft = aiEndpoint.text.toString()
        if (::aiPrompt.isInitialized) aiPromptDraft = aiPrompt.text.toString()
        if (::relayUrl.isInitialized) relayUrlDraft = relayUrl.text.toString()

        pageHost.removeAllViews()
        pageHost.content()
        pageHost.addView(report, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 14 })
        report.background = roundedBackground(Color.WHITE, 16)
    }

    private fun addCard(
        parent: LinearLayout,
        title: String,
        detail: String,
        action: () -> Unit
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 16, 18, 16)
            background = roundedBackground(cardColor, 18)
            elevation = 2f
            setOnClickListener { action() }
        }
        card.addView(TextView(this).apply {
            text = title
            textSize = 17f
            setTextColor(tealDark)
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(TextView(this).apply {
            text = detail
            textSize = 13f
            setTextColor(mutedColor)
            setPadding(0, 6, 0, 0)
        })
        parent.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 12 })
    }

    private fun addHeading(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(tealDark)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(4, 12, 4, 8)
        })
    }

    private fun showHome() {
        showPage("ড্যাশবোর্ড") {
            addHeading(this, "প্রধান বিভাগ")
            addCard(this, "স্ক্রিন নিয়ন্ত্রণ", "স্ক্রিনের লেখা ও বাটন শনাক্ত, Accessibility", ::showScreenPage)
            addCard(this, "ফাইল ব্যবস্থাপনা", "ফোল্ডার, ফাইল তালিকা, ডুপ্লিকেট ও অডিও", ::showFilesPage)
            addCard(this, "অনুমতি ও সক্ষমতা", "Android API, অনুমতি এবং Module Manager", ::showPermissionsPage)
            addCard(this, "AI ও Relay", "AI সংযোগ, নিরাপদ Pairing এবং Relay", ::showAiRelayPage)
            addCard(this, "MCP Plugin Connector", "HTTPS MCP Server যুক্ত করুন, Tools দেখুন ও অনুমোদন দিয়ে চালান", ::showMcpPage)
            addCard(this, "Termux ও কমান্ড", "অনুমোদিত কমান্ড এবং ফলাফল", ::showTermuxPage)
            addCard(this, "ডিভাইস পর্যবেক্ষণ", "স্টোরেজ, RAM ও সিস্টেম তথ্য", ::showToolsPage)
            addHeading(this, "দ্রুত অবস্থা")
            addButton("ডিভাইস তথ্য হালনাগাদ") { refreshDiagnostics() }
            addButton("Engine Self-Test চালান") { runEngineSelfTest() }
        }
    }

    private fun showScreenPage() {
        showPage("স্ক্রিন নিয়ন্ত্রণ") {
            addHeading(this, "পড়া ও শনাক্তকরণ")
            addButton("স্ক্রিনের লেখা ও বাটন শনাক্ত করুন") { showAccessibilitySnapshot() }
            addButton("Accessibility অনুমতি চালু করুন") {
                startActivity(Intent("android.settings.ACCESSIBILITY_SETTINGS"))
            }
            addButton("অনুমতি কেন্দ্র খুলুন") { PermissionCenter.show(this@MainActivity) }
            addTextNote(this, "বর্তমানে স্ক্রিন শনাক্তকরণ read-only। অনুমতি ছাড়া কোনো টাচ বা ক্লিক করা হয় না।")
        }
    }

    private fun showFilesPage() {
        showPage("ফাইল ব্যবস্থাপনা") {
            addHeading(this, "ফোল্ডার নির্বাচন")
            addButton("ফোল্ডার নির্বাচন / পরিবর্তন") { openFolderPicker() }
            addButton("নির্বাচিত ফোল্ডারের ফাইল তালিকা") { scanSelectedFolder(false) }
            addButton("ডুপ্লিকেট ফাইল শনাক্ত") { scanSelectedFolder(true) }
            addButton("অডিও ফাইলের তথ্য দেখুন") { scanAudioMetadata() }
            addTextNote(this, "ফাইল স্ক্যানের সময় কোনো ফাইল মোছা বা পরিবর্তন করা হয় না।")
        }
    }

    private fun showPermissionsPage() {
        showPage("অনুমতি ও সক্ষমতা") {
            addHeading(this, "Android সক্ষমতা")
            addButton("Android সক্ষমতা ও অনুমতি পরীক্ষা") { showCapabilities() }
            addButton("অনুমতি কেন্দ্র খুলুন") { PermissionCenter.show(this@MainActivity) }
            addButton("Android API তালিকা") { report.text = AndroidApiRegistry.report() }
            addButton("Module Manager") { report.text = ModuleRegistry.report(this@MainActivity) }
            addButton("Engine Self-Test চালান") { runEngineSelfTest() }
            addTextNote(this, "কোনো অনুমতি গোপনে চালু করা হয় না। Android-এর নিজস্ব সেটিংস ও সম্মতি ব্যবহার করা হয়।")
        }
    }

    private fun showAiRelayPage() {
        showPage("AI ও Relay") {
            addHeading(this, "AI সংযোগ")
            aiModel = EditText(this@MainActivity).apply {
                hint = "AI মডেলের নাম (যেমন: gemini-2.5-flash)"
                setText(aiModelDraft)
                setSingleLine(true)
            }
            aiEndpoint = EditText(this@MainActivity).apply {
                hint = "OpenAI-compatible HTTPS endpoint"
                setText(aiEndpointDraft)
                setSingleLine(true)
            }
            aiKey = EditText(this@MainActivity).apply {
                hint = "AI API Key (গোপন)"
                setSingleLine(true)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            aiPrompt = EditText(this@MainActivity).apply {
                hint = "AI-কে প্রশ্ন লিখুন"
                setText(aiPromptDraft)
                minLines = 2
            }
            addView(aiModel)
            addView(aiEndpoint)
            addView(aiKey)
            addButton("Gemini সেটিং সংরক্ষণ") { saveAiConfig(AiEngine.Provider.GEMINI) }
            addButton("OpenAI-compatible সেটিং সংরক্ষণ") {
                saveAiConfig(AiEngine.Provider.OPENAI_COMPATIBLE)
            }
            addView(aiPrompt)
            addButton("AI-কে প্রশ্ন পাঠান") { runAi() }
            addButton("AI সেটিংসের অবস্থা") {
                report.text = AiEngine.configurationSummary(this@MainActivity)
            }

            addHeading(this, "Relay সংযোগ")
            relayUrl = EditText(this@MainActivity).apply {
                hint = "Cloudflare Worker URL (https://…workers.dev)"
                setText(relayUrlDraft)
                setSingleLine(true)
            }
            setupToken = EditText(this@MainActivity).apply {
                hint = "Device setup token (গোপন)"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
            }
            addView(relayUrl)
            addView(setupToken)
            addButton("নিরাপদ Relay Pairing শুরু করুন") { beginRelayPairing() }
            addButton("Relay polling চালু করুন") { startRelayPolling() }
            addButton("Relay সংযোগ বন্ধ / বিচ্ছিন্ন করুন") {
                relayPolling = false
                relayApprovalPending = false
                lastRelayCommand = ""
                relayHandler.removeCallbacks(relayTick)
                RelayClient.clear(this@MainActivity)
                status.text = "Relay token মুছে ফেলা হয়েছে; সংযোগ বিচ্ছিন্ন।"
            }
        }
    }

    private fun showTermuxPage() {
        showPage("Termux ও কমান্ড") {
            addHeading(this, "অনুমোদিত read-only কমান্ড")
            addButton("Termux: Python সংস্করণ") { runTermux("termux.python_version") }
            addButton("Termux: ভিডিও প্রজেক্ট Git অবস্থা") { runTermux("termux.git_video_status") }
            addButton("Termux: স্টোরেজ রিপোর্ট") { runTermux("termux.storage_report") }
            addButton("Termux: সিস্টেম তথ্য") { runTermux("termux.system_info") }
            addButton("Termux ফলাফল দেখুন") {
                report.text = TermuxBridge.lastResult(this@MainActivity)
            }
            addTextNote(this, "নিরাপত্তা: arbitrary shell, ফাইল মোছা বা পরিবর্তনের কমান্ড এখানে চালানো হয় না।")
        }
    }

    private fun showToolsPage() {
        showPage("ডিভাইস পর্যবেক্ষণ") {
            addHeading(this, "ডিভাইস ডায়াগনস্টিক")
            addButton("স্টোরেজ ও RAM পরীক্ষা") { refreshDiagnostics() }
            addButton("Android সক্ষমতা ও অনুমতি পরীক্ষা") { showCapabilities() }
            addButton("Engine Self-Test চালান") { runEngineSelfTest() }
        }
    }

    private fun showMcpPage() {
        showPage("MCP Plugin Connector") {
            addTextNote(this, "শুধু HTTPS MCP endpoint ব্যবহার করুন। Tool চালানোর আগে ঝুঁকি ও input দেখে স্পষ্ট অনুমতি দিতে হবে।")
            PluginDashboardPage(
                activity = this@MainActivity,
                worker = worker,
                onStatus = { message -> status.text = message }
            ).render(this)
        }
    }

    private fun addTextNote(parent: LinearLayout, message: String) {
        parent.addView(TextView(this).apply {
            text = message
            textSize = 12f
            setTextColor(mutedColor)
            setPadding(12, 12, 12, 12)
            background = roundedBackground(Color.rgb(232, 243, 238), 14)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 8; bottomMargin = 10 })
    }

    private fun LinearLayout.addButton(label: String, action: () -> Unit) {
        val button = Button(this@MainActivity).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = roundedBackground(teal, 14)
            setPadding(12, 8, 12, 8)
            setOnClickListener { action() }
        }
        addView(button, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 8; topMargin = 3 })
    }

    private fun showAccessibilitySnapshot() {
        report.text = ScreenReaderService.refreshNow()
        status.text = if (ScreenReaderService.isConnected) {
            "স্ক্রিন শনাক্তকরণ সম্পন্ন — শুধু পড়া হয়েছে; কোনো টাচ বা ক্লিক করা হয়নি।"
        } else {
            "Accessibility অনুমতি চালু করুন, তারপর আবার চেষ্টা করুন।"
        }
    }

    private fun saveAiConfig(provider: AiEngine.Provider) {
        val model = aiModel.text.toString().trim()
        val endpoint = aiEndpoint.text.toString().trim()
        val key = aiKey.text.toString()

        runCatching {
            AiEngine.saveConfig(this, provider, model, endpoint, key)
        }.onSuccess {
            aiKey.text.clear()
            status.text = "AI সেটিং সংরক্ষিত। API Key এনক্রিপ্ট করে রাখা হয়েছে।"
            report.text = AiEngine.configurationSummary(this)
        }.onFailure {
            status.text = "AI সেটিং সংরক্ষণ হয়নি: ${it.message}"
        }
    }

    private fun runAi() {
        val prompt = aiPrompt.text.toString().trim()
        if (prompt.isBlank()) {
            status.text = "আগে AI-কে করার প্রশ্ন লিখুন।"
            return
        }
        if (!AiEngine.isConfigured(this)) {
            status.text = "আগে AI Provider, মডেল ও API Key সংরক্ষণ করুন।"
            return
        }

        status.text = "AI উত্তর তৈরি করছে…"
        worker.execute {
            val result = runCatching { AiEngine.ask(this@MainActivity, prompt) }
            runOnUiThread {
                result.onSuccess {
                    report.text = "AI উত্তর\n\n$it"
                    status.text = "AI উত্তর পাওয়া গেছে।"
                }.onFailure {
                    status.text = "AI অনুরোধ ব্যর্থ: ${it.message}"
                }
            }
        }
    }

    private fun runEngineSelfTest() {
        status.text = "Engine পরীক্ষা চলছে…"
        worker.execute {
            val result = EngineSelfTest.run(this@MainActivity)
            runOnUiThread {
                report.text = result.joinToString("\n")
                val failures = result.count { it.startsWith("FAIL:") }
                status.text = if (failures == 0) {
                    "Self-Test শেষ: সব প্রাথমিক পরীক্ষা সফল।"
                } else {
                    "Self-Test-এ ${failures}টি সমস্যা পাওয়া গেছে।"
                }
            }
        }
    }

    private fun beginRelayPairing() {
        val url = relayUrl.text.toString()
        val secret = setupToken.text.toString()
        status.text = "Cloudflare Relay pairing শুরু হচ্ছে…"
        worker.execute {
            val result = runCatching { RelayClient.pair(this, url, secret) }
            runOnUiThread {
                result.onSuccess { code ->
                    setupToken.text.clear()
                    status.text = "Pairing code: $code (৫ মিনিটের মধ্যে অনুমোদিত ChatGPT connector-এ claim করতে হবে)।"
                }.onFailure { error ->
                    status.text = "Pairing ব্যর্থ: ${error.message}"
                }
            }
        }
    }

    private fun requestRelayJobApproval(job: RelayClient.Job) {
        if (isFinishing || !activityVisible) {
            relayApprovalPending = false
            lastRelayCommand = ""
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Relay কাজের অনুমতি")
            .setMessage(
                "অপারেশন: ${job.operationId}\nকমান্ড ID: ${job.commandId.take(8)}…\n\n" +
                    "এটি Termux-এ একটি পূর্বনির্ধারিত read-only কমান্ড চালাবে। " +
                    "আপনি অনুমোদন না দিলে কোনো কমান্ড চলবে না।"
            )
            .setCancelable(false)
            .setNegativeButton("প্রত্যাখ্যান") { _, _ ->
                worker.execute {
                    val result = runCatching {
                        RelayClient.savePendingResult(
                            this@MainActivity,
                            job.commandId,
                            "Rejected by user in Android app",
                            126
                        )
                        RelayClient.retryPendingResult(this@MainActivity)
                    }
                    runOnUiThread {
                        if (result.isFailure) lastRelayCommand = ""
                        status.text = if (result.isSuccess) {
                            "Relay কাজ প্রত্যাখ্যান করা হয়েছে; Termux-এ কিছু চালানো হয়নি।"
                        } else {
                            "Relay প্রত্যাখ্যানের ফল পাঠানো যায়নি: ${result.exceptionOrNull()?.message}"
                        }
                        finishRelayApproval()
                    }
                }
            }
            .setPositiveButton("অনুমোদন করে চালান") { _, _ ->
                worker.execute {
                    // Do not mark a job handled merely because Termux accepted the request.
                    // TermuxResultReceiver marks it only after the execution result is
                    // successfully acknowledged by the Relay server.
                    val execution = TermuxBridge.run(this@MainActivity, job.operationId, job.commandId)
                    runOnUiThread {
                        status.text = execution.fold(
                            { it },
                            { "Termux অনুরোধ ব্যর্থ: ${it.message}" }
                        )
                        finishRelayApproval()
                    }
                    if (execution.isFailure) {
                        runCatching {
                            RelayClient.savePendingResult(
                                this@MainActivity,
                                job.commandId,
                                execution.exceptionOrNull()?.message ?: "Termux request failed",
                                -1
                            )
                            RelayClient.retryPendingResult(this@MainActivity)
                        }
                    }
                }
            }
            .show()
    }

    private fun finishRelayApproval() {
        relayApprovalPending = false
        if (relayPolling && activityVisible) {
            relayHandler.removeCallbacks(relayTick)
            relayHandler.postDelayed(relayTick, 3000)
        }
    }

    private fun startRelayPolling() {
        if (!RelayClient.configured(this)) {
            status.text = "আগে Relay URL ও setup token দিয়ে pairing করুন।"
            return
        }
        relayPolling = true
        relayApprovalPending = false
        lastRelayCommand = ""
        relayHandler.removeCallbacks(relayTick)
        status.text = "Relay polling চালু — অ্যাপ সামনে খোলা রাখুন।"
        relayHandler.post(relayTick)
    }

    override fun onResume() {
        super.onResume()
        activityVisible = true
        val pendingCommand = RelayClient.pendingCommandId(this)
        if (pendingCommand != null) {
            status.text = "আগের Relay কাজের ফলাফল/timeout recovery অপেক্ষমাণ: ${pendingCommand.take(8)}…"
        }
        if (RelayClient.configured(this) && relayPolling) relayHandler.post(relayTick)
    }

    override fun onPause() {
        activityVisible = false
        relayHandler.removeCallbacks(relayTick)
        super.onPause()
    }

    private fun runTermux(operationId: String) {
        status.text = "Termux অনুমতি ও সার্ভিস যাচাই করা হচ্ছে…"
        val result = TermuxBridge.run(this, operationId)
        result.onSuccess { status.text = it }
            .onFailure { error ->
                status.text = "Termux চালানো যায়নি: ${error.message ?: "অনুমতি বা সেটআপ পরীক্ষা করুন"}"
            }
    }

    private fun refreshDiagnostics() {
        val fs = StatFs(Environment.getDataDirectory().path)
        val total = fs.totalBytes
        val free = fs.availableBytes
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        report.text = buildString {
            append("ডিভাইস ডায়াগনস্টিক\n")
            append("ডেটা পার্টিশন মোট: ${formatBytes(total)}\n")
            append("ব্যবহৃত: ${formatBytes((total - free).coerceAtLeast(0))}\n")
            append("ফাঁকা: ${formatBytes(free)}\n\n")
            append("RAM মোট: ${formatBytes(memory.totalMem)}\n")
            append("RAM বর্তমানে উপলব্ধ: ${formatBytes(memory.availMem)}\n")
            append("Android low-memory: ${if (memory.lowMemory) "হ্যাঁ" else "না"}\n")
            append("\nএটি ডেটা পার্টিশনের হিসাব; সব SD/USB স্টোরেজের মোট নয়।")
        }
    }

    private fun showCapabilities() {
        val capabilities = CapabilityRegistry.snapshot(
            this,
            selectedTree != null
        )

        report.text = buildString {
            append("Android সক্ষমতা ও অনুমতির অবস্থা\n\n")
            capabilities.forEach { capability ->
                append("${if (capability.available) "✓" else "○"} ${capability.name}\n")
                append("অবস্থা: ${capability.status}\n")
                append("প্রয়োজন: ${capability.requirement}\n\n")
            }
        }

        status.text = "সক্ষমতা পরীক্ষা সম্পন্ন — কোনো অনুমতি পরিবর্তন করা হয়নি।"
    }

    private fun openFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, folderRequestCode)
    }

    @Deprecated("Platform document picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != folderRequestCode || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
            selectedTree = DocumentFile.fromTreeUri(this, uri)
            status.text = "নির্বাচিত ফোল্ডার: ${selectedTree?.name ?: "নাম অজানা"}"
            report.text = "ফোল্ডার অনুমতি সংরক্ষিত হয়েছে। এখন ফাইল তালিকা বা ডুপ্লিকেট স্ক্যান চালান।"
        } catch (error: SecurityException) {
            status.text = "ফোল্ডার অনুমতি সংরক্ষণ করা যায়নি: ${error.message}"
        }
    }

    private fun scanSelectedFolder(findDuplicates: Boolean) {
        val root = selectedTree
        if (root == null) {
            Toast.makeText(this, "আগে একটি ফোল্ডার নির্বাচন করুন", Toast.LENGTH_SHORT).show()
            return
        }
        status.text = if (findDuplicates) "ডুপ্লিকেট স্ক্যান চলছে…" else "ফাইল তালিকা তৈরি হচ্ছে…"
        worker.execute {
            val files = mutableListOf<DocumentFile>()
            var truncated = false
            fun walk(dir: DocumentFile, depth: Int) {
                if (depth > maxDepth || files.size >= maxFiles) { truncated = true; return }
                val children = runCatching { dir.listFiles() }.getOrDefault(emptyArray())
                for (child in children) {
                    if (files.size >= maxFiles) { truncated = true; break }
                    if (child.isDirectory) walk(child, depth + 1) else if (child.isFile) files.add(child)
                }
            }
            walk(root, 0)
            val output = StringBuilder()
            output.append("মোট ফাইল: ${files.size}${if (truncated) " (সীমার কারণে আংশিক)" else ""}\n")
            output.append("মোট আকার: ${formatBytes(files.sumOf { it.length().coerceAtLeast(0) })}\n\n")
            if (findDuplicates) {
                val groups = files.filter { it.length() > 0L }
                    .groupBy { "${it.length()}:${it.name?.substringAfterLast('.', "")?.lowercase()}" }
                    .values.filter { it.size > 1 }
                var duplicateGroups = 0
                var checked = 0
                for (group in groups) {
                    val hashes = group.mapNotNull { file ->
                        if (checked >= 1200) return@mapNotNull null
                        checked++
                        hashFile(file)
                    }.groupBy { it.second }
                    for ((_, same) in hashes) if (same.size > 1) {
                        duplicateGroups++
                        output.append("সম্ভাব্য ডুপ্লিকেট গ্রুপ $duplicateGroups:\n")
                        same.forEach { (file, _) -> output.append(" • ${file.name} — ${formatBytes(file.length())}\n") }
                    }
                }
                if (duplicateGroups == 0) output.append("একই কনটেন্টের ডুপ্লিকেট পাওয়া যায়নি (স্ক্যান সীমা প্রযোজ্য)।")
                output.append("\n\nকোনো ফাইল মোছা বা পরিবর্তন করা হয়নি।")
            } else {
                files.take(250).forEach { output.append("• ${it.name ?: "(নামহীন)"} — ${formatBytes(it.length())}\n") }
                if (files.size > 250) output.append("\nআরও ${files.size - 250}টি ফাইল তালিকায় দেখানো হয়নি।")
            }
            runOnUiThread {
                status.text = "স্ক্যান সম্পন্ন"
                report.text = output.toString()
            }
        }
    }

    private fun hashFile(file: DocumentFile): Pair<DocumentFile, String>? {
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            contentResolver.openInputStream(file.uri)?.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            } ?: return null
            file to digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    private fun scanAudioMetadata() {
        val root = selectedTree
        if (root == null) {
            Toast.makeText(this, "আগে অডিও থাকা ফোল্ডার নির্বাচন করুন", Toast.LENGTH_SHORT).show()
            return
        }
        status.text = "অডিও তথ্য পড়া হচ্ছে…"
        worker.execute {
            val audio = mutableListOf<DocumentFile>()
            fun walk(dir: DocumentFile, depth: Int) {
                if (depth > maxDepth || audio.size >= maxFiles) return
                for (file in runCatching { dir.listFiles() }.getOrDefault(emptyArray())) {
                    if (file.isDirectory) walk(file, depth + 1)
                    else if (file.type?.startsWith("audio/") == true || file.name?.substringAfterLast('.', "")?.lowercase() in setOf("mp3","m4a","aac","wav","ogg","flac")) audio.add(file)
                }
            }
            walk(root, 0)
            val output = StringBuilder("অডিও ফাইল: ${audio.size}\n\n")
            audio.take(100).forEach { file ->
                val retriever = MediaMetadataRetriever()
                val details = runCatching {
                    retriever.setDataSource(this, file.uri)
                    val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "অজানা শিল্পী"
                    val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "অজানা অ্যালবাম"
                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    "${file.name}\nশিল্পী: $artist | অ্যালবাম: $album | সময়: ${duration?.let { "${it / 60000}:${(it / 1000) % 60}" } ?: "অজানা"}"
                }.getOrElse { "${file.name}\nমেটাডেটা পড়া যায়নি" }
                runCatching { retriever.release() }
                output.append(details).append("\n\n")
            }
            if (audio.size > 100) output.append("প্রথম ১০০টি দেখানো হয়েছে।")
            runOnUiThread { status.text = "অডিও স্ক্যান সম্পন্ন"; report.text = output.toString() }
        }
    }

    private fun formatBytes(value: Long): String {
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var amount = value.toDouble()
        var index = -1
        do { amount /= 1024.0; index++ } while (amount >= 1024 && index < units.lastIndex)
        return String.format("%.2f %s", amount, units[index])
    }

    override fun onDestroy() {
        relayPolling = false
        relayHandler.removeCallbacks(relayTick)
        worker.shutdownNow()
        super.onDestroy()
    }
}
