import unittest
from unittest.mock import patch
from audit_maven_metadata import normalize_license, parse_pom, pom_urls, resolve_license_metadata, sanitize_scm_reference, licenses_match, audit

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

    def test_inherits_license_from_exact_parent_pom(self):
        child = b"""<project xmlns="http://maven.apache.org/POM/4.0.0"><parent><groupId>com.google.guava</groupId><artifactId>guava-parent</artifactId><version>26.0-android</version></parent><artifactId>listenablefuture</artifactId></project>"""
        parent = b"""<project xmlns="http://maven.apache.org/POM/4.0.0"><licenses><license><name>Apache License, Version 2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0</url></license></licenses></project>"""
        with patch("audit_maven_metadata.fetch_pom", return_value=(parent, "https://repo.example/guava-parent.pom")):
            result = resolve_license_metadata(child, "https://repo.example/listenablefuture.pom")
        self.assertEqual(result["license_source"], "parent_pom")
        self.assertEqual(result["inherited_licenses"][0]["normalized"], "Apache-2.0")
        self.assertEqual(result["parent_pom_url"], "https://repo.example/guava-parent.pom")
        self.assertEqual(len(result["parent_pom_sha256"]), 64)

    def test_child_license_overrides_parent_and_does_not_fetch(self):
        child = b"""<project><licenses><license><name>MIT</name></license></licenses><parent><groupId>x</groupId><artifactId>p</artifactId><version>1</version></parent></project>"""
        with patch("audit_maven_metadata.fetch_pom") as fetch:
            result = resolve_license_metadata(child, "https://repo.example/child.pom")
        fetch.assert_not_called()
        self.assertEqual(result["license_source"], "pom")
        self.assertEqual(result["metadata"]["licenses"][0]["normalized"], "MIT")

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

if __name__ == "__main__":
    unittest.main()
