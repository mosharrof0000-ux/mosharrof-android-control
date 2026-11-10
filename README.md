# Mosharrof Android Control

A standalone Android companion for user-authorized device inspection, file/media inventory, and explicitly approved MCP tool calls. It is independent of the Mosharrof AI app.

## Implemented in the current branch
- Android-reported internal data-partition storage and RAM diagnostics.
- User-selected folder access through Android Storage Access Framework (SAF).
- Recursive file inventory (bounded to 5,000 files / 12 directory levels).
- SHA-256 duplicate-content detection; duplicate files are reported, never deleted.
- Audio metadata inspection (artist, album, duration) for common audio formats.
- HTTPS MCP connector UI reachable from the Android app home screen and navigation.
- MCP server discovery, cursor-based tools/list pagination (100-page/5,000-tool safety caps), supported-subset JSON Schema/argument validation (unsupported constraints are rejected), and Streamable HTTP tool calls.
- Explicit per-call approval for write-capable tools, with short-lived single-use grants bound to the exact tool and arguments; disconnect revokes connector grants.
- Connector bearer tokens encrypted with Android Keystore AES-GCM; plaintext tokens are not written to SharedPreferences.
- Optional device-initiated HTTPS relay client with encrypted device-token storage, bounded responses, validated command IDs, local duplicate protection, and a fixed allowlist of read-only Termux diagnostics. Every incoming job requires an in-app approval before Termux execution.
- CI-built debug APK artifact.

## Not yet verified / not claimed
- MCP 2026-07-28 `input_required` multi-round-trip responses and the optional Tasks extension are not yet supported; only `complete` results are consumed.
- MCP OAuth/CIMD authorization is not implemented; the current connector accepts a manually supplied Bearer token.
- Physical-device QA on the user's phone, including a real external MCP server connection and real tool execution.
- Production deployment and end-to-end operation of the Cloudflare relay and its separately configured ChatGPT connector.
- Server-side relay expiry, replay/idempotency, rate-limit, and audit enforcement; these require verification in the relay service.
- Generic Android control, arbitrary shell access, or permission bypass.

## Android constraints
The user must select folders and grant access. Android restricts protected folders and app-private data. RAM is managed by Android; this app reports memory but cannot reliably force-clear other apps. SAF provider capabilities vary by device and storage provider. MCP servers must use a valid HTTPS endpoint; each tool call requires the user to review and approve the call in the app.

## Security
Read-only inspection is the default. Remote MCP tools are treated as untrusted capabilities: schemas and arguments are validated, write-capable calls require explicit approval, and grants are short-lived, single-use, and bound to the exact arguments. Connector credentials use Android Keystore AES-GCM. The relay currently accepts only predefined read-only Termux operation IDs; it is not a generic shell.

See [architecture](docs/ARCHITECTURE.md), [security model](docs/SECURITY.md), and [Android build notes](android/README.md).