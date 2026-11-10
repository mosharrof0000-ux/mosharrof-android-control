# Plugin System Execution Plan

This is the working completion ledger for PR #7 and the Android Plugin System. Status is evidence-based; source changes are not marked complete until their required tests pass.

## Repository work

| Workstream | Status | Acceptance gate |
|---|---|---|
| Connector registry and least-privilege authorization | Implemented; audit ongoing | Registry and approval-grant unit tests pass |
| Single-use, argument-bound approval grants | Implemented; audit ongoing | Expiry, replay, mismatch, revoke and capacity tests pass |
| MCP 2026-07-28 discovery and tools/list pagination | Implemented in source | Current-head Android tests pass; real server interoperability remains separate |
| MCP 2026-07-28 MRTR request envelope | Implemented in source | Input request validation and request-state round-trip tests pass |
| MRTR user interaction | Implemented as manual JSON entry | Android build passes; real-device UX and server tests remain |
| Native schema-driven elicitation forms | Pending | Safe typed form generation and schema validation tests |
| Elicitation URL mode / browser handoff | Pending | HTTPS-only external handoff and explicit return/consent handling |
| MCP Tasks extension | Pending | Capability negotiation, task polling/update/cancel, bounded lifecycle and recovery tests |
| OAuth 2.1 / CIMD | Pending | Protected-resource and authorization-server discovery, PKCE browser flow, token refresh and encrypted storage tests |
| Relay Android client recovery | Implemented in source; audit ongoing | Current-head unit tests and physical-device QA |
| Relay Worker leases/idempotency/rate limits | Implemented in source | Worker behavioral tests pass; deployed Worker still needs live verification |
| OpenAPI/Worker/Android contract consistency | Audit pending | Contract tests and generated/import validation |
| Credential/log leakage review | Pending | Static review plus failure-path tests |
| Documentation accuracy | Updated for MRTR status | Docs match tested code and remaining gaps |

## External verification gates

- Physical Android install and real-device testing require the user's explicit approval.
- Termux integration requires the user to enable Termux RUN_COMMAND permission and the external-app setting.
- Cloudflare deployment changes the live Worker and must not run without explicit approval.
- ChatGPT connector configuration and a real relay round-trip require live credentials and user-side setup.
- PR #7 must remain open until the user explicitly approves merge; no release is authorized by this plan.

## Rules

- Do not report a feature complete from code inspection alone.
- Record the exact commit SHA and CI run for every verified build/test gate.
- Keep protocol versions distinct: modern 2026-07-28 behavior must not be conflated with legacy 2025 protocol behavior.
- Never claim full MCP conformance while Tasks, OAuth/CIMD, schema-driven forms, or URL-mode handoff remain incomplete.
