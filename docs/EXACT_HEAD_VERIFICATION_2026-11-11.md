# Exact-Head Verification Addendum — 2026-11-11

**Repository:** `mosharrof0000-ux/mosharrof-android-control`  
**PR:** #7 — [open, unmerged](https://github.com/mosharrof0000-ux/mosharrof-android-control/pull/7)  
**Branch:** `fix/mcp-pagination-20261110`  
**Exact source head:** `ba0f1db6add35ef5c202e5dc82a740701f95dc25`

This addendum supersedes older pending/in-progress CI statements in the historical audit entries for earlier heads. It does not claim physical-device, live-provider, real-MCP-server, deployed-Worker, or independent security verification.

## Exact-head CI

| Check | Run ID | Result | Verified scope |
|---|---:|---|---|
| Android | `42975815907` (#351) | PASS | Android unit tests, Debug APK assembly, artifact upload |
| Cloudflare Worker | `42975815785` (#307) | PASS | Worker CI/typecheck |

Android job `assemble` completed successfully. Its test, APK assembly, and artifact-upload steps each reported success.

## CI artifact

- Name: `mosharrof-android-control-debug`
- Artifact ID: `13835885346`
- Size: `2,417,093` bytes
- SHA-256: `7b27e61676eb75ed1fa5b6dc9056ca4861fbb512ba16c5e60ac8fcc94b444021`
- Artifact expiry reported by GitHub: 2027-02-09
- Workflow metadata binds this artifact to branch `fix/mcp-pagination-20261110` and exact head `ba0f1db6add35ef5c202e5dc82a740701f95dc25`.

This is a CI-produced debug artifact only. It has not been installed, launched, or tested on a physical Android device.

## Latest MCP Task input-response hardening

At this head, `McpTaskProtocol.kt` recursively bounds task input-response values:
- JSON nesting depth: maximum 16
- Aggregate value nodes: maximum 1,024
- Entries per object/array: maximum 128
- String length: maximum 8,192 characters
- Allowed values: JSON-compatible null, Boolean, String, finite/valid Number, Map and List
- Unsupported object types and over-limit structures are rejected.

Regression coverage includes valid nested values and rejection of excessive depth, oversized strings/collections, aggregate node overflow, non-finite numbers and unsupported object types. This is structural client-side validation; server-defined semantic validation and real MCP interoperability remain authoritative/unverified.

## PR state and remaining gates

PR #7 remains **OPEN, UNMERGED** and GitHub reports it mergeable. No merge was performed.

Still not verified/performed:
- Full semantic repository and Git-history security audit; the tracked Android Kotlin pattern scan is not a semantic proof.
- Reproducible npm/Gradle dependency locking, complete transitive SBOM, license attribution and vulnerability review.
- OAuth/CIMD discovery, PKCE, token exchange, encrypted refresh/revocation lifecycle and live OAuth interoperability.
- Android Keystore runtime behavior, native UI transitions, Termux handoff and AccessibilityService behavior on a physical device.
- Live provider API round-trips, real MCP interoperability and deployed Worker behavior.
- Independent security review, APK installation, production deployment and PR merge.

No credentials or secrets were used or recorded in this addendum. No APK installation, live endpoint test, deployment or merge is claimed. PR merge and physical APK installation remain explicit-approval gates.


## Latest exact-head verification — 2026-11-11 (`c51ba5f6cb215a461bc6ec3b684276b47ed3f4b2`)

This section supersedes the older exact-head and pending-CI status above for the latest branch head.

- Branch: `fix/mcp-pagination-20261110`
- Exact source head: `c51ba5f6cb215a461bc6ec3b684276b47ed3f4b2`
- Android workflow run: [42979220470](https://github.com/mosharrof0000-ux/mosharrof-android-control/actions/runs/42979220470) — PASS.
- Worker workflow run: [42979220474](https://github.com/mosharrof0000-ux/mosharrof-android-control/actions/runs/42979220474) — PASS.
- Android job `assemble`: unit tests, debug APK assembly, and artifact upload all completed successfully.
- Worker CI/typecheck completed successfully.

### Exact-head CI artifact

- Name: `mosharrof-android-control-debug`
- Artifact ID: `13837350101`
- Size: `2,417,211` bytes
- GitHub artifact digest: `sha256:06d77b5ee3bde06af98913b55864f9ef6fbb25ba297d04fdf8717b23569d6899`
- Expiry: 2027-02-09
- GitHub workflow metadata binds the artifact to the exact branch and head above.

This is a CI-produced debug artifact only. It was not installed or launched on a physical Android device.

### Gate 6 — MCP task lifecycle polling regression

The task polling regression fix was verified by the Android unit-test job on this exact head. The poller now honors the server-provided polling interval without an artificial 30-second early-poll cap, while still stopping at the caller's remaining wait budget. An injected monotonic clock supports deterministic timing tests. The regression fixture uses a 60-second server interval and a 45-second maximum wait, asserting that only the remaining 45 seconds are slept before polling stops.

**Gate 6 code + automated regression/CI: COMPLETE.** Physical Android persistence/process-restart behavior remains a separate Gate 20 device-validation item; it is not represented as verified here.

### Gate 9 — provider capability verification audit

The provider-neutral capability model distinguishes `SUPPORTED`, `UNSUPPORTED`, and `UNKNOWN`, and `supports()` returns true only for an explicit `SUPPORTED` value. The built-in outbound adapters currently report tool calling, parallel calls, streaming, cancellation, JSON schema, and structured outputs as `UNKNOWN`; model discovery is reported as `UNSUPPORTED`. The provider catalog labels Gemini, OpenAI, xAI, and Anthropic adapters as `TRANSLATION_ONLY`, not live integrations.

Mock adapter/contract tests verify translation behavior, not vendor/model capability. No live capability discovery or credentialed provider round-trip was performed. Accordingly, Gate 9 remains **PARTIAL** until provider/model/API-version-specific capability evidence is obtained through official documented verification and authorized live tests where required.

PR #7 remains open and unmerged. No deployment, APK installation, live provider test, or merge is claimed.


## Latest exact-head verification — 2026-11-11 (`96d22594ed467bd1cf9e48fd0faebd9147864725`)

- Branch: `fix/mcp-pagination-20261110`
- Exact source head: `96d22594ed467bd1cf9e48fd0faebd9147864725`
- Android workflow: [42982088214](https://github.com/mosharrof0000-ux/mosharrof-android-control/actions/runs/42982088214) — PASS; unit tests, Debug APK assembly, and artifact upload all succeeded.
- Worker workflow: [42982088285](https://github.com/mosharrof0000-ux/mosharrof-android-control/actions/runs/42982088285) — PASS; Worker typecheck succeeded.

### Exact-head CI artifact

- Name: `mosharrof-android-control-debug`
- Artifact ID: `13837594028`
- Size: `2,422,195` bytes
- GitHub artifact digest: `sha256:e606ae59e43d1586c9f8a1487b319edfdbef4c53716cb79d3d9bc6a9f95261c5`
- Expires: 2027-02-09
- GitHub workflow metadata confirms branch `fix/mcp-pagination-20261110` and exact head `96d22594ed467bd1cf9e48fd0faebd9147864725`.

### Gate 10 — OAuth protected-resource / authorization-server metadata discovery

Added an HTTPS-only, bounded metadata fetcher with redirects disabled, finite connect/read timeouts, JSON content-type and structure limits, protected-resource metadata validation, authorization-server issuer binding, and CIMD metadata validation. The connector service exposes an explicit discovery operation and rejects unknown connectors and non-MCP transports before any network access. Regression tests cover successful metadata discovery, redirects, oversized bodies, invalid content type, CIMD validation, and the two service-level fail-closed cases.

This implements metadata retrieval and validation only. It does **not** implement OAuth authorization, PKCE, token exchange, token storage, refresh, revocation, or live OAuth interoperability. Gate 10 remains **PARTIAL** pending a complete user-initiated application flow and further protocol/security validation; Gate 17 remains incomplete.

This artifact is CI-produced only and has not been installed or launched on a physical Android device. PR #7 remains open and unmerged. No live provider credentials, live MCP servers, deployment, or merge were used/performed.


## Latest exact-head verification — 2026-11-11 (`f10b0f9a14da79cacc65311c29b2cd31006e32e2`)

This section records the OAuth metadata discovery UI integration and exact-head CI evidence. It supersedes earlier branch-head status sections above.

- Branch: `fix/mcp-pagination-20261110`
- Exact source head: `f10b0f9a14da79cacc65311c29b2cd31006e32e2`
- Android workflow: [42983020001](https://github.com/mosharrof0000-ux/mosharrof-android-control/actions/runs/42983020001) — PASS; Android unit tests, Debug APK assembly, and artifact upload all succeeded.
- Worker workflow: [42983020293](https://github.com/mosharrof0000-ux/mosharrof-android-control/actions/runs/42983020293) — PASS; Worker typecheck succeeded.
- PR #7 remains OPEN and UNMERGED.

### Exact-head CI artifact

- Name: `mosharrof-android-control-debug`
- Artifact ID: `13838138671`
- Size: `2,423,681` bytes
- GitHub artifact digest: `sha256:f31933b7dc305d285cd2d855ebc7e42d36ef5cdc8cc3c1d00fd0b0edd63abb16`
- Expiry: 2027-02-09
- GitHub workflow metadata binds the artifact to branch `fix/mcp-pagination-20261110` and exact head `f10b0f9a14da79cacc65311c29b2cd31006e32e2`.

This is a CI-produced debug artifact only. It has not been installed, launched, or tested on a physical Android device.

### Gate 10 — OAuth metadata discovery UI

The Plugin Dashboard now exposes an explicit **OAuth Metadata যাচাই (শুধু GET)** action before MCP Connect. It can register/use the configured MCP Streamable HTTP endpoint for metadata discovery, checks that an existing connector has the same endpoint and transport, avoids overlapping discovery requests, and scopes reuse of a connected service to the matching connector session. The operation performs metadata GET discovery only; it does not launch a browser/login, authorize a user, perform PKCE or token exchange, or store credentials.

The metadata fetcher remains HTTPS-only, disables redirects, applies finite timeouts and bounded JSON parsing, validates protected-resource and authorization-server metadata including issuer binding, and validates retrieved CIMD metadata. Unit and service regression tests passed in the exact-head Android CI run above.

Gate 10 remains **PARTIAL**. A complete user-initiated OAuth application flow and protocol/security validation are still outstanding; Gate 17 (PKCE, token exchange, secure token lifecycle, refresh and revocation) remains incomplete. No live OAuth server interoperability or provider credential was tested.

PR #7 remains open and unmerged. No APK installation, physical-device testing, deployment, or merge is claimed.
