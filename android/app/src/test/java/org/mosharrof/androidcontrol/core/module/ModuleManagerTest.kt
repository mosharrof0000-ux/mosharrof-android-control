package org.mosharrof.androidcontrol.core.module

import android.content.Context
import org.junit.Assert.*
import org.junit.Test

class ModuleManagerTest {

    private class FakeModule(
        override val descriptor: ModuleDescriptor
    ) : ControlModule {
        override fun initialize(context: Context) {}
        override fun onStart(context: Context) {}
        override fun onStop(context: Context) {}
        override fun onDestroy(context: Context) {}
    }

    @Test
    fun registerStoresModuleAsRegistered() {
        val manager = ModuleManager()
        val module = FakeModule(ModuleDescriptor("test.core", "Test Core", "1.0"))

        assertTrue(manager.register(module).isSuccess)
        assertEquals(ModuleStatus.REGISTERED, manager.snapshot()["test.core"])
    }

    @Test
    fun duplicateModuleIdIsRejected() {
        val manager = ModuleManager()
        val first = FakeModule(ModuleDescriptor("test.core", "First", "1.0"))
        val second = FakeModule(ModuleDescriptor("test.core", "Second", "1.0"))

        assertTrue(manager.register(first).isSuccess)
        assertTrue(manager.register(second).isFailure)
        assertEquals(ModuleStatus.REGISTERED, manager.snapshot()["test.core"])
    }

    @Test
    fun blankModuleIdIsRejected() {
        val manager = ModuleManager()
        val module = FakeModule(ModuleDescriptor("", "Test", "1.0"))

        assertTrue(manager.register(module).isFailure)
        assertTrue(manager.snapshot().isEmpty())
    }

    @Test
    fun blankModuleNameIsRejected() {
        val manager = ModuleManager()
        val module = FakeModule(ModuleDescriptor("test.core", " ", "1.0"))

        assertTrue(manager.register(module).isFailure)
        assertTrue(manager.snapshot().isEmpty())
    }

    @Test
    fun blankModuleVersionIsRejected() {
        val manager = ModuleManager()
        val module = FakeModule(ModuleDescriptor("test.core", "Test", ""))

        assertTrue(manager.register(module).isFailure)
        assertTrue(manager.snapshot().isEmpty())
    }

}
