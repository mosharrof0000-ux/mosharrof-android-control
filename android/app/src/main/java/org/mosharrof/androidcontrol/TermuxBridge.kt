package org.mosharrof.androidcontrol

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle

/** Narrow Termux integration: fixed executable paths and fixed argument vectors only. */
object TermuxBridge {
    private const val TERMUX_PACKAGE = "com.termux"
    private const val TERMUX_SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION_RUN = "com.termux.RUN_COMMAND"
    private const val HOME = "/data/data/com.termux/files/home"
    private const val PREFIX = "/data/data/com.termux/files/usr/bin"
    private const val PREFS = "termux_bridge"
    private const val LAST_RESULT = "last_result"

    data class Operation(val id: String, val label: String, val path: String, val args: Array<String>, val workDir: String = HOME)

    // Read-only diagnostics and a fixed Git status query; no shell or arbitrary command input.
    private val operations = mapOf(
        "termux.python_version" to Operation("termux.python_version", "Python version", "$PREFIX/python", arrayOf("--version")),
        "termux.git_video_status" to Operation(
            "termux.git_video_status", "Video project Git status", "$PREFIX/git",
            arrayOf("-C", "$HOME/mosharrof-video-processing", "status", "--short", "--branch")
        ),
        "termux.storage_report" to Operation("termux.storage_report", "Termux home storage", "$PREFIX/df", arrayOf("-h", HOME)),
        "termux.system_info" to Operation("termux.system_info", "Termux system information", "$PREFIX/uname", arrayOf("-a"))
    )

    fun run(context: Context, operationId: String): Result<String> = runCatching {
        val operation = operations[operationId] ?: error("এই Termux অপারেশন অনুমোদিত নয়")
        val intent = Intent(ACTION_RUN).apply {
            setClassName(TERMUX_PACKAGE, TERMUX_SERVICE)
            putExtra("com.termux.RUN_COMMAND_PATH", operation.path)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", operation.args)
            putExtra("com.termux.RUN_COMMAND_WORKDIR", operation.workDir)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_LABEL", operation.label)
            putExtra("com.termux.RUN_COMMAND_DESCRIPTION", "Read-only, predefined diagnostic operation")
        }
        val requestCode = (System.currentTimeMillis() and 0x7fffffff).toInt()
        val callback = Intent(context, TermuxResultReceiver::class.java).putExtra("request_id", requestCode)
        val flags = PendingIntent.FLAG_ONE_SHOT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val pending = PendingIntent.getBroadcast(context, requestCode, callback, flags)
        intent.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pending)
        context.startService(intent)
        "Termux-এ '${operation.label}' কাজ পাঠানো হয়েছে। ফলাফল দেখতে ‘Termux ফলাফল’ চাপুন।"
    }

    fun lastResult(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(LAST_RESULT, "এখনো কোনো Termux ফলাফল আসেনি।") ?: "ফলাফল নেই"

    internal fun saveResult(context: Context, result: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(LAST_RESULT, result.take(12000)).apply()
    }
}

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val bundle: Bundle? = intent.getBundleExtra("com.termux.app.EXTRA_PLUGIN_RESULT_BUNDLE")
            ?: intent.getBundleExtra("com.termux.EXTRA_PLUGIN_RESULT_BUNDLE")
        val result = if (bundle == null) {
            "Termux থেকে ফলাফলের Bundle পাওয়া যায়নি।"
        } else {
            val stdout = bundle.getString("com.termux.app.EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT")
                ?: bundle.getString("stdout").orEmpty()
            val stderr = bundle.getString("com.termux.app.EXTRA_PLUGIN_RESULT_BUNDLE_STDERR")
                ?: bundle.getString("stderr").orEmpty()
            val exitCode = bundle.getInt("com.termux.app.EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE", -999)
            buildString {
                append("Exit code: $exitCode\n")
                if (stdout.isNotBlank()) append("\nআউটপুট:\n").append(stdout)
                if (stderr.isNotBlank()) append("\nত্রুটি:\n").append(stderr)
                if (stdout.isBlank() && stderr.isBlank()) append("কোনো আউটপুট পাওয়া যায়নি।")
            }
        }
        TermuxBridge.saveResult(context, result)
    }
}
