package org.mosharrof.androidcontrol

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Read-only registry of Android capabilities.
 * Does not request or change any permission.
 */
object CapabilityRegistry {

    data class Capability(
        val id: String,
        val name: String,
        val available: Boolean,
        val status: String,
        val requirement: String
    )

    fun snapshot(
        context: Context,
        selectedFolderGranted: Boolean = false
    ): List<Capability> {

        val pm = context.packageManager

        fun feature(
            id: String,
            name: String,
            feature: String
        ) = Capability(
            id,
            name,
            pm.hasSystemFeature(feature),
            if (pm.hasSystemFeature(feature)) "ডিভাইসে আছে" else "ডিভাইসে নেই",
            "Android hardware feature"
        )

        fun permission(
            id: String,
            name: String,
            permission: String,
            requirement: String
        ): Capability {
            val declared = context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                ?.contains(permission) == true

            val granted = declared &&
                context.checkSelfPermission(permission) ==
                PackageManager.PERMISSION_GRANTED

            return Capability(
                id,
                name,
                granted,
                when {
                    granted -> "অনুমতি দেওয়া আছে"
                    declared -> "অনুমতি প্রয়োজন"
                    else -> "Manifest-এ অনুমতি যোগ করা হয়নি"
                },
                requirement
            )
        }

        val usageGranted = runCatching {
            val appOps = context.getSystemService(AppOpsManager::class.java)
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            ) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)

        val overlayGranted = Settings.canDrawOverlays(context)

        val notificationAccess = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            )?.contains(context.packageName) == true
        }.getOrDefault(false)

        val dndGranted = runCatching {
            context.getSystemService(NotificationManager::class.java)
                .isNotificationPolicyAccessGranted
        }.getOrDefault(false)

        val termuxGranted =
            context.checkSelfPermission("com.termux.permission.RUN_COMMAND") ==
            PackageManager.PERMISSION_GRANTED

        return listOf(
            Capability(
                "android.version",
                "Android সংস্করণ",
                true,
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                "তথ্য শনাক্তকরণ"
            ),

            Capability(
                "termux.run_command",
                "Termux কমান্ড সংযোগ",
                termuxGranted,
                if (termuxGranted) "অনুমতি আছে" else "অনুমতি প্রয়োজন",
                "Termux এবং RUN_COMMAND অনুমতি"
            ),

            Capability(
                "storage.saf",
                "নির্বাচিত ফোল্ডার",
                selectedFolderGranted,
                if (selectedFolderGranted) "ফোল্ডার অনুমতি সংরক্ষিত"
                else "ফোল্ডার নির্বাচন করা হয়নি",
                "ব্যবহারকারীর Folder Picker অনুমতি"
            ),

            feature(
                "hardware.camera",
                "ক্যামেরা",
                PackageManager.FEATURE_CAMERA_ANY
            ),

            feature(
                "hardware.microphone",
                "মাইক্রোফোন",
                PackageManager.FEATURE_MICROPHONE
            ),

            feature(
                "hardware.bluetooth",
                "ব্লুটুথ",
                PackageManager.FEATURE_BLUETOOTH
            ),

            feature(
                "hardware.nfc",
                "NFC",
                PackageManager.FEATURE_NFC
            ),

            feature(
                "hardware.wifi",
                "Wi-Fi",
                PackageManager.FEATURE_WIFI
            ),

            feature(
                "hardware.gps",
                "GPS",
                PackageManager.FEATURE_LOCATION_GPS
            ),

            feature(
                "hardware.usb_host",
                "USB Host",
                PackageManager.FEATURE_USB_HOST
            ),

            feature(
                "hardware.accelerometer",
                "Accelerometer সেন্সর",
                PackageManager.FEATURE_SENSOR_ACCELEROMETER
            ),

            feature(
                "hardware.gyroscope",
                "Gyroscope সেন্সর",
                PackageManager.FEATURE_SENSOR_GYROSCOPE
            ),

            Capability(
                "accessibility",
                "টাচ ও Accessibility নিয়ন্ত্রণ",
                false,
                "এখনো মডিউল তৈরি হয়নি",
                "AccessibilityService ও ব্যবহারকারীর অনুমতি"
            ),

            Capability(
                "screen.capture",
                "লাইভ স্ক্রিন দেখা",
                false,
                "এখনো মডিউল তৈরি হয়নি",
                "MediaProjection ও প্রতিবার সিস্টেম সম্মতি"
            ),

            Capability(
                "overlay",
                "অন্য অ্যাপের ওপর প্রদর্শন",
                overlayGranted,
                if (overlayGranted) "অনুমতি আছে" else "অনুমতি নেই",
                "Overlay বিশেষ অনুমতি"
            ),

            Capability(
                "usage.stats",
                "অ্যাপ ব্যবহারের তথ্য",
                usageGranted,
                if (usageGranted) "অনুমতি আছে" else "অনুমতি নেই",
                "Usage Access বিশেষ অনুমতি"
            ),

            Capability(
                "notification.access",
                "নোটিফিকেশন পড়া",
                notificationAccess,
                if (notificationAccess) "সিস্টেমে সক্রিয়" else "সক্রিয় নয়",
                "Notification Listener মডিউল ও অনুমতি"
            ),

            Capability(
                "notification.dnd",
                "Do Not Disturb নিয়ন্ত্রণ",
                dndGranted,
                if (dndGranted) "অনুমতি আছে" else "অনুমতি নেই",
                "Notification Policy Access"
            ),

            Capability(
                "vpn",
                "VPN সংযোগ",
                false,
                "এখনো মডিউল তৈরি হয়নি",
                "VPNService ও ব্যবহারকারীর সম্মতি"
            ),

            Capability(
                "shizuku",
                "Shizuku সংযোগ",
                false,
                "এখনো সংযোগ যাচাই করা হয়নি",
                "Shizuku ইনস্টল ও ব্যবহারকারীর অনুমোদন"
            )
        )
    }
}
