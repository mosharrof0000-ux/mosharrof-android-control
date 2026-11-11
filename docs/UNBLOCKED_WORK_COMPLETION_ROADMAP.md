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
- [x] Add provider-request cancellation and a bounded, privacy-minimal unknown-outcome marker store with unit coverage for marker/timestamp persistence, review clearing, and rejection of invalid timestamps. This is a local review reminder only; it does not reconcile provider-side execution.
- [ ] Add redacted audit events and verify no credential/argument leakage.
- **Gate:** read-only, approval, denial, revocation, replay, mismatch, and failure tests pass.

### Phase 5 — MCP policy and task lifecycle
- [x] Bounded tools/list pagination and repeated-cursor rejection.
- [x] Input schema and canonical argument bounds.
- [ ] Explicit local trust/risk classification workflow for discovered tools.
- [x] Add a bounded durable task metadata store backed by Android SharedPreferences; persist only connector/task IDs, status, TTL, poll interval and observation time (never server payloads, results, elicitation data or credentials).
- [x] Restore pending task IDs after process recreation as untrusted recovery candidates; require a fresh `tasks/get` response before the caller may rely on a recovered state.
- [x] Expire task metadata by server TTL, cap storage at 100 records, synchronously commit metadata, and clear a connector's records on explicit disconnect.
- [x] Expose pending recovery candidates through the service and persist snapshots returned by `tasks/get`, `tasks/update` and `tasks/cancel`.
- [x] Keep synchronous polling bounded by caller-supplied elapsed-time and poll-count limits; it stops on terminal or input-required states.
- [x] Add service-level reconnect/recovery tests: pending candidates survive service recreation, are revalidated through `tasks/get`, and terminal server state removes them from the pending list.
- [x] Add bounded polling cancellation regression coverage; cancellation prevents the next remote request.
- [x] Integrate MCP task-handle persistence and start/result/input-required handling with the approved Android UI; task input and cancellation require explicit user confirmation.
- **Gate:** task recovery works in deterministic store and service tests; stale candidates are revalidated against the server before use; unsupported task-start/tool flows remain fail-closed until UI approval and orchestration are integrated.

### Phase 6 — Android UX
- [x] Provider profile add/select/delete UI.
- [ ] Model and verified capability display.
- [ ] Credential replace/revoke UX and explicit active-profile state.
- [x] AI-generated tool approval and rejection dialog wired into the runtime.
- [x] Basic AI request status and safe failure display.
- [ ] UI state regression tests for provider/profile/approval/task transitions.
- **Gate:** UI decisions match the underlying permission and profile state.

### Phase 7 — Security, integration, and release readiness
- [ ] Credential, endpoint, redirect, response-size, JSON-depth and logging audit.
- [ ] AI + MCP + approval integration regression.
- [ ] Android unit tests, debug APK build, Worker typecheck.
- [ ] Dependency/license review and known-limitations register.
- [ ] Update architecture and ToolHub handoff materials.
- **Gate:** no unresolved critical/high findings; all automated checks pass.

### MCP Tasks recovery metadata implementation
- Added `McpTaskStore`: bounded in-memory test implementation and Android SharedPreferences durable implementation.
- Store schema deliberately excludes task result/error/inputRequests/requestState and credentials; only identifiers, status and bounded timing metadata are persisted.
- Pending records restored after process recreation are candidates only. Callers must query `tasks/get` against the connected server before relying on their state.
- Explicit connector disconnect clears persisted records. Connector reconnect keeps candidates available for server revalidation.
- Unit coverage added for connector isolation, pending-state filtering, TTL expiry, 100-record cap, explicit cleanup, and persistence across store recreation using a fake SharedPreferences implementation.
- Service integration records snapshots returned by `tasks/get/update/cancel`, exposes pending candidates, and provides `refreshPendingTasks` to revalidate each restored candidate against the server before use.
- Bounded polling supports caller cancellation before the first request and after each delay; cancellation prevents the next remote request.
- Android UI now recognizes `resultType=task`, persists only bounded task metadata, supports explicit status refresh, revalidates restored candidates with `tasks/get`, and presents input/cancel actions behind explicit user confirmation. Server-provided request data is shown as untrusted; input JSON keys must exactly match the request keys.
- Commits: `a8172fa` (store), `92198dc` / `cf00f16` (service integration), `743b763` (Android wiring), `c469594` / `6db8d5d` (store tests), `e44e10c` (initial service persistence), `d7ba354` (deterministic test clock fix), `dec0006` (service recovery tests), `ad29dd3` (remote refresh helper), `735bfd6` (refresh regression), `ca7c247` (poll cancellation), `dd61f3c` (cancellation regression test).
- Android CI #42870349189 and Worker CI #42870348894 PASS on `735bfd6514a8d3a1b768fc72d7fbef18a1f80696`; latest cancellation test CI is tracked separately below.

