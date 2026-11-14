# MCP OAuth Automatic Refresh Before Every Request

Status: implementation and mocked regression coverage are present for per-request OAuth transport across asynchronous connection negotiation, paginated discovery, tool calls, and task lifecycle. OAuth-created clients fail closed on synchronous/static-token transport. Manual-token compatibility remains intentionally available. Live authorization-server/MCP interoperability and physical-device verification remain incomplete.

## Current verified state

- `McpOAuthTokenRefresher.withFreshToken(...)` is callback-based and persists refreshed AppAuth state through the encrypted `McpOAuthAuthStateStore` before exposing an in-memory Bearer token.
- Refresh attempts are serialized per connector; duplicate in-flight attempts fail safely.
- `McpOAuthAccessTokenProvider` now verifies the registered MCP connector, exact endpoint, encrypted AppAuth state, and exact RFC 8707 resource binding before requesting a fresh token. It rejects invocation from the Android main thread.\n- `McpHttpClient.postAsync(...)` now resolves a token per request on an IO executor and only then sends the HTTP request; it does not retain the OAuth token on the client. The legacy `post(...)` path remains for static/manual-token connectors. Per-request Bearer values are checked for header injection.
- `McpConnectorService` retains synchronous compatibility methods for manual-token connectors, and also exposes async OAuth variants for connection negotiation, initialized notification, paginated `tools/list`, tool calls, and task get/update/cancel. OAuth-created clients set `allowSynchronousRequests=false`, so accidental calls into the synchronous methods fail before network I/O.
- OAuth UI entry points now pass `McpOAuthAccessTokenProvider` and the worker `Executor` into the async service methods. The resolver rejects main-thread entry, validates the exact registered connector endpoint and RFC 8707 resource binding, and resolves from encrypted AppAuth state for each request.
- MainActivity persists the authorization-code/PKCE token response, then constructs an OAuth-only client without a static Bearer token and calls `connectAsync`; every handshake and discovery request resolves a token independently. Later dashboard tool/task operations also use async per-request resolution.

## Exact source call-site audit (branch head before this audit update)

The request boundary is centralized in `McpHttpClient.post(...)`, but the service currently invokes it synchronously from these paths:

| Source location | Operation | Outbound request coverage required |
|---|---|---|
| `McpConnectorService.connect` | session setup + tool discovery | Modern discovery, legacy initialize fallback, every paginated tools/list page |
| `McpConnectorService.negotiateProtocol` | protocol negotiation | Modern discovery request and legacy initialize request |
| `McpConnectorService.negotiateLegacyProtocol` | legacy session initialization | Initialize and initialized notification |
| `McpConnectorService.callTool` | tool execution | Every tools/call, after approval grant consumption |
| `McpConnectorService.getTask` | task snapshot | Every tasks/get |
| `McpConnectorService.updateTask` | task input submission | Every tasks/update |
| `McpConnectorService.cancelTask` | task cancellation | Every tasks/cancel |
| `McpConnectorService.disconnect` | session teardown | Audit whether the transport sends a network request; local-only teardown must not be mistaken for one |

The UI currently supplies a single-thread `Executors.newSingleThreadExecutor()` from `MainActivity`; `PluginDashboardPage` dispatches its service operations to that worker. OAuth callback handling is separate and returns to the UI thread. This is a useful starting point for a callback-based pipeline, but it is not sufficient evidence that every possible caller is off-main-thread. The token provider now asserts that resolution starts off the main thread. Migrate all service call sites before enabling OAuth transport mode.

The current OAuth handoff in `MainActivity.startMcpOAuthAuthorization` performs one refresh after code exchange, then constructs `McpHttpClient(endpoint, bearerToken = freshToken.value)`. That creates a static-token client, so later requests can use an expired token. The initial refresh must be removed from the long-lived-client design once the per-request provider is wired; code exchange should persist state, then the authenticated transport should resolve a token for each request.

## Required behavior

