package org.mosharrof.androidcontrol.core.module

import android.content.Context
import org.junit.Assert.*
import org.junit.Test

class ModuleManagerLifecycleTest {

    private class FakeModule(
        override val descriptor: ModuleDescriptor
    ) : ControlModule {
        var initialized = false
        var started = false
        var stopped = false
        var destroyed = false

        override fun initialize(context: Context) {
            initialized = true
        }

        override fun onStart(context: Context) {
            started = true
        }

        override fun onStop(context: Context) {
            stopped = true
        }

        override fun onDestroy(context: Context) {
            destroyed = true
        }
    }

    @Test
    fun dependencyMustBeRegisteredFirst() {
        val manager = ModuleManager()
        val dependent = FakeModule(
            ModuleDescriptor("feature", "Feature", "1.0", setOf("core"))
        )

        assertTrue(manager.register(dependent).isFailure)
    }

    @Test
    fun dependencyMustBeInitializedBeforeDependent() {
        val manager = ModuleManager()
        val core = FakeModule(ModuleDescriptor("core", "Core", "1.0"))
        val feature = FakeModule(
            ModuleDescriptor("feature", "Feature", "1.0", setOf("core"))
        )

        assertTrue(manager.register(core).isSuccess)
        assertTrue(manager.register(feature).isSuccess)

        val result = manager.initialize("feature", coreContext())
        assertTrue(result.isFailure)
        assertFalse(feature.initialized)
    }

    @Test
    fun runningDependentPreventsDependencyStop() {
        val manager = ModuleManager()
        val core = FakeModule(ModuleDescriptor("core", "Core", "1.0"))
        val feature = FakeModule(
            ModuleDescriptor("feature", "Feature", "1.0", setOf("core"))
        )
        val context = coreContext()

        assertTrue(manager.register(core).isSuccess)
        assertTrue(manager.register(feature).isSuccess)
        assertTrue(manager.initialize("core", context).isSuccess)
        assertTrue(manager.initialize("feature", context).isSuccess)
        assertTrue(manager.start("core", context).isSuccess)
        assertTrue(manager.start("feature", context).isSuccess)

        assertTrue(manager.stop("core", context).isFailure)
        assertFalse(core.stopped)
    }

    @Test
    fun registeredModuleCannotBeDestroyed() {
        val manager = ModuleManager()
        val core = FakeModule(ModuleDescriptor("core", "Core", "1.0"))

        assertTrue(manager.register(core).isSuccess)
        assertTrue(manager.destroy("core", coreContext()).isFailure)
        assertFalse(core.destroyed)
    }

    @Test
    fun dependentCanStopBeforeDependency() {
        val manager = ModuleManager()
        val core = FakeModule(ModuleDescriptor("core", "Core", "1.0"))
        val feature = FakeModule(
            ModuleDescriptor("feature", "Feature", "1.0", setOf("core"))
        )
        val context = coreContext()

        assertTrue(manager.register(core).isSuccess)
        assertTrue(manager.register(feature).isSuccess)
        assertTrue(manager.initialize("core", context).isSuccess)
        assertTrue(manager.initialize("feature", context).isSuccess)
        assertTrue(manager.start("core", context).isSuccess)
        assertTrue(manager.start("feature", context).isSuccess)
        assertTrue(manager.stop("feature", context).isSuccess)
        assertTrue(manager.stop("core", context).isSuccess)
        assertTrue(manager.destroy("feature", context).isSuccess)
    }

    @Test
    fun initializedDependentPreventsDependencyDestroy() {
        val manager = ModuleManager()
        val core = FakeModule(ModuleDescriptor("core", "Core", "1.0"))
        val feature = FakeModule(
            ModuleDescriptor("feature", "Feature", "1.0", setOf("core"))
        )
        val context = coreContext()

        assertTrue(manager.register(core).isSuccess)
        assertTrue(manager.register(feature).isSuccess)
        assertTrue(manager.initialize("core", context).isSuccess)
        assertTrue(manager.initialize("feature", context).isSuccess)

        assertTrue(manager.destroy("core", context).isFailure)
        assertFalse(core.destroyed)
        assertEquals(ModuleStatus.INITIALIZED, manager.snapshot()["core"])
    }

