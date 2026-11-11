# Dependency and Supply-Chain Audit — 2026-11-11

**Repository:** `mosharrof0000-ux/mosharrof-android-control`  
**Branch:** `fix/mcp-pagination-20261110`  
**Audit scope:** dependency declarations and expected lock/verification/SBOM files inspected through GitHub repository content. This is an inventory, not a vulnerability or license clearance.

## Android dependency declarations

- Android Gradle Plugin: `com.android.application` 8.7.3.
- Kotlin Android Gradle Plugin: 2.0.21.
- App direct dependencies:
  - `androidx.documentfile:documentfile:1.0.1`
  - `net.openid:appauth:0.11.1`
- Test-only direct dependencies:
  - `junit:junit:4.13.2`
  - `org.json:json:20240303`
- App compile SDK 35, min SDK 26, target SDK 35, Java/Kotlin target 17.

Sources inspected:
- `android/build.gradle.kts`
- `android/app/build.gradle.kts`
- `android/settings.gradle.kts`
- `android/gradle.properties`

## Cloudflare Worker dependency declarations

From `cloudflare/worker/package.json`:
- `@cloudflare/workers-types: ^5.20261101.1`
- `typescript: ^5.9.3`
- `wrangler: ^4.45.0`

These are semver ranges, not exact resolved versions.

## Reproducibility and supply-chain findings

The following expected files were not found at the audited branch paths:
- `cloudflare/worker/package-lock.json`
- `cloudflare/worker/npm-shrinkwrap.json`
- `android/gradle.lockfile`
- `android/gradle/verification-metadata.xml`
- Gradle wrapper files at `android/gradle/wrapper/gradle-wrapper.properties` and `gradle/wrapper/gradle-wrapper.properties`.

No generated SBOM, complete resolved transitive dependency inventory, license attribution report, or vulnerability scan result was present in the reviewed audit material.

Consequences:
- Worker transitive dependency resolution is not evidenced as reproducible from a committed npm lockfile.
- Android transitive dependency versions and artifact checksums are not pinned by Gradle dependency locking/verification metadata in the inspected paths.
- Direct dependency declarations alone cannot establish transitive license obligations or vulnerability status.
- No conclusion that dependencies are vulnerability-free or license-cleared is made.

## Required follow-up before release

1. In the project's build-capable CI environment, use the configured Node/npm toolchain to generate and commit `cloudflare/worker/package-lock.json`; update CI to use `npm ci` and verify `npm ci` plus Worker tests/typecheck.
2. Enable Gradle dependency locking for relevant resolvable configurations and generate lock state by resolving the actual Android build/test configurations.
3. Generate Gradle dependency verification metadata from trusted repositories in a controlled build, review checksums, and commit it; do not hand-author artifact hashes.
4. Generate an SPDX or CycloneDX SBOM for both Android and Worker resolved dependency graphs.
5. Review direct and transitive licenses, attribution/notice obligations, known vulnerabilities and remediation status; record scanner/database date and exact resolved graph.
6. Re-run Android and Worker CI on the exact final commit after generated lock, verification, SBOM and policy files are added.

## Audit limits

This pass did not execute package managers or Gradle, resolve transitive graphs, download dependencies, generate checksums, run vulnerability scanners, inspect every dependency's license, or alter CI install behavior. These steps require the project's build-capable CI/runtime and must not be simulated by guessed lockfiles or fabricated hashes.

No dependency lock, dependency upgrade, deployment, APK installation or PR merge was performed during this inventory.

## Wrangler advisory triage — 2026-11-11

- A current package advisory lookup identifies a command-injection issue in Wrangler's `pages deploy --commit-hash` handling. The advisory lists Wrangler 4.x versions below 4.59.1 as affected when an attacker controls the `--commit-hash` input; it lists 4.59.1 as the fixed floor. Source: Snyk package advisory for `wrangler@4.45.0` (reviewed 2026-11-11): https://security.snyk.io/package/npm/wrangler/4.45.0
- The repository declared `wrangler: ^4.45.0`, a range whose lower bound includes affected releases. No committed lockfile was present to prove the exact resolved CI version.
- Raised the declared minimum to `^4.59.1` in source commit `178678ed80f7f9d0d160049f2deb9ec41f5188d8`. This is a minimum-version mitigation, not a complete vulnerability scan; the range still floats within compatible 4.x releases and a lockfile is still required.
- Worker CI must pass on the exact updated head. No Wrangler deployment was performed.
