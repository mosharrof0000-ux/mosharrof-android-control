# Plugin System Execution Plan

This is the working completion ledger for PR #7 and the Android Plugin System. Status is evidence-based; source changes are not marked complete until their required tests pass.

## Repository work

| Workstream | Status | Acceptance gate |
|---|---|---|
| Multi-AI adapter implementation | Shared runtime, bounded transport, guarded tool orchestration, encrypted provider profiles, provider continuation and approval UI are integrated in source; exact-head mock/E2E CI verified at `8713269590dc3a1b9621d1abf66bbe7a15ea8bd0` | `AiEngine.askWithMcpApproval` selects the active encrypted provider profile and calls `AiProviderRuntime`; the runtime builds requests, decodes responses, bounds continuation/tool calls, and delegates through `AiToolCallOrchestrator` to the exact connected MCP service session. Approval-required calls use the UI callback and one-time service grant. Mock tests cover Gemini Interactions, OpenAI Responses, xAI Responses, Anthropic Messages and Custom OpenAI-compatible flows. Live provider APIs, per-model capability verification and provider connection tests remain pending. Provider model/API/adapter capability display and hidden credential replacement/removal UX exist in source; capability UNKNOWN is not a live vendor test. |
| AI-provider-neutral adapter architecture | Architecture and implementation checklist recorded | `docs/AI_PROVIDER_ADAPTER_ARCHITECTURE.md` and `docs/MULTI_AI_ADAPTER_IMPLEMENTATION_PLAN.md` define the common contract, provider capability profiles, inbound MCP server bridge, API-change workflow and release gates. Grok and Claude outbound translation adapters exist; Microsoft remains an inbound MCP/connector route, not a generic outbound model API. |

