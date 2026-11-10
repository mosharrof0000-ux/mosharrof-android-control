# Android app

Open the android/ directory in Android Studio (Ladybug or newer), sync Gradle, and run the app configuration.

## Requirements
- Android SDK platform 35
- JDK 17
- Android Gradle Plugin 8.7.3 / Kotlin 2.0.21
- AndroidX DocumentFile 1.0.1

## Current features
1. Internal data-partition storage and system RAM report.
2. Folder selection via Android Storage Access Framework.
3. Bounded recursive file listing and SHA-256 duplicate reporting.
4. Audio metadata inspection.
5. MCP Plugin Connector page available from Home and the bottom navigation.
6. HTTPS MCP discovery, tools/list, schema validation, and tools/call.
7. Explicit approval dialog for each tool invocation; WRITE tools require a short-lived, single-use approval grant.
8. Android Keystore AES-GCM storage for MCP bearer tokens.
9. Optional HTTPS device relay client with encrypted device-token storage, bounded responses, validated job IDs, and a fixed allowlist of read-only Termux diagnostics; every incoming job requires an in-app approval before execution.

## Limits and verification status
- The app only sees the selected tree and what its document provider exposes.
- Scans are bounded to 5,000 files and 12 directory levels; audio details display at most 100 files.
- Hashing can take time and reads file contents; use a folder you own and have permission to inspect.
- No arbitrary shell command or unrestricted Android control is exposed.
- The relay client code is present, but relay deployment, ChatGPT connector configuration, server-side replay/expiry controls, and end-to-end operation have not been verified.
- Real external MCP server connection, tool execution, and disconnect still require physical-device QA.

## CI
GitHub Actions builds a debug APK and uploads it as mosharrof-android-control-debug. Install only after downloading it from the repository's trusted Actions run and reviewing Android's install prompt.