# Mosharrof Android Control

A standalone Android companion for user-authorized device inspection, file/media inventory, and explicitly approved MCP tool calls. It is independent of the Mosharrof AI app.

## AI-provider-neutral direction

The plugin runtime is designed to be independent of any AI vendor. Translation adapters and a shared runtime are now integrated in source for Gemini Interactions, OpenAI Responses, xAI Responses, Anthropic Messages and a Custom OpenAI-compatible path. AiEngine.askWithMcpApproval selects an encrypted provider profile and routes through bounded HTTP transport, provider response/continuation handling, guarded MCP tool orchestration and the explicit approval callback. Mocked provider E2E tests cover the local contracts; live vendor API round-trips, per-model capability verification, model/capability display and credential replace/revoke UX remain pending. Microsoft 365 Copilot remains a possible inbound MCP/declarative-agent route and requires a separately implemented MOSHARROF MCP server bridge. See [AI Provider Adapter Architecture](docs/AI_PROVIDER_ADAPTER_ARCHITECTURE.md) and [Multi-AI Implementation Plan](docs/MULTI_AI_ADAPTER_IMPLEMENTATION_PLAN.md).

## Implemented in the current branch
- Android-reported internal data-partition storage and RAM diagnostics.
- User-selected folder access through Android Storage Access Framework (SAF).
- Recursive file inventory (bounded to 5,000 files / 12 directory levels).
- SHA-256 duplicate-content detection; duplicate files are reported, never deleted.
- Audio metadata inspection (artist, album, duration) for common audio formats.
- HTTPS MCP connector UI reachable from the Android app home screen and navigation.
- MCP server discovery, cursor-based tools/list pagination (100-page/5,000-tool safety caps), supported-subset JSON Schema/argument validation (unsupported constraints are rejected), and Streamable HTTP tool calls.
- Explicit per-call approval for write-capable tools, with short-lived single-use grants bound to the exact tool and arguments; disconnect revokes connector grants.
- Provider-neutral canonical plugin/call/result models, capability profiles, built-in Gemini/OpenAI/xAI/Anthropic wire adapters, and a fail-closed provider tool-call guard that rejects unknown, ambiguous, malformed, or disconnected tools and never executes them itself.
- Connector bearer tokens encrypted with Android Keystore AES-GCM; plaintext tokens are not written to SharedPreferences.
- Optional device-initiated HTTPS relay client with encrypted device-token storage, bounded responses, strict job validation, in-app approval, persisted active-job/result recovery, and bounded network retries for idempotent operations. Termux commands remain limited to four fixed read-only diagnostics.
- Cloudflare Worker source uses expiring job leases, mandatory idempotency keys, idempotent result acknowledgements, queue completion, route-specific rate limits, and audit-state updates; deployed end-to-end behavior remains unverified.
- CI-built debug APK artifact.

## Not yet verified / not claimed
- MCP 2026-07-28 input_required MRTR is implemented with an explicit user-mediated response dialog, opaque requestState pass-through, exact request-key matching and bounded continuation. Native schema-driven elicitation/tool argument forms and HTTPS URL-mode browser handoff are implemented for supported shapes. MCP Tasks task-handle persistence/revalidation and explicit user-triggered refresh/input/cancel UI exist in source; no background polling is enabled. Real-server interoperability remains unverified.
- MCP OAuth/CIMD currently has bounded metadata validation and discovery URL helpers only. Live metadata/CIMD fetch, AppAuth PKCE authorization, issuer/resource-bound token exchange and encrypted refresh/revocation lifecycle remain pending; the connector currently accepts a manually supplied Bearer token.
- Physical-device QA on the user's phone, including a real external MCP server connection and real tool execution.
- Production deployment and end-to-end operation of the Cloudflare relay and its separately configured ChatGPT connector.
- Production deployment and end-to-end verification of Worker lease recovery, rate limits, idempotency, and audit records.
- Generic Android control, arbitrary shell access, or permission bypass.

## Android constraints
The user must select folders and grant access. Android restricts protected folders and app-private data. RAM is managed by Android; this app reports memory but cannot reliably force-clear other apps. SAF provider capabilities vary by device and storage provider. MCP servers must use a valid HTTPS endpoint; each tool call requires the user to review and approve the call in the app.

## Security
Read-only inspection is the default. Remote MCP tools are treated as untrusted capabilities: schemas and arguments are validated, write-capable calls require explicit approval, and grants are short-lived, single-use, and bound to the exact arguments. Connector credentials use Android Keystore AES-GCM. The relay currently accepts only predefined read-only Termux operation IDs; it is not a generic shell.

See [current-head audit](docs/CURRENT_HEAD_AUDIT_2026-11-11.md), [architecture](docs/ARCHITECTURE.md), [security model](docs/SECURITY.md), [Plugin System execution plan](docs/PLUGIN_SYSTEM_EXECUTION_PLAN.md), [Multi-AI implementation plan](docs/MULTI_AI_ADAPTER_IMPLEMENTATION_PLAN.md), and [Android build notes](android/README.md).