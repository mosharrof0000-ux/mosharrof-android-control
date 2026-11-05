package org.mosharrof.androidcontrol

import android.content.Context

/**
 * Central inventory of implemented modules and their current readiness.
 * Readiness is not the same as a successful end-to-end test.
 */
object ModuleRegistry {
    data class Module(
        val id: String,
        val name: String,
        val implemented: Boolean,
        val ready: Boolean,
        val detail: String
    )

    fun snapshot(context: Context): List<Module> = listOf(
        Module("core.modules", "কেন্দ্রীয় Module Manager", true, true, "মডিউল তালিকা ও অবস্থা"),
        Module("core.android_api", "Android API Engine", true, true, "API স্তর ও প্রয়োজনীয় সম্মতির তালিকা"),
        Module("core.ai", "AI Core", true, AiEngine.isConfigured(context), AiEngine.configurationSummary(context)),
        Module("core.capabilities", "Capability Registry", true, true, "হার্ডওয়্যার ও অনুমতি শনাক্তকরণ"),
        Module("screen.reader", "Accessibility Screen Reader", true, ScreenReaderService.isConnected, if (ScreenReaderService.isConnected) "Service সংযুক্ত" else "Accessibility Service সংযুক্ত নয়"),
        Module("relay", "Secure Relay", true, RelayClient.configured(context), if (RelayClient.configured(context)) "Pairing সংরক্ষিত" else "Pairing প্রয়োজন"),
        Module("files", "File Manager", true, false, "ফোল্ডার নির্বাচন ও বাস্তব ডিভাইস পরীক্ষা প্রয়োজন"),
        Module("termux", "Termux Bridge", true, false, "ডিভাইসে RUN_COMMAND অনুমতি ও Termux পরীক্ষা প্রয়োজন"),
        Module("automation", "Automation Engine", false, false, "পরবর্তী পর্যায়ে বাস্তবায়ন হবে"),
        Module("screen.capture", "Live Screen Capture", false, false, "MediaProjection বাস্তবায়ন বাকি"),
        Module("plugins", "Plugin System", false, false, "পরবর্তী পর্যায়ে বাস্তবায়ন হবে")
    )

    fun report(context: Context): String = buildString {
        append("CENTRAL MODULE MANAGER\n\n")
        snapshot(context).forEach { module ->
            val state = when {
                !module.implemented -> "এখনো তৈরি হয়নি"
                module.ready -> "প্রস্তুত/সংযুক্তির অবস্থা ভালো"
                else -> "কোড আছে, সেটআপ বা অনুমতি বাকি"
            }
            append("${if (module.implemented) "✓" else "○"} ${module.name}\n")
            append("ID: ${module.id}\n")
            append("অবস্থা: $state\n")
            append("${module.detail}\n\n")
        }
        append("দ্রষ্টব্য: প্রস্তুত দেখানো মানেই বাস্তব ডিভাইসে পূর্ণ পরীক্ষা সফল—এমন নয়।")
    }
}