1. For an OAuth-backed connector, every outbound MCP HTTP request must obtain a fresh-or-valid token through AppAuth immediately before the request.
2. Do not block the Android main thread, use `CountDownLatch`, or synchronously wait for AppAuth callbacks.
3. Preserve current static-token/manual-token behavior for connectors that are not OAuth-backed.
4. Persist rotated/refreshed AuthState encrypted before sending the request with the new access token.
5. Bind the OAuth resource parameter to the exact registered MCP endpoint on both refresh and request execution.
6. On refresh failure, do not delete encrypted credentials, do not retry blindly, and do not send the MCP request.
7. Do not expose token values in UI, logs, exceptions, analytics, or test failure messages.
8. Avoid concurrent refresh-token rotation races for the same connector; serialize requests or coalesce refresh work without allowing stale AuthState writes.

## Required architectural change

The transport boundary now has a callback-based `postAsync(...)` path, but the current synchronous `McpConnectorService` call graph still uses `post(...)`. The next required step is to migrate every network-bearing service operation to continuation/callback-based APIs and route each request through `postAsync(...)` without blocking.

Suggested separation:

- `McpOAuthAccessTokenProvider`: resolves a connector's current access token asynchronously using the encrypted AuthState and `McpOAuthTokenRefresher`.
- `McpHttpClient.postAsync`: accepts a one-request token resolver, then performs the existing bounded HTTP operation on an IO executor. It must not capture a stale token when the client is constructed.
- `McpConnectorService`: provide asynchronous variants for connect/negotiation/paginated discovery, callTool, getTask, updateTask, and cancelTask. Every internal `post` and notification must use the same authenticated request pipeline.
- `PluginDashboardPage`: consume async results and return to the UI thread only for rendering; preserve approval-grant single-use semantics and user confirmation boundaries.
- Keep legacy synchronous entry points only if they are explicitly limited to non-OAuth/static-token clients; tests must prevent OAuth clients from silently using that path.

Do not implement a partial wrapper that refreshes only connect or tool calls. The guarantee is per outbound request, including initialization, notifications, pagination, retries, and task lifecycle requests.

## Acceptance tests

- Expired and non-expired OAuth state: each outbound request obtains a valid token before transport.
- Refresh-token rotation: the newly returned AuthState is durably encrypted before the MCP request begins.
- Refresh failure: no MCP HTTP request is sent; previous encrypted credentials remain intact; safe typed recovery is returned.
- Concurrent calls for one connector: no duplicate refresh-token rotation or stale-state overwrite.
- Different connectors: refreshes remain independent.
- Resource mismatch, missing resource, unsafe endpoint, and non-Bearer token: request is rejected before transport.
- Request ordering: initialization, initialized notification, every tools/list page, tools/call, tasks/get, tasks/update, and tasks/cancel each pass through the resolver.
- Main-thread guard: token resolution rejects main-thread entry; full service migration must preserve this invariant for every request.
- Regression: manual/static Bearer token clients retain existing behavior.
- Redaction: access/refresh tokens never appear in logs, exceptions, status strings, or test output.

## Release status

This work is required before marking automatic OAuth refresh complete. Until all service and UI call sites are migrated and exact-head CI tests pass, automatic refresh before every MCP request remains **incomplete**. No live OAuth login, token refresh, or remote revocation has been run as part of this audit.


## Latest CI finding and repair

- Android run `42996782193` failed at Kotlin compilation because `McpAccessTokenResolver` was referenced by the HTTP client and provider but its interface declaration was absent from the compiled source tree.
- The Worker Typecheck for that head passed.
- Added the missing internal resolver contract beside `McpOAuthAccessTokenProvider` in commit `a350bf68a91ba28e85a5d4add1deb232c5e5791d`. This is a compile-contract repair only; it does not complete service migration.
- Exact-head Android verification for the repair is running as workflow `42997172705`; do not treat it as passing until the job completes successfully.
- The synchronous `McpConnectorService` call sites and the OAuth callback's static-token client construction remain outstanding. No OAuth network login or refresh has been executed during this work.


## Follow-up transport slice — 2026-11-11

