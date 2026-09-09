import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET


spec = importlib.util.spec_from_file_location(
    "api_audit", Path(__file__).parents[1] / "audit-android-api.py")
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


class ApiAuditTest(unittest.TestCase):
    def run_report(self, xml, returncode):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = root / "build/reports/api-audit/30/report.xml"
            report.parent.mkdir(parents=True)
            report.write_text('<issues/>')

            def run(command, **kwargs):
                if "--lint-model" in command:
                    self.assertFalse(report.exists(), "stale report survived")
                    if xml is not None:
                        report.write_text(xml)
                    return audit.subprocess.CompletedProcess(command, returncode)
                return audit.subprocess.CompletedProcess(command, 0)

            with patch.object(audit, "ROOT", root), \
                    patch.object(audit, "prepare_model", return_value=root / "sdk"), \
                    patch.object(audit.subprocess, "run", side_effect=run), \
                    patch("sys.argv", ["audit", "--min-sdk", "30"]):
                return audit.main()

    def test_api_findings_are_a_completed_audit_not_tool_failure(self):
        self.assertEqual(0, self.run_report('<issues><issue id="NewApi"/></issues>', 1))

    def test_failed_analysis_cannot_reuse_old_report(self):
        self.assertNotEqual(0, self.run_report(None, 1))
        self.assertNotEqual(0, self.run_report(None, 0))

    def test_lint_internal_failure_is_not_a_compatibility_result(self):
        self.assertNotEqual(0, self.run_report('<issues><issue id="LintError"/></issues>', 1))
        self.assertNotEqual(0, self.run_report('<issues/>', 1))

    def test_lower_floor_only_changes_isolated_model_and_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, destination = root / "source", root / "audit"
            source.mkdir()
            app = root / "app"
            app.mkdir()
            manifest = app / "AndroidManifest.xml"
            manifest.write_text(
                '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
                '<uses-sdk android:minSdkVersion="35" android:targetSdkVersion="37"/>'
                '</manifest>')
            sdk = root / "sdk"
            ET.ElementTree(ET.Element("module", {
                "dir": str(app), "bootClassPath": os.pathsep.join([
                    str(sdk / "platforms/android-37.0/android.jar"), str(sdk / "lambda.jar")])
            })).write(source / "module.xml")
            ET.ElementTree(ET.Element("variant", {
                "minSdkVersion": "35", "targetSdkVersion": "37.0",
                "mergedManifest": "AndroidManifest.xml", "partialResultsDir": "production-results"
            })).write(source / "debug.xml")
            (source / "debug-dependencies.xml").write_text("<dependencies/>")
            originals = {path: path.read_bytes() for path in [manifest, *source.glob("*.xml")]}

            self.assertEqual(sdk, audit.prepare_model(source, destination, 30))
            for path, contents in originals.items():
                self.assertEqual(contents, path.read_bytes(), str(path))
            variant = ET.parse(destination / "debug.xml").getroot()
            self.assertEqual("30", variant.get("minSdkVersion"))
            self.assertEqual("37.0", variant.get("targetSdkVersion"))
            self.assertEqual(str(destination / "partial-results"), variant.get("partialResultsDir"))
            copied_manifest = ET.parse(variant.get("mergedManifest")).getroot()
            self.assertEqual("30", copied_manifest.find("uses-sdk").get(audit.ANDROID + "minSdkVersion"))
            self.assertEqual("37", copied_manifest.find("uses-sdk").get(audit.ANDROID + "targetSdkVersion"))
            self.assertEqual("<dependencies/>", (destination / "debug-dependencies.xml").read_text())


if __name__ == "__main__":
    unittest.main()