    @Test
    fun initializeFailureSetsFailedStateAndStoresReason() {
        val manager = ModuleManager()
        val broken = object : ControlModule {
            override val descriptor = ModuleDescriptor("broken", "Broken", "1.0")
            override fun initialize(context: Context) {
                throw IllegalStateException("initialization failed")
            }
            override fun onStart(context: Context) {}
            override fun onStop(context: Context) {}
            override fun onDestroy(context: Context) {}
        }

        assertTrue(manager.register(broken).isSuccess)
        assertTrue(manager.initialize("broken", coreContext()).isFailure)
        assertEquals(ModuleStatus.FAILED, manager.snapshot()["broken"])
        assertEquals("অপারেশন সম্পন্ন হয়নি। নিরাপত্তার জন্য বিস্তারিত error text আড়াল রাখা হয়েছে।", manager.failureDetails()["broken"])
    }

    @Test
    fun failedDependentPreventsDependencyDestroyUntilCleanedUp() {
        val manager = ModuleManager()
        val core = FakeModule(ModuleDescriptor("core", "Core", "1.0"))
        val broken = object : ControlModule {
            override val descriptor =
                ModuleDescriptor("broken", "Broken", "1.0", setOf("core"))
            override fun initialize(context: Context) {
                throw IllegalStateException("partial initialization")
            }
            override fun onStart(context: Context) {}
            override fun onStop(context: Context) {}
            override fun onDestroy(context: Context) {}
        }
        val context = coreContext()

        assertTrue(manager.register(core).isSuccess)
        assertTrue(manager.register(broken).isSuccess)
        assertTrue(manager.initialize("core", context).isSuccess)
        assertTrue(manager.initialize("broken", context).isFailure)

        assertTrue(manager.destroy("core", context).isFailure)
        assertFalse(core.destroyed)

        assertTrue(manager.destroy("broken", context).isSuccess)
        assertTrue(manager.destroy("core", context).isSuccess)
        assertTrue(core.destroyed)
    }

    @Test
    fun dependentCannotStartUntilDependencyIsRunning() {
        val manager = ModuleManager()
        val core = FakeModule(ModuleDescriptor("core", "Core", "1.0"))
        val feature = FakeModule(
            ModuleDescriptor("feature", "Feature", "1.0", setOf("core"))
        )
        val context = coreContext()

        assertTrue(manager.register(core).isSuccess)
        assertTrue(manager.register(feature).isSuccess)
        assertTrue(manager.initialize("core", context).isSuccess)
        assertTrue(manager.initialize("feature", context).isSuccess)

        assertTrue(manager.start("feature", context).isFailure)
        assertFalse(feature.started)

        assertTrue(manager.start("core", context).isSuccess)
        assertTrue(manager.start("feature", context).isSuccess)
        assertTrue(feature.started)
    }


    @Test
    fun startFailureSetsFailedStateAndStoresReason() {
        val manager = ModuleManager()
        val broken = object : ControlModule {
            override val descriptor = ModuleDescriptor("broken-start", "Broken Start", "1.0")
            override fun initialize(context: Context) {}
            override fun onStart(context: Context) {
                throw IllegalStateException("start failed")
            }
            override fun onStop(context: Context) {}
            override fun onDestroy(context: Context) {}
        }
        assertTrue(manager.register(broken).isSuccess)
        assertTrue(manager.initialize("broken-start", coreContext()).isSuccess)
        assertTrue(manager.start("broken-start", coreContext()).isFailure)
        assertEquals(ModuleStatus.FAILED, manager.snapshot()["broken-start"])
        assertEquals("অপারেশন সম্পন্ন হয়নি। নিরাপত্তার জন্য বিস্তারিত error text আড়াল রাখা হয়েছে।", manager.failureDetails()["broken-start"])
    }

