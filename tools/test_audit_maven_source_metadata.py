import unittest
from audit_maven_source_metadata import assess_component, valid_scm_reference, audit


class MavenSourceMetadataTest(unittest.TestCase):
    def test_accepts_https_repository_url(self):
        self.assertTrue(valid_scm_reference("https://github.com/example/project", allow_scp=False))

    def test_accepts_scm_git_ssh_and_scp_forms(self):
        self.assertTrue(valid_scm_reference("scm:git:ssh://git@github.com/example/project.git"))
        self.assertTrue(valid_scm_reference("scm:git:git@github.com:example/project.git"))

    def test_rejects_malformed_and_non_repository_urls(self):
        self.assertFalse(valid_scm_reference("not a URL", allow_scp=False))
        self.assertFalse(valid_scm_reference("https:///missing-host", allow_scp=False))
        self.assertFalse(valid_scm_reference("file:///tmp/source", allow_scp=False))

    def test_missing_scm_is_not_mistaken_for_verified(self):
        result = assess_component({"coordinate": "g:a:1"})
        self.assertEqual(result["assessment"], "MISSING_REFERENCE")
        self.assertFalse(result["repository_existence_verified"])
        self.assertFalse(result["release_tag_verified"])

    def test_invalid_field_is_flagged_even_if_another_field_is_valid(self):
        result = assess_component({"coordinate": "g:a:1", "scm": {
            "url": "https://github.com/example/project",
            "connection": "bad value"
        }})
        self.assertEqual(result["assessment"], "INVALID_OR_MIXED_REFERENCE")
        self.assertEqual(result["invalid_fields"], ["connection"])

    def test_valid_pom_reference_remains_unverified(self):
        result = assess_component({"coordinate": "g:a:1", "pom_url": "https://repo.test/a.pom",
                                   "pom_sha256": "abc", "scm": {
                                       "url": "https://github.com/example/project"
                                   }})
        self.assertEqual(result["assessment"], "POM_REFERENCE_PRESENT_UNVERIFIED")
        self.assertFalse(result["repository_existence_verified"])
        self.assertFalse(result["publisher_identity_verified"])

    def test_audit_summary_never_claims_external_verification(self):
        result = audit({"components": [
            {"coordinate": "g:a:1", "scm": {"url": "https://github.com/a/b"}},
            {"coordinate": "g:b:1"},
        ]})
        self.assertEqual(result["component_count"], 2)
        self.assertEqual(result["repository_existence_verified_count"], 0)
        self.assertEqual(result["release_tag_verified_count"], 0)
        self.assertEqual(result["assessment_counts"]["MISSING_REFERENCE"], 1)



    def test_scm_json_report_redacts_userinfo_query_and_fragment(self):
        result = assess_component({"coordinate": "g:a:1", "scm": {
            "url": "https://alice:secret@example.test/source?token=secret#frag",
            "connection": "scm:git:ssh://git@github.com/example/project.git?access_token=secret#readme",
        }})
        self.assertEqual(result["scm"]["url"], "https://example.test/source")
        self.assertEqual(result["scm"]["connection"], "scm:git:ssh://github.com/example/project.git")
        serialized = str(result["scm"])
        for secret in ("alice", "secret", "token", "access_token", "git@"):
            self.assertNotIn(secret, serialized)

if __name__ == "__main__":
    unittest.main()
