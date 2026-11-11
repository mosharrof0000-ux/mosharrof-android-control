# Dependency and Supply-Chain Audit — 2026-11-11

**Repository:** `mosharrof0000-ux/mosharrof-android-control`  
**Branch:** `fix/mcp-pagination-20261110`  
**Audit scope:** dependency declarations, CI toolchain, and expected lock/verification/SBOM files inspected through GitHub repository content. This is an inventory and targeted advisory triage, not a complete vulnerability or license clearance.

## Android dependency declarations and CI toolchain

- Android Gradle Plugin: `com.android.application` 8.7.3.
- Kotlin Android Gradle Plugin: 2.0.21.
- App direct dependencies:
  - `androidx.documentfile:documentfile:1.0.1`
  - `net.openid:appauth:0.11.1`
- Test-only direct dependencies:
  - `junit:junit:4.13.2`
  - `org.json:json:20240303`
- App compile SDK 35, min SDK 26, target SDK 35, Java/Kotlin target 17.
- Android CI previously pinned its setup action to Gradle 8.9. This is below the patched versions for the October 7, 2026 high-severity Gradle advisories (8.14.6 / 9.8.1). The workflow has now been changed to Gradle 8.14.6; exact-head Android tests/build must pass before treating this mitigation as verified.
- CI uses `gradle/actions/setup-gradle@v4` with an explicit Gradle version; no Gradle wrapper distribution is currently used by this workflow.

Sources inspected:
- `android/build.gradle.kts`
- `android/app/build.gradle.kts`
- `android/settings.gradle.kts`
- `android/gradle.properties`
- `.github/workflows/android-build.yml`

## Cloudflare Worker dependency declarations

From `cloudflare/worker/package.json`:
- `@cloudflare/workers-types: ^5.20261101.1`
- `typescript: ^5.9.3`
- `wrangler: ^4.59.1`

These are semver ranges, not exact resolved versions. The Wrangler minimum was raised from `^4.45.0` to `^4.59.1` in source commit `178678ed80f7f9d0d160049f2deb9ec41f5188d8` to exclude the advisory's affected versions below 4.59.1. This is a minimum-version mitigation, not a complete vulnerability scan; a committed lockfile is still required.

The current Worker workflow `.github/workflows/worker-check.yml` still runs `npm install`, not `npm ci`, because no lockfile is committed yet.

## Reproducibility and supply-chain findings

The following expected files were not found at the audited branch paths:
- `cloudflare/worker/package-lock.json`
- `cloudflare/worker/npm-shrinkwrap.json`
- `android/gradle.lockfile`
- `android/gradle/verification-metadata.xml`
- Gradle wrapper files at `android/gradle/wrapper/gradle-wrapper.properties` and `gradle/wrapper/gradle-wrapper.properties`.

No generated SBOM, complete resolved transitive dependency inventory, license attribution report, or vulnerability scan result was present in the reviewed audit material.

Consequences:
- Worker transitive dependency resolution is not yet reproducible from a committed npm lockfile.
- Android transitive dependency versions and artifact checksums are not pinned by Gradle dependency locking/verification metadata in the inspected paths.
- Direct dependency declarations alone cannot establish transitive license obligations or vulnerability status.
- No conclusion that dependencies are vulnerability-free or license-cleared is made.

## Required follow-up before release

1. Generate `cloudflare/worker/package-lock.json` using npm in a build-capable CI environment; commit the generated lockfile, change the Worker workflow to `npm ci`, and verify tests/typecheck.
2. Enable Gradle dependency locking for relevant resolvable configurations and generate lock state by resolving the actual Android build/test configurations.
3. Generate Gradle dependency verification metadata from trusted repositories in a controlled build, review checksums, and commit it; do not hand-author artifact hashes.
4. Generate an SPDX or CycloneDX SBOM for both Android and Worker resolved dependency graphs.
5. Review direct and transitive licenses, attribution/notice obligations, known vulnerabilities and remediation status; record scanner/database date and exact resolved graph.
6. Re-run Android and Worker CI on the exact final commit after generated lock, verification, SBOM and policy files are added.

## Audit limits

This pass did not execute package managers or Gradle locally, resolve transitive graphs, download dependencies, generate checksums, run vulnerability scanners, inspect every dependency's license, or generate SBOMs. These steps require the project's build-capable CI/runtime and must not be simulated by guessed lockfiles or fabricated hashes.

No dependency lock, deployment, APK installation or PR merge was performed during the inventory.

## Advisory references

- Wrangler advisory: https://security.snyk.io/package/npm/wrangler/4.45.0 — Wrangler 4.x below 4.59.1 is affected by the described `pages deploy --commit-hash` input-validation issue when the input is attacker-controlled.
- Gradle advisory list: https://github.com/gradle/gradle/security/advisories — multiple high-severity advisories were published 2026-10-07.
- Gradle advisory GHSA-mvvg-497x-hmj8: https://github.com/gradle/gradle/security/advisories/GHSA-mvvg-497x-hmj8 — affected versions include all versions below 8.14.6; patched versions include 8.14.6 and 9.8.1.
- Gradle advisory GHSA-j5m7-59rp-24f5: https://github.com/gradle/gradle/security/advisories/GHSA-j5m7-59rp-24f5 — affected versions include all versions below 8.14.6; patched versions include 8.14.6 and 9.8.1.

## Exact-head CI evidence before this update

At commit `4e1c1e11ea9e3df40ca30f3cbfc39688ed443446`:
- Android workflow run `43006072623`: Unit Tests, Debug APK Assembly, and Upload all completed successfully.
- Worker workflow run `43006072544`: Typecheck/test workflow completed successfully.

These results predate the Gradle 8.14.6 workflow update and do not verify that update. A new exact-head CI run is required.
