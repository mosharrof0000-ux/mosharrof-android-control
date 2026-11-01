# Architecture and implementation plan

## Product boundary
Mosharrof Android Control is an independent Android companion. ChatGPT is the conversational interface. The Mosharrof app may optionally integrate later, but is not a dependency.

## Components
1. **Android companion** — native app, user-granted SAF access, local diagnostics, consent screens, and a foreground service only when a user-started session needs it.
2. **Device agent** — narrow typed operations; no arbitrary shell endpoint. Termux integration is optional and separately permissioned.
3. **Authenticated relay** — outbound device connection over TLS, pairing, short-lived credentials, command IDs, expiry, replay protection, rate limits, and audit records.
4. **ChatGPT connector** — an HTTPS API described by OpenAPI. The user must explicitly connect/authorize it in ChatGPT; this repository alone cannot create a native ChatGPT connection.

## Initial milestone
- Buildable Android starter app.
- Show available storage and Android memory diagnostics.
- Let the user choose a folder through SAF.
- Explain capability and permission boundaries.
- Do not delete or modify files in this milestone.

## Later milestones
- SAF/MediaStore inventory and duplicate scan (hash-based).
- Review screen, quarantine/backup, explicit delete confirmation.
- Audio metadata extraction and opt-in audio analysis.
- App usage insights with Usage Access permission.
- User-initiated screen sharing using MediaProjection and Android's consent UI.
- Secure relay and ChatGPT connector.
- Optional Termux integration for explicitly allowlisted developer tasks.

## Important Android limits
- Scoped storage means access is limited to user-selected files/folders and platform media APIs.
- Usage Access is a separate user-granted special access.
- MediaProjection requires Android's system consent flow; no covert capture.
- Android controls process memory. The app can report memory information but cannot guarantee clearing other apps' RAM.
- Some app-private data and protected system settings are inaccessible to ordinary apps.