| Workstream | Status | Acceptance gate |
|---|---|---|
| Connector registry and least-privilege authorization | Implemented; source audit and regression coverage pass | Unavailable connectors fail closed; READ_ONLY is the only no-approval risk; WRITE, EXTERNAL_SIDE_EFFECT and DESTRUCTIVE require approval; schema snapshots are defensive copies; Android CI run 42809461736 passed |
| Single-use, argument-bound approval grants | Implemented; recursive input bound added and CI verified | Expiry, replay, mismatch, revoke, capacity, timestamp overflow and deep-input tests; Android CI run 42804238420 passed |
| MCP 2026-07-28 discovery and tools/list pagination | Implemented in source | Current-head Android tests pass; real server interoperability remains separate |
| MCP 2026-07-28 MRTR request envelope | Implemented in source | Input request validation and request-state round-trip tests pass |
| MRTR user interaction | Implemented as manual JSON entry | Android build passes; real-device UX and server tests remain |
| Native schema-driven elicitation forms | Implemented for one flat form-mode elicitation request; complex/multiple requests fall back to manual JSON | Explicit accept/decline/cancel, schema validation and user approval; Android unit tests + APK build pass at b51df1e40c6d89f976b92b9deec4f0072c4c379c; run 42805267132 |
| Native schema-driven tool argument forms | Implemented for flat string/number/integer/boolean/enum fields; complex schemas fall back to JSON | Android unit tests and APK build pass at commit c5ff3c61b830c7f0416e0cbc4c9f9840f42caab9; run 42804709728 |
| Elicitation URL mode / browser handoff | Implemented and Android CI verified | HTTPS-only URL validation, external browser launch, explicit user response remains required; Android CI run 42803567809 passed |
| MCP Tasks extension | Partial client lifecycle API with regression tests; not advertised or enabled | Added get/update/cancel with response taskId binding; polling counts initial fetch within maxPolls, bounds time/polls/delay and stops at input_required/terminal; tests cover all three operations, disconnected/empty/mismatched IDs, terminal polling and malformed snapshots; Android CI run 42809461736 passed. Capability negotiation and real-server interoperability remain pending; durable task metadata recovery and explicit user-facing input_required/task refresh/cancel UI now exist in source and have Android CI coverage |
| OAuth 2.1 / CIMD | Authorization flow, CIMD fetch/validation, AppAuth S256 PKCE, callback checks, RFC 8707 resource-bound token exchange, encrypted AuthState, per-request async refresh, and RFC 7009 revocation are implemented in source with JVM regression coverage; exact-head CI passed at `0bb1cf53b3c79b20292dc68fa1b03e55eaac9098` | Android run `43051676040` passed unit tests, strict dependency verification, APK assembly, license-notice validation and dependency evidence upload; Worker run `43051675862` passed install, npm audit, SBOM generation and typecheck. Live authorization-server/CIMD/MCP interoperability, token rotation/revocation against a real server, Android Keystore runtime behavior and physical-device QA remain pending |
| Relay Android client recovery | Implemented in source; audit ongoing | Current-head unit tests and physical-device QA |
| Relay Worker leases/idempotency/rate limits | Implemented in source; edge/security regression tests pass | Worker tests cover idempotency, leases/retry, result acceptance/replay/conflict, expiry, rate limiting, auth, operation allowlist, malformed results and secret-safe health output; Worker CI run 42809461635 passed; deployed Worker still needs live verification |
| OpenAPI/Worker/Android contract consistency | Audit pending | Contract tests and generated/import validation |
| Credential/log leakage review | Partial focused audit only; whole-repository/history audit pending | HTTP error bodies are not copied into exception messages; AI/UI/Termux error surfaces use fixed redacted messages; encrypted secret envelopes are capped at 48,000 characters before Base64 decoding. Changed Android production Kotlin files received a source-pattern scan, but complete tracked-source/history, dependency/license, runtime-log and crash-report review remain pending. Android Keystore instrumentation remains pending |
| MCP argument traversal bounds | Implemented and CI verified | Validation and approval fingerprinting cap depth at 32 and nodes at 2,048; Android CI run 42804238420 passed |
| AI request/response resource bounds | Implemented and CI verified at 73df7108318f84d5684cbed367970254b395d5ba | API key ≤8,192 chars, model ≤128 chars, prompt ≤64 KiB, response ≤256 KiB; provider tool arguments bounded to depth 32 / 2,048 nodes; Android CI run 42825622524 passed |
| Documentation accuracy | Current-head addendum added; older historical notes explicitly superseded where stale | docs/CURRENT_HEAD_AUDIT_2026-11-11.md records source findings and exact-head CI. This ledger and README are being reconciled; documentation commits require their own exact-head CI before they can be called verified |

## External verification gates

- Physical Android install and real-device testing require the user's explicit approval.
- Termux integration requires the user to enable Termux RUN_COMMAND permission and the external-app setting.
- Cloudflare deployment changes the live Worker and must not run without explicit approval.
- ChatGPT connector configuration and a real relay round-trip require live credentials and user-side setup.
- Each AI provider integration requires its own official API/documentation review, adapter, credentials where applicable, and live interoperability verification. A provider without a suitable documented interface remains unsupported until the vendor exposes one or an approved alternative exists.
- PR #7 must remain open until the user explicitly approves merge; no release is authorized by this plan.

## Rules

- Do not report a feature complete from code inspection alone.
- Record the exact commit SHA and CI run for every verified build/test gate.
- Keep protocol versions distinct: modern 2026-07-28 behavior must not be conflated with legacy 2025 protocol behavior.
- Never claim full MCP conformance while live Tasks/OAuth interoperability, schema-driven forms, or URL-mode handoff verification remain incomplete.
- Never claim multi-provider support based only on a generic architecture document; verify each provider/model/API version and its capability profile separately.
- Follow `docs/MULTI_AI_ADAPTER_IMPLEMENTATION_PLAN.md`; implement the canonical contract before provider-specific adapters, and track outbound AI API adapters separately from the inbound MCP server/bridge.


