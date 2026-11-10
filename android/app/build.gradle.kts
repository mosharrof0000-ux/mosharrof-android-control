plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Local JVM unit tests for the Android Plugin System.
    testImplementation("junit:junit:4.13.2")
}
