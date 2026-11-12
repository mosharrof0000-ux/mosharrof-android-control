#!/usr/bin/env python3
"""Audit upstream Maven POM license and source metadata for runtime SBOM components."""
from __future__ import annotations
import argparse, hashlib, json, re, sys, urllib.error, urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

MAX_POM_BYTES = 4 * 1024 * 1024
SAFE = re.compile(r"^[A-Za-z0-9_.+-]+$")
ALLOWED_SCM_SCHEMES = {"http", "https", "ssh", "git"}
SCP_SCM = re.compile(r"^(?:[^@/:\s]+@)?(?P<host>[A-Za-z0-9.-]+):(?P<path>[^\s]+)$")


def sanitize_scm_reference(value: str) -> str:
    """Remove userinfo, query and fragment from SCM references before persistence."""
    value = (value or "").strip()
    if not value:
        return ""
    prefix = ""
    if value.startswith("scm:"):
        parts = value.split(":", 2)
        if len(parts) != 3 or not parts[2].strip():
            return "[unparseable SCM reference]"
        prefix = parts[0] + ":" + parts[1] + ":"
        value = parts[2].strip()
    if value.startswith("git+"):
        prefix += "git+"
        value = value[4:]
    try:
        parsed = urlsplit(value)
        if parsed.scheme.lower() in ALLOWED_SCM_SCHEMES and parsed.hostname:
            port = f":{parsed.port}" if parsed.port else ""
            return prefix + f"{parsed.scheme.lower()}://{parsed.hostname}{port}{parsed.path}"
    except ValueError:
        pass
    match = SCP_SCM.fullmatch(value)
    if match:
        return prefix + match.group("host") + ":" + match.group("path").split("?", 1)[0].split("#", 1)[0]
    return "[unparseable SCM reference]"

def normalize_license(value: str) -> str:
    """Normalize common POM labels without collapsing distinct SPDX licenses."""
    value = value.strip()
    lowered = value.lower()
    compact = re.sub(r"[ _]", "-", lowered)

    if "apache" in lowered and ("2.0" in lowered or "license-2.0" in lowered):
        return "Apache-2.0"
    if compact in {"mit", "mit-license"} or "mit license" in lowered:
        return "MIT"

    # Preserve the BSD clause count: BSD-2-Clause and BSD-3-Clause are not
    # interchangeable license identifiers.
    if re.search(r"\bbsd[- ]?4[- ]clause\b|\b4[- ]clause bsd\b", lowered):
        return "BSD-4-Clause"
    if re.search(r"\bbsd[- ]?3[- ]clause\b|\b3[- ]clause bsd\b|modified bsd", lowered):
        return "BSD-3-Clause"
    if re.search(r"\bbsd[- ]?2[- ]clause\b|\b2[- ]clause bsd\b|simplified bsd", lowered):
        return "BSD-2-Clause"
    if "bsd" in lowered:
        # Keep generic BSD declarations distinguishable from specific SPDX IDs.
        return "BSD"

    # Keep already-normalized SPDX IDs (including compound expressions)
    # intact; unknown free-form labels remain visible for manual review.
    return value.strip()

def child_text(parent: ET.Element, name: str) -> str:
    node = parent.find(name)
    return (node.text or "").strip() if node is not None else ""

def parse_pom(data: bytes) -> dict[str, Any]:
    root = ET.fromstring(data)
    for node in root.iter():
        if isinstance(node.tag, str) and node.tag.startswith("{"):
            node.tag = node.tag.split("}", 1)[1]
    licenses = []
    for item in root.findall("./licenses/license"):
        name, url = child_text(item, "name"), child_text(item, "url")
        if name or url:
            licenses.append({"name": name, "url": url,
                             "distribution": child_text(item, "distribution"),
                             "normalized": normalize_license(name + " " + url)})
    scm = root.find("./scm")
    scm_data = {k: sanitize_scm_reference(child_text(scm, k))
                for k in ("url", "connection", "developerConnection")} if scm is not None else {}
    developers = []
    for item in root.findall("./developers/developer"):
        developer = {k: child_text(item, k) for k in ("name", "organization", "organizationUrl")}
        if any(developer.values()): developers.append(developer)
    organization = root.find("./organization")
    org = {k: child_text(organization, k) for k in ("name", "url")} if organization is not None else {}
    parent = root.find("./parent")
    parent_data = {k: child_text(parent, k) for k in ("groupId", "artifactId", "version")} if parent is not None else {}
    return {"licenses": licenses, "scm": scm_data, "developers": developers, "organization": org, "parent": parent_data}

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

def resolve_license_metadata(data: bytes, source_url: str, depth: int = 0,
                             seen: set[tuple[str, str, str]] | None = None) -> dict[str, Any]:
    """Resolve license declarations from the artifact POM or its bounded parent chain."""
    if seen is None:
        seen = set()
    metadata = parse_pom(data)
    if metadata["licenses"]:
        return {"metadata": metadata, "license_source": "pom",
                "license_pom_url": source_url,
                "license_pom_sha256": hashlib.sha256(data).hexdigest(),
                "parent_pom_url": None, "parent_pom_sha256": None}
    parent = metadata.get("parent") or {}
    if depth >= 4 or not all(parent.get(k) for k in ("groupId", "artifactId", "version")):
        return {"metadata": metadata, "license_source": "missing",
                "license_pom_url": None, "license_pom_sha256": None,
                "parent_pom_url": None, "parent_pom_sha256": None}
    coordinate = (parent["groupId"], parent["artifactId"], parent["version"])
    if coordinate in seen:
        raise ValueError("Maven parent POM cycle detected")
    seen.add(coordinate)
    parent_data, parent_url = fetch_pom(pom_urls(*coordinate))
    resolved = resolve_license_metadata(parent_data, parent_url, depth + 1, seen)
    if resolved["license_source"] == "missing":
        return {"metadata": metadata, "license_source": "missing",
                "license_pom_url": None, "license_pom_sha256": None,
                "parent_pom_url": parent_url,
                "parent_pom_sha256": hashlib.sha256(parent_data).hexdigest()}
    return {"metadata": metadata, "license_source": "parent_pom",
            "license_pom_url": resolved["license_pom_url"],
            "license_pom_sha256": resolved["license_pom_sha256"],
            "parent_pom_url": parent_url,
            "parent_pom_sha256": hashlib.sha256(parent_data).hexdigest(),
            "inherited_licenses": resolved.get("inherited_licenses", resolved["metadata"]["licenses"])}