## 2026-11-10 implementation log — AI tool-call orchestration foundation

- Added `AiToolCallOrchestrator.kt` as a policy-aware boundary between provider-generated canonical calls and the existing connector runtime.
- Added bounded batch planning (1–32 calls), duplicate provider-call-ID rejection, and call-order preservation.
- Each call is resolved through `CanonicalToolCallGuard`; unknown, ambiguous, schema-invalid, disconnected, or unauthorized tools are denied.
- Execution rechecks tool identity, connector availability, schema and risk immediately before dispatch.
- Approval-required calls remain pending until the caller supplies a grant from an explicit user-approval flow. The orchestrator never issues grants.
- Read-only calls reject unexpected grants. Execution is one call at a time to avoid unsafe partial execution of a mixed parallel batch.
- Tool output and failure text are bounded to 64 KiB; failures are returned as error tool results for provider continuation.
- Added `AiToolCallOrchestratorTest.kt` covering read-only execution, approval gating, grant forwarding, disconnected/schema-invalid denial, permission recheck, unexpected grants, bounded output, safe errors, batch bounds, duplicate IDs and ordering.
- **Status: source and tests added; Android CI passed at PR head 1b668ede28ff3f7641a20343b3627be8991d4dff (run 42819796245).** This is an orchestration foundation, not yet wired into `AiEngine` or the Android approval UI. The caller must connect its executor to `McpConnectorService.callTool` and obtain grants only after user confirmation.


## 2026-11-10 implementation log — provider request construction

- Added `AiProviderRequestFactory.kt` to build fixed-origin HTTPS first-turn requests for Gemini Interactions, OpenAI Responses, xAI Responses and Anthropic Messages.
- Uses each provider's documented authentication header; credentials are never placed in URL query strings or request bodies.
- Sets provider-specific request bodies and disables parallel tool calls for OpenAI/xAI/Anthropic to align with the current one-call-at-a-time execution boundary.
- Requests provider-side storage off where the API supports it (`store=false`); Anthropic Messages remains stateless.
- Anthropic API version is pinned to the currently documented `2026-10-21`.
- Added request-factory tests for endpoints, headers, continuation/tool options, prompt/key bounds, unsupported providers and credential redaction in diagnostic string output.
- **Status: source and tests added; Android CI passed at PR head 1b668ede28ff3f7641a20343b3627be8991d4dff (run 42819796245).** This is request construction only: actual HTTP transport, stored credential lookup, AiEngine integration and provider conversation continuation are still pending.


## 2026-11-10 current-head verification and integration gaps

- Current audited PR head: `1b668ede28ff3f7641a20343b3627be8991d4dff`.
- Android CI run `42819796245`: SUCCESS. Its `assemble` job completed Android unit tests, debug APK assembly and artifact upload successfully.
- Cloudflare Worker CI run `42819796066`: SUCCESS.
- Added shared `AiProviderHttpTransport` with HTTPS/header validation, redirects disabled, bounded request/response bodies, timeouts and retry classification. It has fake-connection tests but is not called by `AiEngine` yet.
- Added `AiToolCallOrchestrator` with guarded bounded execution planning and explicit grant forwarding. It is not connected to `AiEngine`, `McpConnectorService.callTool` or the native approval UI.
- `AiEngine` remains a two-path legacy text client (Gemini generateContent and generic OpenAI-compatible Chat Completions). It now uses the shared bounded HTTP transport for its legacy text requests, but still bypasses the new provider request factory and four provider adapters. Four provider adapters are translation foundations only.
- Gemini Interactions, OpenAI Responses, xAI Responses and Anthropic Messages continuation need a provider-aware conversation loop. The request factory currently creates first-turn requests only; continuation IDs/history are not retained or replayed.
- AI credentials remain a single active encrypted AiEngine key, not independent per-provider profiles. Provider setup/test-connection UI is pending.
- MCP Tasks are API-level/in-memory only; durable recovery and user-facing input-required flow are pending. Real MCP server, live AI-provider round-trips, physical Android QA, APK install and deployment remain external gates.
- ToolHub/master registry is maintained on the user's Termux device and cannot be updated through this repository connection; no claim of a local ToolHub update is made.


