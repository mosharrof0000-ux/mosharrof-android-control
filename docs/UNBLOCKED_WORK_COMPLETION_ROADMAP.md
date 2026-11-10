# Unblocked Work Completion Roadmap

**Scope:** Complete implementation and automated verification that can be performed without live provider credentials, a physical Android device, production deployment, or PR merge.

**Working branch:** `fix/mcp-pagination-20261110`  
**Baseline commit:** `642ca45db7f76fa66286aa956231c51cd63c8457`

## Rules

- A task is complete only when source, relevant automated tests, CI evidence, and documentation agree.
- Mocked contract tests do not count as live provider verification.
- No secrets are written to logs or documentation.
- Do not merge PR #7, deploy Cloudflare, or install the APK on a physical device as part of this unblocked work.
- Preserve the existing legacy Gemini/OpenAI-compatible configuration while introducing new provider abstractions.
- Keep unknown provider capabilities marked UNKNOWN until verified.

## Work lanes and dependencies

| Lane | Scope | Depends on |
|---|---|---|
| A — Provider foundation | Provider profiles, encrypted credentials, adapters, request/response translation | Baseline audit |
| B — Conversation and tools | Conversation state, provider continuation, canonical tool loop | Provider contracts |
| C — MCP and permissions | Tool policy, task persistence abstraction, approval integration | Canonical tool-call contract |
| D — Android UX | Provider/profile management and approval UI | A, B, C interfaces |
| E — Security and QA | Security review, regression, CI, evidence ledger | All implementation lanes |

Independent work may proceed concurrently, but shared contracts and UI integration require checkpoints to avoid conflicting changes.

## Phase checklist

### Phase 0 — Source and architecture audit
- [x] Inventory Android AI provider, profile store, runtime, MCP task/OAuth, Relay, Keystore diagnostic and corresponding test modules at commit `ffd375415030c0a84e722f5de734ed2c8ca78fc2`.
- [x] Map principal ownership: `AiProviderAdapter`/request factories/HTTP transport; `AiProviderProfileStore`; `AiProviderRuntime` + `AiToolCallOrchestrator`; `McpTaskProtocol`; `McpOAuthMetadata`; `RelayClient`; Android approval UI in `MainActivity`.
- [x] Record implemented, partial, pending and externally gated behavior in this roadmap.
- [ ] Freeze canonical interfaces and migration requirements after remaining API compatibility review.
- **Gate:** audit report is checked against source and test tree.

### Phase 1 — Provider adapter framework
- [x] Initial provider-neutral adapter contract and registry.
- [x] Initial capability profile and canonical tool models.
- [x] Bounded shared HTTPS transport.
- [x] First-turn request builders for Gemini Interactions, OpenAI Responses, xAI Responses, and Anthropic Messages.
- [x] Route active provider profiles through the shared first-turn runtime and all four registered adapters for normalized text responses.
- [x] Add bounded JSON response conversion and adapter response decoding tests for Gemini, OpenAI, xAI and Anthropic.
- [x] Integrate provider tool calls into the bounded approval-gated continuation loop; active profiles advertise only tools from the exact connected MCP service session. `MainActivity.runAi()` passes the active `PluginDashboardSession.service` and the explicit `requestAiToolApproval` callback into `AiEngine.askWithMcpApproval()`.
- [x] Add provider-specific continuation request builders for Gemini Interactions, OpenAI Responses, xAI Responses, Anthropic Messages and Custom OpenAI-compatible format.
- [ ] Verify schema conversion rejects unsupported constraints with provider-specific end-to-end fixtures.
- **Gate:** all adapters pass common mock contract and malformed-response tests.

### Phase 2 — Encrypted provider profiles
- [x] Add multi-profile encrypted credential repository.
- [x] Add profile CRUD and active-profile selection APIs.
- [x] Add explicit legacy Gemini configuration migration; legacy OpenAI-compatible configuration remains on its existing path until a compatible adapter is available.
- [x] Integrate active profile selection into AiEngine's first-turn text runtime.
- [x] Add provider management UI for profile creation, selection, deletion and explicit legacy Gemini migration.
- [x] Wire active profile requests into `AiProviderRuntime` and the Android AI prompt action.
- [ ] Add model/capability display and credential replace/revoke UX.
- [x] Verify metadata separation and resolved-profile secret redaction with repository tests.
- [ ] Verify Android Keystore failure behavior on supported Android runtimes.
- **Gate:** profile CRUD, encryption boundaries, migration, and no-secret-leak tests pass.

