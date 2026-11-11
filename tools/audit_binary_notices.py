#!/usr/bin/env python3
"""Inspect resolved Android AAR/JAR archives and preserve upstream license/notice text."""
from __future__ import annotations
import argparse, hashlib, json, re, sys, zipfile
from collections import Counter
from pathlib import Path
from typing import Any

LICENSE_NAME = re.compile(r"(^|/)(?:LICENSE|LICENCE|NOTICE|COPYING|COPYRIGHT)(?:[._-].*)?$", re.I)
MAX_TEXT_BYTES = 2 * 1024 * 1024
MAX_ARCHIVE_BYTES = 64 * 1024 * 1024

def safe_text(data: bytes):
    if not data or len(data) > MAX_TEXT_BYTES or b"\x00" in data[:4096]: return None
    value = data.decode("utf-8", errors="replace").strip()
    return value or None

def scan_zip(data: bytes, source: str, depth: int = 0):
    if depth > 4 or len(data) > MAX_ARCHIVE_BYTES: return []
    from io import BytesIO
    found = []
    try:
        with zipfile.ZipFile(BytesIO(data)) as archive:
            for info in archive.infolist():
                if info.is_dir() or info.file_size > MAX_TEXT_BYTES: continue
                name = info.filename.replace("\\", "/")
                try: payload = archive.read(info)
                except (OSError, RuntimeError, zipfile.BadZipFile): continue
                if LICENSE_NAME.search(name):
                    value = safe_text(payload)
                    if value:
                        found.append({"path": source + "!/" + name,
                                      "sha256": hashlib.sha256(payload).hexdigest(),
                                      "content": value})
                if name.lower().endswith((".jar", ".zip")) and len(payload) <= MAX_ARCHIVE_BYTES:
                    found.extend(scan_zip(payload, source + "!/" + name, depth + 1))
    except (OSError, zipfile.BadZipFile):
        return []
    return found

def licenses_for(component: dict[str, Any]):
    labels = set()
    for item in component.get("licenses") or []:
        lic = item.get("license") or item.get("expression") or {}
        if isinstance(lic, dict):
            label = lic.get("id") or lic.get("name")
            if label: labels.add(str(label))
        elif isinstance(lic, str): labels.add(lic)
    return sorted(labels)

def audit(sbom_path: Path, artifact_dir: Path, notice_output: Path):
    bom = json.loads(sbom_path.read_text(encoding="utf-8"))
    components = bom.get("components") or []
    if not components: raise ValueError("SBOM contains no components")
    results, failures, unique_text = [], [], {}
    for component in components:
        group, name, version = (str(component.get(k) or "") for k in ("group", "name", "version"))
        if not all((group, name, version)):
            raise ValueError("SBOM component lacks Maven group/name/version: " + str(component.get("bom-ref")))
        coordinate = f"{group}:{name}:{version}"
        directory = artifact_dir / group.replace(".", "/") / name / version
        binaries = sorted(p for p in directory.glob("*") if p.is_file() and p.suffix.lower() in {".aar", ".jar"}) if directory.is_dir() else []
        declared = licenses_for(component)
        extracted = []
        if not binaries: failures.append(coordinate + ": resolved AAR/JAR binary not collected")
        for binary in binaries:
            try: extracted.extend(scan_zip(binary.read_bytes(), coordinate + "/" + binary.name))
            except OSError as exc: failures.append(coordinate + ": cannot read " + binary.name + ": " + str(exc))
        for entry in extracted: unique_text.setdefault(entry["sha256"], entry)
        evidence = "ARCHIVE_LICENSE_OR_NOTICE" if extracted else ("SBOM_DECLARED_LICENSE_ONLY" if declared else "NO_LICENSE_EVIDENCE")
        if evidence == "NO_LICENSE_EVIDENCE": failures.append(coordinate + ": no archive notice/license and no SBOM license declaration")
        results.append({"coordinate": coordinate, "binary_files": [p.name for p in binaries],
                        "binary_count": len(binaries), "declared_licenses": declared,
                        "archive_notice_files": [e["path"] for e in extracted],
                        "evidence_level": evidence, "notice_found": bool(extracted)})
    notice_output.parent.mkdir(parents=True, exist_ok=True)
    lines = ["UPSTREAM THIRD-PARTY LICENSE AND ATTRIBUTION EVIDENCE",
             "Generated from releaseRuntimeClasspath AAR/JAR files.",
             "Exact matching upstream text files are preserved below.",
             "SBOM declarations are not represented as extracted license text.", ""]
    for item in results:
        lines += ["## " + item["coordinate"],
                  "Declared SBOM license(s): " + (", ".join(item["declared_licenses"]) or "UNIDENTIFIED"),
                  "Evidence: " + item["evidence_level"]]
        matching = [unique_text[e["sha256"]] for e in (e for e in unique_text.values() if e["path"] in item["archive_notice_files"])]
        if matching:
            for entry in matching: lines += ["", "Source: " + entry["path"], "", entry["content"], ""]
        else: lines.append("No matching LICENSE/NOTICE/COPYING/COPYRIGHT text file found in this binary archive.")
        lines.append("")
    notice_output.write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")
    return {"schema_version": 1, "component_count": len(results),
            "binary_component_count": sum(bool(x["binary_files"]) for x in results),
            "binary_file_count": sum(x["binary_count"] for x in results),
            "archive_notice_component_count": sum(x["notice_found"] for x in results),
            "evidence_counts": dict(Counter(x["evidence_level"] for x in results)),
            "components": results, "failures": failures}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sbom", required=True, type=Path)
    parser.add_argument("--artifact-dir", required=True, type=Path)
    parser.add_argument("--notice-output", required=True, type=Path)
    parser.add_argument("--json-report", required=True, type=Path)
    parser.add_argument("--markdown-report", required=True, type=Path)
    args = parser.parse_args()
    try: report = audit(args.sbom, args.artifact_dir, args.notice_output)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print("Binary notice audit could not run: " + str(exc), file=sys.stderr); return 2
    args.json_report.parent.mkdir(parents=True, exist_ok=True)
    args.json_report.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    lines = ["# Binary-level third-party notice audit", "",
             f"- SBOM components: **{report['component_count']}**",
             f"- Components with collected AAR/JAR binaries: **{report['binary_component_count']}**",
             f"- Binary files inspected: **{report['binary_file_count']}**",
             f"- Components with archive LICENSE/NOTICE text: **{report['archive_notice_component_count']}**", "",
             "| Evidence level | Components |", "|---|---:|"]
    for label, count in sorted(report["evidence_counts"].items()): lines.append(f"| {label} | {count} |")
    lines += ["", "## Component details", "", "| Component | Binary files | Declared license(s) | Evidence |", "|---|---|---|---|"]
    for item in report["components"]:
        lines.append("| " + item["coordinate"] + " | " + (", ".join(item["binary_files"]) or "MISSING") +
                     " | " + (", ".join(item["declared_licenses"]) or "UNIDENTIFIED") + " | " + item["evidence_level"] + " |")
    if report["failures"]: lines += ["", "## Failures", ""] + ["- " + x for x in report["failures"]]
    else: lines += ["", "No missing runtime binaries or unidentified license declarations detected."]
    args.markdown_report.parent.mkdir(parents=True, exist_ok=True)
    args.markdown_report.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Binary notice audit: {report['binary_component_count']}/{report['component_count']} components; {report['archive_notice_component_count']} have archive notices.")
    return 1 if report["failures"] else 0

if __name__ == "__main__": raise SystemExit(main())
