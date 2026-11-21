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
SHA1_RE = re.compile(r"^[0-9a-f]{40}$")
COORDINATE_RE = re.compile(r"^[A-Za-z0-9_.+-]+:[A-Za-z0-9_.+-]+:[A-Za-z0-9_.+-]+$")
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

    # Keep compound SPDX expressions intact; never collapse "Apache-2.0 OR MIT"
    # to Apache-2.0, which would discard one side of the declaration.
    if re.search(r"\b(?:and|or|with)\b|[()]", value, flags=re.IGNORECASE):
        return "SPDX-EXPRESSION:" + re.sub(r"\s+", " ", value).strip()

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
        url = sanitize_scm_reference(url) if url else ""
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
        if developer["organizationUrl"]:
            developer["organizationUrl"] = sanitize_scm_reference(developer["organizationUrl"])
        if any(developer.values()): developers.append(developer)
    organization = root.find("./organization")
    org = {k: child_text(organization, k) for k in ("name", "url")} if organization is not None else {}
    if org.get("url"):
        org["url"] = sanitize_scm_reference(org["url"])
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

def _source_summary(provenance: dict[str, dict[str, str]]) -> str:
    sources = {item.get("source", "missing") for item in provenance.values()}
    sources.discard("missing")
    if not sources:
        return "missing"
    if len(sources) > 1:
        return "mixed"
    return next(iter(sources))


def _merge_metadata_fields(child: dict[str, str], parent: dict[str, str],
                           keys: tuple[str, ...], source_url: str, source_sha256: str,
                           parent_provenance: dict[str, dict[str, str]] | None = None,
                           parent_url: str | None = None,
                           parent_sha256: str | None = None):
    values, provenance = {}, {}
    parent_provenance = parent_provenance or {}
    for key in keys:
        if child.get(key):
            values[key] = child[key]
            provenance[key] = {"source": "pom", "pom_url": source_url,
                               "pom_sha256": source_sha256}
        elif parent.get(key):
            inherited = parent_provenance.get(key) or {}
            values[key] = parent[key]
            provenance[key] = {
                "source": "parent_pom",
                "pom_url": inherited.get("pom_url") or parent_url or "",
                "pom_sha256": inherited.get("pom_sha256") or parent_sha256 or "",
                "inherited_via_pom": parent_url or "",
            }
        else:
            provenance[key] = {"source": "missing", "pom_url": "", "pom_sha256": ""}
    return values, provenance


