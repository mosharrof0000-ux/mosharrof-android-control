package org.mosharrof.androidcontrol

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Read-only screen inspection.
 * Does not click, type, gesture, save, or transmit screen content.
 */
class ScreenReaderService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
        isConnected = true
        captureSnapshot()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) captureSnapshot()
    }

    private fun captureSnapshot() {
        val root = rootInActiveWindow
        if (root == null) {
            latestSnapshot = "বর্তমান স্ক্রিন থেকে তথ্য পড়া যাচ্ছে না।"
            return
        }

        val lines = mutableListOf<String>()

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 20 || lines.size >= 120) return

            try {
                if (!node.isPassword) {
                    val text = node.text?.toString()?.trim().orEmpty()
                    val description =
                        node.contentDescription?.toString()?.trim().orEmpty()

                    val label = when {
                        text.isNotEmpty() -> text
                        description.isNotEmpty() -> description
                        else -> ""
                    }

                    if (label.isNotEmpty()) {
                        val kind = if (node.isClickable) "[বাটন] " else ""
                        lines.add(kind + label.take(180))
                    } else if (node.isClickable) {
                        lines.add("[নামহীন বাটন]")
                    }
                }

                for (i in 0 until node.childCount) {
                    if (lines.size >= 120) break
                    walk(node.getChild(i), depth + 1)
                }
            } catch (_: Exception) {
                // Screen may change while being inspected.
            } finally {
                runCatching { node.recycle() }
            }
        }

        walk(root, 0)

        latestSnapshot = if (lines.isEmpty()) {
            "এই স্ক্রিনে শনাক্ত করার মতো লেখা বা বাটন পাওয়া যায়নি।"
        } else {
            lines.distinct().joinToString("\\n")
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (activeService === this) {
            activeService = null
            isConnected = false
        }
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var activeService: ScreenReaderService? = null

        @Volatile
        var isConnected: Boolean = false
            private set

        @Volatile
        var latestSnapshot: String =
            "এখনো কোনো স্ক্রিনের তথ্য শনাক্ত হয়নি।"

        fun refreshNow(): String {
            val service = activeService
            if (service == null) {
                return "স্ক্রিন পড়ার Accessibility Service চালু বা সংযুক্ত নেই। Permission Center থেকে অনুমতি চালু করুন।"
            }
            service.captureSnapshot()
            return latestSnapshot
        }
    }
}
