# Cloudflare ChatGPT ↔ Android relay (deployment scaffold)

This worker is a **first implementation scaffold**, not a live connection. It uses one Durable Object for one paired device. It never accepts shell text or arbitrary arguments.

## Required secrets (never commit these)
- `CHATGPT_CONNECTOR_TOKEN`: high-entropy bearer token for the authorized ChatGPT connector.
- `DEVICE_SETUP_TOKEN`: separate high-entropy secret used only by the Android app to begin pairing.

## Deploy
1. Create a Cloudflare Worker with Durable Objects enabled and bind the SQLite-backed Durable Object named `RELAY`.
2. Set both secrets with `wrangler secret put`.
3. Deploy from this directory with Wrangler.
4. Confirm `GET /healthz` returns status ok.
5. Configure an authenticated ChatGPT custom connector using `openapi/chatgpt-device-relay.yaml` and the connector token. Connector availability/configuration is an explicit account-side step.

## Pair
Android app calls `POST /v1/device/pair/start` with the setup token; it receives a six-digit code and a device token. The app must store the device token in Android Keystore-backed storage and display the short-lived code. The connector calls `POST /v1/chat/pair/claim` with that code. Only then can connector commands be queued.

## Command flow
- Connector: `POST /v1/chat/commands` with a fixed `operation_id` and idempotency key.
- Android: poll `GET /v1/device/jobs/next`, execute only the matching local allowlist operation, then POST its bounded result.
- Connector: `GET /v1/chat/commands/{command_id}`.

Jobs expire after 60 seconds; queue is capped at 20; results expire after 24 hours; audit metadata expires after 7 days. This initial scaffold is read-only. Android polling/client integration, Keystore storage, consent UI, automated tests, rate-limit hardening, and live deployment remain required before real use. Do not expose this as connected until physical-device round-trip QA passes.

## Security notes
- Keep both secrets out of source control and APK resources.
- Use HTTPS only; Cloudflare Worker routes are HTTPS.
- Rotate/revoke secrets immediately if exposed.
- The current setup token is a bootstrap credential; deployment should restrict its use and rotate it after pairing.
- One Durable Object currently represents one device; do not use this design for multiple devices without a device registry and per-device isolation.