    @Test
    fun stopFailureSetsFailedStateAndStoresReason() {
        val manager = ModuleManager()
        val broken = object : ControlModule {
            override val descriptor = ModuleDescriptor("broken-stop", "Broken Stop", "1.0")
            override fun initialize(context: Context) {}
            override fun onStart(context: Context) {}
            override fun onStop(context: Context) {
                throw IllegalStateException("stop failed")
            }
            override fun onDestroy(context: Context) {}
        }
        assertTrue(manager.register(broken).isSuccess)
        assertTrue(manager.initialize("broken-stop", coreContext()).isSuccess)
        assertTrue(manager.start("broken-stop", coreContext()).isSuccess)
        assertTrue(manager.stop("broken-stop", coreContext()).isFailure)
        assertEquals(ModuleStatus.FAILED, manager.snapshot()["broken-stop"])
        assertEquals("অপারেশন সম্পন্ন হয়নি। নিরাপত্তার জন্য বিস্তারিত error text আড়াল রাখা হয়েছে।", manager.failureDetails()["broken-stop"])
    }

    @Test
    fun destroyFailureSetsFailedStateAndStoresReason() {
        val manager = ModuleManager()
        val broken = object : ControlModule {
            override val descriptor = ModuleDescriptor("broken-destroy", "Broken Destroy", "1.0")
            override fun initialize(context: Context) {}
            override fun onStart(context: Context) {}
            override fun onStop(context: Context) {}
            override fun onDestroy(context: Context) {
                throw IllegalStateException("destroy failed")
            }
        }
        assertTrue(manager.register(broken).isSuccess)
        assertTrue(manager.initialize("broken-destroy", coreContext()).isSuccess)
        assertTrue(manager.destroy("broken-destroy", coreContext()).isFailure)
        assertEquals(ModuleStatus.FAILED, manager.snapshot()["broken-destroy"])
        assertEquals("অপারেশন সম্পন্ন হয়নি। নিরাপত্তার জন্য বিস্তারিত error text আড়াল রাখা হয়েছে।", manager.failureDetails()["broken-destroy"])
    }

    @Test
    fun destroyedModuleCanBeInitializedAndStartedAgain() {
        val manager = ModuleManager()
        val module = FakeModule(ModuleDescriptor("re-enable", "Re-enable", "1.0"))
        val context = coreContext()

        assertTrue(manager.register(module).isSuccess)
        assertTrue(manager.initialize("re-enable", context).isSuccess)
        assertTrue(manager.destroy("re-enable", context).isSuccess)
        assertEquals(ModuleStatus.DISABLED, manager.snapshot()["re-enable"])

        assertTrue(manager.initialize("re-enable", context).isSuccess)
        assertEquals(ModuleStatus.INITIALIZED, manager.snapshot()["re-enable"])
        assertTrue(manager.start("re-enable", context).isSuccess)
        assertEquals(ModuleStatus.RUNNING, manager.snapshot()["re-enable"])
        assertTrue(module.initialized)
        assertTrue(module.destroyed)
        assertTrue(module.started)
    }

    @Test
    fun disabledDependencyPreventsDependentReinitializationUntilRestored() {
        val manager = ModuleManager()
        val core = FakeModule(ModuleDescriptor("core-reenable", "Core", "1.0"))
        val feature = FakeModule(
            ModuleDescriptor("feature-reenable", "Feature", "1.0", setOf("core-reenable"))
        )
        val context = coreContext()

        assertTrue(manager.register(core).isSuccess)
        assertTrue(manager.register(feature).isSuccess)

        assertTrue(manager.initialize("core-reenable", context).isSuccess)
        assertTrue(manager.initialize("feature-reenable", context).isSuccess)
        assertTrue(manager.start("core-reenable", context).isSuccess)
        assertTrue(manager.start("feature-reenable", context).isSuccess)

        assertTrue(manager.stop("feature-reenable", context).isSuccess)
        assertTrue(manager.stop("core-reenable", context).isSuccess)
        assertTrue(manager.destroy("feature-reenable", context).isSuccess)
        assertTrue(manager.destroy("core-reenable", context).isSuccess)

        assertEquals(ModuleStatus.DISABLED, manager.snapshot()["core-reenable"])
        assertEquals(ModuleStatus.DISABLED, manager.snapshot()["feature-reenable"])

        assertTrue(manager.initialize("feature-reenable", context).isFailure)
        assertEquals(ModuleStatus.DISABLED, manager.snapshot()["feature-reenable"])

        assertTrue(manager.initialize("core-reenable", context).isSuccess)
        assertTrue(manager.initialize("feature-reenable", context).isSuccess)
        assertEquals(ModuleStatus.INITIALIZED, manager.snapshot()["feature-reenable"])
    }


