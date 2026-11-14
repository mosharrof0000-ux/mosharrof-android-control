# Current-Head Implementation Audit — 2026-11-11

**Repository:** `mosharrof0000-ux/mosharrof-android-control`  
**PR:** #7 — open, unmerged  
**Audited head:** `8713269590dc3a1b9621d1abf66bbe7a15ea8bd0`  
**Branch:** `fix/mcp-pagination-20261110`

This is a source-and-CI audit, not physical-device, live-provider, real-MCP-server, or deployed-Worker verification.

## Exact-head CI evidence

| Check | Run | Result | Evidence |
|---|---:|---|---|
| Android build | 42966451646 | PASS | Unit tests, debug APK assembly and artifact upload all completed successfully on the audited head |
| Cloudflare Worker typecheck | 42966451641 | PASS | Dependency install and typecheck completed successfully on the audited head |

Android artifact:
- Name: `mosharrof-android-control-debug`
- Artifact ID: `13831841393`
- Size: `2,412,723` bytes
- SHA-256: `267b9a4f295f67365f049f76a70eb92070b0b43aff816b2e7810b1cf064c7b43`
- CI artifact only; not installed on a physical device.

## Source findings at audited head

### Outbound AI provider path
- `AiEngine.askWithMcpApproval` selects the active encrypted provider profile and routes it to `AiProviderRuntime.askText`; without an active profile it falls back to the legacy Gemini generateContent / generic OpenAI-compatible text path.
- `AiProviderRuntime` builds first-turn requests through `AiProviderRequestFactory`, decodes canonical responses, bounds continuation rounds and total tool calls, and routes calls through `AiToolCallOrchestrator`.
- The runtime limits tools to the exact connected `McpConnectorService` session. Approval-required calls pass through the supplied approval callback and then the service's one-time approval grant path. Read-only calls do not receive grants.
- Gemini Interactions, OpenAI Responses, xAI Responses, Anthropic Messages and Custom OpenAI-compatible translation/request/continuation builders are present in the source tree. Mock contract/E2E tests do not establish live endpoint compatibility.
- Legacy configuration remains supported. Per-provider profile storage uses Android Keystore AES-GCM with profile-ID AAD and separate metadata/secret entries.
- Provider model/capability display, credential replace/revoke UX, live connection tests and live API round-trips remain incomplete or unverified.

### Cancellation and recovery
- Provider HTTP POST requests are not automatically retried. Cancellation disconnects the active connection; a dispatched request without a received HTTP response is treated as an uncertain outcome.
- The AI recovery marker persists only a boolean and observation timestamp. Its four JVM tests cover store recreation against retained persistence, review clearing, the deliberately narrow data contract and negative timestamp rejection.
- The marker is a user review reminder, not provider-side reconciliation. Actual Android SharedPreferences behavior still requires Android runtime/device verification.

### MCP and approval
- Source includes bounded opaque-cursor pagination, schema/argument validation, exact-service-session tool scoping, one-time argument-bound approval grants, and fail-closed handling for unsupported result types.
- MCP task metadata is persisted with connector/task IDs, status and bounded timing metadata only. Recovered pending records are untrusted candidates and must be revalidated against the server with `tasks/get`. Task start/input/cancel UI paths require explicit user actions.
- OAuth/CIMD work in `McpOAuthMetadata` is metadata URL derivation and validation logic. It is not a complete OAuth flow: metadata fetching, CIMD integration, PKCE authorization/token exchange, encrypted token lifecycle and live end-to-end verification remain outstanding.

### Relay and device boundary
- Android Relay source constrains operations to a fixed read-only Termux allowlist, validates command IDs, requires the app-side approval path before execution, bounds responses, and avoids automatically re-executing a command after an uncertain Termux callback.
- Worker lease/idempotency/rate-limit controls are source-level plus CI-tested only; deployed Worker behavior has not been verified.
- Manifest includes the Termux RUN_COMMAND permission and an exported AccessibilityService. Actual Android permission prompts, accessibility enablement, Termux handoff and physical-device behavior have not been tested.

## Documentation drift found

