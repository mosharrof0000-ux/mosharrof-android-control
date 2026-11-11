import unittest
from audit_maven_metadata import normalize_license, parse_pom, pom_urls

class MavenMetadataAuditTest(unittest.TestCase):
    def test_normalizes_apache_license_name_and_url(self):
        self.assertEqual(normalize_license("The Apache License, Version 2.0"), "Apache-2.0")
        self.assertEqual(normalize_license("http://www.apache.org/licenses/LICENSE-2.0.txt"), "Apache-2.0")

    def test_parses_license_scm_and_developer_metadata(self):
        xml = b"""<project><licenses><license><name>Apache License, Version 2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0</url></license></licenses><scm><url>https://example.test/source</url><connection>scm:git:test</connection></scm><developers><developer><name>Example Dev</name><organization>Example Org</organization></developer></developers></project>"""
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

if __name__ == "__main__":
    unittest.main()
