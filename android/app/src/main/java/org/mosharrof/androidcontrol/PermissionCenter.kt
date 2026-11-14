package org.mosharrof.androidcontrol

import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.Settings

object PermissionCenter {

    private data class Item(
        val name: String,
        val status: String,
        val detail: String,
        val action: String? = null
    )


    private fun accessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(
            context,
            ScreenReaderService::class.java
        ).flattenToString()

        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()

        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun items(context: Context): List<Item> {
        val overlay = Settings.canDrawOverlays(context)

        val usage = runCatching {
            val ops = context.getSystemService(AppOpsManager::class.java)
            ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            ) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)

        val dnd = runCatching {
            context.getSystemService(NotificationManager::class.java)
                .isNotificationPolicyAccessGranted
        }.getOrDefault(false)

        val termux = context.checkSelfPermission(
            "com.termux.permission.RUN_COMMAND"
        ) == PackageManager.PERMISSION_GRANTED

        return listOf(
            Item(
                "অন্য অ্যাপের ওপর প্রদর্শন (Overlay)",
                if (overlay) "চালু" else "চালু নয়",
                "ভাসমান নিয়ন্ত্রণ দেখানোর বিশেষ অনুমতি।",
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION
            ),
            Item(
                "অ্যাপ ব্যবহারের তথ্য (Usage Access)",
                if (usage) "চালু" else "চালু নয়",
                "অ্যাপ ব্যবহারের পরিসংখ্যান পড়ার বিশেষ অনুমতি।",
                Settings.ACTION_USAGE_ACCESS_SETTINGS
            ),
            Item(
                "Do Not Disturb নিয়ন্ত্রণ",
                if (dnd) "চালু" else "চালু নয়",
                "নীরবতা ও বিরক্তি-নিয়ন্ত্রণ সেটিংস পরিচালনার অনুমতি।",
                Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
            ),
            Item(
                "Termux RUN_COMMAND",
                if (termux) "অনুমতি আছে" else "অনুমতি নেই",
                "Termux অ্যাপ ও তার নিজস্ব অনুমতি-ব্যবস্থা প্রয়োজন।"
            ),
            Item(
                "Accessibility / স্ক্রিন পড়া",
                if (accessibilityEnabled(context)) "চালু" else "চালু নয়",
                "ব্যবহারকারীর অনুমতিতে স্ক্রিনের লেখা ও বাটন শনাক্ত করবে। এই সংস্করণে টাচ বা ক্লিক করবে না।",
                Settings.ACTION_ACCESSIBILITY_SETTINGS
            ),
            Item(
                "Notification Access",
                "মডিউল তৈরি হয়নি",
                "NotificationListenerService এখনো তৈরি ও Manifest-এ ঘোষণা করা হয়নি।"
            ),
            Item(
                "লাইভ স্ক্রিন দেখা",
                "মডিউল তৈরি হয়নি",
                "MediaProjection বাস্তবায়ন ও প্রতিটি সেশনে Android-এর সম্মতি প্রয়োজন।"
            ),
            Item(
                "VPN সংযোগ",
                "মডিউল তৈরি হয়নি",
                "VPNService ও ব্যবহারকারীর অনুমোদন প্রয়োজন।"
            ),
            Item(
                "ফাইল ও ফোল্ডার",
                "Folder Picker ব্যবহার করুন",
                "নির্বাচিত ফোল্ডারের অনুমতি Android-এর Document Picker দিয়ে দেওয়া হয়।"
            )
        )
    }

    fun show(activity: Activity) {
        val entries = items(activity)
        val labels = entries.map {
            "${it.name}\nঅবস্থা: ${it.status}"
        }.toTypedArray()

        AlertDialog.Builder(activity)
            .setTitle("Android Permission Center")
            .setItems(labels) { dialog, index ->
                val item = entries[index]
                val action = item.action

                if (action == null) {
                    AlertDialog.Builder(activity)
                        .setTitle(item.name)
                        .setMessage("${item.status}\n\n${item.detail}")
                        .setPositiveButton("ঠিক আছে", null)
                        .show()
                } else {
                    val intent = Intent(action).apply {
                        if (action == Settings.ACTION_MANAGE_OVERLAY_PERMISSION) {
                            data = Uri.parse("package:${activity.packageName}")
                        }
                    }

                    try {
                        activity.startActivity(intent)
                    } catch (_: Exception) {
                        try {
                            activity.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${activity.packageName}")
                                )
                            )
                        } catch (error: Exception) {
                            AlertDialog.Builder(activity)
                                .setMessage("সেটিংস খোলা যায়নি। ${safeUiErrorMessage(error)}")
                                .setPositiveButton("ঠিক আছে", null)
                                .show()
                        }
                    }
                }
                dialog.dismiss()
            }
            .setPositiveButton("বন্ধ করুন", null)
            .setNeutralButton("অবস্থা পুনরায় দেখুন") { _, _ ->
                show(activity)
            }
            .show()
    }
}
