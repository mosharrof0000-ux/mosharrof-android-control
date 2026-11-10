# AI-Provider-Neutral Plugin Adapter Architecture

## Product requirement

MOSHARROF is an AI-provider-neutral plugin platform. It must not be designed around OpenAI-only request formats, tool schemas, authentication, or lifecycle assumptions. OpenAI, xAI Grok, Google Gemini, Anthropic Claude, Microsoft Copilot, and future providers are examples of providers to evaluate; listing a provider does not imply that its integration has been implemented or verified.

## Architectural boundary

Keep the plugin runtime and device-safety policy independent of any AI vendor:

1. **Canonical Plugin Contract** — stable provider-neutral tool identity, description, JSON-schema input, result envelope, risk classification, approval requirements, timeout/cancellation, and error model.
2. **Plugin Runtime** — discovery/registration, schema validation, execution dispatch, audit events, approval grants, and result normalization. The runtime never trusts a provider to authorize device actions.
3. **Provider Adapter** — translates each provider's native request, tool/function declaration, response, streaming and authentication conventions to/from the canonical contract.
4. **Capability Profile** — records verified support per provider/model/API version: native tool calling, parallel calls, streaming, structured output, MCP client/bridge support, confirmation handoff, file/image inputs, and known limits. Unknown means unsupported until verified.
5. **Provider Catalog** — versioned documentation links, API versions, auth requirements, supported capabilities, known limitations, test fixtures, last verification date, and adapter version.

## Adapter contract

Every adapter should implement the same conceptual operations:

- identify provider/model/API version and validate configuration;
- report capabilities and unsupported features explicitly;
- convert canonical plugin definitions to provider-native declarations;
- parse provider tool-call requests into canonical calls;
- return normalized tool results/errors to the provider;
- preserve call IDs, argument integrity, streaming boundaries and cancellation semantics;
- never bypass MOSHARROF's user approval and least-privilege gates.

Provider-specific payloads must remain inside the adapter. They must not leak into the plugin runtime or Android device-control layer.

## Integration strategy

- Prefer the provider's official API, SDK, MCP support, or documented tool/function-calling interface.
- If a provider uses a different protocol, implement a dedicated adapter or a documented protocol bridge; do not fork the plugin runtime.
- If a provider lacks a required capability, mark that capability unavailable and use only an explicitly supported alternative workflow. Never pretend an unsupported capability works.
- If documentation or API behavior changes, inspect current official documentation and release notes, update the provider capability profile and adapter, add fixtures/regression tests, then verify against a real provider endpoint when credentials and approval are available.
- If a provider exposes no permitted integration interface for the needed operation, record the blocker. An adapter cannot create a vendor-side API or force a provider to add a feature.

## Security invariants

- Provider adapters are untrusted translation boundaries; validate all inbound tool names, IDs, arguments and schemas again in the canonical runtime.
- User approval is enforced by MOSHARROF, not delegated to the AI provider.
- Write, external-side-effect and destructive operations require explicit, argument-bound, single-use approval.
- Credentials are isolated per provider and stored using platform secure storage; never place secrets in logs, fixtures, screenshots or documentation.
- Apply bounded payload sizes, timeouts, retry policies and response parsing to every adapter.
- Provider failure or capability uncertainty fails closed; it must not silently downgrade into arbitrary shell, broad device access or an unapproved action.

## Onboarding a new provider

1. Identify the exact provider, API product, model family and current API version.
2. Read official API/tool-calling/MCP/auth documentation and note regional/account restrictions.
3. Complete a capability matrix with source links and verification date.
4. Implement the adapter against the canonical contract without modifying plugin runtime policy.
5. Add unit fixtures for valid, malformed, oversized, duplicate, partial, streaming and error responses.
6. Add contract tests proving identical canonical plugin behavior across adapters.
7. Run CI; then, with credentials and explicit approval, perform live endpoint interoperability tests.
8. Update this catalog, security notes, execution ledger and ToolHub/master registry with verified versions, commands, configuration paths, evidence and rollback notes.

## Current status

This document defines the required architecture; it is not evidence that Grok, Gemini, Claude, Copilot or OpenAI adapters are already implemented. The current Android repository has an MCP client and provider-neutral plugin safety/runtime foundations, but it does not yet contain a complete multi-provider adapter registry or verified live provider integrations. Track implementation and tests in `PLUGIN_SYSTEM_EXECUTION_PLAN.md`.