Some earlier sections in `PLUGIN_SYSTEM_EXECUTION_PLAN.md`, `MULTI_AI_ADAPTER_IMPLEMENTATION_PLAN.md` and `SECURITY.md` still describe older states—for example, that the provider runtime/tool loop is not wired, that task persistence/UI is pending, or that Tasks are not implemented. Those statements conflict with the current source and later roadmap entries. This audit records the current source state; the older documents still need a careful line-by-line reconciliation so historical notes are not mistaken for current status.

## Remaining unblocked work

1. Reconcile the execution plan, multi-AI plan, security document and roadmap against the current source and exact-head CI.
2. Complete a whole-repository tracked-source and history review for secret/log leakage; inspect error surfaces and add redacted audit events.
3. Add interrupted-continuation and malformed continuation regression fixtures; strengthen UI state regression coverage.
4. Complete provider model/capability and credential replace/revoke UX.
5. Complete dependency/license review and a known-limitations register.

## External release gates (not performed)

- Live provider API round-trips require credentials and explicit authorization.
- Real MCP interoperability requires a reachable server and explicit test setup.
- Android Keystore failure/durability, Termux handoff and AccessibilityService behavior require Android runtime/physical-device verification.
- Deployed Worker verification requires deployment approval.
- APK installation and physical Android QA require explicit user approval.
- PR #7 merge requires explicit user approval.

**No merge, deployment, live credentialed test, or APK installation was performed for this audit.**

## 2026-11-11 provider profile UX follow-up

After the source audit above, the branch added:
- Credential-only replacement for an existing encrypted provider profile. Provider/model/endpoint metadata and active selection are preserved; invalid values are rejected before the replacement write.
- Profile-manager UI for hidden credential replacement, and a per-profile model/API-version/adapter-version capability view. Capability values remain UNKNOWN unless the adapter explicitly reports otherwise; this is not live model discovery or a vendor API test.
- Regression tests for successful replacement, metadata/active-profile preservation, and rejection of missing profiles/invalid credentials.

Verification for source commit `fad2354c48d96f0f2a2c2e82eb6842a19e328814`: Android CI run `42968929411` PASS (unit tests, debug APK assembly and artifact upload); Worker CI run `42968929491` PASS. Artifact `mosharrof-android-control-debug`, ID `13832821191`, size `2,415,440` bytes, SHA-256 `035720631ab1cfd2fc8ce73141e10f07f53f41bc818fb06e8ff811561d8251c0`. CI artifact only; no physical install/device QA.

## Partial static logging/error-surface scan

- Reviewed 24 of the 65 files changed in PR #7 at the provider profile UX source head. Pattern checks covered Android Log calls, println/printStackTrace, direct HTTP error-body-to-exception patterns, and obvious credential interpolation.
- No direct Android Log/println/printStackTrace calls or obvious credential interpolation were found in those 24 inspected files.
- Two UI status paths surface exception messages (credential replacement and MCP token deletion). The visible messages do not interpolate credential values, but their exception-source safety still belongs in the full error-surface review.
- This is a limited changed-file scan only. It does not cover every tracked file, commit history, dependencies, generated files, runtime logs, crash reports, or every exception path; the whole-repository/history audit remains OPEN.


## 2026-11-11 high-risk transport and credential review — PR head `d639cd0ed3b531dae514d198b4be227810c7f6f0`

Reviewed the following 12 changed files at the exact PR head: `AiProviderProfileStore.kt`, `AiProviderHttpTransport.kt`, `AiProviderRuntime.kt`, `AiToolCallOrchestrator.kt`, `McpHttpClient.kt`, `McpConnectorService.kt`, `RelayClient.kt`, `TermuxBridge.kt`, `ToolApprovalGrants.kt`, `EncryptedSecretEnvelope.kt`, `AndroidManifest.xml`, and `android/app/build.gradle.kts`. Also inspected the relevant MCP/AI transport and Relay security test fixtures.

