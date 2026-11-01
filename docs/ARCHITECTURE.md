# Architecture and implementation status

## Product boundary
This is a standalone Android companion intended to be controlled conversationally through ChatGPT. It does not require the Mosharrof AI app.

## Implemented local capability
- Read-only storage and RAM diagnostics.
- User-selected Storage Access Framework tree, bounded inventory, SHA-256 duplicate detection, and audio metadata.
- Optional Termux RUN_COMMAND bridge with a strict operation allowlist: Python version, fixed video-project Git status, Termux home storage report, and system information.
- Termux command output is returned through a private app BroadcastReceiver and stored locally for display.

## Termux setup required on the phone
1. Install Termux from a trusted source and open it once.
2. In Termux, edit `~/.termux/termux.properties` and set `allow-external-apps = true`, then restart Termux.
3. In Android Settings -> Apps -> Android Companion -> Permissions -> Additional permissions, grant **Run commands in Termux environment**.
4. Open the companion and run one of the four predefined Termux diagnostics.
5. The video-project status check expects the repository at `~/mosharrof-video-processing`.

Termux documents both the Android permission and `allow-external-apps` as mandatory safeguards. See the [official RUN_COMMAND documentation](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent).

## Not yet connected to ChatGPT
This local bridge is not a remote ChatGPT connection. Still required:
1. Device pairing and a secure HTTPS relay with short-lived, revocable credentials.
2. Typed job queue, command IDs, expiry, replay/idempotency protection, rate limits, and audit logs.
3. Device-initiated outbound polling and visible foreground-session consent.
4. An OpenAPI contract and an explicitly configured/authorized ChatGPT connector.
5. Integration tests and physical-device QA.

A GitHub repository or APK alone does not connect this chat to the phone. The relay must be deployed and the connector explicitly configured.

## Android limits
Only the fixed Termux operations are allowed; there is no generic shell, caller-provided command string, delete, overwrite, or permission bypass. Android SAF limits file access to user-selected folders. MediaProjection requires Android system consent. Ordinary apps cannot reliably clear other apps' RAM.
