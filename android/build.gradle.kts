buildscript {
    // Lock the root buildscript classpath separately from Android subprojects.
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
        resolutionStrategy.eachDependency {
            if (requested.group == "io.netty" &&
                requested.name.startsWith("netty-") &&
                !requested.name.startsWith("netty-tcnative")) {
                useVersion("4.1.140.Final")
            }
            if (requested.group == "com.google.guava" && requested.name == "guava") {
                useVersion("33.8.0-jre")
            }
            if (requested.group.startsWith("com.fasterxml.jackson")) {
                // jackson-annotations uses the 2.22 line without a patch suffix;
                // the other Jackson 2.22 modules use patch versions such as 2.22.4.
                useVersion(if (requested.name == "jackson-annotations") "2.22" else "2.22.4")
            }
            if (requested.group == "com.google.protobuf" &&
                requested.name in setOf("protobuf-java", "protobuf-kotlin", "protobuf-javalite", "protobuf-kotlin-lite", "protobuf-java-util")) {
                useVersion("3.25.5")
            }
            if (requested.group == "org.bouncycastle" &&
                requested.name in setOf("bcprov-jdk18on", "bcpkix-jdk18on", "bcutil-jdk18on")) {
                // 1.86 is the upstream release that resolves the audited 1.79 findings.
                useVersion("1.86")
            }
            if (requested.group == "org.jdom" && requested.name == "jdom2") {
                useVersion("2.0.6.1")
            }
            if (requested.group == "org.bitbucket.b_c" && requested.name == "jose4j") {
                useVersion("0.9.6")
            }
            if (requested.group == "org.apache.commons" && requested.name == "commons-compress") {
                useVersion("1.28.0")
            }
        }
    }
}

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

    // Align security-sensitive transitive dependencies across all Android
    // subproject configurations, including internal test-platform configurations.
    // Root plugin/buildscript classpaths are audited separately above.
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "io.netty" &&
                requested.name.startsWith("netty-") &&
                !requested.name.startsWith("netty-tcnative")) {
                useVersion("4.1.140.Final")
            }
            if (requested.group == "com.google.guava" && requested.name == "guava") {
                // Keep the Android-specific artifact variant for app/test tooling.
                useVersion("33.8.0-android")
            }
            if (requested.group.startsWith("com.fasterxml.jackson")) {
                // jackson-annotations uses the 2.22 line without a patch suffix;
                // the other Jackson 2.22 modules use patch versions such as 2.22.4.
                useVersion(if (requested.name == "jackson-annotations") "2.22" else "2.22.4")
            }
            if (requested.group == "com.google.protobuf" &&
                requested.name in setOf("protobuf-java", "protobuf-kotlin", "protobuf-javalite", "protobuf-kotlin-lite", "protobuf-java-util")) {
                useVersion("3.25.5")
            }
        }
    }
}
