# Multi-AI Plugin Adapter Implementation Plan

## Goal
Implement a working, provider-neutral plugin platform. A new AI provider should be onboarded by adding and testing an adapter, not by rewriting plugin definitions, approval policy, Android device-control code, or the plugin runtime.

This is an implementation checklist. Distinguish implemented source/mock-test paths from live vendor verification; the authoritative current snapshot is docs/CURRENT_HEAD_AUDIT_2026-11-11.md. Earlier dated notes below are historical and are superseded where they conflict with that audit.

## Two integration directions
- MOSHARROF → AI provider (outbound): MOSHARROF calls an AI API and translates native tool/function-call responses into the canonical plugin runtime.
- AI provider/client → MOSHARROF plugins (inbound): an AI product with documented MCP-client or connector support connects to a secured MCP/HTTP bridge exposing approved MOSHARROF tools.

An outbound API adapter does not automatically make MOSHARROF an MCP server. These directions must be implemented and tested separately.

## Phase 0 — Baseline and canonical contract
- [x] Inventory current AI code, MCP client, relay, connector registry, approval grants, secret storage, UI and tests (source inventory recorded in docs/CURRENT_HEAD_AUDIT_2026-11-11.md; live integrations remain separate).
- [ ] Record provider-specific assumptions. Current AiEngine supports Gemini generateContent text requests and a generic OpenAI-compatible chat-completions format; this is not a complete multi-provider tool-calling layer.
- [x] Define initial canonical models: ProviderId, ModelId, ProviderConfig, CapabilityProfile, PluginDefinition, CanonicalToolCall and CanonicalToolResult. CanonicalError, stream event and cancellation contracts remain pending.
- [ ] Define a versioned canonical JSON Schema subset.
- [ ] Define lifecycle: proposed → validated → approval-pending → approved/denied → executing → completed/failed/cancelled.
- [ ] Preserve call IDs and argument integrity across conversions; reject ambiguous or malformed payloads.
- [ ] Define compatibility and migration rules before changing current MCP and AiEngine contracts.
**Gate:** canonical contract tests pass; provider-specific payloads do not leak into core runtime.

## Phase 1 — Core adapter framework
- [x] Add initial AiProviderAdapter interface for provider identity, configuration validation, capability profiles, tool-definition translation, tool-call parsing and result continuation. Model listing, streaming, cancellation and normalized transport errors remain pending.
- [x] Add AiProviderRegistry with duplicate rejection, adapter version and built-in registration for Gemini Interactions, OpenAI Responses, xAI Responses and Anthropic Messages. Provider health/status persistence remains pending.
- [x] Add capability profiles with SUPPORTED / UNSUPPORTED / UNKNOWN per feature, model and API version; initial profiles are conservative and leave unverified capabilities UNKNOWN.
- [x] Project ConnectorRegistry tools into a provider-neutral ToolCatalog snapshot.
- [ ] Add schema conversion; unsupported constraints must be rejected or explicitly explained, never silently dropped.
- [x] Add common bounded HTTPS transport: redirects disabled, 20s connect / 60s read timeouts, 512 KiB request and 256 KiB response caps, retry classification, redacted diagnostics, no automatic POST retries, and fake-connection regression tests. The legacy AiEngine text path now uses this transport; the four new provider adapters are not yet wired into AiEngine.
- [x] Add provider-specific first-turn HTTP request construction for Gemini Interactions, OpenAI Responses, xAI Responses and Anthropic Messages, with fixed HTTPS origins, provider auth headers, bounded prompt/credential values and credential-redacted diagnostics. These four provider requests are not yet wired into AiEngine; the existing legacy Gemini/OpenAI-compatible text path does use the shared transport.
- [ ] Store provider/account credentials separately using Android Keystore; never log secrets.
- [ ] Keep ConnectorRegistry authorization and ToolApprovalGrants as the final authority for every adapter.
- [x] Add initial shared adapter contract and Gemini/OpenAI wire-format tests; broaden with fuzzing, malformed payload and live fixtures.
**Gate:** fake adapter passes contract suite; malformed provider responses cannot bypass validation or approval.

