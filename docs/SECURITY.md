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
- MCP `tools/list` follows opaque `nextCursor` values, rejects repeated cursors, and caps discovery at 100 pages / 5,000 tools. Modern list/discovery cache hints must use `public`/`private` scope and a non-negative integer `ttlMs`.
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
- Relay URLs reject credentials, query strings, and fragments; redirects are disabled; response bodies are bounded; command IDs and fixed operation IDs are validated before use.
- Android persists the active Relay command and Termux result locally before network delivery. GET polling and result submissions use bounded retries with exponential backoff; pairing is not retried. A missing Termux result callback times out after 90 seconds and reports an unknown outcome instead of silently re-running the command.
- Relay Worker source uses 15–30 second job leases with a 60-second job expiry, requires idempotency keys, accepts identical result retries, rejects conflicting results, applies route-specific rate limits, and updates audit status when results are accepted. These are source-level controls until deployed-worker verification passes.

## Remaining threats and release gates
- Verify the deployed Worker’s lease/retry, idempotency, rate-limit, and audit behavior end-to-end; source-level implementation alone is not deployment evidence.
- Verify credential revocation and token invalidation behavior on a physical Android device.
- MRTR input_required handling uses a user-reviewed JSON response dialog, opaque requestState pass-through, exact request-key matching, and a bounded continuation limit. Native schema-driven elicitation/tool argument forms and HTTPS URL-mode browser handoff are implemented for supported shapes. MCP Tasks task-handle persistence/revalidation and explicit user-triggered refresh/input/cancel UI are implemented in source; no background polling is enabled. Real-server interoperability remains pending.
- OAuth/CIMD is not a complete authorization flow: metadata URL derivation/validation helpers exist, but live metadata/CIMD fetch, AppAuth PKCE authorization, issuer/resource-bound token exchange, encrypted token refresh/revocation and end-to-end tests remain pending.
- Run a real HTTPS MCP server through connect, discovery, approval, tool execution, and disconnect.
- Complete a whole-repository tracked-source and history audit of logs, exception/error surfaces, crash paths and generated artifacts for credential/private-data leakage. The 2026-11-11 pass scanned all 22 changed Android production Kotlin files for direct exception-message rendering, logging/stack traces, raw response-body forwarding and obvious authorization concatenation; this is still not a whole-repository/history audit.
- Complete dependency/version/license and software-supply-chain review. The Cloudflare Worker package currently uses semver ranges for devDependencies and no package-lock.json/npm-shrinkwrap was found at the audited PR head; exact transitive dependency reproducibility and license inventory are therefore not yet established.
- Provider HTTP POST requests are not automatically retried. If cancellation occurs after dispatch but before an HTTP response, the app records an unknown-outcome marker; that marker stores only a boolean and timestamp and does not reconcile provider-side execution.
Until these gates pass, describe remote relay and physical-device behavior as implemented in code but not end-to-end verified.
## 2026-11-11 current-head security implementation notes

- AI provider profiles are stored separately using Android Keystore AES-GCM with profile-ID authenticated additional data. Source-level implementation and JVM tests do not substitute for Android Keystore runtime/device verification.
- AI tool execution is routed through the exact connected MCP service session. Unknown tools, malformed arguments, disconnected tools and unsupported result types fail closed; approval-required calls require a one-time, argument-bound grant after explicit user confirmation.
- MCP task recovery persists bounded identifiers/status/timing metadata only. Restored pending records are untrusted candidates and are revalidated with the remote server before use. Task result, input request payloads, credentials and provider responses are not persisted by the task metadata store.
- The AI request recovery marker persists only a pending flag and timestamp. It is a review reminder, not evidence of vendor execution or a provider reconciliation mechanism.
- Exact-head source/CI findings are recorded in docs/CURRENT_HEAD_AUDIT_2026-11-11.md. No live provider, real MCP server, physical device, or deployed Worker security verification is claimed.


## 2026-11-11 redaction and CI follow-up
- AI tool execution errors returned to providers use a fixed redacted message rather than arbitrary MCP/server/library exception text.
- MainActivity and PluginDashboardPage status surfaces use a fixed UI-safe error message. The Termux bridge no longer concatenates raw SecurityException or IllegalStateException messages into operation errors.
- Regression tests assert raw simulated MCP exception text and bearer-like values are not reflected in redacted output.
- Exact source commit `d162a314ac0520d5511dd06ad751b9ce163e3c3b`: Android CI `42970919165` PASS (unit tests, debug APK assembly and upload); Worker CI `42970919190` PASS.
- Artifact `13832594882`, SHA-256 `30f9dfe103e9003f6a6af8bc50d40da20d7e368b36d582064666e45150e6a76d`; CI artifact only, not installed.
- These checks do not close live provider/MCP, physical Android, deployed Worker, OAuth/CIMD, full repository/history, dependency/license, or independent security-review gates.


### Expanded Android source error-surface pass — 2026-11-11
- Expanded the static pattern scan to all 35 tracked Android production Kotlin files.
- Four additional exception-origin paths were found in the Android Keystore diagnostic, app self-test, permission settings fallback and module lifecycle failure registry.
- Those paths now store/display the shared fixed redacted message; module lifecycle tests now assert redaction rather than the raw thrown message.
- Source commits: `14c460e97b439ceb420bd2611c5f2f4a7611c7db`, `c76254451b91cf17c18146d33c96fb31a3990ddf`, `6222ebaa78efabb2a62a728709dc4e075af33242`, `99ce8ec7309bc417b4fa05f4948bceffc1eb5205`; test commit `b465c948d47129b6d3df1d77f6b548ad0353e7fc`.
- Selected patterns found no remaining direct exception-message, logging, stack-trace, raw response-body or obvious authorization-concatenation matches in the newly inspected files after fixes.
- This does not close a semantic full-repository/history audit or dependency/license, crash-report, runtime-log and deployed-service review. Android CI for the latest source/test commit is pending.