- Android run `42997172705` confirmed the resolver interface repair compiled, then failed one regression test: the test used literal backslash-r/backslash-n instead of actual CR/LF characters. Corrected the test input in commit `154907dc15c91e592624267adb1c75881712c9a1`.
- Worker Typecheck for `154907dc...` passed (run `42997519827`). Its Android run `42997519747` was still running at the time of this update; no result is inferred from the earlier failed run.
- Added `McpConnectorService.callToolAsync(...)` in commit `250f5f88035e7c71ba5e67793ce3e6a68cbf6d8a`. It repeats the existing connector/session, argument-schema and single-use approval checks before invoking `McpHttpClient.postAsync`; it processes complete, input-required and task responses and persists task metadata. The token resolver and IO executor are supplied by the caller; no token is stored in the service.
- Exact-head Android run `42997652420` and Worker Typecheck `42997652353` were in progress at documentation time. Their results must be checked before marking this slice verified.
- Remaining migration: wire the Android UI to provide an OAuth token resolver and use `callToolAsync`; migrate connect/protocol negotiation/tools-list pagination and task get/update/cancel to callback-based transport. Existing synchronous service entry points remain for manual-token compatibility and are not OAuth-refresh-safe.


## Async tool/task API extension

- Added `McpConnectorService.callToolAsync` for OAuth-backed tool calls. Its authorization preflight is synchronized, validates connector session and input schema, consumes a matching single-use grant before dispatch, and validates complete/input-required/task responses after transport.
- Added `getTaskAsync`, `updateTaskAsync`, and `cancelTaskAsync`; each uses `McpHttpClient.postAsync` and therefore resolves a fresh OAuth bearer token per operation. Task response parsing continues through the existing task-state validation/persistence path.
- Added service regression coverage for async tool-call approval preservation, bearer-header delivery, resolver-failure fail-closed behavior, and the three task operations. Test additions are in commits `06e120fef10bece689551871dbff9a0b0273b774`, `d90358b979b3556e76d18f0b88e63690c95fe52b`, and `d93e28bab0dcb8717621a3541f31fde4fe3053da`.
- The latest source head at this update is `d93e28bab0dcb8717621a3541f31fde4fe3053da`. Do not treat tests as passing until its Android CI finishes.
- These new service APIs are not yet wired into `PluginDashboardPage` or `MainActivity`. Existing UI flows still use synchronous service methods, including connect and pagination; OAuth-safe operation is therefore not end-to-end until UI construction, authorization callback, connection negotiation, pagination and all tool/task actions are migrated.


## Dashboard transport integration — 2026-11-11

- `PluginDashboardPage` now detects an encrypted OAuth AuthState for the active connector and creates a short-lived `AuthorizationService` plus `McpOAuthAccessTokenProvider` for each user-triggered operation.
- Schema-form and JSON-form tool calls, including MRTR retries and elicitation form responses, now route through `McpConnectorService.callToolAsync` when OAuth state exists. The single-use approval grant is still issued before transport and consumed by the service preflight.
- Task refresh, task input submission, and task cancellation now route through `getTaskAsync`, `updateTaskAsync`, and `cancelTaskAsync` for OAuth-backed connectors. Each call resolves a token immediately before the HTTP request. The AppAuth service is disposed after the asynchronous result callback.
- Connectors without encrypted OAuth state retain the existing static/manual-token synchronous path. If encrypted OAuth state exists but cannot be read, the UI fails closed instead of falling back to a potentially stale static token.
- This slice does **not** migrate connection negotiation, legacy initialize/initialized notification, `tools/list` pagination, or the OAuth callback's initial static-token connection. Those remain outstanding and prevent an end-to-end per-request guarantee for all MCP HTTP operations.
- The latest source head for this slice is `12c2355e75e54bbbb7e0742fe16bf69594621141`. Exact-head Android and Worker CI must complete before this change is considered verified. No live OAuth authorization or token refresh was executed.


## Exact-head verification — 2026-11-11

Dashboard tool-call and task-lifecycle migration commit `d6bdaa29d13ec9362d1d49547be74b5aede67465` passed:
- Android build workflow run `42998747291`: Android unit tests, Debug APK assembly, and artifact upload all completed successfully.
- Worker typecheck workflow run `42998747304`: passed.
- These results verify compilation and automated tests at that exact source head only. They do not verify live OAuth authorization, a real refresh-token exchange, server interoperability, or device behavior.

