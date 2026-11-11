#!/usr/bin/env python3
"""Audit upstream Maven POM license and source metadata for runtime SBOM components."""
from __future__ import annotations
import argparse, hashlib, json, re, sys, urllib.error, urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any

MAX_POM_BYTES = 4 * 1024 * 1024
SAFE = re.compile(r"^[A-Za-z0-9_.+-]+$")

def normalize_license(value: str) -> str:
    value = value.strip().lower()
    if "apache" in value or "apache.org/licenses/license-2.0" in value:
        return "Apache-2.0"
    if "mit license" in value or value == "mit":
        return "MIT"
    if "bsd" in value:
        return "BSD"
    return value

def child_text(parent: ET.Element, name: str) -> str:
    node = parent.find(name)
    return (node.text or "").strip() if node is not None else ""

def parse_pom(data: bytes) -> dict[str, Any]:
    root = ET.fromstring(data)
    licenses = []
    for item in root.findall("./licenses/license"):
        name, url = child_text(item, "name"), child_text(item, "url")
        if name or url:
            licenses.append({"name": name, "url": url,
                             "distribution": child_text(item, "distribution"),
                             "normalized": normalize_license(name + " " + url)})
    scm = root.find("./scm")
    scm_data = {k: child_text(scm, k) for k in ("url", "connection", "developerConnection")} if scm is not None else {}
    developers = []
    for item in root.findall("./developers/developer"):
        developer = {k: child_text(item, k) for k in ("name", "organization", "organizationUrl")}
        if any(developer.values()): developers.append(developer)
    organization = root.find("./organization")
    org = {k: child_text(organization, k) for k in ("name", "url")} if organization is not None else {}
    return {"licenses": licenses, "scm": scm_data, "developers": developers, "organization": org}

def pom_urls(group: str, name: str, version: str) -> list[str]:
    if not all(SAFE.fullmatch(x) for x in (group, name, version)):
        raise ValueError("Unsafe Maven coordinate")
    path = group.replace(".", "/") + "/" + name + "/" + version + "/" + name + "-" + version + ".pom"
    roots = (["https://dl.google.com/dl/android/maven2/"] if group.startswith("androidx.") else [])
    roots += ["https://repo.maven.apache.org/maven2/"]
    return [root + path for root in roots]

def fetch_pom(urls: list[str]) -> tuple[bytes, str]:
    errors = []
    for url in urls:
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "MOSHARROF-license-audit/1.0"})
            with urllib.request.urlopen(request, timeout=25) as response:
                data = response.read(MAX_POM_BYTES + 1)
                if len(data) > MAX_POM_BYTES: raise ValueError("POM exceeds size limit")
                return data, url
        except (OSError, urllib.error.URLError, ValueError) as exc:
            errors.append(url + ": " + str(exc))
    raise RuntimeError("; ".join(errors))

def sbom_licenses(component: dict[str, Any]) -> list[str]:
    values = set()
    for entry in component.get("licenses") or []:
        lic = entry.get("license") or {}
        if isinstance(lic, dict):
            label = lic.get("id") or lic.get("name") or ""
            if label: values.add(normalize_license(str(label)))
    return sorted(values)