    @Test
    fun stoppedModuleCanStartAgainWithoutReinitializing() {
        val manager = ModuleManager()
        val module = FakeModule(ModuleDescriptor("restart-test", "Restart Test", "1.0"))
        val context = coreContext()

        assertTrue(manager.register(module).isSuccess)
        assertTrue(manager.initialize("restart-test", context).isSuccess)
        assertTrue(manager.start("restart-test", context).isSuccess)
        assertTrue(manager.stop("restart-test", context).isSuccess)
        assertEquals(ModuleStatus.STOPPED, manager.snapshot()["restart-test"])

        assertTrue(manager.start("restart-test", context).isSuccess)
        assertEquals(ModuleStatus.RUNNING, manager.snapshot()["restart-test"])
    }

    @Test
    fun stoppedModuleCannotBeInitializedAgainBeforeDestroy() {
        val manager = ModuleManager()
        val module = FakeModule(ModuleDescriptor("no-double-init", "No Double Init", "1.0"))
        val context = coreContext()

        assertTrue(manager.register(module).isSuccess)
        assertTrue(manager.initialize("no-double-init", context).isSuccess)
        assertTrue(manager.start("no-double-init", context).isSuccess)
        assertTrue(manager.stop("no-double-init", context).isSuccess)

        assertTrue(manager.initialize("no-double-init", context).isFailure)
        assertEquals(ModuleStatus.STOPPED, manager.snapshot()["no-double-init"])
    }


    @Test
    fun mutableDependencySetCannotBypassStopProtection() {
        val manager = ModuleManager()
        val core = FakeModule(ModuleDescriptor("frozen-core", "Core", "1.0"))
        val dependencies = mutableSetOf("frozen-core")
        val feature = FakeModule(
            ModuleDescriptor("frozen-feature", "Feature", "1.0", dependencies)
        )
        val context = coreContext()

        assertTrue(manager.register(core).isSuccess)
        assertTrue(manager.register(feature).isSuccess)
        assertTrue(manager.initialize("frozen-core", context).isSuccess)
        assertTrue(manager.initialize("frozen-feature", context).isSuccess)
        assertTrue(manager.start("frozen-core", context).isSuccess)
        assertTrue(manager.start("frozen-feature", context).isSuccess)

        dependencies.clear()

        assertTrue(manager.stop("frozen-core", context).isFailure)
        assertFalse(core.stopped)
        assertEquals(ModuleStatus.RUNNING, manager.snapshot()["frozen-core"])
    }

    @Test
    fun transitiveDependenciesMustStopInReverseOrder() {
        val manager = ModuleManager()
        val core = FakeModule(ModuleDescriptor("chain-core", "Core", "1.0"))
        val middle = FakeModule(
            ModuleDescriptor("chain-middle", "Middle", "1.0", setOf("chain-core"))
        )
        val leaf = FakeModule(
            ModuleDescriptor("chain-leaf", "Leaf", "1.0", setOf("chain-middle"))
        )
        val context = coreContext()

        assertTrue(manager.register(core).isSuccess)
        assertTrue(manager.register(middle).isSuccess)
        assertTrue(manager.register(leaf).isSuccess)

        assertTrue(manager.initialize("chain-core", context).isSuccess)
        assertTrue(manager.initialize("chain-middle", context).isSuccess)
        assertTrue(manager.initialize("chain-leaf", context).isSuccess)

        assertTrue(manager.start("chain-core", context).isSuccess)
        assertTrue(manager.start("chain-middle", context).isSuccess)
        assertTrue(manager.start("chain-leaf", context).isSuccess)

        assertTrue(manager.stop("chain-core", context).isFailure)
        assertTrue(manager.stop("chain-middle", context).isFailure)

        assertTrue(manager.stop("chain-leaf", context).isSuccess)
        assertTrue(manager.stop("chain-middle", context).isSuccess)
        assertTrue(manager.stop("chain-core", context).isSuccess)
    }


