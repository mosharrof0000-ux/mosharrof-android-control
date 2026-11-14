# Architecture and implementation status

## Product boundary
This is a standalone Android companion intended to expose user-authorized capabilities through a controlled interface. It does not require the Mosharrof AI app. MCP support is provider-neutral at the protocol/tool layer; an AI provider still needs a compatible MCP client or separately configured connector.

## AI-provider-neutral integration direction

The plugin contract and safety runtime are intended to be independent of any AI vendor. Each AI provider/model/API version must connect through its own adapter translating native tool/function/MCP requests into the same canonical plugin contract. Capability support must be documented and verified per provider; unsupported features must fail closed rather than be simulated. See [AI Provider Adapter Architecture](AI_PROVIDER_ADAPTER_ARCHITECTURE.md). This is an architectural requirement, not a claim that Grok, Gemini, Claude, Copilot or OpenAI adapters are already implemented or live-tested.

## Implemented local capabilities
- Read-only storage and RAM diagnostics.
- User-selected Storage Access Framework tree, bounded inventory, SHA-256 duplicate detection, and audio metadata.
- Optional Termux RUN_COMMAND bridge with a strict operation allowlist: Python version, fixed video-project Git status, Termux home storage report, and system information.
- Termux command output is returned through a private app BroadcastReceiver and stored locally for display.
- MCP Plugin Connector page is wired into the Home dashboard and bottom navigation.
- The MCP client supports modern per-request discovery and the legacy initialize handshake, HTTPS Streamable HTTP requests, tools/list, tools/call, schema/argument validation, and connector disconnect.
- Each tool invocation is presented with its risk and input JSON. The user must explicitly approve the call; write-capable tools require a short-lived, single-use grant bound to the exact tool and arguments. Disconnect removes registered tools and revokes connector grants.
- MCP bearer tokens are stored encrypted using Android Keystore AES-GCM.
- The optional HTTPS relay client supports device pairing, polling, and posting results for a fixed allowlist of read-only Termux operations. Its device token is encrypted with Android Keystore AES-GCM.

## Relay and external integration status
Client-side relay code and UI are present, but this repository alone does not prove that the relay Worker is deployed, that a ChatGPT connector is configured, or that an end-to-end job has completed. Those steps remain unverified until the deployed service and a real device are tested together.
The relay allowlist is intentionally narrow. It does not accept arbitrary shell commands or caller-supplied executable paths/arguments. Server-side job expiry, replay/idempotency protection, rate limits, and audit enforcement must be verified in the relay service before production use.

## MCP verification status
GitHub Actions Run #39 passed the JVM unit tests and debug APK build for commit 92efbf55b897992edc354622813a2aab87ab3efa. The MCP dashboard wiring and protocol fallback correction in the current change require the new CI run to complete. Real-device verification remains pending:
1. Install the resulting APK.
2. Connect to a real HTTPS MCP server.
3. Confirm tool discovery and schemas.
4. Approve a safe test tool call and verify the returned result.
5. Disconnect and verify the tools/grants are no longer usable.
Do not treat a successful build or mock-server test as proof of real-device connectivity.

## Termux setup required on the phone
1. Install Termux from a trusted source and open it once.
2. In Termux, edit ~/.termux/termux.properties and set allow-external-apps = true, then restart Termux.
3. In Android Settings -> Apps -> Android Companion -> Permissions -> Additional permissions, grant Run commands in Termux environment.
4. Open the companion and run one of the four predefined Termux diagnostics.
5. The video-project status check expects the repository at ~/mosharrof-video-processing.

Termux documents both the Android permission and allow-external-apps as mandatory safeguards. See the official RUN_COMMAND documentation: https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent.

## Android limits
Only the fixed Termux operations are allowed; there is no generic shell, delete, overwrite, or permission bypass. Android SAF limits file access to user-selected folders. MediaProjection requires Android system consent. Ordinary apps cannot reliably clear other apps' RAM.