## Phase 2 — Safe tool execution loop
- [ ] Convert canonical plugin definitions to each provider's native tool declaration.
- [x] Add bounded canonical provider tool-call parsing with call-ID preservation and duplicate rejection in the adapters; end-to-end AiEngine integration remains pending.
- [x] Validate tool name, connector state, schema, argument size/depth and risk before execution through CanonicalToolCallGuard.
- [x] Add a fail-closed CanonicalToolCallGuard that resolves provider tool names against registered MOSHARROF tools, validates arguments against the registered schema, rejects ambiguous/unknown/disconnected tools, and separates READ_ONLY from approval-required risks. The guard never issues grants or executes tools.
- [x] Connect approval-required decisions to the user-facing confirmation callback and issue an exact-argument, single-use grant only after explicit approval for WRITE, EXTERNAL_SIDE_EFFECT and DESTRUCTIVE; exact-head mocked E2E tests cover approved/declined continuations.
- [x] Add AiToolCallOrchestrator bridge for guarded execution through the exact connected MCP service session; runtime/UI wiring is integrated in source and mock-tested. Arbitrary provider-supplied code is never executed.
- [x] Add provider-native result translators for Gemini Interactions, OpenAI Responses, xAI Responses and Anthropic Messages. End-to-end continuation state/history orchestration remains pending.
- [ ] Handle parallel calls with bounded concurrency and separate approval per call.
- [x] Add bounded continuation rounds, total tool-call limits, request/response/prompt/argument size bounds and cancellation/unknown-outcome handling; provider-specific usage/quota accounting remains pending.
- [x] Add cancellation, timeout, bounded transport and unknown-outcome handling without automatic POST retries; provider-side reconciliation of uncertain outcomes remains pending.
- [ ] Add audit events with secrets and sensitive payloads redacted.
**Gate:** identical mock plugin behavior and approval outcomes across adapters; replay, duplicate, mismatch and timeout tests pass.

## Phase 3 — Provider adapters, one at a time
- [x] Google Gemini Interactions function_call/function_result adapter is the registered default; generateContent translator remains as a legacy compatibility adapter. Official reference: https://ai.google.dev/gemini-api/docs/function-calling. Safety/finish reasons, model-specific capability verification, streaming and live API round-trip remain pending.
- [x] Initial official OpenAI Responses API function-tool definition, function_call parsing and function_call_output continuation adapter added. Official reference: https://developers.openai.com/api/docs/guides/function-calling. Network orchestration, streaming, strict-schema normalization and live API round-trip remain pending.
- [x] xAI Grok Responses API function tool declarations, function_call parsing and function_call_output continuation translator is the registered default; Chat Completions translator remains compatibility-only. Official reference: https://docs.x.ai/developers/tools/function-calling. Authentication/network orchestration, streaming and live API round-trip remain pending.
- [x] Initial Anthropic Messages API tool-use/tool_result translation adapter added, including error results. Official references: https://platform.claude.com/docs/en/agents-and-tools/tool-use/how-tool-use-works and https://platform.claude.com/docs/en/agents-and-tools/tool-use/handle-tool-calls. Authentication/network orchestration, stop-reason handling, streaming and live API round-trip remain pending.
- [x] Microsoft integration feasibility assessed: Microsoft 365 Copilot declarative-agent plugins can expose MCP servers or REST APIs; Copilot Studio agents can use MCP, REST API and custom connectors. This is an inbound MCP/connector integration route, not a generic outbound model API adapter. Consumer Copilot must not be treated as a public general-purpose model endpoint. Official references: https://learn.microsoft.com/en-us/microsoft-365/copilot/extensibility/overview-plugins and https://learn.microsoft.com/en-us/microsoft-copilot-studio/add-tools-custom-agent.
- [ ] Implement Microsoft 365 Copilot declarative-agent plugin manifest and Copilot Studio setup package after the MOSHARROF MCP server/bridge passes its security and interoperability gates.
- [ ] Add provider setup/test-connection UI and secret-safe diagnostics.
- [ ] Add sanitized provider response fixtures.
- [ ] Run adapter contract tests and live API smoke tests when credentials and user authorization are available.
**Gate:** each provider is VERIFIED only after contract tests and a real endpoint tool-call round-trip pass. Unsupported features remain visibly unsupported.

## Phase 4 — Inbound MCP / connector bridge
- [ ] Audit whether current Android MCP client/Worker Relay can safely expose an MCP server; an MCP client is not an MCP server.
- [ ] Implement a separate MCP server/bridge exposing only explicitly enabled canonical plugins.
- [ ] Implement server-role protocol negotiation, tools/list pagination, tools/call, capability declarations and errors.
- [ ] Authenticate clients with revocable scoped credentials bound to user/device and permitted tools.
- [ ] Enforce Android-side approval for sensitive actions; bridge credentials must not grant blanket authority.
- [ ] Add bounded queues, expiry, replay/idempotency protection, rate limits, audit and reconnect recovery.
- [ ] Add setup instructions for AI products with verified MCP client/custom connector support.
- [ ] Test a real supported AI client through bridge → Android approval → plugin execution → result return.
**Gate:** a real client discovers and calls a harmless plugin; sensitive calls remain blocked until approved; revocation stops access.

