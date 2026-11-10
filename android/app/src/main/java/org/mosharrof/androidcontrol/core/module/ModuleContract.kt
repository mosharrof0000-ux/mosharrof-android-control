package org.mosharrof.androidcontrol.core.module

import android.content.Context

data class ModuleDescriptor(
    val id: String,
    val name: String,
    val version: String,
    val dependencies: Set<String> = emptySet()
)

interface ControlModule {
    val descriptor: ModuleDescriptor

    fun initialize(context: Context)

    fun onStart(context: Context)

    fun onStop(context: Context)

    fun onDestroy(context: Context)
}

enum class ModuleStatus {
    REGISTERED,
    INITIALIZED,
    RUNNING,
    STOPPED,
    DISABLED,
    FAILED
}