def resolve_license_metadata(data: bytes, source_url: str, depth: int = 0,
                             seen: set[tuple[str, str, str]] | None = None) -> dict[str, Any]:
    """Resolve license and missing source metadata from a bounded Maven parent chain."""
    if seen is None:
        seen = set()
    metadata = parse_pom(data)
    data_sha256 = hashlib.sha256(data).hexdigest()
    direct_licenses = metadata["licenses"]
    direct_scm = metadata["scm"]
    direct_developers = metadata["developers"]
    direct_organization = metadata["organization"]

    direct_scm_values, direct_scm_provenance = _merge_metadata_fields(
        direct_scm, {}, ("url", "connection", "developerConnection"), source_url, data_sha256)
    direct_org_values, direct_org_provenance = _merge_metadata_fields(
        direct_organization, {}, ("name", "url"), source_url, data_sha256)
    base = {
        "metadata": metadata,
        "license_source": "pom" if direct_licenses else "missing",
        "license_pom_url": source_url if direct_licenses else None,
        "license_pom_sha256": data_sha256 if direct_licenses else None,
        "effective_licenses": direct_licenses,
        "parent_pom_url": None,
        "parent_pom_sha256": None,
        "effective_scm": direct_scm_values,
        "scm_source": _source_summary(direct_scm_provenance),
        "scm_provenance": direct_scm_provenance,
        "scm_pom_url": source_url if any(direct_scm_values.values()) else None,
        "scm_pom_sha256": data_sha256 if any(direct_scm_values.values()) else None,
        "scm_parent_pom_url": None,
        "scm_parent_pom_sha256": None,
        "effective_developers": direct_developers,
        "developers_source": "pom" if direct_developers else "missing",
        "developers_pom_url": source_url if direct_developers else None,
        "developers_pom_sha256": data_sha256 if direct_developers else None,
        "developers_parent_pom_url": None,
        "effective_organization": direct_org_values,
        "organization_source": _source_summary(direct_org_provenance),
        "organization_provenance": direct_org_provenance,
        "organization_pom_url": source_url if any(direct_org_values.values()) else None,
        "organization_pom_sha256": data_sha256 if any(direct_org_values.values()) else None,
        "organization_parent_pom_url": None,
        "metadata_inheritance_error": None,
        "parent_resolution_limited": False,
    }

    needs_parent = (
        not direct_licenses
        or any(not direct_scm.get(key) for key in ("url", "connection", "developerConnection"))
        or not direct_developers
        or any(not direct_organization.get(key) for key in ("name", "url"))
    )
    parent = metadata.get("parent") or {}
    if (not needs_parent
            or not all(parent.get(key) for key in ("groupId", "artifactId", "version"))):
        return base
    if depth >= 4:
        base["parent_resolution_limited"] = True
        return base

    coordinate = (parent["groupId"], parent["artifactId"], parent["version"])
    if coordinate in seen:
        if direct_licenses:
            base["metadata_inheritance_error"] = "Maven parent POM cycle detected"
            return base
        raise ValueError("Maven parent POM cycle detected")
    seen.add(coordinate)
    try:
        parent_data, parent_url = fetch_pom(pom_urls(*coordinate))
    except (OSError, RuntimeError, ValueError) as exc:
        if direct_licenses:
            base["metadata_inheritance_error"] = type(exc).__name__ + ": " + str(exc)
            return base
        raise

    parent_sha256 = hashlib.sha256(parent_data).hexdigest()
    resolved = resolve_license_metadata(parent_data, parent_url, depth + 1, seen)

    if direct_licenses:
        effective_licenses = direct_licenses
        license_source = "pom"
        license_pom_url, license_pom_sha256 = source_url, data_sha256
    else:
        effective_licenses = resolved["effective_licenses"]
        license_source = "parent_pom" if resolved["license_source"] != "missing" else "missing"
        license_pom_url = resolved["license_pom_url"]
        license_pom_sha256 = resolved["license_pom_sha256"]

    effective_scm, scm_provenance = _merge_metadata_fields(
        direct_scm, resolved["effective_scm"],
        ("url", "connection", "developerConnection"), source_url, data_sha256,
        resolved["scm_provenance"], parent_url, parent_sha256)
    inherited_scm = [item for item in scm_provenance.values() if item["source"] == "parent_pom"]
    direct_scm_present = any(direct_scm.values())
    scm_parent_pom_url = next((item["pom_url"] for item in inherited_scm if item.get("pom_url")), None)
    scm_parent_pom_sha256 = next((item["pom_sha256"] for item in inherited_scm if item.get("pom_sha256")), None)

    if direct_developers:
        effective_developers = direct_developers
        developers_source = "pom"
        developers_pom_url, developers_pom_sha256 = source_url, data_sha256
        developers_parent_pom_url = None
    elif resolved["effective_developers"]:
        effective_developers = resolved["effective_developers"]
        developers_source = "parent_pom"
        developers_pom_url = resolved["developers_pom_url"]
        developers_pom_sha256 = resolved["developers_pom_sha256"]
        developers_parent_pom_url = parent_url
    else:
        effective_developers = []
        developers_source = "missing"
        developers_pom_url = developers_pom_sha256 = developers_parent_pom_url = None

    effective_organization, organization_provenance = _merge_metadata_fields(
        direct_organization, resolved["effective_organization"],
        ("name", "url"), source_url, data_sha256,
        resolved["organization_provenance"], parent_url, parent_sha256)
    inherited_org = [item for item in organization_provenance.values() if item["source"] == "parent_pom"]
    direct_org_present = any(direct_organization.values())
    organization_parent_pom_url = next((item["pom_url"] for item in inherited_org if item.get("pom_url")), None)
    organization_pom_url = source_url if direct_org_present else organization_parent_pom_url
    organization_pom_sha256 = data_sha256 if direct_org_present else next(
        (item["pom_sha256"] for item in inherited_org if item.get("pom_sha256")), None)

    return {
        "metadata": metadata,
        "license_source": license_source,
        "license_pom_url": license_pom_url,
        "license_pom_sha256": license_pom_sha256,
        "effective_licenses": effective_licenses,
        "parent_pom_url": parent_url,
        "parent_pom_sha256": parent_sha256,
        "effective_scm": effective_scm,
        "scm_source": _source_summary(scm_provenance),
        "scm_provenance": scm_provenance,
        "scm_pom_url": source_url if direct_scm_present else scm_parent_pom_url,
        "scm_pom_sha256": data_sha256 if direct_scm_present else scm_parent_pom_sha256,
        "scm_parent_pom_url": scm_parent_pom_url,
        "scm_parent_pom_sha256": scm_parent_pom_sha256,
        "effective_developers": effective_developers,
        "developers_source": developers_source,
        "developers_pom_url": developers_pom_url,
        "developers_pom_sha256": developers_pom_sha256,
        "developers_parent_pom_url": developers_parent_pom_url,
        "effective_organization": effective_organization,
        "organization_source": _source_summary(organization_provenance),
        "organization_provenance": organization_provenance,
        "organization_pom_url": organization_pom_url,
        "organization_pom_sha256": organization_pom_sha256,
        "organization_parent_pom_url": organization_parent_pom_url,
        "metadata_inheritance_error": resolved["metadata_inheritance_error"],
        "parent_resolution_limited": resolved["parent_resolution_limited"],
    }