Remaining high-priority migration work:
- Convert the full connection lifecycle (modern discovery, legacy initialize/initialized notification) to callback-driven token-resolved requests.
- Convert every tools/list pagination page to its own fresh-token request while preserving bounded cursor validation and atomic tool registration.
- Wire MainActivity's connection entry point to the async service API; ensure no OAuth connector can fall back to a stale static token.
- Add deterministic fake-resolver/fake-transport tests for negotiation fallback, pagination continuation, duplicate cursors, resolver failure, and cancellation.


## Async connection lifecycle migration — 2026-11-11

- Added `McpConnectorService.connectAsync(...)` to route modern discovery, legacy initialize fallback, `notifications/initialized`, and every `tools/list` page through `McpHttpClient.postAsync`. Each outbound request therefore asks the supplied resolver for a fresh-or-valid Bearer token; no OAuth token is retained by the HTTP client.
- Tool definitions are staged in memory and registered only after the full pagination chain validates. A resolver, protocol, schema, cursor, or registration failure removes connector tools, revokes grants, and leaves the connector disconnected. Pagination remains bounded by the 100-page / 5,000-tool limits; duplicate or blank cursors fail closed.
- `PluginDashboardPage.connect()` now selects the OAuth async connection path when encrypted OAuth state exists and no explicit manual token was entered. The prior stale static-token reconnect guard is replaced by a live async path. Explicit manual-token connection remains separate.
- `MainActivity` now uses the async connection path after the user completes the explicit AppAuth authorization flow. It no longer performs an initial one-off refresh and pins that access token into a long-lived client; the resolver is used for every handshake and discovery request.
- Added regression coverage for per-request resolver count across modern discovery + two-page pagination, legacy discovery + initialize + initialized notification + tools/list, and rollback when a later page contains an invalid tool.
- Source changes are currently on branch `fix/mcp-pagination-20261110`; exact latest head at this note: `57026a9d2928d6b999190c60a182ea49a64302fa`. Worker Typecheck passed at run `43000892511`; Android unit/build job `43000892481` was still in progress when this note was written. Do not mark this migration verified until Android completes successfully.
- No live OAuth browser login, token refresh, real MCP server connection, remote revocation, device QA, APK install, merge, or deployment was performed.


### Latest async-connection hardening and CI correction

- Added a per-connector in-flight connection guard so two concurrent OAuth connect attempts cannot race session negotiation; duplicate attempts fail safely.
- Added a 4,096-character maximum to opaque pagination cursors, alongside duplicate/blank-cursor rejection.
- Initial Android CI for the first async connection slice exposed a Kotlin callback-label compile error (`return@onSuccess` inside `Result.fold`). Corrected to `return@fold` in commit `171142e230424fd1f2c23610baf37538b4b31fc7`.
- Exact-head Android workflow run `43001294295` is running unit tests; Worker Typecheck is still pending/completing separately. The prior failed run `43000636860` is not a passing verification and is retained as evidence of the compile issue that was fixed.
- Current branch head after this note: documentation commit will be newer than the source fix; use the resulting PR head SHA and its own CI checks as the authoritative verification point.


## Connection-slot ownership hardening — 2026-11-11

- A code review found that a rejected duplicate `connectAsync` invocation could run its completion handler and remove the in-flight marker owned by the first invocation. That could admit a third concurrent negotiation for the same connector.
- Fixed this with an invocation-local ownership flag: only the call that successfully acquires the connector slot can release it. Rejected duplicates cannot alter the active attempt's lock.
- Added `asyncConnectDuplicateAttemptCannotReleaseActiveConnectionSlot`, which holds the first token callback, attempts two duplicate connections, verifies both are rejected without extra token resolution, releases the first attempt, and verifies a later reconnect succeeds.
- Source commit: `519f0758a89c202331c664358f325a6e2f7d6186`; regression test commit: `4d5c7266dac0bed73b6b25148ca63269f13064f1`.
- Exact-head CI for the test commit is required before this hardening is considered verified. No live OAuth or device testing was performed.


## OAuth-only transport enforcement — 2026-11-11