### Controls confirmed in source
- AI provider transport enforces HTTPS, rejects URL user-info/query/fragment, uses a fixed allowlist of request headers, bounded request/response sizes, finite connect/read timeouts, disables redirects, and does not automatically retry POST requests.
- MCP transport validates its endpoint at construction, disables redirects, bounds request/response bodies, and converts HTTP errors to status/RPC metadata rather than retaining arbitrary server error text. Regression tests explicitly verify that an echoed bearer token and arbitrary error message are not exposed.
- Relay normalizes an HTTPS endpoint without user-info/query/fragment, disables redirects, bounds responses, limits command IDs and operation IDs, stores the device token encrypted with Android Keystore AES-GCM, and restricts retry to its documented idempotent request paths/statuses.
- Termux bridge uses fixed executable paths and fixed argument arrays; unknown operation IDs are rejected. The result receiver is non-exported and the manifest disables app backup.
- Provider credentials are encrypted using Android Keystore AES-GCM with profile ID as AAD. Profile metadata and encrypted secret are separate; credential replacement validates before writing and preserves profile metadata/active selection.

### Findings and limits
- No confirmed credential disclosure or unsafe redirect was demonstrated in this reviewed batch.
- `AiToolCallOrchestrator.safeFailureMessage` forwards a bounded, newline-normalized exception message to the provider; `TermuxBridge` also includes selected Android exception messages in local status text. These are not proven secret leaks, but their exception-origin review and redaction policy remain OPEN.
- Relay URL validation permits a caller-configured HTTPS host and optional port/path; this is intentional for configurable relay endpoints, but trust still depends on the user configuring the correct server. No domain pinning is implemented or claimed.
- This is a targeted review of 12 high-risk changed files, not a complete audit of all 65 PR files, every tracked file, Git history, dependencies, runtime logs, crash reporting, or deployed services. Whole-repository/history review remains OPEN.
- No source code was changed as part of this audit pass. This review does not replace CI, live endpoint testing, Android runtime testing, or independent security review.


## 2026-11-11 PR-wide error-surface scan and redaction follow-up

**Reviewed PR head before redaction:** `552d496ae3db21c1b429196e3e9c6577a32b72ea`. A patch-level heuristic scan covered all 65 changed files and classified added lines for logging calls, credential-shaped values, exception-message surfaces, HTTP transport configuration and sensitive preference use. This is a triage scan, not proof that every possible leak is absent.

### Changes made
- Added `safeUiErrorMessage(Throwable)`, which returns a fixed generic message and never includes the throwable message, class name, stack trace, URL, server response or credential.
- Replaced raw exception-message interpolation across `MainActivity` and `PluginDashboardPage` status surfaces with the redaction helper.
- Changed AI tool execution error content sent back into the provider continuation to a fixed redacted failure string instead of forwarding arbitrary exception text from MCP/server/library failures.
- Added regression coverage asserting that an exception containing a bearer-like secret is not reflected in the UI-safe message, and updated the orchestrator failure test to assert the fixed redacted result.
- Post-change source scan of the two UI files and orchestrator found no remaining direct `.message` / `localizedMessage` / `exceptionOrNull()?.message` surfaces.

Commits:
- `e3bd5542de328657e909196e3e9c6577a32b72ea` — orchestrator/provider error redaction
- `ea5ecbc4b40892b3284e8a5c249a879762fc46e7` and `8ac81f14f4fe9030b2de9ec2ce2e3d8cb5a5cd37` — initial Android UI redaction
- `b09ab81f8f2557968364dba40467680e212ce77f` — redaction regression test
- `8caf5dfe1fde0d2f268e42cb30b3a4c231d336a8` and `397384a2b1b9a7546e107adba4b526fe33539981` — removed remaining raw Termux/MCP connection exception text

### Verification status
- Worker CI run `42970314846`: PASS.
- Android CI run `42970314972`: in progress at documentation update; Android unit tests, APK assembly and artifact upload are not yet claimed as passed.
- The UI helper test is JVM test coverage only. No Android device, real server, live provider or deployed Worker test was performed.
- Whole-repository source and Git-history audit, dependency/license audit, runtime log/crash-report review and independent security review remain OPEN.


