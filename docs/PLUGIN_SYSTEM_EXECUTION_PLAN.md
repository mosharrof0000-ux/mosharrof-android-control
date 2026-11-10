# Plugin System Execution Plan

This is the working completion ledger for PR #7 and the Android Plugin System. Status is evidence-based; source changes are not marked complete until their required tests pass.

## Repository work

| Workstream | Status | Acceptance gate |
|---|---|---|
| Connector registry and least-privilege authorization | Implemented; audit ongoing | Registry and approval-grant unit tests pass; re-audit edge cases before release |
| Single-use, argument-bound approval grants | Implemented; recursive input bound added and CI verified | Expiry, replay, mismatch, revoke, capacity, timestamp overflow and deep-input tests; Android CI run 42804238420 passed |
| MCP 2026-07-28 discovery and tools/list pagination | Implemented in source | Current-head Android tests pass; real server interoperability remains separate |
| MCP 2026-07-28 MRTR request envelope | Implemented in source | Input request validation and request-state round-trip tests pass |
| MRTR user interaction | Implemented as manual JSON entry | Android build passes; real-device UX and server tests remain |
| Native schema-driven elicitation forms | Implemented for one flat form-mode elicitation request; complex/multiple requests fall back to manual JSON | Explicit accept/decline/cancel, schema validation and user approval; Android unit tests + APK build pass at b51df1e40c6d89f976b92b9deec4f0072c4c379c; run 42805267132 |
| Native schema-driven tool argument forms | Implemented for flat string/number/integer/boolean/enum fields; complex schemas fall back to JSON | Android unit tests and APK build pass at commit c5ff3c61b830c7f0416e0cbc4c9f9840f42caab9; run 42804709728 |
| Elicitation URL mode / browser handoff | Implemented and Android CI verified | HTTPS-only URL validation, external browser launch, explicit user response remains required; Android CI run 42803567809 passed |
| MCP Tasks extension | Partial client lifecycle API; not advertised or enabled | Added bounded get/update/cancel operations and bounded synchronous polling (time/poll caps; stops at input_required/terminal); Android CI pending for commit e01ddfc3. Capability negotiation, durable task recovery, persisted task state, user-facing input_required approval UI, and real-server interoperability remain pending |
| OAuth 2.1 / CIMD | Pending | Protected-resource and authorization-server discovery, PKCE browser flow, token refresh and encrypted storage tests |
| Relay Android client recovery | Implemented in source; audit ongoing | Current-head unit tests and physical-device QA |
| Relay Worker leases/idempotency/rate limits | Implemented in source | Worker behavioral tests pass; deployed Worker still needs live verification |
| OpenAPI/Worker/Android contract consistency | Audit pending | Contract tests and generated/import validation |
| Credential/log leakage review | Pending | Static review plus failure-path tests |
| MCP argument traversal bounds | Implemented and CI verified | Validation and approval fingerprinting cap depth at 32 and nodes at 2,048; Android CI run 42804238420 passed |
| Documentation accuracy | Updated for MRTR status; URL-mode ledger updated | Docs match tested code and remaining gaps |

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
