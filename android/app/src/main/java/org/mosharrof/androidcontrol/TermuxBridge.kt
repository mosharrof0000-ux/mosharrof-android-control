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

    data class Operation(val label: String, val path: String, val args: Array<String>, val workDir: String = HOME)

    // Fixed read-only diagnostics; never accept caller-provided executable paths or arguments.
    private val operations = mapOf(
        "termux.python_version" to Operation("Python version", "$PREFIX/python", arrayOf("--version")),
        "termux.git_video_status" to Operation(
            "Video project Git status", "$PREFIX/git",
            arrayOf("-C", "$HOME/mosharrof-video-processing", "status", "--short", "--branch")
        ),
        "termux.storage_report" to Operation("Termux home storage", "$PREFIX/df", arrayOf("-h", HOME)),
        "termux.system_info" to Operation("Termux system information", "$PREFIX/uname", arrayOf("-a"))
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
            putExtra("com.termux.RUN_COMMAND_DESCRIPTION", "Predefined read-only diagnostic operation")
        }
        val requestCode = (System.currentTimeMillis() and 0x7fffffff).toInt()
        val callback = Intent(context, TermuxResultReceiver::class.java)
            .putExtra("request_id", requestCode)
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
        // TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE is "result";
        // its nested fields are "stdout", "stderr", "exitCode", "err", and "errmsg".
        val bundle: Bundle? = intent.getBundleExtra("result")
        val result = if (bundle == null) {
            "Termux থেকে ফলাফলের Bundle পাওয়া যায়নি (Termux >= 0.109 প্রয়োজন)।"
        } else {
            val stdout = bundle.getString("stdout").orEmpty()
            val stderr = bundle.getString("stderr").orEmpty()
            val exitCode = bundle.getInt("exitCode", -999)
            val termuxError = bundle.getInt("err", -999)
            val errorMessage = bundle.getString("errmsg").orEmpty()
            buildString {
                append("Exit code: ").append(exitCode)
                append("\nTermux internal status: ").append(termuxError)
                if (stdout.isNotBlank()) append("\n\nআউটপুট:\n").append(stdout)
                if (stderr.isNotBlank()) append("\n\nত্রুটি:\n").append(stderr)
                if (errorMessage.isNotBlank()) append("\n\nTermux বার্তা:\n").append(errorMessage)
                if (stdout.isBlank() && stderr.isBlank() && errorMessage.isBlank()) {
                    append("\nকোনো আউটপুট পাওয়া যায়নি।")
                }
            }
        }
        TermuxBridge.saveResult(context, result)
    }
}
