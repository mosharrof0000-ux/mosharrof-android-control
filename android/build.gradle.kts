plugins {
    id("com.android.application") version "8.13.2" apply false
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

    // Keep Netty modules aligned on the maintained 4.1 security release across
    // Android test-platform configurations that currently resolve older lines.
    // Root plugin/buildscript classpaths are audited separately and are not
    // changed by this subproject-only rule.
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "io.netty") {
                useVersion("4.1.140.Final")
            }
        }
    }
}
