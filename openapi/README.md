# ChatGPT connector API — not deployed

The connector is **not live**. No public endpoint or credentials are included in this repository. Do not configure a ChatGPT connector against an untrusted or unauthenticated endpoint.

Before enabling remote actions, implement and security-test:
- HTTPS/TLS and explicit device pairing.
- Short-lived, revocable credentials stored outside source control.
- Typed, allowlisted operations only; never a generic shell endpoint.
- Per-command ID, expiry, scope, replay protection, and rate limiting.
- Explicit user consent for each sensitive operation and a durable audit record.
- Read-only defaults; preview plus separate confirmation for any destructive action.

The user must deploy the relay and explicitly configure/authorize a compatible ChatGPT connector. This repo and the local APK do not create that connection automatically.
