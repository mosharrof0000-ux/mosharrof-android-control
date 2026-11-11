plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    // CycloneDX SBOM for the resolved Android application dependency graph.
    id("org.cyclonedx.bom") version "3.5.1" apply false
}

// Lock every resolvable dependency configuration in Android subprojects.
// CI uses --write-locks to generate the reviewed lock state from real resolution.
subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}
