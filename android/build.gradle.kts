plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

// Lock every resolvable dependency configuration in Android subprojects.
// CI uses --write-locks to generate the reviewed lock state from real resolution.
subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}
