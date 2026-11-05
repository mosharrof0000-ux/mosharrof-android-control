package org.mosharrof.androidcontrol

import android.content.Context
import android.os.Build

object EngineSelfTest {
    fun run(context: Context): List<String> {
        val results = mutableListOf<String>()

        runCatching {
            val modules = ModuleRegistry.snapshot(context)
            check(modules.map { it.id }.distinct().size == modules.size) {
                "Module ID duplicate"
            }
            results.add("PASS: Module Registry — ${modules.size}টি মডিউল, ID অনন্য")
        }.onFailure {
            results.add("FAIL: Module Registry — ${it.message}")
        }

        runCatching {
            val apis = AndroidApiRegistry.entries()
            check(apis.isNotEmpty()) { "API catalog empty" }
            check(apis.map { it.id }.distinct().size == apis.size) {
                "Android API ID duplicate"
            }
            check(apis.all { it.introducedApi > 0 }) { "Invalid API level" }
            results.add("PASS: Android API Registry — ${apis.size}টি API entry")
        }.onFailure {
            results.add("FAIL: Android API Registry — ${it.message}")
        }

        runCatching {
            check(Build.VERSION.SDK_INT >= 26) { "Device below app minimum SDK" }
            results.add("PASS: Android SDK — API ${Build.VERSION.SDK_INT}")
        }.onFailure {
            results.add("FAIL: Android SDK — ${it.message}")
        }

        runCatching {
            check(AiEngine.cryptoSelfTest()) { "Keystore encryption round-trip failed" }
            results.add("PASS: AI Key Vault — Android Keystore AES-GCM encryption")
        }.onFailure {
            results.add("FAIL: AI Key Vault — ${it.message}")
        }

        results.add(
            if (AiEngine.isConfigured(context)) "INFO: AI configuration সংরক্ষিত"
            else "INFO: AI configuration বাকি; API key যোগ করার পর নেটওয়ার্ক পরীক্ষা হবে"
        )
        return results
    }
}