### Phase 3 — Conversation continuation
- [x] Provider-specific stateless continuation request builders for Gemini, OpenAI, xAI and Anthropic; previous provider output is replayed with matching tool results.
- [x] Bounded history item count, prompt, request/response bytes, JSON depth/nodes, tool-call count and continuation rounds.
- [x] Mock continuation request-shape tests for Gemini, OpenAI, xAI, Anthropic and custom OpenAI-compatible providers.
- [x] Add approved MCP tool-call → execution → provider continuation round-trip test across Gemini, OpenAI, xAI and Anthropic; the test uses provider-shaped mocked responses and isolated connector registries.
- [ ] Add interrupted-continuation/retry-state tests and provider-specific malformed continuation fixtures.
- [ ] Confirm live continuation behavior against each official provider API (requires credentials and authorization).
- **Gate:** each provider completes mock text → tool call → tool result → assistant response flow.

### Phase 4 — Tool orchestration and approval
- [x] Canonical tool-call guard and bounded orchestrator.
- [x] Connect provider response parsing to orchestrator planning in `AiProviderRuntime`.
- [x] Connect exact-argument one-time approval grants to Android approval UI via `MainActivity.requestAiToolApproval`.
- [x] Connect approved execution to the exact active `McpConnectorService` session.
- [x] Add bounded serial execution, duplicate-call rejection, denial/error continuation and maximum call/round limits.
- [ ] Add explicit cancellation and unknown-outcome recovery for provider/MCP requests.
- [ ] Add redacted audit events and verify no credential/argument leakage.
- **Gate:** read-only, approval, denial, revocation, replay, mismatch, and failure tests pass.

### Phase 5 — MCP policy and task lifecycle
- [x] Bounded tools/list pagination and repeated-cursor rejection.
- [x] Input schema and canonical argument bounds.
- [ ] Explicit local trust/risk classification workflow for discovered tools.
- [ ] Durable task-state abstraction and recovery tests.
- [ ] Reconnect invalidation and bounded polling tests.
- **Gate:** malformed/unknown tools fail closed; task recovery works in deterministic tests.

### Phase 6 — Android UX
- [x] Provider profile add/select/delete UI.
- [ ] Model and verified capability display.
- [ ] Credential replace/revoke UX and explicit active-profile state.
- [x] AI-generated tool approval and rejection dialog wired into the runtime.
- [x] Basic AI request status and safe failure display.
- [ ] UI state regression tests for provider/profile/approval transitions.
- **Gate:** UI decisions match the underlying permission and profile state.

### Phase 7 — Security, integration, and release readiness
- [ ] Credential, endpoint, redirect, response-size, JSON-depth and logging audit.
- [ ] AI + MCP + approval integration regression.
- [ ] Android unit tests, debug APK build, Worker typecheck.
- [ ] Dependency/license review and known-limitations register.
- [ ] Update architecture and ToolHub handoff materials.
- **Gate:** no unresolved critical/high findings; all automated checks pass.

## External gates excluded from this roadmap

- Live provider API calls require provider credentials and authorization.
- Real MCP interoperability requires a reachable server/client.
- Physical Android install and device QA require explicit user approval.
- Cloudflare deployment requires explicit user approval.
- PR #7 merge requires explicit user approval.

## Evidence ledger

| Evidence | Result | Scope |
|---|---|---|
| Android CI run 42829753454 | IN PROGRESS at commit `1994168e2c559fc609003dbc8a66c1c1662cc290` | Unit tests and debug APK build; does not include later runtime/test commit `642ca45db7f76fa66286aa956231c51cd63c8457` |
| Worker CI run 42829753492 | PASS | Commit `1994168e2c559fc609003dbc8a66c1c1662cc290`; docs/runtime commits do not change Worker source |
| Physical device QA | NOT RUN | Requires user approval |
| Live provider round-trips | NOT RUN | Requires credentials and authorization |
| Production deployment | NOT RUN | Requires user approval |

| Latest source commit `642ca45db7f76fa66286aa956231c51cd63c8457` | PENDING CI | Profile-backed first-turn runtime and provider response contract tests added; latest-head CI evidence not yet returned |\n\nUpdate this ledger only with run IDs and commit SHAs verified from CI.
