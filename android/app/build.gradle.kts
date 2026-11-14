plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.cyclonedx.bom")
}

android {
    namespace = "org.mosharrof.androidcontrol"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.mosharrof.androidcontrol"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        // AppAuth library manifest requires this placeholder for its redirect receiver.
        manifestPlaceholders["appAuthRedirectScheme"] = "org.mosharrof.androidcontrol"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget("17"))
    }
}

dependencies {
    implementation("androidx.documentfile:documentfile:1.0.1")
    // OAuth 2.0 Authorization Code + PKCE using the system browser / Custom Tabs.
    implementation("net.openid:appauth:0.11.1")

    // Local JVM unit tests for the Android Plugin System.
    testImplementation("junit:junit:4.13.2")
    // Use the fixed org.json release for local JVM tests (CVE-2026-59171).
    testImplementation("org.json:json:20260719")
}

// The distributable APK's dependency boundary: exclude build tooling and test-only graphs.
tasks.cyclonedxDirectBom {
    includeConfigs = listOf("releaseRuntimeClasspath")
}
