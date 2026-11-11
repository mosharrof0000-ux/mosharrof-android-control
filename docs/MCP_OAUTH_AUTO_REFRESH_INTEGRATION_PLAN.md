# MCP OAuth Automatic Refresh Before Every Request

Status: design/audit only — implementation not yet complete.

## Current verified state

- `McpOAuthTokenRefresher.withFreshToken(...)` is callback-based and persists refreshed AppAuth state through the encrypted `McpOAuthAuthStateStore` before exposing an in-memory Bearer token.
- Refresh attempts are serialized per connector; duplicate in-flight attempts fail safely.
- `McpHttpClient` currently owns a constructor-supplied, static `bearerToken` and its synchronous `post(...)` method uses that value for the Authorization header.
- `McpConnectorService` is synchronous and calls `client.post(...)` from connection negotiation, paginated `tools/list`, tool calls, and task get/update/cancel operations.
- UI entry points currently dispatch service work to the supplied worker `Executor`. This avoids main-thread network I/O but does not make token refresh happen before each request.
- MainActivity performs one refresh after OAuth token exchange and uses that token to create the initial MCP client. This is not a per-request refresh guarantee.

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

The current synchronous `McpConnectorService` / `McpHttpClient.post` call graph cannot safely consume the callback-based refresher without blocking. Introduce an asynchronous request pipeline and migrate all network-bearing service operations to continuation/callback-based APIs.

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
- Main-thread guard: no network or waiting occurs on the UI thread.
- Regression: manual/static Bearer token clients retain existing behavior.
- Redaction: access/refresh tokens never appear in logs, exceptions, status strings, or test output.

## Release status

This work is required before marking automatic OAuth refresh complete. Until implementation and exact-head CI tests pass, automatic refresh before every MCP request remains **incomplete**. No live OAuth login, token refresh, or remote revocation has been run as part of this audit.
