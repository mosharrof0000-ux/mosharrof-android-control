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
            self.assertIn("\n## example.vendor:sample:1.2.3\n", text)
    def test_missing_archive_notice_is_not_fabricated(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); sbom, artifacts=self.fixture(root, outer_notice=False, nested_license=False)
            result=audit(sbom, artifacts, root/"out/notices.txt")
            self.assertEqual(result["components"][0]["evidence_level"], "SBOM_DECLARED_LICENSE_ONLY")
            self.assertEqual(result["manual_review_component_count"], 1)
            self.assertEqual(result["manual_review_components"], ["example.vendor:sample:1.2.3"])
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

    def test_duplicate_notice_bytes_are_preserved_for_each_component(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            components = []
            for name in ("first", "second"):
                d = root / "artifacts/example/vendor" / name / "1.0"
                d.mkdir(parents=True)
                with zipfile.ZipFile(d / f"{name}-1.0.aar", "w") as z:
                    z.writestr("META-INF/NOTICE", "Shared upstream notice.\\n")
                components.append({"group": "example.vendor", "name": name, "version": "1.0",
                                   "licenses": [{"license": {"id": "Apache-2.0"}}]})
            sbom = root / "bom.json"
            sbom.write_text(json.dumps({"components": components}), encoding="utf-8")
            audit(sbom, root / "artifacts", root / "out/notices.txt")
            report = (root / "out/notices.txt").read_text(encoding="utf-8")
            self.assertEqual(report.count("Shared upstream notice."), 2)
            self.assertIn("## example.vendor:first:1.0", report)
            self.assertIn("## example.vendor:second:1.0", report)

    def test_scans_nested_aar_archive(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            d = root / "artifacts/example/vendor/outer/1.0"
            d.mkdir(parents=True)
            nested = io.BytesIO()
            with zipfile.ZipFile(nested, "w") as z:
                z.writestr("META-INF/COPYRIGHT", "Nested AAR copyright text.")
            with zipfile.ZipFile(d / "outer-1.0.aar", "w") as z:
                z.writestr("libs/inner.aar", nested.getvalue())
            sbom = root / "bom.json"
            sbom.write_text(json.dumps({"components": [{"group": "example.vendor", "name": "outer",
                "version": "1.0", "licenses": [{"license": {"id": "Apache-2.0"}}]}]}), encoding="utf-8")
            result = audit(sbom, root / "artifacts", root / "out/notices.txt")
            self.assertEqual(result["archive_notice_component_count"], 1)
            self.assertIn("Nested AAR copyright text.", (root / "out/notices.txt").read_text())

    def test_corrupt_nested_archive_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            d = root / "artifacts/example/vendor/broken/1.0"
            d.mkdir(parents=True)
            with zipfile.ZipFile(d / "broken-1.0.aar", "w") as z:
                z.writestr("classes.jar", b"not a valid zip archive")
            sbom = root / "bom.json"
            sbom.write_text(json.dumps({"components": [{"group": "example.vendor", "name": "broken",
                "version": "1.0", "licenses": [{"license": {"id": "Apache-2.0"}}]}]}), encoding="utf-8")
            result = audit(sbom, root / "artifacts", root / "out/notices.txt")
            self.assertTrue(any("archive could not be inspected" in item for item in result["failures"]))

    def test_oversized_notice_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            d = root / "artifacts/example/vendor/large-notice/1.0"
            d.mkdir(parents=True)
            with zipfile.ZipFile(d / "large-notice-1.0.aar", "w", compression=zipfile.ZIP_STORED) as z:
                z.writestr("META-INF/LICENSE", "L" * (2 * 1024 * 1024 + 1))
            sbom = root / "bom.json"
            sbom.write_text(json.dumps({"components": [{"group": "example.vendor", "name": "large-notice",
                "version": "1.0", "licenses": [{"license": {"id": "Apache-2.0"}}]}]}), encoding="utf-8")
            result = audit(sbom, root / "artifacts", root / "out/notices.txt")
            self.assertTrue(any("notice text exceeds 2 MiB" in item for item in result["failures"]))

    def test_invalid_utf8_notice_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            d = root / "artifacts/example/vendor/invalid-text/1.0"
            d.mkdir(parents=True)
            with zipfile.ZipFile(d / "invalid-text-1.0.aar", "w") as z:
                z.writestr("META-INF/LICENSE", b"\xff\xfe\x00\x81")
            sbom = root / "bom.json"
            sbom.write_text(json.dumps({"components": [{"group": "example.vendor", "name": "invalid-text",
                "version": "1.0", "licenses": [{"license": {"id": "Apache-2.0"}}]}]}), encoding="utf-8")
            result = audit(sbom, root / "artifacts", root / "out/notices.txt")
            self.assertTrue(any("not valid bounded UTF-8 text" in item for item in result["failures"]))


    def test_preserves_exact_notice_whitespace(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            d = root / "artifacts/example/vendor/whitespace/1.0"
            d.mkdir(parents=True)
            exact_text = "  Exact upstream text.\n\n"
            with zipfile.ZipFile(d / "whitespace-1.0.aar", "w") as z:
                z.writestr("META-INF/LICENSE", exact_text)
            sbom = root / "bom.json"
            sbom.write_text(json.dumps({"components": [{"group": "example.vendor", "name": "whitespace",
                "version": "1.0", "licenses": [{"license": {"id": "Apache-2.0"}}]}]}), encoding="utf-8")
            audit(sbom, root / "artifacts", root / "out/notices.txt")
            report = (root / "out/notices.txt").read_text(encoding="utf-8")
            self.assertIn("Source: example.vendor:whitespace:1.0/whitespace-1.0.aar!/META-INF/LICENSE\n\n  Exact upstream text.\n\n", report)

    def test_embedded_nul_after_text_prefix_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            d = root / "artifacts/example/vendor/embedded-nul/1.0"
            d.mkdir(parents=True)
            with zipfile.ZipFile(d / "embedded-nul-1.0.aar", "w") as z:
                z.writestr("META-INF/LICENSE", b"A" * 4096 + b"\x00" + b"B")
            sbom = root / "bom.json"
            sbom.write_text(json.dumps({"components": [{"group": "example.vendor", "name": "embedded-nul",
                "version": "1.0", "licenses": [{"license": {"id": "Apache-2.0"}}]}]}), encoding="utf-8")
            result = audit(sbom, root / "artifacts", root / "out/notices.txt")
            self.assertTrue(any("not valid bounded UTF-8 text" in item for item in result["failures"]))

if __name__ == "__main__": unittest.main()
