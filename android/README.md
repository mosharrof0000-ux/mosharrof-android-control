# Android app

Open the `android/` directory in Android Studio (Ladybug or newer), sync Gradle, and run the `app` configuration.

## Requirements
- Android SDK platform 35
- JDK 17
- Android Gradle Plugin 8.7.3 / Kotlin 2.0.21
- AndroidX DocumentFile 1.0.1

## Current features
1. Internal data-partition storage and system RAM report.
2. Folder selection via Android Storage Access Framework.
3. Bounded recursive file listing.
4. SHA-256 duplicate scan; duplicates are reported only, never deleted.
5. Audio metadata (artist, album, duration) from user-selected folders.

## Limits
- The app only sees the selected tree and what its document provider exposes.
- Scans are bounded to 5,000 files and 12 directory levels; audio details display at most 100 files.
- Hashing can take time and reads file contents; use a folder you own and have permission to inspect.
- Some providers do not expose metadata or stable file operations.
- RAM cleanup of other apps is not supported by Android's ordinary app sandbox.
- No network service or ChatGPT remote-control connection is included yet.

## CI
GitHub Actions builds a debug APK and uploads it as `mosharrof-android-control-debug`. Install only after downloading it from the repository's trusted Actions run and reviewing Android's install prompt.
