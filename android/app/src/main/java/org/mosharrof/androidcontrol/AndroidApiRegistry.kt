package org.mosharrof.androidcontrol

import android.os.Build

/**
 * Android API catalog. SDK support is reported separately from permission
 * grants and from real-device functional tests.
 */
object AndroidApiRegistry {
    data class ApiEntry(
        val id: String,
        val name: String,
        val introducedApi: Int,
        val permission: String,
        val purpose: String
    )

    private val catalog = listOf(
        ApiEntry("api.accessibility", "Accessibility Service", 1, "ব্যবহারকারীর Accessibility সম্মতি", "স্ক্রিনের লেখা ও UI উপাদান শনাক্ত"),
        ApiEntry("api.notification_listener", "Notification Listener", 18, "Notification Access", "নোটিফিকেশন পড়া"),
        ApiEntry("api.media_projection", "MediaProjection", 21, "প্রতিটি সেশনে সিস্টেম সম্মতি", "স্ক্রিন ক্যাপচার"),
        ApiEntry("api.document_tree", "Storage Access Framework", 21, "ব্যবহারকারীর Folder Picker", "নির্বাচিত ফাইল ও ফোল্ডার"),
        ApiEntry("api.usage_stats", "Usage Stats", 21, "Usage Access বিশেষ অনুমতি", "অ্যাপ ব্যবহারের পরিসংখ্যান"),
        ApiEntry("api.overlay", "Display Over Other Apps", 23, "Overlay বিশেষ অনুমতি", "ভাসমান নিয়ন্ত্রণ"),
        ApiEntry("api.vpn", "VpnService", 14, "VPN সিস্টেম সম্মতি", "VPN সংযোগ"),
        ApiEntry("api.bluetooth_runtime", "Bluetooth Runtime Permissions", 31, "Bluetooth scan/connect অনুমতি", "Bluetooth ডিভাইস ব্যবস্থাপনা"),
        ApiEntry("api.notification_runtime", "Notification Runtime Permission", 33, "POST_NOTIFICATIONS", "অ্যাপের নোটিফিকেশন পাঠানো"),
        ApiEntry("api.photo_picker", "Android Photo Picker", 33, "সিস্টেম Picker নির্বাচন", "ব্যবহারকারীর নির্বাচিত ছবি/ভিডিও")
    )

    fun entries(): List<ApiEntry> = catalog

    fun report(): String = buildString {
        append("ANDROID API ENGINE\n")
        append("ডিভাইস: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        append("অ্যাপের ন্যূনতম SDK: 26 | Compile SDK: 35 | Target SDK: 35\n\n")
        catalog.forEach { api ->
            val supported = Build.VERSION.SDK_INT >= api.introducedApi
            append("${if (supported) "✓" else "○"} ${api.name}\n")
            append("শুরু: API ${api.introducedApi} | ${if (supported) "এই Android-এ API স্তর সমর্থিত" else "এই Android-এ API স্তর অনুপস্থিত"}\n")
            append("অনুমতি/সম্মতি: ${api.permission}\n")
            append("কাজ: ${api.purpose}\n\n")
        }
        append("দ্রষ্টব্য: API স্তর সমর্থিত হওয়া মানেই অনুমতি পাওয়া বা ফিচারটি বাস্তবে পরীক্ষা করা হয়েছে—এমন নয়।")
    }
}