## Phase 5 — Provider discovery and maintenance
- [x] Add an initial machine-readable AiProviderCatalog with official documentation links, integration direction, honest implementation status and runtime adapter-registration state. Persisted API/model versions, auth requirements, last review date and per-model verification evidence remain pending.
- [ ] Add provider onboarding template and capability matrix.
- [ ] Review official release notes/API changes; never auto-change production code based on untrusted web content.
- [ ] Change workflow: official-doc review → capability update → adapter update → regression fixtures → contract tests → live smoke test → feature-flagged release.
- [ ] Handle unknown response fields safely; deprecate old API versions with migration warnings.
- [ ] Record rollback version and credential/config migration steps.
**Gate:** a simulated API-version change is handled by updating one adapter without changing canonical plugin definitions or approval logic.

## Phase 6 — UX, observability and release
- [ ] Provider management UI: model/capability display, connection test, credential replace/revoke and explicit active-profile state. Add/select/delete profile flows exist; remaining UX is tracked in the current-head audit.
- [ ] Unified permission UI showing which provider can invoke which tools and their risk.
- [ ] Show every tool request, arguments, risk and approval result before sensitive execution.
- [ ] Add per-provider health, quota/rate-limit state and sanitized errors.
- [ ] Add localization and accessible error states.
- [ ] Run unit, contract, integration, security and regression tests for all adapters and both integration directions.
- [ ] Verify Android CI, Worker CI, static analysis and dependency/license review.
- [ ] Build APK in GitHub CI and verify artifact checksum/provenance.
- [ ] Physical install/device QA and live bridge deployment/client tests require explicit user approval.
- [ ] Update README, architecture, execution ledger and ToolHub/master registry with tested versions, commands, config paths, evidence and rollback.
**Release gate:** do not claim general multi-AI support until each named provider has independently passed its gates. Keep merge, deployment and installation pending explicit approval.

## Recommended implementation order
1. Phase 0 canonical contract.
2. Phase 1 adapter framework and contract tests.
3. Phase 2 safe execution loop.
4. Gemini and official OpenAI adapters.
5. xAI Grok adapter.
6. Anthropic Claude adapter.
7. Microsoft product-specific feasibility and adapter if a documented interface exists.
8. Phase 4 inbound MCP bridge.
9. Provider catalog, update workflow, UI and full QA.

Do not code five unrelated clients in parallel. Stabilize the common contract first, then implement adapters sequentially against the same tests.


## 2026-11-10 adapter compatibility update

- The registered xAI adapter now targets the vendor's current Responses API at `/v1/responses`; the earlier Chat Completions translator remains available only as a compatibility class and is not registered as the default xAI adapter.
- Added xAI Responses function-call parsing and `function_call_output` continuation translation tests. Actual `previous_response_id` network continuation remains pending until the HTTP orchestration layer is implemented.
- Updated Anthropic Messages adapter capability-profile validation to the currently documented API version `2026-10-21`; live request/response compatibility remains unverified.
- These are protocol translation updates only; provider credentials, AiEngine transport integration, conversation state and live endpoint verification remain pending.


- Gemini's Interactions API is now the registered default adapter because Google documents it as the recommended interface for new agentic/tool workflows; the generateContent translator remains available for legacy compatibility. The Interactions adapter accepts `v1beta` and `v1` profile versions and translates function_call/function_result steps. Server-side interaction ID continuation is not yet connected to the HTTP orchestration loop.


## 2026-11-10 verified source and CI audit update

