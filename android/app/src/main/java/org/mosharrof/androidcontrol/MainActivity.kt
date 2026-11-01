package org.mosharrof.androidcontrol

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var report: TextView
    private val folderRequestCode = 4102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        report = TextView(this).apply {
            textSize = 16f
            setPadding(24, 24, 24, 16)
        }
        val refresh = Button(this).apply {
            text = "স্টোরেজ ও RAM আবার দেখুন"
            setOnClickListener { refreshDiagnostics() }
        }
        val chooseFolder = Button(this).apply {
            text = "নিজে একটি ফোল্ডার নির্বাচন করুন"
            setOnClickListener { openFolderPicker() }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            addView(report)
            addView(refresh)
            addView(chooseFolder)
            addView(TextView(this@MainActivity).apply {
                text = "\nনিরাপত্তা: এই প্রাথমিক সংস্করণ কোনো ফাইল মুছে না, গোপনে স্ক্রিন রেকর্ড করে না এবং দূর থেকে কমান্ড গ্রহণ করে না।"
                textSize = 14f
                setPadding(24, 16, 24, 24)
            })
        }
        setContentView(ScrollView(this).apply { addView(content) })
        refreshDiagnostics()
    }

    private fun refreshDiagnostics() {
        val storage = StatFs(Environment.getDataDirectory().path)
        val total = storage.totalBytes
        val available = storage.availableBytes
        val used = (total - available).coerceAtLeast(0)
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memory)

        report.text = buildString {
            append("ডিভাইস ডায়াগনস্টিক\n\n")
            append("অভ্যন্তরীণ ডেটা স্টোরেজ\n")
            append("মোট: ${formatBytes(total)}\n")
            append("ব্যবহৃত: ${formatBytes(used)}\n")
            append("ফাঁকা: ${formatBytes(available)}\n\n")
            append("RAM (Android সিস্টেম রিপোর্ট)\n")
            append("মোট: ${formatBytes(memory.totalMem)}\n")
            append("ফাঁকা: ${formatBytes(memory.availMem)}\n")
            append("কম মেমরি অবস্থা: ${if (memory.lowMemory) "হ্যাঁ" else "না"}\n\n")
            append("নোট: Android অন্য অ্যাপের RAM জোর করে খালি করতে দেয় না।")
        }
    }

    private fun openFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, folderRequestCode)
    }

    @Deprecated("Uses the platform document picker result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == folderRequestCode && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val flags = data.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            contentResolver.takePersistableUriPermission(uri, flags)
            report.append("\n\nআপনার নির্বাচিত ফোল্ডার:\n$uri\nএখনো কোনো ফাইল পরিবর্তন করা হয়নি।")
            Toast.makeText(this, "ফোল্ডার নির্বাচন হয়েছে", Toast.LENGTH_LONG).show()
        }
    }

    private fun formatBytes(value: Long): String {
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var amount = value.toDouble()
        var index = -1
        do {
            amount /= 1024.0
            index++
        } while (amount >= 1024 && index < units.lastIndex)
        return String.format("%.2f %s", amount, units[index])
    }
}
