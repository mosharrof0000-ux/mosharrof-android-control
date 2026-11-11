# Dependency and Supply-Chain Audit — 2026-11-11

**Repository:** `mosharrof0000-ux/mosharrof-android-control`  
**Branch:** `fix/mcp-pagination-20261110`  
**Scope:** dependency declarations, resolved Worker dependency evidence, CI toolchains, Gradle lock/verification bootstrap. This is an evidence inventory and targeted advisory triage, not a complete vulnerability or license clearance.

## Android dependencies and CI toolchain

- Android Gradle Plugin: `com.android.application` 8.7.3.
- Kotlin Android Gradle Plugin: 2.0.21.
- App direct dependencies: `androidx.documentfile:documentfile:1.0.1`, `net.openid:appauth:0.11.1`.
- Test-only direct dependencies: `junit:junit:4.13.2`, `org.json:json:20240303`.
- Compile SDK 35, min SDK 26, target SDK 35, Java/Kotlin target 17.
- Android CI uses explicit Gradle 8.14.6 instead of 8.9. Exact-head Android run `43006803371` at commit `41fe140b15e2a39defe7892119654bc4bdd89c12` passed Unit Tests, Debug APK Assembly and Upload. This verifies the Gradle 8.14.6 change on that commit.
- Root Gradle build now enables `dependencyLocking { lockAllConfigurations() }` for subprojects.
- Current Android workflow attempts to generate dependency lock state and SHA-256 verification metadata using a real Gradle build. Run `43007968344` at head `364828e0fcaa7a85d0fa88da0bf79b4889e2a646` is pending at time of this audit update; generated files are not yet reviewed or committed.
- CI uses `gradle/actions/setup-gradle@v4` with explicit Gradle version; project wrapper files were not found in the audited paths.

## Cloudflare Worker dependencies

Declared in `cloudflare/worker/package.json`:
- `@cloudflare/workers-types: ^5.20261101.1`
- `typescript: ^5.9.3`
- `wrangler: ^4.59.1`

The Wrangler lower bound was raised from `^4.45.0` to `^4.59.1` in commit `178678ed80f7f9d0d160049f2deb9ec41f5188d8` to exclude the advisory's affected releases below 4.59.1. This is a minimum-version mitigation, not a complete scan.

## Worker resolved dependency evidence

CI run `43007566986` at commit `f6226f5e6b958b4fa82a0b88442b075708f4543c` completed successfully:
- Generated npm lockfile from declared dependency ranges.
- `npm ci` installed from that generated lockfile.
- `npm audit --audit-level=high` returned **0 vulnerabilities**; the log also records 0 vulnerabilities from the install/audit pass.
- `npm sbom --sbom-format=cyclonedx` generated CycloneDX 1.5 SBOM.
- Worker typecheck/tests passed.
- Artifact `worker-dependency-evidence`, ID `13848932719`, contains `package-lock.json` and `worker-sbom.cdx.json`.

Lockfile evidence:
- 93 package entries.
- SHA-256: `f1762eaec0854be47bdc25a1208afc93ee96e091ac90cd4e1e8669b337f64dc9`.
- Resolved Wrangler 4.160.0, TypeScript 5.9.3, Workers Types 5.20261110.1.
- All 93 resolved entries have integrity hashes and declared license metadata in the lockfile.
- **Important limitation:** this lockfile is currently a CI artifact, not committed at `cloudflare/worker/package-lock.json`. The workflow regenerates it from semver ranges on each run, so cross-run reproducibility is not yet established. The artifact must be reviewed and committed before changing CI to a lockfile-only `npm ci` install.

SBOM review:
- CycloneDX 1.5, 42 components in the Linux CI install inventory.
- Includes LGPL-3.0-or-later components from the Sharp/libvips dependency family (including Linux x64 and wasm-related entries).
- Several components use compound license expressions. The lockfile contains optional platform-specific entries not all present in the Linux-installed SBOM.
- This flags a license/attribution review requirement; it is not a finding of infringement and not a license clearance.
- npm audit result is limited to the advisory database and threshold used at run time; it does not prove absence of all vulnerabilities.

## Remaining reproducibility and supply-chain gaps

- Worker `package-lock.json` is an artifact only; not yet committed.
- Android Gradle lockfile and `android/gradle/verification-metadata.xml` generation is in progress and must be reviewed before commit.
- No final combined SPDX/CycloneDX SBOM covering the Android and Worker graphs has been reviewed.
- LGPL/compound-license attribution obligations require manual review, including whether optional packages are distributed or merely used in CI.
- No full vulnerability or license clearance is claimed.

## Required follow-up before release

1. Review and commit the generated Worker lockfile; then change Worker CI from bootstrap generation to committed-lockfile `npm ci`.
2. Review generated Gradle lockfiles and SHA-256 verification metadata, then commit them.
3. Generate and review an Android SBOM from the actual resolved Gradle graph.
4. Complete license/notice review, particularly the Sharp/libvips LGPL and compound-license entries.
5. Record vulnerability scan database/time, full findings and remediation; repeat on the final exact commit.
6. Run Android and Worker CI on the exact final commit after all lock, verification and SBOM files are committed.

## Advisory references

- Wrangler advisory: https://security.snyk.io/package/npm/wrangler/4.45.0 — Wrangler 4.x below 4.59.1 is affected by the described `pages deploy --commit-hash` input-validation issue when attacker-controlled input is used.
- Gradle advisory list: https://github.com/gradle/gradle/security/advisories — multiple high-severity advisories were published 2026-10-07.
- Gradle advisory GHSA-mvvg-497x-hmj8: https://github.com/gradle/gradle/security/advisories/GHSA-mvvg-497x-hmj8.
- Gradle advisory GHSA-j5m7-59rp-24f5: https://github.com/gradle/gradle/security/advisories/GHSA-j5m7-59rp-24f5.

## CI evidence snapshot

- Android run `43006803371` at `41fe140b15e2a39defe7892119654bc4bdd89c12`: PASS — Gradle 8.14.6, Unit Tests, Debug APK Assembly and Upload.
- Worker run `43007566986` at `f6226f5e6b958b4fa82a0b88442b075708f4543c`: PASS — lock generation, npm ci, high-severity audit, CycloneDX SBOM and tests/typecheck.
- Latest combined run at `364828e0fcaa7a85d0fa88da0bf79b4889e2a646`: Worker and Android jobs were still running when this document was updated; no pass claim is made for that head.

No deployment, PR merge, APK installation, live OAuth operation or physical Android QA was performed.