def licenses_match(sbom: list[str], pom: list[str]) -> bool:
    """Require non-empty, exact equality between normalized SBOM and POM license sets."""
    return bool(sbom and pom and set(sbom) == set(pom))


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
    results, failures, mismatches = [], [], []
    for component in components:
        group, name, version = (str(component.get(k) or "") for k in ("group", "name", "version"))
        coordinate = f"{group}:{name}:{version}"
        if not all((group, name, version)):
            failures.append(coordinate + ": incomplete Maven coordinate")
            mismatches.append(coordinate)
            continue
        try:
            data, source_url = fetch_pom(pom_urls(group, name, version))
            resolved = resolve_license_metadata(data, source_url)
            metadata = resolved["metadata"]
            license_entries = metadata["licenses"] or resolved.get("inherited_licenses", [])
            pom_licenses = sorted({x["normalized"] for x in license_entries if x["normalized"]})
            declared = sbom_licenses(component)
            mismatch = not licenses_match(declared, pom_licenses)
            if mismatch:
                mismatches.append(coordinate)
            if resolved["license_source"] == "missing":
                failures.append(coordinate + ": POM and bounded parent chain have no license declaration")
            elif mismatch:
                failures.append(coordinate + ": SBOM and POM/parent license identifier sets are empty or differ")
            results.append({"coordinate": coordinate, "pom_url": source_url,
                            "pom_sha256": hashlib.sha256(data).hexdigest(),
                            "license_source": resolved["license_source"],
                            "license_pom_url": resolved["license_pom_url"],
                            "license_pom_sha256": resolved["license_pom_sha256"],
                            "parent_pom_url": resolved["parent_pom_url"],
                            "parent_pom_sha256": resolved["parent_pom_sha256"],
                            "sbom_licenses": declared, "pom_licenses": license_entries,
                            "pom_license_ids": pom_licenses, "scm": metadata["scm"],
                            "developers": metadata["developers"], "organization": metadata["organization"],
                            "license_match": bool(declared and pom_licenses and not mismatch)})
        except (OSError, RuntimeError, ValueError, ET.ParseError) as exc:
            failures.append(coordinate + ": " + str(exc))
            mismatches.append(coordinate)
    return {"schema_version": 1, "component_count": len(components),
            "pom_component_count": len(results),
            "license_match_count": sum(x["license_match"] for x in results),
            "license_mismatch_count": len(mismatches),
            "license_mismatch_components": mismatches,
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
             f"- POM/SBOM license matches: **{report['license_match_count']}**",
             f"- POM/SBOM license mismatches or unresolved entries: **{report['license_mismatch_count']}**", "",
             "| Component | SBOM license | POM license | Source | Match | POM SHA-256 |",
             "|---|---|---|---|---|---|"]
    attribution = ["UPSTREAM MAVEN POM LICENSE AND SOURCE ATTRIBUTIONS",
                   "Metadata is copied from the exact Maven POM for each resolved coordinate.",
                   "This report is POM metadata evidence, not extracted binary LICENSE/NOTICE text.", ""]
    for item in report["components"]:
        pom_names = ", ".join(x["name"] or x["url"] for x in item["pom_licenses"]) or "UNDECLARED"
        sbom_names = ", ".join(item["sbom_licenses"]) or "UNDECLARED"
        lines.append(f"| {item['coordinate']} | {sbom_names} | {pom_names} | {item['license_source']} | {'YES' if item['license_match'] else 'NO'} | {item['pom_sha256']} |")
        attribution += ["## " + item["coordinate"], "POM: " + item["pom_url"],
                        "POM SHA-256: " + item["pom_sha256"],
                        "License metadata source: " + item["license_source"],
                        "SBOM license(s): " + sbom_names, "POM license(s): " + pom_names]
        if item.get("parent_pom_url"):
            attribution += ["Parent POM: " + item["parent_pom_url"],
                            "Parent POM SHA-256: " + str(item["parent_pom_sha256"])]
        if item.get("license_pom_url") and item["license_pom_url"] != item["pom_url"]:
            attribution += ["License declaration POM: " + item["license_pom_url"],
                            "License declaration POM SHA-256: " + str(item["license_pom_sha256"])]
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
    print(f"Maven POM audit: {report['pom_component_count']}/{report['component_count']} POMs; {report['license_match_count']} license matches; {report['license_mismatch_count']} mismatches.")
    for failure in report["failures"]:
        print("AUDIT_FAILURE: " + failure, file=sys.stderr)
    return 1 if report["failures"] or report["pom_component_count"] != report["component_count"] else 0

if __name__ == "__main__":
    raise SystemExit(main())