## 2026-11-10 legacy AiEngine transport consolidation

- `AiEngine.ask` now routes its existing Gemini generateContent and OpenAI-compatible Chat Completions requests through `AiProviderHttpTransport`, removing its separate direct `HttpURLConnection` implementation and duplicate JSON request construction.
- This does not yet enable Gemini Interactions, official OpenAI Responses, xAI Responses or Anthropic Messages in the UI. The new request factory, provider adapters, per-provider credential profiles, provider-specific conversation continuation and tool-call execution loop remain pending integration.
- Fresh CI verification is pending for commit `d006ddd2f64a63590d944cb379990429af95af74` and subsequent documentation updates. Earlier passing CI at `1b668ede28ff3f7641a20343b3627be8991d4dff` does not verify this new source change.


## 2026-11-10 verified provider transport and argument hardening

- Latest verified PR head: `73df7108318f84d5684cbed367970254b395d5ba`.
- Android CI run `42825622524` passed: Android unit tests, debug APK assembly and artifact upload all succeeded.
- Cloudflare Worker CI run `42825622132` passed.
- CI artifact `mosharrof-android-control-debug`, artifact ID `13768487632`, size 2,362,235 bytes; artifact is build output only and has not been installed or physically tested.
- `AiEngine.ask` now uses the shared bounded HTTPS transport for its legacy Gemini generateContent / generic OpenAI-compatible Chat Completions requests. Direct `HttpURLConnection` usage and duplicate legacy request-body construction were removed. Custom endpoints reject URL query strings, userinfo and fragments.
- Provider-generated tool arguments now have canonical depth/node/key/string bounds, and recursive JSON conversion is bounded. Regression tests for deep and oversized arguments passed in Android CI.
- The four new provider adapters and request factory are still not integrated into the UI/engine. Per-provider credential profiles, provider-specific continuation/history, tool orchestration and native approval UI wiring remain pending.


## 2026-11-10 current PR-head verification

- Current PR #7 head: `0252670029a7bddf8ae835df601915323edf7305`.
- Android CI run `42826033690`: SUCCESS on the exact current head. Unit tests, debug APK assembly and artifact upload all passed.
- Cloudflare Worker CI run `42826033812`: SUCCESS on the exact current head. Dependency install and type-check passed.
- Current debug APK artifact: `mosharrof-android-control-debug`, artifact ID `13768837277`, size 2,362,239 bytes. No physical installation/device QA was performed.
- No merge, deployment, credentialed live-provider test or physical-device installation was performed.

## 2026-11-11 authoritative current-head reconciliation

The historical implementation logs above are retained as a record of what was true at their stated commits. They are not the current status when contradicted by the later source audit. For the current implementation snapshot, use docs/CURRENT_HEAD_AUDIT_2026-11-11.md and the exact commit/CI evidence recorded there.

- The shared AI runtime is wired through AiEngine.askWithMcpApproval; it uses the active encrypted provider profile, bounded provider HTTP transport, provider-specific request/response/continuation handling, guarded MCP orchestration, and the explicit approval callback. Mocked provider E2E tests are not live API verification.
- Provider profile add/select/delete and encrypted storage exist. Model/capability display and credential replace/revoke UX remain incomplete.
- MCP task metadata recovery and explicit user-triggered task UI are implemented in source; pending restored tasks are revalidated with the remote server. No background polling is enabled. Live MCP Tasks interoperability remains unverified.
- AI request cancellation records a minimal unknown-outcome marker. This is not reconciliation with provider-side execution state.
- OAuth/CIMD now has an integrated source flow and mocked regression coverage at the 2026-11-11 head; live authorization-server, Android Keystore and real MCP interoperability remain unverified.
- The exact-head Android CI run 42966451646 and Worker CI run 42966451641 passed on 8713269590dc3a1b9621d1abf66bbe7a15ea8bd0. A later documentation-only commit must be independently checked if CI is expected to validate the updated documentation.
- No live provider API, real MCP server, physical Android device, deployed Worker, APK installation, or PR merge is claimed.

