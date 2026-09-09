#!/usr/bin/env python3
"""Audit a prospective API floor without changing Gradle or the installable APK."""

import argparse
from collections import Counter
from pathlib import Path
import os
import shutil
import subprocess
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"


def prepare_model(source: Path, destination: Path, minimum: int) -> Path:
    destination.mkdir(parents=True, exist_ok=True)
    for model in source.glob("*.xml"):
        shutil.copy2(model, destination / model.name)
    module = ET.parse(destination / "module.xml").getroot()
    module_dir = Path(module.attrib["dir"])
    variant_path = destination / "debug.xml"
    variant = ET.parse(variant_path)
    variant.getroot().set("minSdkVersion", str(minimum))
    # Keep partial results separate from the normal production-baseline lint run.
    variant.getroot().set("partialResultsDir", str(destination / "partial-results"))
    manifest = ET.parse(module_dir / variant.getroot().attrib["mergedManifest"])
    manifest.getroot().find("uses-sdk").set(ANDROID + "minSdkVersion", str(minimum))
    manifest_path = destination / "AndroidManifest.xml"
    manifest.write(manifest_path, encoding="utf-8", xml_declaration=True)
    variant.getroot().set("mergedManifest", str(manifest_path))
    variant.write(variant_path, encoding="utf-8", xml_declaration=True)
    # The generated model records the exact SDK used by the normal build.
    android_jar = Path(module.attrib["bootClassPath"].split(os.pathsep)[0])
    return android_jar.parents[2]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--min-sdk", type=int, required=True)
    args = parser.parse_args()
    if not 23 <= args.min_sdk < 35:
        parser.error("choose an audit SDK from 23 through 34; production Desktop remains API 35+")
    gradle = "gradlew.bat" if os.name == "nt" else "./gradlew"
    subprocess.run([gradle, ":app:compileDebugJavaWithJavac",
                    ":app:generateDebugLintReportModel", ":hidden-api-stubs:generateDebugLintModel"],
                   cwd=ROOT, check=True)
    output = ROOT / "build" / "reports" / "api-audit" / str(args.min_sdk)
    model = output / "model"
    sdk = prepare_model(ROOT / "app/build/intermediates/lint_report_lint_model/debug/"
                        "generateDebugLintReportModel", model, args.min_sdk)
    stubs = ROOT / "hidden-api-stubs/build/intermediates/lint_model/debug/generateDebugLintModel"
    executable = sdk / "cmdline-tools/latest/bin" / ("lint.bat" if os.name == "nt" else "lint")
    report = output / "report.xml"
    report.unlink(missing_ok=True)
    result = subprocess.run([str(executable), "--lint-model", os.pathsep.join([str(model), str(stubs)]),
                             "--variant", "debug", "--check", "NewApi,InlinedApi",
                             "--sdk-home", str(sdk), "--offline", "--quiet", "--xml", str(report)],
                            cwd=ROOT)
    if result.returncode not in (0, 1) or not report.exists():
        return result.returncode or 1
    issues = ET.parse(report).getroot().findall("issue")
    if (result.returncode == 1 and not issues) or any(
            issue.get("id") not in ("NewApi", "InlinedApi") for issue in issues):
        print(f"Lint could not complete the API audit; inspect {report}")
        return 1
    print(f"API {args.min_sdk} audit: {len(issues)} findings. APK minSdk is unchanged.")
    counts = Counter(issue.get("id") for issue in issues)
    print(f"NewApi: {counts['NewApi']}; InlinedApi: {counts['InlinedApi']}")
    for name, count in Counter(Path(issue.find("location").attrib["file"]).name
                               for issue in issues if issue.find("location") is not None).most_common(25):
        print(f"{count:4}  {name}")
    print(f"Full report: {report}")
    print("Static findings include Desktop code; absence of findings is not device verification.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
