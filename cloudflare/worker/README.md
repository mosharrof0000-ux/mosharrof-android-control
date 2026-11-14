# Cloudflare ChatGPT ↔ Android relay

The Worker and Android Companion now contain the first end-to-end client implementation. Production use is still gated on deploying the Worker, setting secrets, configuring the ChatGPT connector, pairing, and physical-device QA.

## Deploy
Configure these repository Actions secrets (never commit or paste them into source):
- `CLOUDFLARE_API_TOKEN`: token with Workers Scripts and Durable Objects deployment permissions.
- `CLOUDFLARE_ACCOUNT_ID`: Cloudflare account ID.
- `CHATGPT_CONNECTOR_TOKEN`: high-entropy bearer token for the ChatGPT connector.
- `DEVICE_SETUP_TOKEN`: separate high-entropy bootstrap token for Android pairing.

Run the **Deploy Cloudflare Relay** workflow manually, or push a change under `cloudflare/worker/**`. The workflow installs Wrangler, sets both Worker secrets from GitHub Actions secrets, and deploys the Worker with its SQLite Durable Object migration. The workflow intentionally fails closed if any required secret is missing. Do not print secrets in logs.

After deployment, check `https://<worker-name>.<account>.workers.dev/healthz`. It reports whether deployment secrets are configured, but never returns secret values.

## Pair Android
1. Install the newly built Android Companion APK and grant the Termux RUN_COMMAND permission only if you want Termux diagnostics.
2. In Android Companion, enter the Worker HTTPS URL and the one-time `DEVICE_SETUP_TOKEN`, then tap **নিরাপদ Relay Pairing শুরু করুন**.
3. The app exchanges the setup token over HTTPS, receives a short-lived six-digit pairing code and device token, and stores the device token encrypted using Android Keystore AES-GCM. The setup-token field is cleared after pairing.
4. Use the configured ChatGPT connector to call `POST /v1/chat/pair/claim` with the six-digit code. The connector bearer is `CHATGPT_CONNECTOR_TOKEN`.
5. Tap **Relay polling চালু করুন**. While the app is open in the foreground, it polls every 3 seconds, executes only the four fixed read-only Termux operation IDs, and returns bounded stdout/stderr and exit code to the relay.

## Connector
Import `openapi/chatgpt-device-relay.yaml` into an authenticated custom connector. Set its server URL to the deployed Worker URL and configure HTTP Bearer authentication with `CHATGPT_CONNECTOR_TOKEN`. Never include either token in source, issue comments, or screenshots.

## Boundaries and limitations
- No arbitrary shell text, arbitrary executable paths, caller-provided arguments, file deletion, or file modification.
- Device token is protected by Android Keystore; all network traffic requires HTTPS.
- Polling is foreground-only in this version. Keep Android Companion open while testing; Android may stop background work. A later foreground-service design would require a visible persistent notification and explicit user consent.
- Jobs expire after 60 seconds and use a 15–30 second renewable-on-repoll lease instead of destructive read-and-pop; unacknowledged jobs become available again until expiry. Queue is capped at 20; identical result retries are idempotently accepted, conflicting results are rejected, and accepted results remove the job atomically. Results expire after 24 hours; audit metadata expires after 7 days.
- Server rate limits: connector command creation 20/minute, device polling 30/minute, and result submissions 30/minute. Every command requires a stable 8–100 character idempotency key.
- The current relay is for one paired device. Do not use for multiple devices without a per-device registry and isolation.
- A green deploy does not prove device connectivity. Require a physical Android ↔ Worker ↔ connector round-trip before calling it live.