### 2026-11-11 provider profile UX follow-up

- Added credential-only replacement for an existing provider profile. It keeps provider/model/endpoint metadata and active selection unchanged; invalid replacement input is rejected before storage is touched.
- Added MainActivity profile action to replace the hidden credential without displaying the previous key. Existing profile deletion remains the credential revoke/remove action.
- Added per-profile model/API-version/adapter-version capability summary. Each capability is shown as SUPPORTED, UNSUPPORTED or UNKNOWN; UNKNOWN is not promoted to a positive claim. This is adapter metadata, not a live vendor test.
- Added repository regression tests for credential replacement, active-profile/metadata preservation and invalid replacement rejection.
- Source commit `fad2354c48d96f0f2a2c2e82eb6842a19e328814`: Android CI run `42968929411` PASS (unit tests, debug APK assembly and upload); Worker CI run `42968929491` PASS. Artifact ID `13832821191`, size `2,415,440` bytes, SHA-256 `035720631ab1cfd2fc8ce73141e10f07f53f41bc818fb06e8ff811561d8251c0`. CI artifact only; not installed.


## 2026-11-11 latest exact-source CI and security redaction
- Latest source commit: `d162a314ac0520d5511dd06ad751b9ce163e3c3b`.
- Android CI run #316 / `42970919165`: PASS — unit tests, debug APK assembly and artifact upload.
- Worker CI run #272 / `42970919190`: PASS.
- Debug artifact ID `13832594882`, 2,415,618 bytes, SHA-256 `30f9dfe103e9003f6a6af8bc50d40da20d7e368b36d582064666e45150e6a76d`; CI artifact only, not installed.
- Error redaction now covers AI tool error results sent to providers, Android UI status messages and Termux bridge exception paths. The regression suite verifies the fixed redacted provider error and rejects raw simulated exception text.
- Remaining external gates: live provider/MCP interoperability, OAuth/CIMD completion, Android Keystore and physical-device verification, deployed Worker verification, complete repository/history/dependency audit, PR merge and APK installation.


## 2026-11-11 latest PR-head status correction

At PR head `8f0d2bb4a7cd0fd318e70f01cd26eb359c859afd`, provider capability display and credential replacement/revoke UX are implemented in source. Capability values marked UNKNOWN remain unverified; this is not live vendor discovery. Error redaction includes AI provider tool errors, Android UI status surfaces, Termux bridge, and the additional diagnostic/permission/module lifecycle exception paths, with regression tests. Android CI run `42972244593` and Worker CI run `42972244607` both passed on this exact head. The current CI artifact is ID `13834056036`, SHA-256 `645aca2aca037d4ae7bbaae51433cfc346a27ed0a043589dd1e504148674e0cb`; not installed.

Dependency/license review is not complete: Worker dependencies still use semver ranges and no lockfile is in the PR changed-file inventory; no full transitive SBOM/license/vulnerability evidence exists. Physical Android QA, live providers/MCP, OAuth/CIMD, deployed Worker, independent security review and merge/install approvals remain pending.


## Latest exact-head verification — 2026-11-11 (`9ec21e6ad82d95f2f060da4481ab9b6de895968c`)

