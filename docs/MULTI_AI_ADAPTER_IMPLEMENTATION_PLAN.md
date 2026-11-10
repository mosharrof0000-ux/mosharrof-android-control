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
- [ ] Add common bounded HTTPS transport: redirects disabled, timeouts, response caps, retry classification and redacted diagnostics.
- [ ] Store provider/account credentials separately using Android Keystore; never log secrets.
- [ ] Keep ConnectorRegistry authorization and ToolApprovalGrants as the final authority for every adapter.
- [x] Add initial shared adapter contract and Gemini/OpenAI wire-format tests; broaden with fuzzing, malformed payload and live fixtures.
**Gate:** fake adapter passes contract suite; malformed provider responses cannot bypass validation or approval.

## Phase 2 — Safe tool execution loop
- [ ] Convert canonical plugin definitions to each provider's native tool declaration.
- [ ] Parse one or many tool calls while preserving call IDs and order.
- [ ] Validate tool name, connector state, schema, argument size/depth and risk before execution.
- [ ] Require explicit, exact-argument, single-use approval for WRITE, EXTERNAL_SIDE_EFFECT and DESTRUCTIVE.
- [ ] Execute only through the authorized connector/runtime path; never run provider-supplied shell or arbitrary code.
- [ ] Convert results/errors back to provider-native continuation format.
- [ ] Handle parallel calls with bounded concurrency and separate approval per call.
- [ ] Add max-turn, max-call, total-time, payload and usage limits.
- [ ] Add cancellation, timeout, retry and unknown-outcome handling without duplicate side effects.
- [ ] Add audit events with secrets and sensitive payloads redacted.
**Gate:** identical mock plugin behavior and approval outcomes across adapters; replay, duplicate, mismatch and timeout tests pass.

## Phase 3 — Provider adapters, one at a time
- [x] Initial Google Gemini generateContent function declaration, functionCall parsing and functionResponse encoding adapter added. Safety/finish reasons, model-specific capability verification, streaming and live API round-trip remain pending.
- [x] Initial official OpenAI Responses API function-tool definition, function_call parsing and function_call_output continuation adapter added. Network orchestration, streaming, strict-schema normalization and live API round-trip remain pending.
- [ ] xAI Grok: official API auth, tool declarations/calls, result continuation, streaming and model-specific capabilities.
- [ ] Anthropic Claude: Messages API tool-use/tool-result blocks, stop reasons, streaming and schema limitations.
- [ ] Microsoft: first identify exact product (Copilot Studio, Microsoft 365 Copilot extensibility, or another official API). Implement only against documented permitted interfaces. Do not assume consumer Copilot has a general public tool-calling API.
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
- [ ] Maintain machine-readable catalog: official docs, API version, adapter version, models/features, auth, limitations, last review and verification evidence.
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