- A final call-site audit confirmed that the dashboard intentionally retains synchronous calls only in the no-encrypted-OAuth-state/manual-token branch. To prevent future regressions or accidental direct service calls, `McpHttpClient` now supports `allowSynchronousRequests=false`.
- OAuth-created clients in both `PluginDashboardPage` and the AppAuth callback path in `MainActivity` set this flag to false. Their `post(...)` calls now fail before opening a connection; `postAsync(...)` remains available and resolves a token per request.
- Added regression coverage proving an OAuth-only client rejects the synchronous path without network access and succeeds through asynchronous token resolution with a Bearer header.
- Related commits: `ae2fc2c57cc70f0a84499fd6d3806bf0026fac10`, `478e1cd880fc2012ad6cf644c14ff9a5693fa455`, `129b2be6ece15d4cbd8352f450b773164ab3c927`, `0826469f6dc7efd1210b3a6037555ecc0cad5ea2`, and `37d2da03ffd725edb534bf8a5613af6cf69913b4`.
- The latest PR-head CI must pass before this hardening is considered verified. The synchronous service API remains available to manual-token clients only; live OAuth and real MCP server behavior remain untested.


## Exact-head verification — OAuth async transport enforcement (2026-11-11)

- Verified branch head: `5faf7827f3b227d531c78e75921eb4e493829dad`.
- Android workflow run `43002371560`: PASS. Android unit tests, Debug APK assembly, and CI artifact upload all completed successfully.
- Cloudflare Worker Typecheck run `43002371508`: PASS.
- Regression coverage at this head includes per-request token resolution through asynchronous connection negotiation and paginated discovery, async tool/task transport, duplicate connection-slot ownership, and rejection of synchronous transport by OAuth-only clients while preserving async Bearer-token transport.
- The successful CI build produced a CI artifact only. It was not installed on a device and does not establish physical Android acceptance.
- This verification does not establish live OAuth authorization, real token refresh/rotation, remote revocation, real authorization-server or MCP-server interoperability, or deployed Worker behavior. No live credential flow or remote revocation was executed.
- PR #7 remains open and unmerged. No merge, deployment, or APK installation was performed.


## Superseding implementation and verification update — 2026-11-11

The earlier sections above are chronological engineering notes. Their statements that service/UI migration is pending are superseded by the integrated source at PR #7 head `0bb1cf53b3c79b20292dc68fa1b03e55eaac9098`.

- `McpOAuthMetadataFetcher` retrieves bounded HTTPS protected-resource and authorization-server metadata with redirects disabled, response-size/content-type limits and strict metadata validation. It also fetches and validates CIMD client metadata.
- `McpOAuthAuthorizationRequestFactory` builds the native public-client authorization-code request with AppAuth S256 PKCE and the exact RFC 8707 resource value. The pending request (including PKCE verifier/state) is encrypted before browser handoff.
- Callback validation checks AppAuth error state, the original pending request, state, client ID, redirect URI, response type, authorization/token endpoints, resource parameters and PKCE fields before token exchange.
- `McpOAuthTokenExchange` requires a safe resource, sends that resource again at the token endpoint, accepts only a non-empty Bearer token, persists AuthState through the AndroidKeyStore-backed encrypted secret store, and requires successful deletion of the one-time pending PKCE request before MCP connection readiness.
- `McpOAuthAccessTokenProvider` binds the registered connector ID and exact endpoint to the original resource. `McpOAuthTokenRefresher` serializes refresh per connector, persists rotated AuthState before exposing the in-memory token, and fails closed on refresh/storage errors.
- OAuth connection negotiation, legacy fallback/initialized notification, each tools/list page, tools/call and task get/update/cancel use the asynchronous per-request token resolver. OAuth-only clients reject the synchronous/static-token path. Manual-token clients retain their separate compatibility path.
- Explicit revocation uses the validated metadata revocation endpoint; local encrypted OAuth state is deleted only after remote HTTP 200 confirmation.
- Exact-head Android workflow `43051676040` and Worker workflow `43051675862` both completed successfully on `0bb1cf53b3c79b20292dc68fa1b03e55eaac9098`. Android job included unit tests and APK assembly; Worker job included npm audit, SBOM and typecheck. The subsequent documentation commits require their own exact-head CI.

**Status:** OAuth/CIMD implementation and automated regression coverage are present in source. Do not mark live OAuth complete: no real authorization-server login, token refresh/rotation, revocation, real MCP server interoperability, physical Android/AppAuth callback, or Android Keystore runtime verification has been performed. Remaining work is external integration/security verification, not another duplicate implementation of the same source flow.
