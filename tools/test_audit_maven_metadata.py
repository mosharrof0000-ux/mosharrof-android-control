import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from audit_maven_metadata import (
    normalize_license, parse_pom, pom_urls, resolve_license_metadata,
    sanitize_scm_reference, licenses_match, audit, render_attributions, sbom_licenses,
)

class MavenMetadataAuditTest(unittest.TestCase):
    def test_normalizes_apache_license_name_and_url(self):
        self.assertEqual(normalize_license("The Apache License, Version 2.0"), "Apache-2.0")
        self.assertEqual(normalize_license("http://www.apache.org/licenses/LICENSE-2.0.txt"), "Apache-2.0")

    def test_preserves_distinct_bsd_license_variants(self):
        self.assertEqual(normalize_license("BSD-2-Clause"), "BSD-2-Clause")
        self.assertEqual(normalize_license("BSD 2-Clause License"), "BSD-2-Clause")
        self.assertEqual(normalize_license("BSD-3-Clause"), "BSD-3-Clause")
        self.assertEqual(normalize_license("The 3-Clause BSD License"), "BSD-3-Clause")
        self.assertEqual(normalize_license("BSD-4-Clause"), "BSD-4-Clause")
        self.assertEqual(normalize_license("BSD"), "BSD")
        self.assertNotEqual(normalize_license("BSD-2-Clause"), normalize_license("BSD-3-Clause"))

    def test_sbom_and_pom_license_sets_must_match_exactly(self):
        from audit_maven_metadata import licenses_match
        self.assertTrue(licenses_match(["Apache-2.0", "MIT"], ["Apache-2.0", "MIT"]))
        self.assertFalse(licenses_match(["Apache-2.0"], ["Apache-2.0", "MIT"]))
        self.assertFalse(licenses_match(["Apache-2.0", "MIT"], ["Apache-2.0", "GPL-2.0"]))
        self.assertFalse(licenses_match(["Apache-2.0", "MIT", "GPL-2.0"], ["Apache-2.0", "MIT"]))
        self.assertFalse(licenses_match([], ["Apache-2.0"]))
        self.assertFalse(licenses_match(["Apache-2.0"], []))
        self.assertFalse(licenses_match([], []))

    def test_parses_license_scm_and_developer_metadata(self):
        xml = b"""<project xmlns="http://maven.apache.org/POM/4.0.0"><licenses><license><name>Apache License, Version 2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0</url></license></licenses><scm><url>https://example.test/source</url><connection>scm:git:test</connection></scm><developers><developer><name>Example Dev</name><organization>Example Org</organization></developer></developers></project>"""
        result = parse_pom(xml)
        self.assertEqual(result["licenses"][0]["normalized"], "Apache-2.0")
        self.assertEqual(result["scm"]["url"], "https://example.test/source")
        self.assertEqual(result["developers"][0]["organization"], "Example Org")

    def test_androidx_prefers_google_maven(self):
        urls = pom_urls("androidx.activity", "activity", "1.2.3")
        self.assertIn("https://dl.google.com/dl/android/maven2/", urls[0])
        self.assertIn("https://repo.maven.apache.org/maven2/", urls[1])

    def test_rejects_unsafe_coordinates(self):
        with self.assertRaises(ValueError):
            pom_urls("../bad", "thing", "1.0")

    def test_inherits_license_scm_developers_and_organization_from_parent_pom(self):
        child = b"""<project xmlns="http://maven.apache.org/POM/4.0.0"><parent><groupId>com.google.guava</groupId><artifactId>guava-parent</artifactId><version>26.0-android</version></parent><artifactId>listenablefuture</artifactId></project>"""
        parent = b"""<project xmlns="http://maven.apache.org/POM/4.0.0">
          <licenses><license><name>Apache License, Version 2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0</url></license></licenses>
          <scm><url>https://alice:secret@github.com/google/guava?token=secret#src</url><connection>scm:git:https://github.com/google/guava.git</connection></scm>
          <developers><developer><name>Guava Authors</name><organization>Google</organization></developer></developers>
          <organization><name>Google</name><url>https://github.com/google</url></organization>
        </project>"""
        parent_url = "https://repo.example/guava-parent.pom"
        with patch("audit_maven_metadata.fetch_pom", return_value=(parent, parent_url)):
            result = resolve_license_metadata(child, "https://repo.example/listenablefuture.pom")
        self.assertEqual(result["license_source"], "parent_pom")
        self.assertEqual(result["effective_licenses"][0]["normalized"], "Apache-2.0")
        self.assertEqual(result["parent_pom_url"], parent_url)
        self.assertEqual(len(result["parent_pom_sha256"]), 64)
        self.assertEqual(result["scm_source"], "parent_pom")
        self.assertEqual(result["effective_scm"]["url"], "https://github.com/google/guava")
        self.assertEqual(result["scm_parent_pom_url"], parent_url)
        self.assertEqual(result["scm_provenance"]["url"]["source"], "parent_pom")
        self.assertEqual(result["developers_source"], "parent_pom")
        self.assertEqual(result["effective_developers"][0]["name"], "Guava Authors")
        self.assertEqual(result["organization_source"], "parent_pom")
        self.assertEqual(result["effective_organization"]["name"], "Google")
        self.assertNotIn("secret", str(result))

    def test_complete_child_metadata_does_not_fetch_parent(self):
        child = b"""<project>
          <licenses><license><name>MIT</name></license></licenses>
          <scm><url>https://child.test/source</url><connection>scm:git:https://child.test/source.git</connection><developerConnection>scm:git:https://child.test/source.git</developerConnection></scm>
          <developers><developer><name>Child Dev</name></developer></developers>
          <organization><name>Child Org</name><url>https://child.test/org</url></organization>
          <parent><groupId>x</groupId><artifactId>p</artifactId><version>1</version></parent>
        </project>"""
        with patch("audit_maven_metadata.fetch_pom") as fetch:
            result = resolve_license_metadata(child, "https://repo.example/child.pom")
        fetch.assert_not_called()
        self.assertEqual(result["license_source"], "pom")
        self.assertEqual(result["effective_licenses"][0]["normalized"], "MIT")
        self.assertEqual(result["scm_source"], "pom")
        self.assertEqual(result["developers_source"], "pom")
        self.assertEqual(result["organization_source"], "pom")

    def test_child_license_without_scm_inherits_parent_source_metadata(self):
        child = b"""<project>
          <licenses><license><name>Apache License, Version 2.0</name></license></licenses>
          <parent><groupId>x</groupId><artifactId>parent</artifactId><version>1</version></parent>
        </project>"""
        parent = b"""<project>
          <scm><url>https://parent.test/project</url><connection>scm:git:https://parent.test/project.git</connection></scm>
          <developers><developer><name>Parent Dev</name></developer></developers>
        </project>"""
        parent_url = "https://repo.example/parent.pom"
        with patch("audit_maven_metadata.fetch_pom", return_value=(parent, parent_url)):
            result = resolve_license_metadata(child, "https://repo.example/child.pom")
        self.assertEqual(result["license_source"], "pom")
        self.assertEqual(result["license_pom_url"], "https://repo.example/child.pom")
        self.assertEqual(result["scm_source"], "parent_pom")
        self.assertEqual(result["effective_scm"]["url"], "https://parent.test/project")
        self.assertEqual(result["scm_parent_pom_url"], parent_url)
        self.assertEqual(result["developers_source"], "parent_pom")
        self.assertEqual(result["effective_developers"][0]["name"], "Parent Dev")

    def test_child_scm_fields_override_parent_and_record_mixed_provenance(self):
        child = b"""<project>
          <licenses><license><name>Apache License, Version 2.0</name></license></licenses>
          <scm><url>https://child.test/project</url></scm>
          <parent><groupId>x</groupId><artifactId>parent</artifactId><version>1</version></parent>
        </project>"""
        parent = b"""<project>
          <licenses><license><name>Apache License, Version 2.0</name></license></licenses>
          <scm><url>https://parent.test/project</url><connection>scm:git:https://parent.test/project.git</connection></scm>
        </project>"""
        with patch("audit_maven_metadata.fetch_pom", return_value=(parent, "https://repo.example/parent.pom")):
            result = resolve_license_metadata(child, "https://repo.example/child.pom")
        self.assertEqual(result["scm_source"], "mixed")
        self.assertEqual(result["effective_scm"]["url"], "https://child.test/project")
        self.assertEqual(result["effective_scm"]["connection"], "scm:git:https://parent.test/project.git")
        self.assertEqual(result["scm_provenance"]["url"]["source"], "pom")
        self.assertEqual(result["scm_provenance"]["connection"]["source"], "parent_pom")

    def test_complete_scm_still_inherits_parent_developer_and_organization_metadata(self):
        child = b"""<project>
          <licenses><license><name>Apache License, Version 2.0</name></license></licenses>
          <scm><url>https://child.test/project</url><connection>scm:git:https://child.test/project.git</connection><developerConnection>scm:git:https://child.test/project.git</developerConnection></scm>
          <parent><groupId>x</groupId><artifactId>parent</artifactId><version>1</version></parent>
        </project>"""
        parent = b"""<project>
          <developers><developer><name>Parent Dev</name></developer></developers>
          <organization><name>Parent Org</name><url>https://parent.test/org</url></organization>
        </project>"""
        with patch("audit_maven_metadata.fetch_pom", return_value=(parent, "https://repo.example/parent.pom")):
            result = resolve_license_metadata(child, "https://repo.example/child.pom")
        self.assertEqual(result["scm_source"], "pom")
        self.assertEqual(result["developers_source"], "parent_pom")
        self.assertEqual(result["effective_developers"][0]["name"], "Parent Dev")
        self.assertEqual(result["organization_source"], "parent_pom")
        self.assertEqual(result["effective_organization"]["name"], "Parent Org")

    def test_audit_uses_parent_inherited_scm_and_keeps_license_match_exact(self):
        child = b"""<project>
          <licenses><license><name>Apache License, Version 2.0</name></license></licenses>
          <parent><groupId>x</groupId><artifactId>parent</artifactId><version>1</version></parent>
        </project>"""
        parent = b"""<project>
          <scm><url>https://parent.test/project</url></scm>
          <developers><developer><name>Parent Dev</name></developer></developers>
        </project>"""
        sbom = {"components": [{"group": "x", "name": "child", "version": "1",
                                "licenses": [{"license": {"id": "Apache-2.0"}}]}]}
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "bom.json"
            path.write_text(json.dumps(sbom), encoding="utf-8")
            with patch("audit_maven_metadata.fetch_pom", side_effect=[
                (child, "https://repo.example/child.pom"),
                (parent, "https://repo.example/parent.pom"),
            ]):
                result = audit(path)
        item = result["components"][0]
        self.assertEqual(result["license_match_count"], 1)
        self.assertEqual(result["license_mismatch_count"], 0)
        self.assertEqual(item["scm_source"], "parent_pom")
        self.assertEqual(item["scm"]["url"], "https://parent.test/project")
        self.assertEqual(item["developers_source"], "parent_pom")

    def test_parent_without_license_fails_closed(self):
        child = b"""<project><parent><groupId>x</groupId><artifactId>p</artifactId><version>1</version></parent></project>"""
        parent = b"<project><artifactId>p</artifactId></project>"
        with patch("audit_maven_metadata.fetch_pom", return_value=(parent, "https://repo.example/p.pom")):
            result = resolve_license_metadata(child, "https://repo.example/child.pom")
        self.assertEqual(result["license_source"], "missing")

    def test_parent_cycle_fails_closed(self):
        child = b"""<project><parent><groupId>x</groupId><artifactId>p</artifactId><version>1</version></parent></project>"""
        parent = b"""<project><parent><groupId>x</groupId><artifactId>p</artifactId><version>1</version></parent></project>"""
        with patch("audit_maven_metadata.fetch_pom", return_value=(parent, "https://repo.example/p.pom")):
            with self.assertRaisesRegex(ValueError, "cycle"):
                resolve_license_metadata(child, "https://repo.example/child.pom")


    def test_sanitizes_scm_userinfo_query_fragment_and_scp_user(self):
        xml = b"""<project><scm><url>https://alice:secret@example.test/source?token=secret#frag</url><connection>scm:git:ssh://git@github.com/example/project.git?access_token=secret#readme</connection><developerConnection>scm:git:git@github.com:example/project.git</developerConnection></scm></project>"""
        scm = parse_pom(xml)["scm"]
        self.assertEqual(scm["url"], "https://example.test/source")
        self.assertEqual(scm["connection"], "scm:git:ssh://github.com/example/project.git")
        self.assertEqual(scm["developerConnection"], "scm:git:github.com:example/project.git")
        serialized = str(scm)
        for secret in ("alice", "secret", "token", "access_token", "git@"):
            self.assertNotIn(secret, serialized)

    def test_unparseable_scm_is_redacted(self):
        self.assertEqual(sanitize_scm_reference("bad value?token=secret"), "[unparseable SCM reference]")

    def test_audit_reports_exact_license_mismatch_count(self):
        pom = b"""<project><licenses><license><name>Apache License, Version 2.0</name></license></licenses></project>"""
        sbom = {"components": [{"group": "example", "name": "sample", "version": "1.0",
                                "licenses": [{"license": {"id": "Apache-2.0"}},
                                             {"license": {"id": "MIT"}}]}]}
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "bom.json"
            path.write_text(json.dumps(sbom), encoding="utf-8")
            with patch("audit_maven_metadata.fetch_pom", return_value=(pom, "https://repo.example/sample.pom")):
                result = audit(path)
        self.assertEqual(result["pom_component_count"], 1)
        self.assertEqual(result["license_match_count"], 0)
        self.assertEqual(result["license_mismatch_count"], 1)
        self.assertEqual(result["license_mismatch_components"], ["example:sample:1.0"])


    def test_sanitizes_all_pom_source_urls_before_report(self):
        xml = b"""<project>
          <licenses><license><name>Apache License, Version 2.0</name><url>https://alice:secret@example.test/license?token=secret#terms</url></license></licenses>
          <organization><name>Example</name><url>https://bob:secret@example.test/org?key=secret</url></organization>
          <developers><developer><organizationUrl>https://dev:secret@example.test/team?token=secret</organizationUrl></developer></developers>
        </project>"""
        result = parse_pom(xml)
        self.assertEqual(result["licenses"][0]["url"], "https://example.test/license")
        self.assertEqual(result["organization"]["url"], "https://example.test/org")
        self.assertEqual(result["developers"][0]["organizationUrl"], "https://example.test/team")
        serialized = str(result)
        for secret in ("alice", "bob", "dev:", "secret", "token", "key="):
            self.assertNotIn(secret, serialized)



    def _partial_upstream_record(self, coordinate="example:sample:1.0", license_id="Apache-2.0"):
        repository = "https://github.com/example/sample"
        commit = "a" * 40
        path = "src/main/java/Sample.java"
        return {
            "coordinate": coordinate,
            "review_status": "PARTIAL",
            "license_id": license_id,
            "source_repository": repository,
            "source_ref": "v1.0",
            "source_commit": commit,
            "source_revision_resolution": "Candidate tag resolves to the recorded source commit.",
            "source_mapping_status": "Source mapping is partial; no published artifact build attestation is present.",
            "source_files": [{
                "path": path,
                "git_blob_sha": "b" * 40,
                "url": repository + "/blob/" + commit + "/" + path,
            }],
            "supporting_evidence": [],
            "copyright_attributions": ["Copyright 2024 Example Authors"],
            "notice_review_status": "NOT_FOUND_AT_REVIEWED_PATHS",
            "notice_paths_checked": ["NOTICE"],
            "notice_exclusions": [],
            "artifact_signature_status": "UNVERIFIED",
            "signature_evidence": None,
            "unresolved": [
                "The published artifact has not been attested against this source commit.",
                "The published artifact signature has not been independently verified.",
            ],
        }

    def _not_reviewed_record(self, coordinate="example:sample:1.0"):
        return {
            "coordinate": coordinate,
            "review_status": "NOT_REVIEWED",
            "license_id": None,
            "source_repository": None,
            "source_ref": None,
            "source_commit": None,
            "source_revision_resolution": None,
            "source_mapping_status": "No coordinate-specific source review has been completed.",
            "source_files": [],
            "supporting_evidence": [],
            "copyright_attributions": [],
            "notice_review_status": "NOT_REVIEWED",
            "notice_paths_checked": [],
            "notice_exclusions": [],
            "artifact_signature_status": "UNVERIFIED",
            "signature_evidence": None,
            "unresolved": [
                "Coordinate-specific source, copyright and NOTICE review remains pending.",
                "No artifact signature verification evidence is recorded.",
            ],
        }

    def _write_manifest_fixture(self, directory, record):
        manifest_path = Path(directory) / "upstream-evidence.json"
        manifest_path.write_text(json.dumps({
            "schema_version": 2,
            "components": [record],
        }), encoding="utf-8")
        return manifest_path

    def test_upstream_manifest_must_cover_exact_sbom_coordinates(self):
        from audit_maven_metadata import load_upstream_evidence
        with tempfile.TemporaryDirectory() as tmp:
            path = self._write_manifest_fixture(tmp, self._partial_upstream_record())
            components = [
                {"group": "example", "name": "sample", "version": "1.0",
                 "licenses": [{"license": {"id": "Apache-2.0"}}]},
                {"group": "example", "name": "other", "version": "1.0",
                 "licenses": [{"license": {"id": "Apache-2.0"}}]},
            ]
            with self.assertRaisesRegex(ValueError, "missing=.*example:other:1.0"):
                load_upstream_evidence(path, components)

    def test_upstream_manifest_rejects_duplicate_and_unknown_coordinates(self):
        from audit_maven_metadata import load_upstream_evidence
        component = {"group": "example", "name": "sample", "version": "1.0",
                     "licenses": [{"license": {"id": "Apache-2.0"}}]}
        with tempfile.TemporaryDirectory() as tmp:
            record = self._partial_upstream_record()
            path = Path(tmp) / "upstream.json"
            path.write_text(json.dumps({"schema_version": 2, "components": [record, record]}), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Duplicate upstream attribution coordinate"):
                load_upstream_evidence(path, [component])
            unknown = self._partial_upstream_record(coordinate="example:unknown:1.0")
            path.write_text(json.dumps({"schema_version": 2, "components": [unknown]}), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Unknown upstream attribution coordinate"):
                load_upstream_evidence(path, [component])

    def test_upstream_manifest_rejects_unpinned_source_url_and_bad_blob(self):
        from audit_maven_metadata import load_upstream_evidence
        component = {"group": "example", "name": "sample", "version": "1.0",
                     "licenses": [{"license": {"id": "Apache-2.0"}}]}
        with tempfile.TemporaryDirectory() as tmp:
            record = self._partial_upstream_record()
            record["source_files"][0]["url"] = "https://github.com/example/sample/blob/main/src/main/java/Sample.java"
            path = self._write_manifest_fixture(tmp, record)
            with self.assertRaisesRegex(ValueError, "not pinned"):
                load_upstream_evidence(path, [component])
            record = self._partial_upstream_record()
            record["source_files"][0]["git_blob_sha"] = "bad"
            path = self._write_manifest_fixture(tmp, record)
            with self.assertRaisesRegex(ValueError, "Git blob SHA"):
                load_upstream_evidence(path, [component])

    def test_upstream_manifest_license_must_match_sbom_exactly(self):
        from audit_maven_metadata import load_upstream_evidence
        with tempfile.TemporaryDirectory() as tmp:
            path = self._write_manifest_fixture(tmp, self._partial_upstream_record(license_id="MIT"))
            component = {"group": "example", "name": "sample", "version": "1.0",
                         "licenses": [{"license": {"id": "Apache-2.0"}}]}
            with self.assertRaisesRegex(ValueError, "does not exactly match the SBOM"):
                load_upstream_evidence(path, [component])

    def test_upstream_manifest_cannot_claim_artifact_signature_verified(self):
        from audit_maven_metadata import load_upstream_evidence
        with tempfile.TemporaryDirectory() as tmp:
            record = self._partial_upstream_record()
            record["artifact_signature_status"] = "VERIFIED"
            path = self._write_manifest_fixture(tmp, record)
            component = {"group": "example", "name": "sample", "version": "1.0",
                         "licenses": [{"license": {"id": "Apache-2.0"}}]}
            with self.assertRaisesRegex(ValueError, "must remain UNVERIFIED"):
                load_upstream_evidence(path, [component])

    def test_upstream_manifest_accepts_gitiles_and_cross_repository_evidence(self):
        from audit_maven_metadata import load_upstream_evidence
        repository = "https://android.googlesource.com/platform/frameworks/support"
        commit = "a" * 40
        record = self._partial_upstream_record()
        record.update({
            "source_repository": repository,
            "source_commit": commit,
            "source_files": [{
                "path": "module/Source.java",
                "git_blob_sha": "b" * 40,
                "url": repository + "/+/" + commit + "/module/Source.java",
            }],
            "supporting_evidence": [{
                "repository": "https://android.googlesource.com/platform/prebuilts/tools",
                "commit": "c" * 40,
                "path": "maven/sample.pom",
                "git_blob_sha": "d" * 40,
                "url": "https://android.googlesource.com/platform/prebuilts/tools/+/"+("c"*40)+"/maven/sample.pom",
                "description": "Exact-coordinate mirror POM.",
            }],
        })
        with tempfile.TemporaryDirectory() as tmp:
            path = self._write_manifest_fixture(tmp, record)
            component = {"group": "example", "name": "sample", "version": "1.0",
                         "licenses": [{"license": {"id": "Apache-2.0"}}]}
            loaded = load_upstream_evidence(path, [component])
        self.assertEqual(loaded["example:sample:1.0"]["source_repository"], repository)

    def test_audit_and_attribution_keep_partial_and_unreviewed_distinct(self):
        sbom = {"components": [
            {"group": "example", "name": "sample", "version": "1.0",
             "licenses": [{"license": {"id": "Apache-2.0"}}]},
            {"group": "example", "name": "pending", "version": "2.0",
             "licenses": [{"license": {"id": "Apache-2.0"}}]},
        ]}
        pom = b"<project><licenses><license><name>Apache License, Version 2.0</name></license></licenses></project>"
        partial = self._partial_upstream_record()
        pending = self._not_reviewed_record("example:pending:2.0")
        with tempfile.TemporaryDirectory() as tmp:
            sbom_path = Path(tmp) / "bom.json"
            manifest_path = Path(tmp) / "upstream-evidence.json"
            sbom_path.write_text(json.dumps(sbom), encoding="utf-8")
            manifest_path.write_text(json.dumps({
                "schema_version": 2,
                "components": [partial, pending],
            }), encoding="utf-8")
            with patch("audit_maven_metadata.fetch_pom",
                       return_value=(pom, "https://repo.example/sample.pom")):
                report = audit(sbom_path, manifest_path)
        self.assertEqual(report["upstream_source_review_count"], 1)
        self.assertEqual(report["upstream_source_not_reviewed_count"], 1)
        self.assertEqual(report["upstream_source_mismatch_count"], 0)
        partial_result = next(x for x in report["components"] if x["coordinate"] == "example:sample:1.0")
        pending_result = next(x for x in report["components"] if x["coordinate"] == "example:pending:2.0")
        self.assertEqual(partial_result["upstream_source_review_status"], "PARTIAL")
        self.assertEqual(pending_result["upstream_source_review_status"], "NOT_REVIEWED")
        attribution = render_attributions(report)
        self.assertIn("Source-derived copyright attribution (not archive-extracted): Copyright 2024 Example Authors", attribution)
        self.assertIn("Artifact signature status: UNVERIFIED", attribution)
        self.assertIn("Upstream source review status: NOT_REVIEWED; no coordinate-specific authoritative source record is available.", attribution)
        self.assertIn("Upstream source mapping status: No coordinate-specific source review has been completed.", attribution)
        self.assertIn("Unresolved upstream review: Coordinate-specific source, copyright and NOTICE review remains pending.", attribution)

    def test_source_license_mismatch_with_pom_fails_closed(self):
        sbom = {"components": [
            {"group": "example", "name": "sample", "version": "1.0",
             "licenses": [{"license": {"id": "Apache-2.0"}}]},
        ]}
        pom = b"<project><licenses><license><name>MIT</name></license></licenses></project>"
        record = self._partial_upstream_record()
        with tempfile.TemporaryDirectory() as tmp:
            sbom_path = Path(tmp) / "bom.json"
            manifest_path = Path(tmp) / "upstream-evidence.json"
            sbom_path.write_text(json.dumps(sbom), encoding="utf-8")
            manifest_path.write_text(json.dumps({"schema_version": 2, "components": [record]}), encoding="utf-8")
            with patch("audit_maven_metadata.fetch_pom",
                       return_value=(pom, "https://repo.example/sample.pom")):
                report = audit(sbom_path, manifest_path)
        self.assertEqual(report["upstream_source_mismatch_count"], 1)
        self.assertEqual(report["components"][0]["upstream_source_review_status"], "INVALID_LICENSE_MISMATCH")
        self.assertTrue(any("upstream source evidence license" in failure for failure in report["failures"]))

    def test_sbom_spdx_expression_is_preserved_as_a_license_declaration(self):
        self.assertEqual(
            sbom_licenses({"licenses": [{"expression": "Apache-2.0"}]}),
            ["Apache-2.0"],
        )



    def test_compound_spdx_expression_is_not_collapsed_to_one_license(self):
        self.assertEqual(
            normalize_license("Apache-2.0 OR MIT"),
            "SPDX-EXPRESSION:Apache-2.0 OR MIT",
        )
        self.assertEqual(
            sbom_licenses({"licenses": [{"expression": "Apache-2.0 OR MIT"}]}),
            ["SPDX-EXPRESSION:Apache-2.0 OR MIT"],
        )


if __name__ == "__main__":
    unittest.main()
