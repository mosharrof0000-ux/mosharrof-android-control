# Security model

## Non-negotiable rules
- No unauthenticated listener and no generic remote shell endpoint.
- Pair devices explicitly; use TLS and short-lived, revocable credentials for any relay.
- Read-only inspection is the default.
- Every remote operation must map to a typed, fixed allowlist entry; never accept caller-supplied executable paths or shell text.
- Destructive actions require a clear preview and a separate confirmation; prefer reversible quarantine.
- Never bypass Android permission dialogs, lock screens, OTP, banking controls, or app security boundaries.
- Never store API keys in source control or plaintext preferences.

## MCP connector boundary
- MCP endpoints must be HTTPS; the connector rejects endpoint user-info and fragments.
- Connector bearer tokens are encrypted with Android Keystore AES-GCM and authenticated additional data bound to the connector ID.
- Tool definitions and input schemas are validated before registration/call. Only the explicitly supported JSON Schema subset is accepted; unknown assertions, malformed constraints, and schema trees deeper than 32 levels or 2,048 nodes are rejected instead of silently ignored.
- Tool calls display the tool risk and exact input JSON. Each call requires a deliberate user action.
- Write-capable tool calls require a short-lived, single-use approval grant bound to the connector, tool ID, and exact arguments.
- Disconnect unregisters connector tools and revokes outstanding approval grants.
- A successful unit test or CI build is not proof of real-server or real-device security.

## Termux and relay boundary
- Requests are mapped by a hard-coded operation ID to a fixed executable path and fixed argument vector.
- No shell interpreter, arbitrary command text, or user-supplied arguments are accepted.
- Initial Termux operations are read-only diagnostics and a fixed Git status query.
- Termux's own com.termux.permission.RUN_COMMAND permission and allow-external-apps setting are both required and must be enabled by the user.
- The result receiver is private to this app; returned output is truncated before local storage.
- Relay traffic uses HTTPS and bearer authentication. The device token is encrypted with Android Keystore AES-GCM.
- The app-side relay accepts only the four predefined read-only Termux operation IDs. Every incoming job requires an in-app approval dialog; the app never auto-executes a polled job.
- Relay URLs reject credentials, query strings, and fragments; redirects are disabled; response bodies are bounded; command IDs are validated before use in request paths; the client stores a bounded local handled-ID set.

## Remaining threats and release gates
- Verify server-side job expiry, replay/idempotency protection, rate limits, and audit records in the deployed Worker.
- Verify credential revocation and token invalidation behavior on a physical Android device.
- Run a real HTTPS MCP server through connect, discovery, approval, tool execution, and disconnect.
- Inspect logs and error surfaces for credential or private-data leakage.
Until these gates pass, describe remote relay and physical-device behavior as implemented in code but not end-to-end verified.