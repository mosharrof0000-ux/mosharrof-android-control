import unittest
from unittest.mock import patch
from audit_maven_metadata import normalize_license, parse_pom, pom_urls, resolve_license_metadata

class MavenMetadataAuditTest(unittest.TestCase):
    def test_normalizes_apache_license_name_and_url(self):
        self.assertEqual(normalize_license("The Apache License, Version 2.0"), "Apache-2.0")
        self.assertEqual(normalize_license("http://www.apache.org/licenses/LICENSE-2.0.txt"), "Apache-2.0")

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

if __name__ == "__main__":
    unittest.main()
