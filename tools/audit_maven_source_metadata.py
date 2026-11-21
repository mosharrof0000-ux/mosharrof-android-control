#!/usr/bin/env python3
"""Classify Maven POM SCM references without claiming repository verification."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from urllib.parse import urlsplit

from audit_maven_metadata import sanitize_scm_reference


ALLOWED_SCHEMES = {"http", "https", "ssh", "git"}
SCP_LIKE = re.compile(r"^(?:[^@/:\s]+@)?[A-Za-z0-9.-]+:[^\s]+$")


def valid_scm_reference(value: str, *, allow_scp: bool = True) -> bool:
    """Check reference syntax only; this does not contact or authenticate a repository."""
    value = (value or "").strip()
    if not value:
        return False
    if value.startswith("scm:"):
        parts = value.split(":", 2)
        if len(parts) != 3 or not parts[2].strip():
            return False
        value = parts[2].strip()
    if value.startswith("git+"):
        value = value[4:]
    try:
        parsed = urlsplit(value)
        if parsed.scheme.lower() in ALLOWED_SCHEMES and parsed.hostname:
            return not any(ch.isspace() for ch in value)
    except ValueError:
        return False
    return bool(allow_scp and SCP_LIKE.fullmatch(value))


def assess_component(component: dict) -> dict:
    scm_raw = component.get("scm") or {}
    raw_fields = {key: str(scm_raw.get(key) or "").strip()
                  for key in ("url", "connection", "developerConnection")}
    raw_fields = {key: value for key, value in raw_fields.items() if value}
    invalid = [key for key, value in raw_fields.items()
               if not valid_scm_reference(value, allow_scp=(key != "url"))]
    valid = [key for key, value in raw_fields.items()
             if valid_scm_reference(value, allow_scp=(key != "url"))]
    sanitized = {key: sanitize_scm_reference(value) for key, value in raw_fields.items()}
    if invalid:
        status = "INVALID_OR_MIXED_REFERENCE"
    elif valid:
        status = "POM_REFERENCE_PRESENT_UNVERIFIED"
    else:
        status = "MISSING_REFERENCE"
    return {
        "coordinate": component.get("coordinate", ""),
        "pom_url": component.get("pom_url", ""),
        "pom_sha256": component.get("pom_sha256", ""),
        "parent_pom_url": component.get("parent_pom_url", ""),
        "parent_pom_sha256": component.get("parent_pom_sha256", ""),
        "scm": sanitized,
        "scm_source": component.get("scm_source") or ("pom" if valid else "missing"),
        "scm_provenance": component.get("scm_provenance") or {},
        "scm_parent_pom_url": component.get("scm_parent_pom_url", ""),
        "scm_parent_pom_sha256": component.get("scm_parent_pom_sha256", ""),
        "metadata_inheritance_error": component.get("metadata_inheritance_error"),
        "valid_fields": valid,
        "invalid_fields": invalid,
        "developer_metadata_present": bool(component.get("developers")),
        "developers_source": component.get("developers_source", "missing"),
        "organization_metadata_present": bool((component.get("organization") or {}).get("name")
                                               or (component.get("organization") or {}).get("url")),
        "organization_source": component.get("organization_source", "missing"),
        "assessment": status,
        "repository_existence_verified": False,
        "release_tag_verified": False,
        "publisher_identity_verified": False,
    }

def audit(source: dict) -> dict:
    components = [assess_component(item) for item in source.get("components", [])]
    counts = {key: sum(item["assessment"] == key for item in components)
              for key in ("POM_REFERENCE_PRESENT_UNVERIFIED",
                          "MISSING_REFERENCE", "INVALID_OR_MIXED_REFERENCE")}
    return {
        "schema_version": 1,
        "component_count": len(components),
        "assessment_counts": counts,
        "repository_existence_verified_count": 0,
        "release_tag_verified_count": 0,
        "publisher_identity_verified_count": 0,
        "components": components,
        "policy": "POM SCM syntax is metadata only; repository existence, matching release tags, and publisher identity are not verified by this audit.",
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--json-report", required=True, type=Path)
    parser.add_argument("--markdown-report", required=True, type=Path)
    args = parser.parse_args()
    source = json.loads(args.input.read_text(encoding="utf-8"))
    report = audit(source)
    for path in (args.json_report, args.markdown_report):
        path.parent.mkdir(parents=True, exist_ok=True)
    args.json_report.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n",
                                encoding="utf-8")
    lines = [
        "# Maven POM SCM reference assessment", "",
        f"- Runtime components assessed: **{report['component_count']}**",
        f"- POM SCM reference present (syntax only): **{report['assessment_counts']['POM_REFERENCE_PRESENT_UNVERIFIED']}**",
        f"- SCM reference missing: **{report['assessment_counts']['MISSING_REFERENCE']}**",
        f"- SCM reference invalid or mixed: **{report['assessment_counts']['INVALID_OR_MIXED_REFERENCE']}**",
        f"- SCM metadata inherited from parent POM: **{sum(item['scm_source'] == 'parent_pom' for item in report['components'])}**",
        "- Repository existence verified: **0**",
        "- Matching release tag verified: **0**",
        "- Publisher identity verified: **0**", "",
        "This report validates only the syntax/presence of POM SCM metadata. It does not contact repositories, prove repository ownership, match artifact versions to tags/commits, or authenticate publishers.", "",
        "| Component | Assessment | SCM source | POM SHA-256 | SCM URL | Connection | Developer metadata | Organization metadata |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for item in report["components"]:
        scm = item["scm"]
        values = [item["coordinate"], item["assessment"], item["scm_source"], item["pom_sha256"],
                  scm.get("url", ""), scm.get("connection", ""),
                  "yes" if item["developer_metadata_present"] else "no",
                  "yes" if item["organization_metadata_present"] else "no"]
        lines.append("| " + " | ".join(str(value).replace("|", "\\|").replace("\n", " ")
                                       for value in values) + " |")
    args.markdown_report.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("POM SCM references (syntax only): " +
          json.dumps(report["assessment_counts"], sort_keys=True))
    print("Repository, release-tag and publisher verification: NOT PERFORMED")
    print("Sanitized SCM references for follow-up verification:")
    for item in report["components"]:
        scm_value = item["scm"].get("url") or item["scm"].get("connection") or item["scm"].get("developerConnection") or ""
        if scm_value.startswith("scm:"):
            scm_value = scm_value.split(":", 2)[-1]
        if scm_value.startswith("git+"):
            scm_value = scm_value[4:]
        try:
            parsed = urlsplit(scm_value)
            if parsed.scheme and parsed.hostname:
                safe_ref = f"{parsed.scheme}://{parsed.hostname}{parsed.path}"
            else:
                safe_ref = scm_value.split("?", 1)[0].split("#", 1)[0]
                if "@" in safe_ref and ":" in safe_ref:
                    safe_ref = safe_ref.split("@", 1)[-1]
        except ValueError:
            safe_ref = "[unparseable SCM reference]"
        print(f"SCM_REF {item['coordinate']} | {item['assessment']} | source={item['scm_source']} | {safe_ref}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