### CI failure triage and correction — 2026-11-11
- Android CI run `42970314972` failed in `testDebugUnitTest`: 323 tests ran, one failed.
- Exact failure: `AiProviderRuntimeEndToEndTest.mcpExecutionFailureIsReturnedToProviderAndConversationContinues` still expected the old raw simulated MCP exception text after the intended redaction change.
- This was a stale test expectation, not a Kotlin compilation failure: main and test Kotlin compilation completed; the failure occurred at the assertion in `AiProviderRuntimeEndToEndTest.kt:268`.
- Corrected the regression test to assert the fixed redacted provider error string and assert that the simulated server exception text is absent.
- Fix commit: `a176ef152b515b28818e9dedb4020c11db7fdd55`.
- Worker CI run `42970314846` passed for the prior source head. Android CI for the fix commit has not yet returned a run/result at the time of this note; rerun status is pending.


### Follow-up defense-in-depth source scan
- Scanned all 22 changed Android main-source Kotlin files for direct exception-message rendering, stack-trace printing, println/Android Log calls, raw response-body forwarding and obvious bearer/authorization concatenation.
- No direct logging or raw exception-message surfaces were found in the provider, MCP, Relay, encryption, approval-grant and task source files checked, with one additional Termux bridge exception path found.
- Removed the remaining raw `SecurityException.message` and `IllegalStateException.message` concatenation at the Termux bridge boundary in commit `d162a314ac0520d5511dd06ad751b9ce163e3c3b`.
- `PluginDashboardPage` matches from the regex scan were false positives on `dialog.dismiss()`, not error-text rendering.
- This is a source-pattern review of changed Kotlin production files, not a complete repository, Git-history, dependency, runtime or crash-report audit.

### Latest CI for defense-in-depth source commit
- Worker CI run `42970919190`: PASS.
- Android CI run `42970919165`: still in progress at this note; unit tests currently running, APK assembly/upload pending.