- The latest PR head at audit time is `1b668ede28ff3f7641a20343b3627be8991d4dff`.
- Android CI run `42819796245` completed successfully: Android unit tests, debug APK assembly and APK artifact upload all passed. Cloudflare Worker CI run `42819796066` also passed.
- Added `AiProviderHttpTransport.kt`: shared bounded HTTPS POST transport, fixed HTTPS validation, header allowlist, redirect/cache disablement, 20s/60s timeouts, request/response caps, safe HTTP status classification, no automatic retries, and fake-connection tests.
- Added `AiToolCallOrchestrator.kt`: bounded call planning, duplicate ID rejection, guard recheck before execution, READ_ONLY vs approval-required separation, explicit grant forwarding, and bounded results. It is not yet wired into `AiEngine` or the Android approval UI.
- Current `AiEngine` remains the legacy Gemini generateContent + generic OpenAI-compatible Chat Completions text path. It does not yet call the shared request factory/transport, does not select the four registered provider adapters, and has no provider-specific tool-call continuation loop.
- Credential storage is still one active AiEngine key plus a separate plugin-token store; per-provider encrypted AI credential profiles and management UI remain pending.
- MCP task lifecycle APIs are bounded but in-memory; durable persistence/recovery and user-facing task input UI remain pending. Remote tools are currently registered as WRITE by default; this is conservative/fail-closed but leaves a read-only classification workflow pending.
- These CI results verify the source at the stated PR head only. They do not verify physical Android behavior, live provider APIs, real MCP server interoperability, or full multi-AI support.


## 2026-11-10 legacy transport integration

- `AiEngine.ask` now builds its existing Gemini generateContent / generic OpenAI-compatible Chat Completions request through `AiProviderHttpRequest` and sends it with `AiProviderHttpTransport`; the duplicate direct `HttpURLConnection` path and duplicate JSON request builder were removed.
- This is a transport consolidation only. The legacy two-provider text API is unchanged. The new Gemini Interactions, OpenAI Responses, xAI Responses and Anthropic Messages adapters and `AiProviderRequestFactory` are not yet integrated into `AiEngine`; tool execution and continuation remain pending.
- The new source commit requires a fresh Android CI run. The previously successful CI run `42819796245` applies to the earlier head `1b668ede28ff3f7641a20343b3627be8991d4dff`, not this code change.


## 2026-11-10 provider argument hardening and verified build

- Added `CanonicalArgumentBounds` to reject provider tool-call arguments deeper than 32 levels, wider than 2,048 visited nodes, keys longer than 256 characters, oversized string values and unsupported value types before the call reaches the authorization guard.
- Bounded recursive JSON object/array conversion in the Responses and legacy compatibility adapters so hostile nested JSON cannot recurse without a depth/node ceiling before canonical validation.
- Added regression tests for deeply nested JSON and oversized canonical argument trees.
- Consolidated the legacy AiEngine text path onto `AiProviderHttpTransport`; the new four-provider request factory and adapters remain unintegrated, and tool-call continuation remains pending.
- Android CI run `42825622524` passed on head `73df7108318f84d5684cbed367970254b395d5ba`: unit tests, debug APK assembly and artifact upload succeeded. Cloudflare Worker CI run `42825622132` passed on the same head.
- Debug APK artifact `mosharrof-android-control-debug` (artifact ID `13768487632`, 2,362,235 bytes) was uploaded by CI. It has not been installed on a physical device.


## 2026-11-10 latest-head CI verification

- PR #7 head `0252670029a7bddf8ae835df601915323edf7305` has Android CI run `42826033690` and Cloudflare Worker CI run `42826033812`; both completed successfully on this exact head.
- Android job `assemble`: `Run Android unit tests`, `Assemble debug APK` and `Upload debug APK` all succeeded.
- Worker job `typecheck`: dependency install and type-check succeeded.
- Debug APK artifact `mosharrof-android-control-debug`, artifact ID `13768837277`, size 2,362,239 bytes; artifact not installed on a physical device.
- This verifies CI/build for the current PR head. It does not verify live provider API round-trips, real MCP interoperability, or physical Android behavior.

## 2026-11-11 authoritative current implementation status

The source audit at 8713269590dc3a1b9621d1abf66bbe7a15ea8bd0 supersedes older dated entries above where they describe earlier architecture states:

- Integrated in source: AiEngine.askWithMcpApproval selects the active encrypted provider profile and routes through AiProviderRuntime; bounded HTTPS transport, request factory, provider response decoding and bounded continuation are connected.
- Integrated in source: AiToolCallOrchestrator uses the exact connected McpConnectorService session. Approval-required calls use the Android approval callback and one-time grant path; read-only calls do not receive write grants.
- Mock-tested provider flows: Gemini Interactions, OpenAI Responses, xAI Responses, Anthropic Messages and Custom OpenAI-compatible. This verifies local translation/continuation contracts only, not vendor API availability, credentials, model capability or live endpoint compatibility.
- Provider profiles: encrypted per-provider profiles and add/select/delete flows exist. Model/capability display, credential replace/revoke UX and live connection tests remain incomplete.
- MCP Tasks: task-handle persistence/revalidation and explicit refresh/input/cancel UI exist. There is no background polling. A real server must verify protocol interoperability.
- Cancellation: active HTTP cancellation and persistent unknown-outcome reminder exist. A reminder does not reveal whether the vendor executed the request.
- Still not implemented end-to-end: OAuth/CIMD authorization, inbound MOSHARROF MCP server/bridge, Microsoft declarative-agent packaging, physical Android QA, live provider API round-trips and deployed Worker verification.

Current-head exact CI: Android run 42966451646 and Worker run 42966451641 passed at the audited source head. Do not apply that evidence to later source changes; documentation-only commits do not alter the source but still need a current-head CI check for an up-to-date PR gate.


## 2026-11-11 authoritative current implementation reconciliation
This section supersedes older dated notes above wherever they describe current code as unwired or pending after the later implementation commits.

### Outbound AI provider runtime
- The active encrypted provider profile is selected through `AiEngine.askWithMcpApproval` and routed to `AiProviderRuntime`; legacy Gemini generateContent / generic OpenAI-compatible fallback remains available when no active profile is selected.
- Registered provider adapters and request/response/continuation translation cover Gemini Interactions, OpenAI Responses, xAI Responses, Anthropic Messages and Custom OpenAI-compatible mode.
- Provider tool calls are routed through bounded `AiToolCallOrchestrator` and the exact connected `McpConnectorService` session. Sensitive operations require the Android approval callback and one-time argument-bound grants. The runtime is sequential and bounded; it does not execute arbitrary provider-supplied code.
- Mocked E2E tests exercise provider-shaped text → tool call → result → assistant continuation for Gemini, OpenAI, xAI and Anthropic. These are mock tests, not live vendor API verification.
- Per-provider credential profiles, CRUD, active selection, hidden credential replacement/removal and model/API/adapter capability display exist in source. UNKNOWN capability values remain unverified; there is no live connection test or model discovery claim.
- Cancellation disconnects active HTTP transport and records a minimal unknown-outcome marker when a dispatched request receives no HTTP response. The marker is not provider-side reconciliation; POST requests are not automatically retried.

### MCP, task and security boundaries
- MCP discovery pagination, schema/argument validation, exact-service-session scoping, approval grants, task metadata persistence/revalidation and explicit user-triggered task refresh/input/cancel flows exist in source with regression tests.
- OAuth/CIMD remains metadata/URL validation helper work only; live metadata/CIMD fetch, AppAuth PKCE flow, resource/issuer-bound token exchange, encrypted token refresh/revocation and E2E verification are not implemented.
- Inbound MCP server bridge remains a separate future workstream. The Android app's MCP client and Relay Worker do not by themselves make MOSHARROF an MCP server.
- AI provider error results, Android UI status messages and Termux bridge exception paths now use fixed redacted error text. Regression tests assert raw simulated exception text and bearer-like values are not reflected.
- Changed Android production Kotlin files have received a focused source-pattern scan; whole-repository/history, dependency/license, crash-report and runtime-log audits remain open.

### Exact-source automated verification
- Source commit `d162a314ac0520d5511dd06ad751b9ce163e3c3b`.
- Android CI run `42970919165` (run #316): PASS — Android unit tests, debug APK assembly and artifact upload.
- Worker CI run `42970919190` (run #272): PASS.
- Debug artifact ID `13832594882`, size 2,415,618 bytes, SHA-256 `30f9dfe103e9003f6a6af8bc50d40da20d7e368b36d582064666e45150e6a76d`. CI artifact only; not installed or device-tested.
- No live provider/MCP tests, deployed Worker verification, Android Keystore instrumentation, physical-device QA, APK installation or PR merge are claimed.

### Remaining implementation order
1. Add malformed and interrupted-continuation regression fixtures; add Android UI transition regression tests.
2. Design privacy-minimal, redacted audit events without storing prompts, credentials or tool arguments.
3. Complete whole-repository and Git-history source/error/log scan, then dependency/version/license review.
4. Complete OAuth/CIMD as a separately gated security feature; do not imply OAuth readiness from AppAuth dependency or metadata helpers.
5. Reconcile inbound MCP server/bridge architecture only after its threat model and authentication/authorization model are reviewed.