    @Test
    fun failedInitializeCanBeCleanedUp() {
        val manager = ModuleManager()
        var destroyed = false
        val broken = object : ControlModule {
            override val descriptor = ModuleDescriptor("cleanup-init", "Cleanup Init", "1.0")
            override fun initialize(context: Context) {
                throw IllegalStateException("initialize failed")
            }
            override fun onStart(context: Context) {}
            override fun onStop(context: Context) {}
            override fun onDestroy(context: Context) {
                destroyed = true
            }
        }
        val context = coreContext()

        assertTrue(manager.register(broken).isSuccess)
        assertTrue(manager.initialize("cleanup-init", context).isFailure)
        assertEquals(ModuleStatus.FAILED, manager.snapshot()["cleanup-init"])
        assertTrue(manager.destroy("cleanup-init", context).isSuccess)
        assertTrue(destroyed)
        assertEquals(ModuleStatus.DISABLED, manager.snapshot()["cleanup-init"])
        assertFalse(manager.failureDetails().containsKey("cleanup-init"))
    }

    @Test
    fun failedStartCanBeCleanedUp() {
        val manager = ModuleManager()
        var destroyed = false
        val broken = object : ControlModule {
            override val descriptor = ModuleDescriptor("cleanup-start", "Cleanup Start", "1.0")
            override fun initialize(context: Context) {}
            override fun onStart(context: Context) {
                throw IllegalStateException("start failed")
            }
            override fun onStop(context: Context) {}
            override fun onDestroy(context: Context) {
                destroyed = true
            }
        }
        val context = coreContext()

        assertTrue(manager.register(broken).isSuccess)
        assertTrue(manager.initialize("cleanup-start", context).isSuccess)
        assertTrue(manager.start("cleanup-start", context).isFailure)
        assertEquals(ModuleStatus.FAILED, manager.snapshot()["cleanup-start"])
        assertTrue(manager.destroy("cleanup-start", context).isSuccess)
        assertTrue(destroyed)
        assertEquals(ModuleStatus.DISABLED, manager.snapshot()["cleanup-start"])
        assertFalse(manager.failureDetails().containsKey("cleanup-start"))
    }

    @Test
    fun concurrentLifecycleCallsAreSerialized() {
        val manager = ModuleManager()
        val context = coreContext()
        val initializeEntered = java.util.concurrent.CountDownLatch(1)
        val allowInitializeToFinish = java.util.concurrent.CountDownLatch(1)
        val startFinished = java.util.concurrent.CountDownLatch(1)
        val initializeSucceeded = java.util.concurrent.atomic.AtomicBoolean(false)
        val startSucceeded = java.util.concurrent.atomic.AtomicBoolean(false)

        val module = object : ControlModule {
            override val descriptor = ModuleDescriptor("concurrent-core", "Concurrent Core", "1.0")
            override fun initialize(context: Context) {
                initializeEntered.countDown()
                check(allowInitializeToFinish.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            override fun onStart(context: Context) {}
            override fun onStop(context: Context) {}
            override fun onDestroy(context: Context) {}
        }

        assertTrue(manager.register(module).isSuccess)

        val initializeThread = Thread {
            initializeSucceeded.set(manager.initialize("concurrent-core", context).isSuccess)
        }
        val startThread = Thread {
            try {
                startSucceeded.set(manager.start("concurrent-core", context).isSuccess)
            } finally {
                startFinished.countDown()
            }
        }

        initializeThread.start()
        assertTrue(initializeEntered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        startThread.start()

        val startCompletedTooEarly =
            startFinished.await(150, java.util.concurrent.TimeUnit.MILLISECONDS)

        allowInitializeToFinish.countDown()
        initializeThread.join(5000)
        startThread.join(5000)

        assertFalse("Initialize চলাকালে Start আগে শেষ হওয়া চলবে না", startCompletedTooEarly)
        assertFalse("Initialize thread আটকে আছে", initializeThread.isAlive)
        assertFalse("Start thread আটকে আছে", startThread.isAlive)
        assertTrue(initializeSucceeded.get())
        assertTrue(startSucceeded.get())
        assertEquals(ModuleStatus.RUNNING, manager.snapshot()["concurrent-core"])
    }

    private fun coreContext(): Context =
        android.content.ContextWrapper(null)
}