### Latest Android CI verification — 2026-11-11
- Android CI run `42970919165` (run #316) for source commit `d162a314ac0520d5511dd06ad751b9ce163e3c3b`: PASS.
- Android unit tests: PASS; debug APK assembly: PASS; artifact upload: PASS.
- Cloudflare Worker CI run `42970919190` (run #272): PASS.
- Debug artifact ID: `13832594882`; size: 2,415,618 bytes; SHA-256 digest: `30f9dfe103e9003f6a6af8bc50d40da20d7e368b36d582064666e45150e6a76d`.
- This is a CI-produced debug artifact only; it has not been installed or exercised on a physical Android device.


## 2026-11-11 expanded tracked Android source audit — follow-up
- Retrieved the complete tracked repository tree at source commit `d162a314ac0520d5511dd06ad751b9ce163e3c3b`: 92 tracked files, including 35 Android production Kotlin files and 29 Android JVM test files.
- Expanded the error/log/credential-pattern scan from the 22 changed Android production files to the remaining 13 Android production Kotlin files.
- Found additional raw exception-message surfaces in `AndroidKeystoreDiagnostic.kt`, `EngineSelfTest.kt`, `PermissionCenter.kt` and `core/module/ModuleManager.kt`. These were outside the earlier PR-changed-file scan.
- Replaced those diagnostic/UI/module-status exception strings with the shared fixed `safeUiErrorMessage` redaction helper.
- Updated module lifecycle regression tests to assert the fixed redacted failure detail instead of the raw exception message.
- Source commits: `14c460e97b439ceb420bd2611c5f2f4a7611c7db`, `c76254451b91cf17c18146d33c96fb31a3990ddf`, `6222ebaa78efabb2a62a728709dc4e075af33242`, `99ce8ec7309bc417b4fa05f4948bceffc1eb5205`; regression test commit `b465c948d47129b6d3df1d77f6b548ad0353e7fc`.
- Re-scanned these previously unmodified production files after redaction; no remaining direct exception-message, logging, stack-trace, raw response-body or obvious authorization-concatenation matches were found by the selected patterns.
- This is now a pattern scan across all 35 tracked Android production Kotlin files, not a semantic audit of every call path. Whole-repository non-Kotlin source, full Git history, dependencies/licenses, runtime logs, crash reports, Android Keystore behavior and deployed services remain open.
- Latest Android and Worker CI for the new source/test commits: pending at the time of this note. Check commit `b465c948d47129b6d3df1d77f6b548ad0353e7fc` before treating these additional changes as verified.


## 2026-11-11 latest PR-head CI and dependency inventory follow-up

**Exact PR head checked:** `8f0d2bb4a7cd0fd318e70f01cd26eb359c859afd` (70 changed files; PR #7 remains open and unmerged).

### Exact-head automated verification
- Android build run `42972244593` (run #328): PASS. The `assemble` job completed Android unit tests, debug APK assembly and artifact upload.
- Cloudflare Worker check run `42972244607` (run #284): PASS.
- Android artifact `mosharrof-android-control-debug`, ID `13834056036`, size `2,415,480` bytes, SHA-256 `645aca2aca037d4ae7bbaae51433cfc346a27ed0a043589dd1e504148674e0cb`.
- Artifact is CI output only; it has not been installed or tested on a physical Android device.

### Dependency inventory — preliminary, not a license clearance
- Android direct dependencies in `android/app/build.gradle.kts`: `androidx.documentfile:documentfile:1.0.1`, `net.openid:appauth:0.11.1`, test-only `junit:junit:4.13.2`, and test-only `org.json:json:20240303`.
- Cloudflare Worker direct devDependencies in `cloudflare/worker/package.json`: `@cloudflare/workers-types ^5.20261101.1`, `typescript ^5.9.3`, and `wrangler ^4.45.0`.
- The PR changed-file inventory contains no `package-lock.json`, npm shrinkwrap, Gradle dependency lockfile, or dependency-verification metadata. The Worker ranges therefore do not pin the full transitive graph; the CI workflow's dependency install is not a reproducible lockfile-based install.
- No generated SBOM, complete transitive dependency inventory, license attribution report, or vulnerability scan evidence was found in the reviewed files. Do not treat the direct dependency list as a complete license or vulnerability audit.
- Follow-up needed: generate and commit a Worker lockfile using the project's supported npm version; enable Gradle dependency locking/verification; produce an SBOM and review all direct/transitive licenses and known vulnerabilities before release. This must be done in a build-capable environment so integrity hashes and resolved versions are generated by the package managers, not fabricated here.

### Scope and remaining gates
- All 35 tracked Android production Kotlin files received a selected-pattern scan for exception-message rendering, logging/stack traces, raw response forwarding and obvious authorization concatenation. This is a heuristic source scan, not semantic proof and not a Git-history audit.
- The latest redaction changes and regression tests are covered by the exact-head Android CI above.
- Still open: full semantic source/history audit (including non-Kotlin and generated/configuration paths), lockfiles/SBOM/license and vulnerability review, UI transition coverage, interrupted/malformed continuation fixtures, OAuth/CIMD implementation, Android Keystore and physical-device verification, real MCP/live-provider tests, deployed Worker verification, independent security review, and external release approval.
- No live credentials, deployment, APK installation, or PR merge were used for this verification.


## Latest exact-head verification — 2026-11-11 (`9ec21e6ad82d95f2f060da4481ab9b6de895968c`)

- Android workflow run `42973156641` (run #332): Android unit tests PASS; Debug APK assembly PASS; artifact upload PASS. Job cleanup was still running when inspected; all three build/test/upload steps completed successfully.
- Worker workflow run `42973156667` (run #288): PASS.
- Artifact `mosharrof-android-control-debug`, ID `13833758820`, size `2,415,482` bytes, SHA-256 `2c2acc4fcfada40c741e85e8f5cb2cf5cb3a94145dcdd8531596fc40e951e59d`. CI artifact only; not installed or physically tested.
- Added `cancellationDuringApprovalPreventsMcpExecutionAndContinuation`: cancellation from the approval callback causes cancellation failure, exactly one initial provider request, one approval callback, and zero MCP tool calls.
- Added `rejectsMalformedProviderHistoryBeforeBuildingContinuation`: a malformed Responses API history item is rejected before continuation request construction.
- Remaining release blockers are unchanged: UI transition/real-device coverage, OAuth/CIMD, live MCP/provider tests, deployed Worker verification, reproducible dependency locking, full transitive SBOM/license/vulnerability review, independent security review, and explicit merge/install authorization.


## 2026-11-11 OAuth metadata validator hardening

- Reviewed the OAuth/CIMD metadata helper and JVM tests. The authorization-server metadata validator previously accepted an HTTPS issuer identifier containing a query component, although RFC 8414 issuer identifiers exclude query and fragment components.
- Added a dedicated HTTPS issuer validator that rejects query/fragment components while preserving the existing HTTPS, host and no-user-info checks.
- Added a regression test proving that a query-bearing issuer is rejected by both metadata URL derivation and authorization-server metadata validation.
- This is narrow metadata-validation hardening only. Metadata fetching, CIMD discovery, PKCE, token exchange, refresh/revocation and a complete OAuth flow remain unimplemented.
- This new source/test head requires fresh Android CI; no pass is claimed until the exact-head run completes.

- Follow-up: the authorization-response issuer comparison now independently requires a valid HTTPS issuer identifier too, so an invalid query-bearing expected issuer cannot pass merely by matching the response value.

- Regression coverage extended to fragment-bearing issuer identifiers as well as query-bearing identifiers; both metadata URL derivation and authorization-server metadata validation reject them.


## OAuth issuer hardening CI verification — source commit `ccdff5cd4859bb0107bd318f91984b9e44e81b47`
- Android workflow run `42973901349` (#339): PASS — Android unit tests, debug APK assembly and artifact upload all completed successfully.
- Worker workflow run `42973901430` (#295): PASS.
- CI debug artifact `mosharrof-android-control-debug`, ID `13833924281`, size 2,415,687 bytes, SHA-256 `7206fc24f538e7e5fecbf312edcd787aa55a57ba34c828dcd14e4dbc2f06b30a`. CI artifact only; not installed or physically tested.
- The subsequent test-only commit `c2fa0e0c983d5c0df9646d0db3bfcd78d0ae4107` adds fragment-bearing issuer coverage and is not covered by this prior run; its exact-head CI must complete before that extension is considered verified.


## 2026-11-11 protected-resource authorization-server issuer validation follow-up

- A further OAuth metadata review found that `validateProtectedResourceMetadata` accepted authorization-server entries containing query components because it used the general HTTPS URL validator. These entries are issuer identifiers and must obey the stricter RFC 8414 issuer constraints.
- Changed the authorization-server list check to use the HTTPS issuer validator, rejecting both query and fragment components while retaining HTTPS, host and no-user-info checks.
- Added JVM regression coverage for valid issuer entries and rejection of query-bearing and fragment-bearing entries.
- This is validator-only hardening. OAuth metadata fetching, CIMD discovery, PKCE, token exchange, refresh/revocation and live interoperability remain unimplemented. Exact-head Android and Worker CI are pending for this change.


## 2026-11-11 MCP Task input-response validation follow-up

- Task `input_required` UI now validates that submitted response keys exactly match the server's validated request keys and that each response is a non-empty object with bounded nonblank string keys.
- Added JVM tests for a valid response, mismatched keys, scalar payload rejection and empty object rejection. This guards the client-side envelope before `tasks/update`; server-side semantic validation and real-server interoperability remain required.
- Native Android dialog interaction/process recreation is not covered by these JVM tests. Exact-head CI is pending for this follow-up.


## 2026-11-11 MCP Task service-boundary validation follow-up

- A second-layer review found that the UI enforced exact server request-key matching, but the public connector-service `updateTask` entry point only checked response count. Direct callers could bypass the UI's object-envelope validation.
- Added service-layer validation requiring 1–16 request keys and a non-empty object payload per key, with bounded key names and per-payload property counts. UI validation still additionally requires exact equality with the server's validated request keys.
- Added JVM coverage for valid, empty, scalar, empty-object, blank-key and over-count payloads. This remains structural validation; server-defined semantic rules and real MCP interoperability remain unverified.
- Exact-head Android CI is pending; no build/test pass is claimed for this follow-up.

## Latest OAuth implementation and exact-head CI — 2026-11-11

**Source/test head:** `9331db923d0842f2b035519cb013a482f5c20424`.

- Android workflow run `43004829257`: PASS — Android unit tests, Debug APK assembly and artifact upload completed successfully.
- Worker workflow run `43004829311`: PASS — dependency install and typecheck completed successfully.
- Added `McpOAuthTokenExchangeOutcomeTest` to assert that MCP connection readiness is true only when token exchange succeeded and the one-time encrypted pending PKCE request was cleared; failed exchange and failed cleanup both fail closed.
- `MainActivity` now calls the shared readiness predicate before creating/connecting the OAuth MCP service.
- OAuth implementation is present in source: bounded authorization-server/protected-resource metadata validation; CIMD metadata validation; AppAuth authorization-code + S256 PKCE request and callback checks; RFC 8707 resource-bound token exchange; encrypted AuthState and pending-request persistence; per-request async Bearer refresh; explicit RFC 7009 revocation. OAuth-created MCP clients reject synchronous/static-token transport.
- The preceding exact-head CI run also passed on `11d9fb5ecb7f44358cb9d6b0c8e65fa1ef18b4ad` (Android run `43004301762`, Worker run `43004301859`); the newer `9331db9` run covers the outcome-predicate refactor and regression tests.
- No live browser authorization, token exchange, refresh, revocation, real MCP server, physical Android device, or deployed Worker test was performed. AppAuth activity-result lifecycle, Android Keystore runtime behavior and server interoperability remain external verification gates.
- Historical entries above describing OAuth/PKCE as unimplemented are snapshots from earlier source heads and must not be read as the current source state. Live verification remains pending.
- Dependency lockfiles, Gradle verification metadata, transitive SBOM/license inventory and vulnerability review remain open; the preliminary inventory at lines 183–188 is still not a supply-chain clearance.


## Authoritative PR source-head update — 2026-11-11

**Source head audited:** `0bb1cf53b3c79b20292dc68fa1b03e55eaac9098`  
**Branch:** `fix/mcp-pagination-20261110`  
**PR:** #7 — open, unmerged.

### Exact-head automated verification

- Android workflow run `43051676040`: SUCCESS. The job completed committed Gradle lock/checksum verification, `:app:testDebugUnitTest`, CycloneDX SBOM generation, license inventory generation, debug APK assembly, packaged license-notice validation, and dependency evidence upload. The OSV scanner job also completed successfully.
- Worker workflow run `43051675862`: SUCCESS. The job completed `npm ci`, `npm audit --audit-level=high`, CycloneDX SBOM generation, `npm run check`, and dependency evidence upload. The OSV scanner job also completed successfully.
- Scanner job completion is not, by itself, a legal license clearance, complete vulnerability assessment, or proof that no actionable findings require review. The generated dependency/SBOM/SARIF evidence must be inspected before declaring supply-chain clearance.

### OAuth/CIMD source integration status

- Source includes bounded HTTPS metadata fetch and validation for protected-resource and authorization-server metadata, CIMD fetch/validation, AppAuth native authorization-code + S256 PKCE, encrypted pending-request storage, callback/state/redirect/client/resource binding checks, RFC 8707 resource-bound token exchange, encrypted AuthState persistence, serialized refresh-token rotation, per-request asynchronous Bearer resolution, and RFC 7009 revocation.
- OAuth-backed MCP connection negotiation, legacy initialize/initialized notification, paginated tools/list, tool calls and task lifecycle operations use the async token resolver. OAuth-only clients reject synchronous/static-token transport; manual-token connectors retain a separate compatibility path.
- JVM/mock regression coverage and exact-head CI are present. This is source/CI evidence only; no real OAuth authorization-server, CIMD host, MCP server, token refresh/rotation, remote revocation, Android Keystore runtime, or physical-device/AppAuth callback test has been performed.

### Release and follow-up gates

- Review generated Android/Worker dependency evidence and scanner findings; do not infer zero vulnerabilities or legal approval from a successful workflow alone.
- Perform a semantic whole-repository/history and runtime-data leakage review, plus independent security review.
- Run live OAuth/CIMD and real MCP interoperability with test credentials on a physical Android device; verify browser callback, encrypted state, refresh rotation, revocation and fail-closed behavior. Never place credential values in logs or chat.
- Verify deployed Worker behavior separately. PR #7 remains open/unmerged; no deployment or APK installation was performed. Merge, deployment and physical installation still require explicit user approval.

This addendum supersedes earlier audit statements that OAuth/CIMD was validator-only or that dependency lock/SBOM workflows were absent. Historical entries remain as records of earlier commits.


### Dependency/SBOM evidence review — exact source head `0bb1cf53b3c79b20292dc68fa1b03e55eaac9098`

- Android OSV scan examined 167 app-lock packages, 192 buildscript-lock packages and 409 Gradle verification-metadata packages. The scanner log reports **No issues found**; the SARIF artifact contains 0 result records.
- Worker OSV scan examined 93 locked packages. The scanner log reports **No issues found**; the SARIF artifact contains 0 result records. `npm audit` also reported **0 vulnerabilities** for the resolved Worker install (94 packages during audit; 43 packages in the subsequent clean `npm ci` install output).
- Android release runtime license inventory: 34 components, 34 with source license metadata, 0 missing; all 34 are declared Apache-2.0. The report explicitly excludes buildscript, compile-only, unit-test and Android-test dependencies, so this is not a complete license review of every toolchain dependency or a legal determination. Apache-2.0 license text and third-party notice assets were verified inside the CI APK.
- Android debug APK artifact ID `13870550597`, size `2,582,599` bytes, SHA-256 `5ba5bfe3a035481c3496742b5737737ed40e050d3267ff50b536aeeb3975b5eb`; CI artifact only, not installed. Android dependency evidence artifact ID `13869861290`; Worker dependency evidence artifact ID `13868858628`. SARIF artifacts: Android `13869253629`, Worker `13868579767`.
- These are point-in-time advisory-database results for the scanned lockfiles, not a guarantee of absence of all vulnerabilities. The OSV and npm audit reports do not replace source-code review, runtime testing, legal license review, or future recurring scans.
- Non-blocking toolchain warnings observed in Worker logs include Node deprecations (`punycode`, `url.parse`, `Buffer()`) and GitHub Actions Node 20 deprecation notices for v4 actions. Track for maintenance; no failing audit finding was associated with these warnings.


## Follow-up exact-head CI and artifact verification — 2026-11-11

**Head:** `a4da4a3f2e57ab03e451cdf0c12fb57b519c0af1` (PR #7, branch `fix/mcp-pagination-20261110`, still open/unmerged).

- Android workflow run `43055759358`: SUCCESS. Assemble job `130644644577` passed strict Gradle dependency verification, `:app:testDebugUnitTest`, CycloneDX BOM, runtime license inventory, debug APK assembly, APK license-notice validation and artifact upload. Dependency scan job `130646117821` succeeded; OSV scanned 167 app lock packages, 192 buildscript lock packages and 409 Gradle verification-metadata packages; scanner log: **No issues found**.
- Worker workflow run `43055759511`: SUCCESS (exact same head).
- Android debug APK artifact `13870753793`, archive size 2,584,613 bytes. Downloaded and inspected the ZIP; inner `app-debug.apk` size 2,856,152 bytes; SHA-256 `301ecabe62cdd3501d3f10e611c4e00195ec07fa6ff89996dcf240d0ca120f2e`. This is a CI artifact only; not installed or device-tested.
- Android OSV SARIF artifact `13871801628` is 589 bytes. Scanner log is the primary evidence for the no-findings result.
- Build logs contain non-blocking Node deprecation warnings (`punycode`, `url.parse`) and GitHub Actions Node 20 runtime deprecation notices. Track these for CI maintenance; they did not fail the workflow.
- PR remains open and unmerged. No APK installation, PR merge, or deployment was performed. Live OAuth/CIMD and MCP-server interoperability, Android Keystore/browser callback runtime, physical-device QA, and full legal/toolchain dependency review remain outstanding.
