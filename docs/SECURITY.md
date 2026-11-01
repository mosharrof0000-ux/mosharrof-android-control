# Security model

## Non-negotiable rules
- No unauthenticated listener and no generic remote shell endpoint.
- Pair devices explicitly; use TLS and short-lived, revocable credentials for any future relay.
- Every future remote job must have a typed schema, scope, expiry, unique ID, idempotency/replay check, rate limit, and audit record.
- Read-only inspection is the default.
- Destructive actions require a clear preview and a separate confirmation; prefer reversible quarantine.
- Never bypass Android permission dialogs, lock screens, OTP, banking controls, or app security boundaries.
- Never store API keys in source control or plain-text preferences.

## Termux bridge boundary
- Requests are mapped by a hard-coded operation ID to a fixed executable path and fixed argument vector.
- No shell interpreter, arbitrary command text, or user-supplied arguments are accepted.
- Initial Termux operations are read-only diagnostics and a fixed Git status query.
- Termux's own `com.termux.permission.RUN_COMMAND` permission and `allow-external-apps` setting are both required and must be enabled by the user.
- The result receiver is private to this app; returned output is truncated before local storage.
- Termux output may contain private filenames or project information; the user should only enable this bridge on a device they control.

## Remaining threats before remote control
Stolen pairing credentials, replayed jobs, malicious payloads, compromised relay, accidental bulk operations, privilege escalation, and logs leaking private data. Mitigations include revocation, strict schemas, bounded output, redacted logs, explicit consent, and security tests.
