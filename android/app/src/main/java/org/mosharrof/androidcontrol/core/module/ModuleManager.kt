package org.mosharrof.androidcontrol.core.module

import org.mosharrof.androidcontrol.safeUiErrorMessage

import android.content.Context

class ModuleManager {

    private data class RegisteredModule(val module: ControlModule, val descriptor: ModuleDescriptor)
    private val modules = linkedMapOf<String, RegisteredModule>()
    private val states = linkedMapOf<String, ModuleStatus>()
    private val failures = linkedMapOf<String, String>()

    private fun safeContext(context: Context): Context =
        try {
            context.applicationContext ?: context
        } catch (_: RuntimeException) {
            context
        }


    @Synchronized
    fun register(module: ControlModule): Result<Unit> = runCatching {
        val descriptor = module.descriptor
        require(descriptor.id.isNotBlank()) { "Module ID খালি হতে পারবে না" }
        require(descriptor.name.isNotBlank()) { "Module name খালি হতে পারবে না" }
        require(descriptor.version.isNotBlank()) { "Module version খালি হতে পারবে না" }
        require(descriptor.id !in modules) { "Duplicate module ID: ${descriptor.id}" }
        require(descriptor.id !in descriptor.dependencies) {
            "Module নিজের উপর নির্ভর করতে পারবে না: ${descriptor.id}"
        }
        require(descriptor.dependencies.none { it.isBlank() }) {
            "Dependency ID খালি হতে পারবে না"
        }
        require(descriptor.dependencies.all { it in modules }) {
            "সব dependency আগে register করতে হবে"
        }

        modules[descriptor.id] = RegisteredModule(module, descriptor.copy(dependencies = descriptor.dependencies.toSet()))
        states[descriptor.id] = ModuleStatus.REGISTERED
    }

    @Synchronized
    fun snapshot(): Map<String, ModuleStatus> = states.toMap()

    @Synchronized
    fun failureDetails(): Map<String, String> = failures.toMap()

    private fun activeDependents(id: String): List<String> =
        modules.values
            .filter { candidate ->
                id in candidate.descriptor.dependencies &&
                    (states[candidate.descriptor.id] == ModuleStatus.RUNNING ||
                     states[candidate.descriptor.id] == ModuleStatus.INITIALIZED ||
                     states[candidate.descriptor.id] == ModuleStatus.FAILED)
            }
            .map { it.descriptor.id }

    @Synchronized
    fun initialize(id: String, context: Context): Result<Unit> = runCatching {
        val registered = requireNotNull(modules[id]) { "Unknown module: $id" }
        val module = registered.module
        check(states[id] == ModuleStatus.REGISTERED || states[id] == ModuleStatus.DISABLED) {
            "Module is not ready to initialize: $id"
        }

        val missing = registered.descriptor.dependencies.filter { dependency ->
            states[dependency] != ModuleStatus.INITIALIZED &&
                states[dependency] != ModuleStatus.RUNNING
        }
        check(missing.isEmpty()) {
            "Dependencies not initialized: ${missing.joinToString()}"
        }

        try {
            module.initialize(safeContext(context))
            states[id] = ModuleStatus.INITIALIZED
            failures.remove(id)
        } catch (error: Exception) {
            states[id] = ModuleStatus.FAILED
            failures[id] = safeUiErrorMessage(error)
            throw error
        }
    }

    @Synchronized
    fun start(id: String, context: Context): Result<Unit> = runCatching {
        val registered = requireNotNull(modules[id]) { "Unknown module: $id" }
        val module = registered.module
        check(states[id] == ModuleStatus.INITIALIZED || states[id] == ModuleStatus.STOPPED) {
            "Module must be initialized before start: $id"
        }

        val inactive = registered.descriptor.dependencies.filter { dependency ->
            states[dependency] != ModuleStatus.RUNNING
        }
        check(inactive.isEmpty()) {
            "Dependencies are not active: ${inactive.joinToString()}"
        }

        try {
            module.onStart(safeContext(context))
            states[id] = ModuleStatus.RUNNING
            failures.remove(id)
        } catch (error: Exception) {
            states[id] = ModuleStatus.FAILED
            failures[id] = safeUiErrorMessage(error)
            throw error
        }
    }

    @Synchronized
    fun stop(id: String, context: Context): Result<Unit> = runCatching {
        val registered = requireNotNull(modules[id]) { "Unknown module: $id" }
        val module = registered.module
        check(states[id] == ModuleStatus.RUNNING) {
            "Module is not running: $id"
        }
        val dependents = activeDependents(id)
        check(dependents.isEmpty()) {
            "Active dependent modules must stop first: ${dependents.joinToString()}"
        }

        try {
            module.onStop(safeContext(context))
            states[id] = ModuleStatus.STOPPED
        } catch (error: Exception) {
            states[id] = ModuleStatus.FAILED
            failures[id] = safeUiErrorMessage(error)
            throw error
        }
    }

    @Synchronized
    fun destroy(id: String, context: Context): Result<Unit> = runCatching {
        val registered = requireNotNull(modules[id]) { "Unknown module: $id" }
        val module = registered.module
        check(states[id] == ModuleStatus.INITIALIZED ||
              states[id] == ModuleStatus.STOPPED ||
              states[id] == ModuleStatus.FAILED) {
            "Module must be initialized or stopped before destroy: $id"
        }
        val dependents = activeDependents(id)
        check(dependents.isEmpty()) {
            "Active dependent modules must stop first: ${dependents.joinToString()}"
        }

        try {
            module.onDestroy(safeContext(context))
            states[id] = ModuleStatus.DISABLED
            failures.remove(id)
        } catch (error: Exception) {
            states[id] = ModuleStatus.FAILED
            failures[id] = safeUiErrorMessage(error)
            throw error
        }
    }
}