def audit(sbom_path: Path) -> dict[str, Any]:
    bom = json.loads(sbom_path.read_text(encoding="utf-8"))
    components = bom.get("components") or []
    if not components: raise ValueError("SBOM contains no components")
    results, failures = [], []
    for component in components:
        group, name, version = (str(component.get(k) or "") for k in ("group", "name", "version"))
        coordinate = f"{group}:{name}:{version}"
        if not all((group, name, version)):
            failures.append(coordinate + ": incomplete Maven coordinate")
            continue
        try:
            data, source_url = fetch_pom(pom_urls(group, name, version))
            metadata = parse_pom(data)
            pom_licenses = sorted({x["normalized"] for x in metadata["licenses"] if x["normalized"]})
            declared = sbom_licenses(component)
            mismatch = bool(declared and pom_licenses and not set(declared).intersection(pom_licenses))
            if not metadata["licenses"]: failures.append(coordinate + ": POM has no license declaration")
            if mismatch: failures.append(coordinate + ": POM license declaration does not match SBOM")
            results.append({"coordinate": coordinate, "pom_url": source_url,
                            "pom_sha256": hashlib.sha256(data).hexdigest(),
                            "sbom_licenses": declared, "pom_licenses": metadata["licenses"],
                            "pom_license_ids": pom_licenses, "scm": metadata["scm"],
                            "developers": metadata["developers"], "organization": metadata["organization"],
                            "license_match": bool(declared and pom_licenses and not mismatch)})
        except (OSError, RuntimeError, ValueError, ET.ParseError) as exc:
            failures.append(coordinate + ": " + str(exc))
    return {"schema_version": 1, "component_count": len(components),
            "pom_component_count": len(results),
            "license_match_count": sum(x["license_match"] for x in results),
            "components": results, "failures": failures}

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sbom", required=True, type=Path)
    parser.add_argument("--json-report", required=True, type=Path)
    parser.add_argument("--markdown-report", required=True, type=Path)
    parser.add_argument("--attributions", required=True, type=Path)
    args = parser.parse_args()
    try: report = audit(args.sbom)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print("Maven metadata audit could not run: " + str(exc), file=sys.stderr); return 2
    for path in (args.json_report, args.markdown_report, args.attributions):
        path.parent.mkdir(parents=True, exist_ok=True)
    args.json_report.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    lines = ["# Upstream Maven POM license metadata audit", "",
             f"- SBOM components: **{report['component_count']}**",
             f"- POMs retrieved: **{report['pom_component_count']}**",
             f"- POM/SBOM license matches: **{report['license_match_count']}**", "",
             "| Component | SBOM license | POM license | Match | POM SHA-256 |",
             "|---|---|---|---|---|"]
    attribution = ["UPSTREAM MAVEN POM LICENSE AND SOURCE ATTRIBUTIONS",
                   "Metadata is copied from the exact Maven POM for each resolved coordinate.",
                   "This report is POM metadata evidence, not extracted binary LICENSE/NOTICE text.", ""]
    for item in report["components"]:
        pom_names = ", ".join(x["name"] or x["url"] for x in item["pom_licenses"]) or "UNDECLARED"
        sbom_names = ", ".join(item["sbom_licenses"]) or "UNDECLARED"
        lines.append(f"| {item['coordinate']} | {sbom_names} | {pom_names} | {'YES' if item['license_match'] else 'NO'} | {item['pom_sha256']} |")
        attribution += ["## " + item["coordinate"], "POM: " + item["pom_url"],
                        "POM SHA-256: " + item["pom_sha256"],
                        "SBOM license(s): " + sbom_names, "POM license(s): " + pom_names]
        for lic in item["pom_licenses"]:
            if lic["url"]: attribution.append("License URL: " + lic["url"])
        for key, value in item["scm"].items():
            if value: attribution.append("SCM " + key + ": " + value)
        for person in item["developers"]:
            label = " / ".join(x for x in (person["name"], person["organization"]) if x)
            if label: attribution.append("Developer: " + label)
        if item["organization"].get("name"): attribution.append("Organization: " + item["organization"]["name"])
        attribution.append("")
    if report["failures"]: lines += ["", "## Failures", ""] + ["- " + x for x in report["failures"]]
    args.markdown_report.write_text("\n".join(lines) + "\n", encoding="utf-8")
    args.attributions.write_text("\n".join(attribution).rstrip() + "\n", encoding="utf-8")
    print(f"Maven POM audit: {report['pom_component_count']}/{report['component_count']} POMs; {report['license_match_count']} license matches.")
    return 1 if report["failures"] or report["pom_component_count"] != report["component_count"] else 0

if __name__ == "__main__":
    raise SystemExit(main())
