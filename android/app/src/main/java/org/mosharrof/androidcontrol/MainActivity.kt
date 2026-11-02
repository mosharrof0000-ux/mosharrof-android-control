package org.mosharrof.androidcontrol

import android.app.Activity
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
    private var selectedTree: DocumentFile? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val folderRequestCode = 4102
    private val maxFiles = 5000
    private val maxDepth = 12
    private lateinit var relayUrl: EditText
    private lateinit var setupToken: EditText
    private val relayHandler = Handler(Looper.getMainLooper())
    @Volatile private var relayPolling = false
    private var lastRelayCommand = ""
    private val relayTick = object : Runnable {
        override fun run() {
            if (!relayPolling || isFinishing) return
            worker.execute {
                runCatching { RelayClient.poll(this@MainActivity) }.onSuccess { job ->
                    if (job != null && job.commandId != lastRelayCommand) {
                        lastRelayCommand = job.commandId
                        runOnUiThread { status.text = "Relay থেকে অনুমোদিত কাজ এসেছে: ${job.operationId}" }
                        TermuxBridge.run(this@MainActivity, job.operationId, job.commandId)
                    }
                }.onFailure { error ->
                    runOnUiThread { status.text = "Relay polling সমস্যা: ${error.message}" }
                }
                if (relayPolling) relayHandler.postDelayed(this@MainActivity.relayTick, 3000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        report = TextView(this).apply { textSize = 15f; setPadding(20, 12, 20, 12) }
        status = TextView(this).apply { textSize = 14f; setPadding(20, 8, 20, 8) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 24)
            addView(TextView(this@MainActivity).apply {
                text = "Android Companion"; textSize = 22f
            })
            addView(status)
            relayUrl = EditText(this@MainActivity).apply {
                hint = "Cloudflare Worker URL (https://…workers.dev)"
                singleLine = true
            }
            setupToken = EditText(this@MainActivity).apply {
                hint = "Device setup token (secret)"
                singleLine = true
            }
            addView(relayUrl)
            addView(setupToken)
            addButton("নিরাপদ Relay Pairing শুরু করুন") { beginRelayPairing() }
            addButton("Relay polling চালু করুন") { startRelayPolling() }
            addButton("Relay সংযোগ বন্ধ / বিচ্ছিন্ন করুন") {
                relayPolling = false
                relayHandler.removeCallbacks(relayTick)
                RelayClient.clear(this@MainActivity)
                status.text = "Relay token মুছে ফেলা হয়েছে; সংযোগ বিচ্ছিন্ন।"
            }
            addButton("স্টোরেজ ও RAM পরীক্ষা") { refreshDiagnostics() }
            addButton("ফোল্ডার নির্বাচন / পরিবর্তন") { openFolderPicker() }
            addButton("নির্বাচিত ফোল্ডারের ফাইল তালিকা") { scanSelectedFolder(false) }
            addButton("ডুপ্লিকেট ফাইল শনাক্ত") { scanSelectedFolder(true) }
            addButton("অডিও ফাইলের তথ্য দেখুন") { scanAudioMetadata() }
            addButton("Termux: Python সংস্করণ") { runTermux("termux.python_version") }
            addButton("Termux: ভিডিও প্রজেক্ট Git অবস্থা") { runTermux("termux.git_video_status") }
            addButton("Termux: স্টোরেজ রিপোর্ট") { runTermux("termux.storage_report") }
            addButton("Termux: সিস্টেম তথ্য") { runTermux("termux.system_info") }
            addButton("Termux ফলাফল দেখুন") {
                report.text = TermuxBridge.lastResult(this@MainActivity)
            }
            addView(report, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(TextView(this@MainActivity).apply {
                text = "\nনিরাপত্তা: Termux-এ কেবল নির্দিষ্ট read-only কমান্ড চলে। কোনো arbitrary shell, ফাইল মোছা বা পরিবর্তন নেই।"
                textSize = 13f; setPadding(12, 20, 12, 12)
            })
        }
        setContentView(ScrollView(this).apply { addView(content) })
        refreshDiagnostics()
    }

    private fun LinearLayout.addButton(label: String, action: () -> Unit) {
        addView(Button(this@MainActivity).apply { text = label; setOnClickListener { action() } })
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

    private fun startRelayPolling() {
        if (!RelayClient.configured(this)) {
            status.text = "আগে Relay URL ও setup token দিয়ে pairing করুন।"
            return
        }
        relayPolling = true
        relayHandler.removeCallbacks(relayTick)
        status.text = "Relay polling চালু — অ্যাপ সামনে খোলা রাখুন।"
        relayHandler.post(relayTick)
    }

    override fun onResume() {
        super.onResume()
        if (RelayClient.configured(this) && relayPolling) relayHandler.post(relayTick)
    }

    override fun onPause() {
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
