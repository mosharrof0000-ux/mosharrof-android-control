#!/usr/bin/env python3
"""Create a reviewable third-party license inventory from a CycloneDX SBOM.

The source SBOM is preserved. Explicit metadata corrections are written only to
a separate reviewed copy and are accompanied by their upstream evidence URLs.
This is an engineering inventory, not a legal determination of distribution
obligations or which option in a dual-license offer should be selected.
"""
from __future__ import annotations

import argparse
import collections
import json
from pathlib import Path

OVERRIDES = {
    ("javax.annotation", "javax.annotation-api", "1.3.2"): {
        "name": "CDDL + GPLv2 with Classpath Exception",
        "url": "https://github.com/javaee/javax.annotation/blob/master/LICENSE",
        "evidence": "https://github.com/javaee/javax.annotation/blob/master/pom.xml",
        "note": "Recovered from the upstream POM; CycloneDX source SBOM omitted this field.",
    }
}


def license_label(entry: dict) -> str:
    lic = entry.get("license", {})
    return lic.get("id") or lic.get("name") or "UNIDENTIFIED"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sbom", required=True, type=Path)
    parser.add_argument("--reviewed-sbom", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()

    bom = json.loads(args.sbom.read_text(encoding="utf-8"))
    reviewed = json.loads(json.dumps(bom))
    reviewed_components = reviewed.get("components", [])
    original_components = bom.get("components", [])
    if len(original_components) != len(reviewed_components):
        raise SystemExit("SBOM component copy mismatch")

    counts: collections.Counter[str] = collections.Counter()
    rows: list[tuple[str, str, str, str, str]] = []
    unresolved: list[str] = []
    corrected: list[str] = []

    for component, reviewed_component in zip(original_components, reviewed_components):
        group = component.get("group", "")
        name = component.get("name", "")
        version = component.get("version", "")
        key = (group, name, version)
        licenses = component.get("licenses") or []
        if not licenses and key in OVERRIDES:
            override = OVERRIDES[key]
            reviewed_component["licenses"] = [{
                "license": {
                    "name": override["name"],
                    "url": override["url"],
                    "comments": override["note"],
                }
            }]
            reviewed_component.setdefault("properties", []).append({
                "name": "mosharrof:license-evidence",
                "value": override["evidence"],
            })
            licenses = reviewed_component["licenses"]
            corrected.append(f"{group}:{name}:{version}")
        elif not licenses:
            unresolved.append(f"{group}:{name}:{version}")
            licenses = [{"license": {"name": "UNIDENTIFIED"}}]

        labels = sorted({license_label(item) for item in licenses})
        for label in labels:
            counts[label] += 1
        purl = component.get("purl", "")
        rows.append((group, name, version, " OR ".join(labels), purl))

    args.reviewed_sbom.parent.mkdir(parents=True, exist_ok=True)
    args.reviewed_sbom.write_text(json.dumps(reviewed, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    report = [
        "# Third-party license inventory (automated SBOM review)",
        "",
        f"- Source SBOM: `{args.sbom.name}` (preserved unchanged)",
        "- Scope: **releaseRuntimeClasspath** (distributable app runtime graph only)",
        f"- Runtime components: **{len(original_components)}**",
        f"- Components with source license metadata: **{len(original_components) - len(unresolved) - len(corrected)}**",
        f"- Components corrected from explicit upstream metadata: **{len(corrected)}**",
        f"- Components still missing license metadata: **{len(unresolved)}**",
        "",
        "> Engineering inventory for the configured release runtime graph only. Buildscript, compile-only, unit-test and Android test dependencies are intentionally outside this distributable-runtime report and remain covered by lockfile/SCA checks where applicable. A CycloneDX license list can express alternatives; it does not decide which license option is selected or replace legal review. Include applicable full license texts and notices only after reviewing the final distributed artifact and obligations.",
        "",
        "## License declaration counts",
        "",
        "| Declared license / expression | Declarations |",
        "|---|---:|",
    ]
    for label, count in sorted(counts.items()):
        escaped_label = label.replace("|", "\\|")
        report.append(f"| {escaped_label} | {count} |")

    report += ["", "## Component inventory", "", "| Group | Component | Version | Declared license(s) | Package URL |", "|---|---|---:|---|---|"]
    for group, name, version, labels, purl in sorted(rows):
        safe = [v.replace("|", "\\|") for v in (group, name, version, labels, purl)]
        report.append("| " + " | ".join(safe) + " |")

    if corrected:
        report += ["", "## Explicit metadata corrections", ""]
        for item in corrected:
            override = OVERRIDES[next(k for k in OVERRIDES if ":".join(k) == item)]
            report.append(f"- `{item}`: **{override['name']}**; upstream POM: {override['evidence']}; license text: {override['url']}.")
    if unresolved:
        report += ["", "## Unresolved license metadata", ""]
        report.extend(f"- `{item}`" for item in unresolved)

    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text("\n".join(report) + "\n", encoding="utf-8")
    if unresolved:
        raise SystemExit(f"Unresolved license metadata for {len(unresolved)} component(s)")
    print(f"License inventory generated: {len(original_components)} components; {len(corrected)} explicit metadata correction(s); no unresolved entries.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