- Android workflow run `42973156641` (run #332): Android unit tests PASS; Debug APK assembly PASS; artifact upload PASS. Job cleanup was still running when inspected; all three build/test/upload steps completed successfully.
- Worker workflow run `42973156667` (run #288): PASS.
- Artifact `mosharrof-android-control-debug`, ID `13833758820`, size `2,415,482` bytes, SHA-256 `2c2acc4fcfada40c741e85e8f5cb2cf5cb3a94145dcdd8531596fc40e951e59d`. CI artifact only; not installed or physically tested.
- Added `cancellationDuringApprovalPreventsMcpExecutionAndContinuation`: cancellation from the approval callback causes cancellation failure, exactly one initial provider request, one approval callback, and zero MCP tool calls.
- Added `rejectsMalformedProviderHistoryBeforeBuildingContinuation`: a malformed Responses API history item is rejected before continuation request construction.
- Remaining release blockers are unchanged: UI transition/real-device coverage, OAuth/CIMD, live MCP/provider tests, deployed Worker verification, reproducible dependency locking, full transitive SBOM/license/vulnerability review, independent security review, and explicit merge/install authorization.


## 2026-11-11 authoritative branch-head update — `0bb1cf53b3c79b20292dc68fa1b03e55eaac9098`

- PR #7 remains open and unmerged. This section supersedes earlier OAuth/CIMD and dependency-audit status rows where they describe older source heads.
- OAuth source now includes bounded RFC 9728/RFC 8414 metadata discovery, CIMD HTTPS fetch and validation, native public-client AppAuth authorization-code + S256 PKCE, encrypted pending-request storage, callback/state/request binding checks, RFC 8707 resource-bound token exchange, AndroidKeyStore-backed AuthState persistence, serialized per-connector refresh, per-request async token resolution across connect/negotiation/pagination/tool/task operations, OAuth-only synchronous transport rejection, and explicit RFC 7009 revocation. Unit/mock coverage exists; this is not live interoperability evidence.
- Exact-head CI: Android run `43051676040` completed successfully. Its job passed committed Gradle lock/checksum verification, Android unit tests, CycloneDX SBOM generation, license inventory generation, debug APK assembly, packaged-license validation, and dependency evidence upload; the OSV scan job also completed successfully. Worker run `43051675862` completed successfully, including `npm ci`, `npm audit --audit-level=high`, CycloneDX SBOM generation, typecheck and dependency evidence upload; its OSV scan job completed successfully.
- CI evidence establishes build/test and scanner execution only. Do not describe this as a complete vulnerability, legal-license, live OAuth, or supply-chain clearance without reviewing the generated evidence artifacts and findings.
- Remaining external gates: real authorization-server/CIMD login and token refresh/revocation; real MCP server interoperability; Android Keystore and AppAuth browser callback behavior on a physical device; deployed Worker round-trip; independent semantic security review; and explicit approval before PR merge, deployment, or APK installation.


## Latest exact-head status — 2026-11-11 (`a4da4a3f2e57ab03e451cdf0c12fb57b519c0af1`)

- PR #7 remains open/unmerged.
- Android CI run `43055759358`: SUCCESS, including strict Gradle dependency verification, unit tests, CycloneDX SBOM, runtime license inventory, APK assembly and packaged license notice validation. Android OSV scan checked 167 app lock packages, 192 buildscript lock packages and 409 Gradle verification metadata packages; log reports no issues found.
- Worker CI run `43055759511`: SUCCESS.
- CI debug APK artifact `13870753793` downloaded and archive inspected; APK SHA-256 `301ecabe62cdd3501d3f10e611c4e00195ec07fa6ff89996dcf240d0ca120f2e`. Not installed and not physical-device tested.
- OAuth implementation has source-level RFC 9207 issuer binding in the callback path (pending issuer/capability binding and fail-closed validation); this remains automated/source evidence, not live authorization-server interoperability proof.
- Remaining gates: inspect dependency/license evidence beyond runtime dependencies; live OAuth/CIMD authorization, refresh/rotation and revocation; real MCP interoperability; Android Keystore/AppAuth callback on physical device; deployed Worker round-trip; independent security review. No merge, deployment or install.