### MCP Task service recovery CI evidence
- Android CI run #42870349189 completed SUCCESS on source commit `735bfd6514a8d3a1b768fc72d7fbef18a1f80696`; includes Android unit tests, Debug APK assembly and artifact upload.
- Worker typecheck run #42870348894 completed SUCCESS on the same commit.
- Debug APK artifact `mosharrof-android-control-debug`, artifact ID `13788863906`, SHA-256 `bd1efc5cfa6208d306f24edda681e2e6f5a26085a730f1947add4dfed615e93c`. This is a CI artifact only; it has not been installed or device-tested.
- Recovery tests verify store persistence across service recreation, remote revalidation via `refreshPendingTasks`, terminal-state removal, and explicit-disconnect cleanup.
- Follow-up added cancellable polling (`shouldCancel` checked before network work and after each delay) plus a regression test proving cancellation prevents the next request. Android CI #42870719715 PASS for commit `dd61f3cd68239c25821f87332d89de8c8102d43b`; Worker CI #42870719771 PASS.
- `resultType=task` is now accepted only when a valid task handle parses and its bounded metadata is persisted. Invalid handles fail closed. UI status refresh, input response and cancellation are explicit user-triggered actions; no background polling is enabled.

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


| Latest MCP task recovery source commit `6db8d5dc93fe7a51c0dc74c45ed4cab81c2fda59` | PENDING CI | Durable metadata store and process-recreation regression tests added; final service integration commits are recorded above |

| MCP cancellation test commit `dd61f3cd68239c25821f87332d89de8c8102d43b` | Android CI #42870719715 PASS; Worker CI #42870719771 PASS | Added regression test proving cancellation prevents the next MCP task poll request |

| MCP cancellation test APK artifact | Uploaded | Artifact ID `13788908067`; SHA-256 `b54daa85508bf88ea5db1a8d9d27225ed46fdfe0ac3b538753f373fac6823b82`; CI artifact only, not installed |


### MCP task UI integration CI evidence
- Source commit `f68aec3d012f46eb1c1794f03bbdcbce3e811f6d` passed Android CI run #42872350018 (unit tests, debug APK assembly and artifact upload) and Worker typecheck run #42872350226.
- Debug APK artifact `mosharrof-android-control-debug`, artifact ID `13789243666`, size 2,407,912 bytes, SHA-256 `abd961f495a2b78f5c3c2db34b2f5eb56c4a73867054e963f31f754ecf0f8352`. CI artifact only; not installed or device-tested.
- The source adds task-handle validation/persistence, explicit task refresh and cancellation, revalidation of restored candidates, and user-confirmed task input submission. No automatic polling or implicit task input is performed.
- PR #7 remains open and unmerged. Physical Android QA, live MCP interoperability, provider API round-trips and production deployment remain external gates.


### AI request recovery marker implementation
- Extracted `AiRequestRecoveryStore` and `SharedPreferencesAiRequestRecoveryPersistence` from `MainActivity` into a dedicated module.
- The persistence contract stores only a pending boolean and observation timestamp. It has no API for prompts, API keys, tool arguments, request bodies, or provider responses.
- Added JVM tests for store recreation against a retained persistence implementation, marker/timestamp clearing after review, the deliberately narrow persistence contract, and negative timestamp rejection.
- These tests validate the store contract; Android SharedPreferences durability is provided by the platform adapter and still requires Android runtime/device verification.
- Source commits: `0c833489f053b86c77cb0d8a8356db9fa1fde6cb`, `b9bb6cc7f3404c0249f8341fb5556285e45a5333`, `b4ac776ee7f7a5e3f374293122b3ab9d5da26861`.
