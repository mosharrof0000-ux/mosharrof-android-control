# Plugin System Execution Plan

This is the working completion ledger for PR #7 and the Android Plugin System. Status is evidence-based; source changes are not marked complete until their required tests pass.

## Repository work

| Workstream | Status | Acceptance gate |
|---|---|---|
| Multi-AI adapter implementation | Core contract, four provider translators, bounded HTTP transport and guarded tool-call orchestration foundation implemented; latest Android CI passed at 1b668ede28ff3f7641a20343b3627be8991d4dff | Added `AiProviderAdapter.kt`, canonical plugin/call/result models, capability profiles, duplicate-safe provider registry, built-in Gemini Interactions/OpenAI Responses/xAI Responses/Anthropic Messages adapter registration at app startup, machine-readable provider catalog, bounded tool-call/result translation, fail-closed CanonicalToolCallGuard (registered name/schema/connection/risk checks), and contract/wire-format tests. These are translation adapters only: AiEngine network orchestration, credential binding, approval-bound execution loop, live API round-trips, Microsoft inbound MCP server/plugin packaging, and user-facing provider-call approval loop remain pending. |
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
| MCP Tasks extension | Partial client lifecycle API with regression tests; not advertised or enabled | Added get/update/cancel with response taskId binding; polling counts initial fetch within maxPolls, bounds time/polls/delay and stops at input_required/terminal; tests cover all three operations, disconnected/empty/mismatched IDs, terminal polling and malformed snapshots; Android CI run 42809461736 passed. Capability negotiation, durable task recovery, persisted task state, user-facing input_required approval UI, and real-server interoperability remain pending |
| OAuth 2.1 / CIMD | Metadata validation/discovery URL helpers only; flow pending | RFC 9728/RFC 8414 URL derivation, exact resource/issuer binding, HTTPS endpoints, native public-client metadata, redirect safety and PKCE S256 validators have unit coverage. Collection bounds now cap client name, redirect/grant/response lists and authorization-server lists; Android CI run 42819796245 passed at head 1b668ede28ff3f7641a20343b3627be8991d4dff. Still missing live metadata fetch, CIMD hosting/fetch integration, AppAuth PKCE Activity flow, issuer/resource-bound token exchange, encrypted AuthState refresh/revocation and end-to-end tests |
| Relay Android client recovery | Implemented in source; audit ongoing | Current-head unit tests and physical-device QA |
| Relay Worker leases/idempotency/rate limits | Implemented in source; edge/security regression tests pass | Worker tests cover idempotency, leases/retry, result acceptance/replay/conflict, expiry, rate limiting, auth, operation allowlist, malformed results and secret-safe health output; Worker CI run 42809461635 passed; deployed Worker still needs live verification |
| OpenAPI/Worker/Android contract consistency | Audit pending | Contract tests and generated/import validation |
| Credential/log leakage review | Partial static audit and regression tests pass | HTTP error bodies are no longer copied into exception messages; only the supported-version hint is retained for -32022; encrypted secret envelopes are capped at 48,000 characters before Base64 decoding. A focused regex scan of 10 security-sensitive source/config files found no common API-key/token/PEM/Bearer patterns (not a whole-repository/history audit). Android Keystore instrumentation and full tracked-source/history log audit remain pending |
| MCP argument traversal bounds | Implemented and CI verified | Validation and approval fingerprinting cap depth at 32 and nodes at 2,048; Android CI run 42804238420 passed |
| AI request/response resource bounds | Implemented and current-head CI verified | API key ≤8,192 chars, model ≤128 chars, prompt ≤64 KiB, response ≤256 KiB; bounded stream reader regression tests; Android CI run 42819796245 passed at head 1b668ede28ff3f7641a20343b3627be8991d4dff |
| Documentation accuracy | Updated with current evidence-backed Tasks, OAuth, AI-bounds, Worker and credential-audit status | Android CI run 42819796245 passed (unit tests, debug APK assemble, APK upload) and Worker CI run 42819796066 passed for head 1b668ede28ff3f7641a20343b3627be8991d4dff; external and release gates remain explicitly pending |

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
- Never claim full MCP conformance while Tasks, OAuth/CIMD, schema-driven forms, or URL-mode handoff remain incomplete.
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