def licenses_match(sbom: list[str], pom: list[str]) -> bool:
    """Require non-empty, exact equality between normalized SBOM and POM license sets."""
    return bool(sbom and pom and set(sbom) == set(pom))


def sbom_licenses(component: dict[str, Any]) -> list[str]:
    values = set()
    for entry in component.get("licenses") or []:
        expression = entry.get("expression")
        if expression:
            values.add(normalize_license(str(expression)))
            continue
        lic = entry.get("license") or {}
        if isinstance(lic, dict):
            label = lic.get("id") or lic.get("name") or ""
            if label: values.add(normalize_license(str(label)))
    return sorted(values)

def _manifest_line(value: Any, label: str, *, required: bool = True) -> str | None:
    if value is None and not required:
        return None
    if not isinstance(value, str):
        raise ValueError(label + " must be a string")
    if required and not value.strip():
        raise ValueError(label + " must not be empty")
    if len(value) > 4096 or any(ch in value for ch in ("\x00", "\r", "\n")):
        raise ValueError(label + " contains unsafe control characters or is too long")
    return value.strip()


def _repository_kind(repository: str) -> str:
    parsed = urlsplit(repository)
    try:
        port = parsed.port
    except ValueError as exc:
        raise ValueError("Invalid upstream repository URL") from exc
    if (parsed.scheme != "https" or parsed.username or parsed.password or port
            or parsed.query or parsed.fragment or parsed.netloc != parsed.hostname):
        raise ValueError("Upstream repository URL must be a credential-free HTTPS URL")
    parts = parsed.path.strip("/").split("/")
    if not parts or any(part in {"", ".", ".."} for part in parts):
        raise ValueError("Upstream repository URL has an unsafe path")
    if parsed.hostname == "github.com" and len(parts) == 2:
        return "github"
    if parsed.hostname == "android.googlesource.com":
        return "gitiles"
    raise ValueError("Unsupported upstream source host")


def _validate_evidence_file(item: Any, default_repository: str,
                            default_commit: str, label: str) -> dict[str, Any]:
    if not isinstance(item, dict):
        raise ValueError(label + " must be an object")
    path = _manifest_line(item.get("path"), label + " path")
    if path.startswith("/") or "\\" in path or any(part in {"", ".", ".."} for part in path.split("/")):
        raise ValueError(label + " path is not repository-relative")
    repository = _manifest_line(item.get("repository") or default_repository,
                                label + " repository")
    commit = _manifest_line(item.get("commit") or default_commit, label + " commit")
    if not SHA1_RE.fullmatch(commit):
        raise ValueError(label + " commit must be a full 40-character Git SHA")
    kind = _repository_kind(repository)
    expected_url = (repository.rstrip("/") + "/blob/" + commit + "/" + path
                    if kind == "github"
                    else repository.rstrip("/") + "/+/" + commit + "/" + path)
    url = _manifest_line(item.get("url"), label + " URL")
    if url != expected_url:
        raise ValueError(label + " URL is not pinned to its declared repository, commit and path")
    blob_sha = _manifest_line(item.get("git_blob_sha"), label + " Git blob SHA")
    if not SHA1_RE.fullmatch(blob_sha):
        raise ValueError(label + " Git blob SHA must be a full 40-character SHA")
    return dict(item)


