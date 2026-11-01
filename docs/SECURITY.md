# Security model

## Non-negotiable rules
- Never expose an unauthenticated listener or generic shell endpoint.
- Pair devices explicitly; use TLS and short-lived, revocable credentials.
- Every command has a typed schema, scope, expiry, unique ID, and idempotency/replay check.
- Keep an append-only audit trail of requested action, consent, result, and timestamp.
- Read-only inspection is the default.
- Destructive actions (delete, overwrite, bulk move, permission changes) require a clear preview and a separate user confirmation.
- Prefer reversible quarantine/backup before permanent deletion.
- Never bypass Android permission dialogs, lock screens, OTP, banking controls, or app security boundaries.
- Do not store API keys in source control or plain-text preferences.

## Initial release
This starter release performs only local, read-only diagnostics and opens the Android folder picker. It has no network control channel and no delete capability.

## Threats to address before remote control
- stolen pairing credentials;
- replayed or duplicated commands;
- malicious or malformed command payloads;
- compromised relay;
- accidental bulk deletion;
- privilege escalation through shell or accessibility;
- logs leaking filenames, tokens, or personal data.

## Required mitigations
Credential revocation, command allowlists, strict input validation, rate limits, least privilege, redacted logs, explicit consent, and security tests are release blockers.
