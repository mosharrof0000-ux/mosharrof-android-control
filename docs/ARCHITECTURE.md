# Architecture and implementation status

## Product boundary
Mosharrof Android Control is a standalone Android companion. ChatGPT is intended to be the conversational interface. The Mosharrof app is not a dependency.

## Implemented local capability
- Native Android app with read-only storage and RAM diagnostics.
- User-selected Storage Access Framework tree.
- Bounded recursive inventory, SHA-256 duplicate detection, and audio metadata extraction.
- CI debug APK build.

## Still required for remote ChatGPT control
1. Device enrollment and pairing UX.
2. Authenticated relay service over TLS with short-lived/revocable credentials.
3. Typed operation schemas, command expiry, unique IDs, replay/idempotency checks, rate limits, and audit trail.
4. Device-initiated outbound connection and foreground-session consent.
5. OpenAPI contract and an explicitly configured/authorized ChatGPT connector.
6. Integration tests, threat review, and real-device validation.

A GitHub repository or APK alone does not connect ChatGPT to the phone. The hosted relay needs an actual deployment and credentials; the user must explicitly authorize the connector.

## Future local milestones
- Reversible quarantine and safe file move/copy with preview and confirmation.
- More complete media indexing, playlists, and exportable reports.
- Usage Access app-usage reporting (separate user-granted special access).
- User-initiated screen sharing using MediaProjection system consent.
- Optional separately permissioned Termux task agent.

## Android limits
Scoped storage limits visibility to user-selected folders and public media APIs. Android/data and app-private files are protected. MediaProjection requires the system consent UI. Ordinary apps cannot reliably clear other apps' RAM or override system controls.
