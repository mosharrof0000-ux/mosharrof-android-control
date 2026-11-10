# Unblocked Work Completion Roadmap

**Scope:** Complete implementation and automated verification that can be performed without live provider credentials, a physical Android device, production deployment, or PR merge.

**Working branch:** `fix/mcp-pagination-20261110`  
**Baseline commit:** `f0852eec634539a98810daffc47be95aa05b3b26`

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
- [ ] Inventory all Android, Worker, MCP, Relay, AI, credential, approval, UI, and test modules.
- [ ] Map existing classes and ownership boundaries.
- [ ] Record implemented, partial, pending, and externally gated behavior.
- [ ] Freeze canonical interfaces and migration requirements.
- **Gate:** audit report is checked against source and test tree.

### Phase 1 — Provider adapter framework
- [x] Initial provider-neutral adapter contract and registry.
- [x] Initial capability profile and canonical tool models.
- [x] Bounded shared HTTPS transport.
- [x] First-turn request builders for Gemini Interactions, OpenAI Responses, xAI Responses, and Anthropic Messages.
- [ ] Integrate all four adapters into AiEngine.
- [ ] Add provider-specific response parsing and normalized transport errors to the runtime.
- [ ] Verify schema conversion rejects unsupported constraints.
- **Gate:** all adapters pass common mock contract and malformed-response tests.

### Phase 2 — Encrypted provider profiles
- [ ] Add multi-profile encrypted credential repository.
- [ ] Add profile CRUD and active-profile selection.
- [ ] Add profile migration from the legacy single-provider configuration.
- [ ] Integrate profile selection into AiEngine.
- [ ] Add provider management UI.
- [ ] Verify secret redaction and Android Keystore failure behavior.
- **Gate:** profile CRUD, encryption boundaries, migration, and no-secret-leak tests pass.

### Phase 3 — Conversation continuation
- [ ] Provider-specific conversation state model.
- [ ] Gemini interaction continuation.
- [ ] OpenAI and xAI response continuation.
- [ ] Anthropic full-history continuation.
- [ ] Bounded history, token/payload, turn and time limits.
- [ ] Mock multi-turn and interrupted-continuation tests.
- **Gate:** each provider completes mock text → tool call → tool result → assistant response flow.

### Phase 4 — Tool orchestration and approval
- [x] Initial canonical tool-call guard and bounded orchestrator.
- [ ] Connect provider response parsing to orchestrator planning.
- [ ] Connect exact-argument one-time approval grants to Android UI.
- [ ] Connect approved execution to MCP service.
- [ ] Add bounded serial execution, cancellation and unknown-outcome handling.
- [ ] Add redacted audit events.
- **Gate:** read-only, approval, denial, revocation, replay, mismatch, and failure tests pass.

### Phase 5 — MCP policy and task lifecycle
- [x] Bounded tools/list pagination and repeated-cursor rejection.
- [x] Input schema and canonical argument bounds.
- [ ] Explicit local trust/risk classification workflow for discovered tools.
- [ ] Durable task-state abstraction and recovery tests.
- [ ] Reconnect invalidation and bounded polling tests.
- **Gate:** malformed/unknown tools fail closed; task recovery works in deterministic tests.

### Phase 6 — Android UX
- [ ] Provider profile management UI.
- [ ] Model and capability display.
- [ ] Credential add/replace/revoke flow.
- [ ] AI-generated tool approval and rejection UI.
- [ ] Tool execution status and safe error display.
- [ ] UI state regression tests.
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
| Android CI run 42826745595 | PASS | Commit `f0852eec634539a98810daffc47be95aa05b3b26`: unit tests, debug APK assembly, artifact upload |
| Worker CI run 42826745677 | PASS | Same commit: typecheck |
| Physical device QA | NOT RUN | Requires user approval |
| Live provider round-trips | NOT RUN | Requires credentials and authorization |
| Production deployment | NOT RUN | Requires user approval |

Update this ledger only with run IDs and commit SHAs verified from CI.
