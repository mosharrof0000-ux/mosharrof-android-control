# Multi-AI Plugin Adapter Implementation Plan

## Goal
Implement a working, provider-neutral plugin platform. A new AI provider should be onboarded by adding and testing an adapter, not by rewriting plugin definitions, approval policy, Android device-control code, or the plugin runtime.

This is an implementation checklist, not a claim that provider adapters or live integrations already exist.

## Two integration directions
- MOSHARROF → AI provider (outbound): MOSHARROF calls an AI API and translates native tool/function-call responses into the canonical plugin runtime.
- AI provider/client → MOSHARROF plugins (inbound): an AI product with documented MCP-client or connector support connects to a secured MCP/HTTP bridge exposing approved MOSHARROF tools.

An outbound API adapter does not automatically make MOSHARROF an MCP server. These directions must be implemented and tested separately.

## Phase 0 — Baseline and canonical contract
- [ ] Inventory current AI code, MCP client, relay, connector registry, approval grants, secret storage, UI and tests.
- [ ] Record provider-specific assumptions. Current AiEngine supports Gemini generateContent text requests and a generic OpenAI-compatible chat-completions format; this is not a complete multi-provider tool-calling layer.
- [x] Define initial canonical models: ProviderId, ModelId, ProviderConfig, CapabilityProfile, PluginDefinition, CanonicalToolCall and CanonicalToolResult. CanonicalError, stream event and cancellation contracts remain pending.
- [ ] Define a versioned canonical JSON Schema subset.
- [ ] Define lifecycle: proposed → validated → approval-pending → approved/denied → executing → completed/failed/cancelled.
- [ ] Preserve call IDs and argument integrity across conversions; reject ambiguous or malformed payloads.
- [ ] Define compatibility and migration rules before changing current MCP and AiEngine contracts.
**Gate:** canonical contract tests pass; provider-specific payloads do not leak into core runtime.

## Phase 1 — Core adapter framework
- [x] Add initial AiProviderAdapter interface for provider identity, configuration validation, capability profiles, tool-definition translation, tool-call parsing and result continuation. Model listing, streaming, cancellation and normalized transport errors remain pending.
- [x] Add AiProviderRegistry with duplicate rejection, adapter version and built-in registration for Gemini/OpenAI. Provider health/status persistence remains pending.
- [x] Add capability profiles with SUPPORTED / UNSUPPORTED / UNKNOWN per feature, model and API version; initial profiles are conservative and leave unverified capabilities UNKNOWN.
- [x] Project ConnectorRegistry tools into a provider-neutral ToolCatalog snapshot.
- [ ] Add schema conversion; unsupported constraints must be rejected or explicitly explained, never silently dropped.
- [ ] Add common bounded HTTPS transport: redirects disabled, timeouts, response caps, retry classification and redacted diagnostics.\n- [x] Add provider-specific first-turn HTTP request construction for Gemini Interactions, OpenAI Responses, xAI Responses and Anthropic Messages, with fixed HTTPS origins, provider auth headers, bounded prompt/credential values and credential-redacted diagnostics. Actual network transport and AiEngine integration remain pending.
- [ ] Store provider/account credentials separately using Android Keystore; never log secrets.
- [ ] Keep ConnectorRegistry authorization and ToolApprovalGrants as the final authority for every adapter.
- [x] Add initial shared adapter contract and Gemini/OpenAI wire-format tests; broaden with fuzzing, malformed payload and live fixtures.
**Gate:** fake adapter passes contract suite; malformed provider responses cannot bypass validation or approval.

## Phase 2 — Safe tool execution loop
- [ ] Convert canonical plugin definitions to each provider's native tool declaration.
- [ ] Parse one or many tool calls while preserving call IDs and order.
- [ ] Validate tool name, connector state, schema, argument size/depth and risk before execution.
- [x] Add a fail-closed CanonicalToolCallGuard that resolves provider tool names against registered MOSHARROF tools, validates arguments against the registered schema, rejects ambiguous/unknown/disconnected tools, and separates READ_ONLY from approval-required risks. The guard never issues grants or executes tools.
- [ ] Connect the approval-required decision to a user-facing confirmation UI and issue an exact-argument, single-use grant only after explicit approval for WRITE, EXTERNAL_SIDE_EFFECT and DESTRUCTIVE.
- [ ] Execute only through the authorized connector/runtime path; never run provider-supplied shell or arbitrary code.
- [ ] Convert results/errors back to provider-native continuation format.
- [ ] Handle parallel calls with bounded concurrency and separate approval per call.
- [ ] Add max-turn, max-call, total-time, payload and usage limits.
- [ ] Add cancellation, timeout, retry and unknown-outcome handling without duplicate side effects.
- [ ] Add audit events with secrets and sensitive payloads redacted.
**Gate:** identical mock plugin behavior and approval outcomes across adapters; replay, duplicate, mismatch and timeout tests pass.

## Phase 3 — Provider adapters, one at a time
- [x] Initial Google Gemini generateContent function declaration, functionCall parsing and functionResponse encoding adapter added. Official reference: https://ai.google.dev/gemini-api/docs/function-calling. Safety/finish reasons, model-specific capability verification, streaming and live API round-trip remain pending.
- [x] Initial official OpenAI Responses API function-tool definition, function_call parsing and function_call_output continuation adapter added. Official reference: https://developers.openai.com/api/docs/guides/function-calling. Network orchestration, streaming, strict-schema normalization and live API round-trip remain pending.
- [x] Initial xAI Grok Chat Completions-compatible function tool declarations, tool_calls parsing and tool-role result continuation adapter added. Official reference: https://docs.x.ai/developers/tools/function-calling. Authentication/network orchestration, streaming and live API round-trip remain pending.
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
- [ ] Provider management UI: add/edit/disable, model selection, connection test, capability view and credential revocation.
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
- These are protocol translation updates only; provider credentials, HTTP transport, conversation state and live endpoint verification remain pending.


- Gemini's Interactions API is now the registered default adapter because Google documents it as the recommended interface for new agentic/tool workflows; the generateContent translator remains available for legacy compatibility. The Interactions adapter accepts `v1beta` and `v1` profile versions and translates function_call/function_result steps. Server-side interaction ID continuation is not yet connected to the HTTP orchestration loop.
