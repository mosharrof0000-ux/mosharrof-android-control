# Mosharrof Android Control

A standalone Android companion for user-authorized device inspection and file/media inventory. It is independent of the Mosharrof app.

## Implemented in the current branch
- Android-reported internal data-partition storage and RAM diagnostics.
- User-selected folder access through Android Storage Access Framework (SAF).
- Recursive file inventory (bounded to 5,000 files / 12 directory levels).
- SHA-256 duplicate-content detection, grouped by size/extension before hashing.
- Audio metadata inspection (artist, album, duration) for common audio formats.
- CI-built debug APK artifact.

## Not yet implemented / not claimed
- ChatGPT-to-phone remote command bridge or hosted relay.
- File deletion, quarantine, copy/move, bulk rename, or system/app settings control.
- MediaProjection screen sharing, Usage Access app statistics, or Termux command agent.
- Physical-device QA on the user's phone.

## Android constraints
The user must select folders and grant access. Android restricts protected folders and app-private data. RAM is managed by Android; this app reports memory but cannot reliably force-clear other apps. SAF provider capabilities vary by device and storage provider.

## Security
Current app operations are read-only. Duplicate detection never deletes files. Remote control must not be enabled until device pairing, TLS, revocable credentials, typed command allowlists, expiry/replay checks, audit logs, and explicit destructive-action confirmation are implemented and tested.

See [architecture](docs/ARCHITECTURE.md), [security model](docs/SECURITY.md), and [Android build notes](android/README.md).