def load_upstream_evidence(path: Path, components: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(manifest, dict) or manifest.get("schema_version") != 2:
        raise ValueError("Upstream attribution manifest must use schema_version 2")
    records = manifest.get("components")
    if not isinstance(records, list):
        raise ValueError("Upstream attribution manifest components must be an array")

    sbom_by_coordinate: dict[str, dict[str, Any]] = {}
    for component in components:
        group, name, version = (str(component.get(k) or "") for k in ("group", "name", "version"))
        coordinate = f"{group}:{name}:{version}"
        if not all((group, name, version)) or not COORDINATE_RE.fullmatch(coordinate):
            raise ValueError("SBOM contains an incomplete or unsafe Maven coordinate")
        if coordinate in sbom_by_coordinate:
            raise ValueError("SBOM contains duplicate Maven coordinate: " + coordinate)
        sbom_by_coordinate[coordinate] = component

    result: dict[str, dict[str, Any]] = {}
    for item in records:
        if not isinstance(item, dict):
            raise ValueError("Upstream attribution manifest record must be an object")
        coordinate = _manifest_line(item.get("coordinate"), "Manifest coordinate")
        if not COORDINATE_RE.fullmatch(coordinate):
            raise ValueError("Unsafe upstream attribution coordinate: " + coordinate)
        if coordinate in result:
            raise ValueError("Duplicate upstream attribution coordinate: " + coordinate)
        if coordinate not in sbom_by_coordinate:
            raise ValueError("Unknown upstream attribution coordinate: " + coordinate)

        status = item.get("review_status")
        if status not in {"PARTIAL", "NOT_REVIEWED"}:
            raise ValueError("Unsupported upstream review status for " + coordinate)
        if item.get("artifact_signature_status") != "UNVERIFIED":
            raise ValueError("Artifact signature status must remain UNVERIFIED for " + coordinate)
        unresolved = item.get("unresolved")
        if not isinstance(unresolved, list) or not unresolved:
            raise ValueError("Every upstream record requires explicit unresolved reasons: " + coordinate)
        for index, value in enumerate(unresolved):
            _manifest_line(value, coordinate + " unresolved reason " + str(index + 1))

        source_files = item.get("source_files")
        supporting = item.get("supporting_evidence") or []
        copyrights = item.get("copyright_attributions")
        notice_paths = item.get("notice_paths_checked")
        exclusions = item.get("notice_exclusions") or []
        if not isinstance(source_files, list) or not isinstance(supporting, list):
            raise ValueError("Upstream source file evidence must be arrays: " + coordinate)
        if not isinstance(copyrights, list) or not isinstance(notice_paths, list) or not isinstance(exclusions, list):
            raise ValueError("Upstream attribution and NOTICE fields must be arrays: " + coordinate)

        if status == "NOT_REVIEWED":
            if (item.get("license_id") not in (None, "")
                    or item.get("source_repository") not in (None, "")
                    or item.get("source_ref") not in (None, "")
                    or item.get("source_commit") not in (None, "")
                    or source_files or supporting or copyrights or notice_paths
                    or item.get("notice_review_status") != "NOT_REVIEWED"
                    or item.get("signature_evidence") is not None):
                raise ValueError("NOT_REVIEWED record contains unsupported source or attribution claims: " + coordinate)
            _manifest_line(item.get("source_mapping_status"), coordinate + " source mapping status")
            result[coordinate] = dict(item)
            continue

        license_id = _manifest_line(item.get("license_id"), coordinate + " license_id")
        declared = sbom_licenses(sbom_by_coordinate[coordinate])
        if len(declared) != 1 or normalize_license(license_id) not in declared:
            raise ValueError("Upstream source license does not exactly match the SBOM declaration: " + coordinate)
        repository = _manifest_line(item.get("source_repository"), coordinate + " source repository")
        _repository_kind(repository)
        _manifest_line(item.get("source_ref"), coordinate + " source ref")
        commit = _manifest_line(item.get("source_commit"), coordinate + " source commit")
        if not SHA1_RE.fullmatch(commit):
            raise ValueError("Upstream source commit must be a full 40-character Git SHA: " + coordinate)
        _manifest_line(item.get("source_revision_resolution"), coordinate + " source revision resolution")
        _manifest_line(item.get("source_mapping_status"), coordinate + " source mapping status")
        if not source_files:
            raise ValueError("PARTIAL record requires commit-pinned source files: " + coordinate)
        for index, source_file in enumerate(source_files):
            _validate_evidence_file(source_file, repository, commit,
                                    coordinate + " source file " + str(index + 1))
        for index, evidence_file in enumerate(supporting):
            if not isinstance(evidence_file, dict):
                raise ValueError("Supporting evidence must be an object: " + coordinate)
            _validate_evidence_file(evidence_file,
                                    evidence_file.get("repository") or repository,
                                    evidence_file.get("commit") or commit,
                                    coordinate + " supporting evidence " + str(index + 1))
        if not copyrights:
            raise ValueError("PARTIAL record requires exact source-derived copyright text: " + coordinate)
        for index, value in enumerate(copyrights):
            _manifest_line(value, coordinate + " copyright attribution " + str(index + 1))
        notice_status = item.get("notice_review_status")
        if notice_status not in {"NOT_FOUND_AT_REVIEWED_PATHS", "NO_STDLIB_NOTICE_FOUND_AT_REVIEWED_PATHS"}:
            raise ValueError("PARTIAL record requires an explicit unresolved NOTICE review status: " + coordinate)
        if not notice_paths:
            raise ValueError("PARTIAL record requires the NOTICE paths reviewed: " + coordinate)
        for index, value in enumerate(notice_paths):
            _manifest_line(value, coordinate + " NOTICE path " + str(index + 1))
        for index, value in enumerate(exclusions):
            _manifest_line(value, coordinate + " NOTICE exclusion " + str(index + 1))
        signature_evidence = item.get("signature_evidence")
        if signature_evidence is not None:
            if (not isinstance(signature_evidence, dict)
                    or signature_evidence.get("status") != "HISTORICAL_THIRD_PARTY_REPORT"):
                raise ValueError("Historical signature evidence must not be treated as current verification: " + coordinate)
            signature_url = _manifest_line(signature_evidence.get("url"), coordinate + " signature evidence URL")
            parsed_signature_url = urlsplit(signature_url)
            if (parsed_signature_url.scheme != "https" or parsed_signature_url.hostname != "github.com"
                    or parsed_signature_url.netloc != "github.com" or parsed_signature_url.query
                    or parsed_signature_url.fragment or parsed_signature_url.username):
                raise ValueError("Historical signature evidence URL must be a public HTTPS GitHub URL: " + coordinate)
            _manifest_line(signature_evidence.get("summary"), coordinate + " signature evidence summary")
        result[coordinate] = dict(item)

    missing = sorted(set(sbom_by_coordinate) - set(result))
    unknown = sorted(set(result) - set(sbom_by_coordinate))
    if missing or unknown or len(records) != len(sbom_by_coordinate):
        raise ValueError("Upstream attribution manifest must cover the SBOM exactly; missing="
                         + str(missing) + "; unknown=" + str(unknown))
    return result


def audit(sbom_path: Path, upstream_evidence_path: Path | None = None) -> dict[str, Any]:
    bom = json.loads(sbom_path.read_text(encoding="utf-8"))
    components = bom.get("components") or []
    if not components:
        raise ValueError("SBOM contains no components")
    upstream_by_coordinate = (load_upstream_evidence(upstream_evidence_path, components)
                              if upstream_evidence_path else {})
    results, failures, mismatches, upstream_mismatches = [], [], [], []
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
            license_entries = resolved["effective_licenses"]
            pom_licenses = sorted({x["normalized"] for x in license_entries if x["normalized"]})
            declared = sbom_licenses(component)
            mismatch = not licenses_match(declared, pom_licenses)
            if mismatch:
                mismatches.append(coordinate)
            if resolved["license_source"] == "missing":
                failures.append(coordinate + ": POM and bounded parent chain have no license declaration")
            elif mismatch:
                failures.append(coordinate + ": SBOM and POM/parent license identifier sets are empty or differ")

            source_record = upstream_by_coordinate.get(coordinate)
            source_status = source_record.get("review_status") if source_record else "NOT_REVIEWED"
            if source_record and source_status == "PARTIAL":
                source_license = normalize_license(str(source_record.get("license_id") or ""))
                if declared != [source_license] or pom_licenses != [source_license]:
                    source_status = "INVALID_LICENSE_MISMATCH"
                    upstream_mismatches.append(coordinate)
                    failures.append(coordinate + ": upstream source evidence license does not exactly match POM and SBOM")

            results.append({"coordinate": coordinate, "pom_url": source_url,
                            "pom_sha256": hashlib.sha256(data).hexdigest(),
                            "license_source": resolved["license_source"],
                            "license_pom_url": resolved["license_pom_url"],
                            "license_pom_sha256": resolved["license_pom_sha256"],
                            "parent_pom_url": resolved["parent_pom_url"],
                            "parent_pom_sha256": resolved["parent_pom_sha256"],
                            "sbom_licenses": declared, "pom_licenses": license_entries,
                            "pom_license_ids": pom_licenses, "scm": resolved["effective_scm"],
                            "scm_source": resolved["scm_source"],
                            "scm_provenance": resolved["scm_provenance"],
                            "scm_pom_url": resolved["scm_pom_url"],
                            "scm_pom_sha256": resolved["scm_pom_sha256"],
                            "scm_parent_pom_url": resolved["scm_parent_pom_url"],
                            "scm_parent_pom_sha256": resolved["scm_parent_pom_sha256"],
                            "developers": resolved["effective_developers"],
                            "developers_source": resolved["developers_source"],
                            "developers_pom_url": resolved["developers_pom_url"],
                            "developers_pom_sha256": resolved["developers_pom_sha256"],
                            "developers_parent_pom_url": resolved["developers_parent_pom_url"],
                            "organization": resolved["effective_organization"],
                            "organization_source": resolved["organization_source"],
                            "organization_provenance": resolved["organization_provenance"],
                            "organization_pom_url": resolved["organization_pom_url"],
                            "organization_pom_sha256": resolved["organization_pom_sha256"],
                            "organization_parent_pom_url": resolved["organization_parent_pom_url"],
                            "metadata_inheritance_error": resolved["metadata_inheritance_error"],
                            "parent_resolution_limited": resolved["parent_resolution_limited"],
                            "upstream_source_review_status": source_status,
                            "upstream_source_evidence": source_record,
                            "license_match": bool(declared and pom_licenses and not mismatch)})
        except (OSError, RuntimeError, ValueError, ET.ParseError) as exc:
            failures.append(coordinate + ": " + str(exc))
            mismatches.append(coordinate)

    source_statuses = [item["upstream_source_review_status"] for item in results]
    return {"schema_version": 2, "component_count": len(components),
            "pom_component_count": len(results),
            "license_match_count": sum(x["license_match"] for x in results),
            "license_mismatch_count": len(mismatches),
            "license_mismatch_components": mismatches,
            "upstream_source_review_count": source_statuses.count("PARTIAL"),
            "upstream_source_not_reviewed_count": source_statuses.count("NOT_REVIEWED"),
            "upstream_source_mismatch_count": len(upstream_mismatches),
            "upstream_source_mismatch_components": upstream_mismatches,
            "components": results, "failures": failures}


def render_attributions(report: dict[str, Any]) -> str:
    attribution = [
        "UPSTREAM MAVEN POM LICENSE AND SOURCE ATTRIBUTIONS",
        "Metadata is copied from the exact Maven POM and bounded parent POM chain for each resolved coordinate.",
        "This report is POM metadata evidence, not extracted binary LICENSE/NOTICE text.",
        "Coordinate-specific source records below are separate evidence and do not prove the published artifact was built from that source revision.",
        "",
    ]
    for item in report["components"]:
        pom_names = ", ".join(x["name"] or x["url"] for x in item["pom_licenses"]) or "UNDECLARED"
        sbom_names = ", ".join(item["sbom_licenses"]) or "UNDECLARED"
        attribution += [
            "## " + item["coordinate"], "POM: " + item["pom_url"],
            "POM SHA-256: " + item["pom_sha256"],
            "License metadata source: " + item["license_source"],
            "SBOM license(s): " + sbom_names, "POM license(s): " + pom_names,
            "Copyright/NOTICE attribution status: NOT ESTABLISHED BY POM METADATA; authoritative upstream artifact/source review required.",
        ]
        if item.get("parent_pom_url"):
            attribution += ["Parent POM: " + item["parent_pom_url"],
                            "Parent POM SHA-256: " + str(item["parent_pom_sha256"])]
        if item.get("metadata_inheritance_error"):
            attribution.append("Metadata inheritance warning: " + item["metadata_inheritance_error"])
        if item.get("parent_resolution_limited"):
            attribution.append("Parent metadata inheritance: depth limit reached; missing fields remain unresolved")
        if item.get("scm_source"):
            attribution.append("SCM metadata source: " + item["scm_source"])
        if item.get("scm_pom_url"):
            attribution += ["SCM source POM: " + item["scm_pom_url"],
                            "SCM source POM SHA-256: " + str(item["scm_pom_sha256"])]
        if item.get("scm_parent_pom_url"):
            attribution += ["Inherited SCM source POM: " + item["scm_parent_pom_url"],
                            "Inherited SCM source POM SHA-256: " + str(item["scm_parent_pom_sha256"])]
        if item.get("developers_source"):
            attribution.append("Developers metadata source: " + item["developers_source"])
        if item.get("developers_pom_url"):
            attribution += ["Developers source POM: " + item["developers_pom_url"],
                            "Developers source POM SHA-256: " + str(item["developers_pom_sha256"])]
        if item.get("organization_source"):
            attribution.append("Organization metadata source: " + item["organization_source"])
        if item.get("organization_pom_url"):
            attribution += ["Organization source POM: " + item["organization_pom_url"],
                            "Organization source POM SHA-256: " + str(item["organization_pom_sha256"])]
        if item.get("license_pom_url") and item["license_pom_url"] != item["pom_url"]:
            attribution += ["License declaration POM: " + item["license_pom_url"],
                            "License declaration POM SHA-256: " + str(item["license_pom_sha256"])]
        for lic in item["pom_licenses"]:
            if lic["url"]:
                attribution.append("License URL: " + lic["url"])
        for key, value in item["scm"].items():
            if value:
                attribution.append("SCM " + key + ": " + value)
        for person in item["developers"]:
            label = " / ".join(x for x in (person["name"], person["organization"]) if x)
            if label:
                attribution.append("Developer: " + label)
        if item["organization"].get("name"):
            attribution.append("Organization: " + item["organization"]["name"])

        evidence = item.get("upstream_source_evidence")
        status = item.get("upstream_source_review_status", "NOT_REVIEWED")
        if evidence and status in {"PARTIAL", "INVALID_LICENSE_MISMATCH"}:
            attribution.append("Upstream source review status: " + status)
            attribution += [
                "Upstream source license identifier (not archive text): " + str(evidence.get("license_id") or "UNIDENTIFIED"),
                "Upstream source repository: " + str(evidence.get("source_repository") or ""),
                "Upstream source ref: " + str(evidence.get("source_ref") or ""),
                "Upstream source commit: " + str(evidence.get("source_commit") or ""),
                "Upstream source revision assessment: " + str(evidence.get("source_revision_resolution") or ""),
                "Upstream artifact/source mapping assessment: " + str(evidence.get("source_mapping_status") or ""),
            ]
            for source_file in evidence.get("source_files") or []:
                attribution.append("Source file (Git blob SHA-1 " + source_file["git_blob_sha"] + "): " + source_file["url"])
            for supporting_file in evidence.get("supporting_evidence") or []:
                attribution.append("Supporting source/POM evidence (Git blob SHA-1 " + supporting_file["git_blob_sha"] + "): " + supporting_file["url"])
            for copyright_text in evidence.get("copyright_attributions") or []:
                attribution.append("Source-derived copyright attribution (not archive-extracted): " + copyright_text)
            attribution.append("Upstream NOTICE review status: " + str(evidence.get("notice_review_status") or "UNREVIEWED")
                               + "; paths checked: " + (", ".join(evidence.get("notice_paths_checked") or []) or "NONE"))
            for exclusion in evidence.get("notice_exclusions") or []:
                attribution.append("NOTICE scope exclusion recorded by source review: " + exclusion)
            attribution.append("Artifact signature status: UNVERIFIED")
            signature_evidence = evidence.get("signature_evidence")
            if signature_evidence:
                attribution.append("Historical signature evidence only (not current verification): "
                                   + signature_evidence["url"] + " — " + signature_evidence["summary"])
            for unresolved in evidence.get("unresolved") or []:
                attribution.append("Unresolved upstream review: " + unresolved)
        else:
            attribution += [
                "Upstream source review status: NOT_REVIEWED; no coordinate-specific authoritative source record is available.",
                "Upstream source mapping status: " + str((evidence or {}).get("source_mapping_status") or "No source mapping has been reviewed."),
                "Copyright attribution: NOT_REVIEWED; manual upstream review required.",
                "Upstream NOTICE review status: NOT_REVIEWED.",
                "Artifact signature status: UNVERIFIED",
            ]
            for unresolved in (evidence or {}).get("unresolved") or [
                    "No coordinate-specific authoritative source/copyright/NOTICE review has been completed.",
                    "No artifact signature verification evidence is recorded."]:
                attribution.append("Unresolved upstream review: " + unresolved)
        attribution.append("")
    return "\n".join(attribution).rstrip() + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sbom", required=True, type=Path)
    parser.add_argument("--upstream-evidence", required=True, type=Path)
    parser.add_argument("--json-report", required=True, type=Path)
    parser.add_argument("--markdown-report", required=True, type=Path)
    parser.add_argument("--attributions", required=True, type=Path)
    args = parser.parse_args()
    try:
        report = audit(args.sbom, args.upstream_evidence)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print("Maven metadata audit could not run: " + str(exc), file=sys.stderr)
        return 2
    for path in (args.json_report, args.markdown_report, args.attributions):
        path.parent.mkdir(parents=True, exist_ok=True)
    args.json_report.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    lines = [
        "# Upstream Maven POM license metadata audit", "",
        f"- SBOM components: **{report['component_count']}**",
        f"- POMs retrieved: **{report['pom_component_count']}**",
        f"- POM/SBOM license matches: **{report['license_match_count']}**",
        f"- POM/SBOM license mismatches or unresolved entries: **{report['license_mismatch_count']}**",
        f"- Coordinate-specific upstream source records (PARTIAL): **{report['upstream_source_review_count']}**",
        f"- Components without coordinate-specific source review: **{report['upstream_source_not_reviewed_count']}**",
        f"- Upstream source/POM/SBOM license mismatches: **{report['upstream_source_mismatch_count']}**", "",
        "| Component | SBOM license | POM license | License source | SCM source | Upstream source review | Match | POM SHA-256 |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for item in report["components"]:
        pom_names = " OR ".join(x["name"] or x["url"] for x in item["pom_licenses"]) or "UNDECLARED"
        sbom_names = " OR ".join(item["sbom_licenses"]) or "UNDECLARED"
        lines.append(
            f"| {item['coordinate']} | {sbom_names} | {pom_names} | {item['license_source']} | "
            f"{item['scm_source']} | {item['upstream_source_review_status']} | "
            f"{'YES' if item['license_match'] else 'NO'} | {item['pom_sha256']} |"
        )
    if report["failures"]:
        lines += ["", "## Failures", ""] + ["- " + failure for failure in report["failures"]]
    args.markdown_report.write_text("\n".join(lines) + "\n", encoding="utf-8")
    args.attributions.write_text(render_attributions(report), encoding="utf-8")
    print(f"Maven POM audit: {report['pom_component_count']}/{report['component_count']} POMs; "
          f"{report['license_match_count']} license matches; {report['license_mismatch_count']} mismatches.")
    print("Upstream source review: " + str(report["upstream_source_review_count"]) + " PARTIAL; "
          + str(report["upstream_source_not_reviewed_count"]) + " NOT_REVIEWED; "
          + str(report["upstream_source_mismatch_count"]) + " mismatches.")
    for failure in report["failures"]:
        print("AUDIT_FAILURE: " + failure, file=sys.stderr)
    return 1 if report["failures"] or report["pom_component_count"] != report["component_count"] else 0



if __name__ == "__main__":
    raise SystemExit(main())
