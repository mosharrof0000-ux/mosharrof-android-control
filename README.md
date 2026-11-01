# Mosharrof Android Control

A standalone Android companion intended to let the user inspect and manage their own Android device through an authenticated, permission-scoped bridge with ChatGPT.

**Status: foundation in progress.** This project is separate from the Mosharrof app. The current code is a starter Android app; it does not yet provide remote ChatGPT control or claim to have access to the phone.

## Current starter
- Android app scaffold under `android/`.
- Read-only internal data-partition storage and Android-reported RAM diagnostics.
- User-initiated Android folder picker (Storage Access Framework).
- Security and architecture documentation.

## Planned capabilities
- User-selected files and media via SAF/MediaStore.
- Duplicate detection with preview, quarantine/backup, and explicit confirmation before deletion.
- Audio metadata and opt-in analysis.
- Storage and app-usage insights.
- Optional Termux agent for developer/project tasks.
- Authenticated ChatGPT-facing HTTPS API with scoped commands and audit logs.

## Safety boundaries
No public shell, arbitrary remote command execution, silent deletion, permission bypass, or hidden screen capture. Android permission prompts remain under the user's control. Android itself manages RAM; the app can report memory and guide the user but cannot reliably force-clear other apps' memory.

## Project layout
- `android/`: native Android companion.
- `docs/`: architecture, permissions, threat model, staged implementation.
- `openapi/`: planned ChatGPT-facing API contract.
- `agent/`: future authenticated relay/agent implementation.

See [architecture](docs/ARCHITECTURE.md), [security model](docs/SECURITY.md), and [Android build notes](android/README.md).
