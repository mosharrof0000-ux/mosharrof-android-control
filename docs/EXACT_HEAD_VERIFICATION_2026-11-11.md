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

## Latest verified PR-head addendum — 2026-11-11 (`d60c96649b99ceebb1fcf39074f8d03ad2be552`)

- PR #7 head: `d60c96649b99ceebb1fcf39074f8d03ad2be552`; PR remains OPEN and UNMERGED.
- Android CI run [43069263656](https://github.com/mosharrof0000-ux/mosharrof-android-control/actions/runs/43069263656): SUCCESS. The strict Gradle command ran `:app:testDebugUnitTest`, `:app:cyclonedxDirectBom`, and `:app:assembleDebug`; Gradle reported `BUILD SUCCESSFUL`. Binary notice scanner tests passed, all 34 SBOM runtime components were covered by the binary notice audit with no reported failures, APK notice validation passed, and the debug APK plus dependency evidence were uploaded.
- Worker CI run [43069263948](https://github.com/mosharrof0000-ux/mosharrof-android-control/actions/runs/43069263948): SUCCESS; locked dependency installation, npm audit, SBOM generation, typecheck/tests and dependency evidence upload passed.
- Android debug APK artifact ID: `13876819071`. Dependency evidence artifact ID: `13876684553`. These are CI artifacts only; no installation or physical-device test was performed.

### Gate 10 — OAuth metadata and CIMD discovery

At this exact PR head, the Android source includes bounded HTTPS-only protected-resource and authorization-server metadata discovery, issuer binding, redirect-disabled GET retrieval, response/content/depth/node limits, and CIMD metadata retrieval/validation. The Plugin Dashboard exposes a user-triggered metadata-only discovery action and a separate explicit CIMD validation/PKCE request preparation flow. Regression suites are present in `McpOAuthMetadataTest`, `McpOAuthMetadataFetcherTest`, and `McpOAuthAuthorizationRequestFactoryTest`; the exact-head Android unit-test task passed in run `43069263656`.

**Gate 10 implementation and automated-test scope: COMPLETE.** This does not mean live authorization-server/CIMD interoperability has passed. Live OAuth, token lifecycle, provider APIs, real MCP server tests, Android Keystore physical-runtime behavior, deployment, APK installation and merge remain separately gated. Gate 9 provider/model capabilities remain UNKNOWN unless specifically verified; no vendor capability is inferred from documentation alone.


## Latest exact-head verification — 2026-11-11 (`fb875863b1aa3a35079fe9d9f5129a3c24e27f86`)

- PR #7 branch head: `fb875863b1aa3a35079fe9d9f5129a3c24e27f86`; PR remains OPEN and UNMERGED.
- Android CI run [43072209300](https://github.com/mosharrof0000-ux/mosharrof-android-control/actions/runs/43072209300): SUCCESS. Exact Gradle command ran `:app:testDebugUnitTest`, `:app:cyclonedxDirectBom`, and `:app:assembleDebug` with `--dependency-verification=strict`; Gradle reported BUILD SUCCESSFUL. The binary notice audit covered 34/34 runtime SBOM components with zero reported failures, APK license/notice validation passed, and dependency evidence was uploaded.
- Worker CI run [43072209559](https://github.com/mosharrof0000-ux/mosharrof-android-control/actions/runs/43072209559): SUCCESS; locked dependency installation, audit, SBOM, typecheck/tests, dependency evidence upload and OSV scan completed successfully.
- Android debug artifact ID: `13879111294` (2,586,035 bytes; uploaded artifact ZIP SHA-256 `d7754fd64b419eb4210353624b759a398208aaf413ab66522cc8cc308ac01892`). Gradle dependency evidence artifact ID: `13878632831`.
- The exact-head Android tests include OAuth refresh commit-gate cases: successful Bearer persistence before release, rejected refresh, non-Bearer response, failed/thrown encrypted persistence, blank access token, and missing token type. Raw OAuth exception causes are no longer retained in refresh exceptions; safe reason-only errors are asserted by regression tests.

### OAuth refresh failure redaction

The refresh failure model now retains only a typed safe reason and user-facing message. The original authorization-server/storage exception is not attached as a cause, avoiding accidental propagation through stack traces or crash reporting. The refresh commit gate rejects authorization errors, blank access tokens and non-Bearer token types before persistence, and releases a Bearer token only after encrypted AuthState persistence succeeds.

This exact-head CI verifies JVM unit tests and build behavior, not live OAuth server interoperability, actual encrypted Android Keystore behavior on a device, token rotation/revocation against a real server, or crash-reporting behavior in production. The debug artifact is CI-only and was not installed or launched on a physical Android device. No deployment or merge was performed.


## Exact-head OAuth revocation regression verification — 2026-11-11 (`98fb0deb97627299211022ab09dbe6ab3516f66f`)

- PR #7 source/test head: `98fb0deb97627299211022ab09dbe6ab3516f66f`; PR remains OPEN and UNMERGED.
- Android CI run [43073118767](https://github.com/mosharrof0000-ux/mosharrof-android-control/actions/runs/43073118767): SUCCESS. The strict Gradle command ran `:app:testDebugUnitTest`, `:app:cyclonedxDirectBom`, and `:app:assembleDebug`; Gradle reported BUILD SUCCESSFUL. The OAuth revocation test additions are included in the successful Android JVM unit-test task.
- Android binary notice audit covered 34/34 runtime SBOM components; APK license/notice validation passed. Android OSV scan and SARIF upload completed successfully.
- Worker CI run [43073118888](https://github.com/mosharrof0000-ux/mosharrof-android-control/actions/runs/43073118888): SUCCESS; locked dependency install, dependency audit, SBOM, typecheck/tests, dependency evidence and OSV scan completed successfully.
- Android Debug APK artifact ID: `13879926314`, size 2,586,036 bytes, uploaded artifact ZIP SHA-256 `32c59324be498a7bc603e785d1a7e29cdaf46a852b1c12fb3ddfb26c9011102c`. Android dependency evidence artifact ID: `13879906381`. OSV SARIF artifact ID: `13879981470`.
- Added revocation regression cases reject endpoint userinfo/fragment before network access; reject blank/oversized client IDs and blank tokens before network access; and accept `access_token` token hint while verifying URL form encoding. Existing tests also cover HTTPS-only endpoints, redirect/non-success rejection, invalid token hints and token bounds.
- These are JVM/unit and CI checks, not live RFC 7009 server interoperability or Android Keystore physical-device verification. The APK is CI-only and was not installed or launched. No deployment or merge was performed.
