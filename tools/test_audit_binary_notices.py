import json, tempfile, unittest, zipfile, io
from pathlib import Path
from audit_binary_notices import audit

class BinaryNoticeAuditTest(unittest.TestCase):
    def fixture(self, root, outer_notice=True, nested_license=True):
        d = root / "artifacts/example/vendor/sample/1.2.3"
        d.mkdir(parents=True)
        nested = io.BytesIO()
        with zipfile.ZipFile(nested, "w") as z:
            if nested_license:
                z.writestr("META-INF/LICENSE", "Nested license text.")
        with zipfile.ZipFile(d / "sample-1.2.3.aar", "w") as z:
            z.writestr("classes.jar", nested.getvalue())
            if outer_notice: z.writestr("META-INF/NOTICE.txt", "Sample upstream notice.")
        sbom = root / "bom.json"
        sbom.write_text(json.dumps({"components":[{"group":"example.vendor","name":"sample","version":"1.2.3","licenses":[{"license":{"id":"Apache-2.0"}}]}]}), encoding="utf-8")
        return sbom, root / "artifacts"
    def test_outer_and_nested_notice_extracted(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); sbom, artifacts=self.fixture(root)
            result=audit(sbom, artifacts, root/"out/notices.txt")
            self.assertEqual(result["archive_notice_component_count"], 1)
            text=(root/"out/notices.txt").read_text()
            self.assertIn("Sample upstream notice.", text); self.assertIn("Nested license text.", text)
    def test_missing_archive_notice_is_not_fabricated(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); sbom, artifacts=self.fixture(root, outer_notice=False, nested_license=False)
            result=audit(sbom, artifacts, root/"out/notices.txt")
            self.assertEqual(result["components"][0]["evidence_level"], "SBOM_DECLARED_LICENSE_ONLY")
            notice_text=(root/"out/notices.txt").read_text()
            self.assertNotIn("Sample upstream notice.", notice_text)
            self.assertNotIn("Nested license text.", notice_text)
    def test_scans_nested_jar_larger_than_text_limit(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            d = root / "artifacts/example/vendor/large/1.2.3"
            d.mkdir(parents=True)
            nested = io.BytesIO()
            with zipfile.ZipFile(nested, "w", compression=zipfile.ZIP_STORED) as z:
                z.writestr("META-INF/LICENSE", "Large nested license text.")
                z.writestr("assets/padding.bin", bytes(range(256)) * 9000)
            self.assertGreater(len(nested.getvalue()), 2 * 1024 * 1024)
            with zipfile.ZipFile(d / "large-1.2.3.aar", "w", compression=zipfile.ZIP_STORED) as z:
                z.writestr("classes.jar", nested.getvalue())
            sbom = root / "bom.json"
            sbom.write_text(json.dumps({"components": [{
                "group": "example.vendor", "name": "large", "version": "1.2.3",
                "licenses": [{"license": {"id": "Apache-2.0"}}]
            }]}), encoding="utf-8")
            result = audit(sbom, root / "artifacts", root / "out/notices.txt")
            self.assertEqual(result["archive_notice_component_count"], 1)
            self.assertIn("Large nested license text.", (root / "out/notices.txt").read_text())

    def test_missing_binary_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); sbom, artifacts=self.fixture(root)
            (artifacts/"example/vendor/sample/1.2.3/sample-1.2.3.aar").unlink()
            self.assertTrue(audit(sbom, artifacts, root/"out/notices.txt")["failures"])
if __name__ == "__main__": unittest.main()
