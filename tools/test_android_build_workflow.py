import unittest
from pathlib import Path


WORKFLOW = Path(__file__).resolve().parents[1] / ".github" / "workflows" / "android-build.yml"


class AndroidBuildWorkflowPathTest(unittest.TestCase):
    def test_osv_scan_lockfiles_match_uploaded_artifact_layout(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        expected = (
            "android/app/gradle.lockfile",
            "android/buildscript-gradle.lockfile",
            "android/gradle/verification-metadata.xml",
        )
        for path in expected:
            self.assertIn("            " + path + "\n", workflow)
            self.assertIn("        --lockfile=" + path + "\n", workflow)

        stale_paths = (
            "--lockfile=app/gradle.lockfile",
            "--lockfile=buildscript-gradle.lockfile",
            "--lockfile=gradle/verification-metadata.xml",
        )
        for path in stale_paths:
            self.assertNotIn(path, workflow)


if __name__ == "__main__":
    unittest.main()
