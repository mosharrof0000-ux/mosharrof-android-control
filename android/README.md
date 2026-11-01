# Android app build

Open the `android/` directory in Android Studio (Ladybug or newer), allow Gradle sync, then run the `app` configuration on an Android device or emulator.

Requirements:
- Android SDK platform 35
- JDK 17
- Android Gradle Plugin 8.7.3 / Kotlin 2.0.21 (resolved by Gradle)

This starter screen reports Android-reported data-partition storage and RAM totals/free memory, and opens the system folder picker. It does not enumerate files yet, does not delete anything, and has no remote ChatGPT bridge.

No Gradle wrapper is committed in this first scaffold; Android Studio can import the project and use its configured Gradle installation. A wrapper and CI build will be added in the next milestone.
