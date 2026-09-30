#!/usr/bin/env python3
"""Run stock headless Chromium with its sandbox enabled; do not certify isolation."""
import argparse
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import quote
import uuid


class Result(HTMLParser):
    def __init__(self):
        super().__init__()
        self.capture = False
        self.values = []

    def handle_starttag(self, tag, attrs):
        self.capture = tag == "h1" and dict(attrs).get("id") == "result"

    def handle_endtag(self, tag):
        self.capture = False

    def handle_data(self, data):
        if self.capture:
            self.values.append(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", type=Path, required=True)
    parser.add_argument("--store", required=True)
    args = parser.parse_args()
    report = {"completed": False, "sandboxSupportEstablished": False, "layerOneEstablished": False, "runs": []}
    try:
        for index in range(3):
            token = uuid.uuid4().hex
            expected = "MD-JS-" + str(sum(range(50000 + index)))
            page = ('<body style="background:white;color:black;font:28px sans-serif">'
                    '<h1 id="result">NOT EXECUTED</h1><script>'
                    'let total=0;for(let n=0;n<' + str(50000 + index) + ';n++)total+=n;'
                    'document.getElementById("result").textContent="MD-JS-"+total;'
                    '</script></body>')
            artifact = "/tmp/md-chrome-" + token + ".png"
            program = ["/usr/bin/env", "CHROME_DEVEL_SANDBOX=/tmp/md-chrome-helper-ordinary",
                       "/usr/lib/chromium/chromium", "--headless", "--no-first-run", "--enable-logging=stderr",
                       "--user-data-dir=/tmp/md-chrome-profile-" + token, "--window-size=800,600", "--dump-dom"]
            if index == 2:
                program.append("--screenshot=" + artifact)
            program.append("data:text/html," + quote(page))
            command = [sys.executable, str(Path(__file__).with_name("run.py")),
                       "--build", str(args.build), "--store", args.store,
                       "--admit-elf", "/tmp/md-chrome-helper-ordinary",
                       "--deadline-seconds", "60", "--quiet", "--expect-output", ">" + expected + "</h1>"]
            if index == 2:
                command += ["--expect-artifact", artifact]
            command += ["--", *program]
            # EVENT_WAIT: owned browser completion and cleanup; expiry fails.
            result = subprocess.run(command, text=True, capture_output=True)
            print(result.stdout, end="", flush=True)
            print(result.stderr, end="", file=sys.stderr, flush=True)
            paths = re.findall(r"^Report: (.+)$", result.stdout, re.MULTILINE)
            assert len(paths) == 1, "missing exact run report"
            run = json.loads(Path(paths[0]).read_text())
            entry = {"report": paths[0], "verified": False, "sandboxFlagsDisabled": False}
            report["runs"].append(entry)
            assert result.returncode == run["exitCode"] == 0 and run["workloadCompleted"] and run["consoleClosed"]
            output = next(item["output"] for item in run["commands"] if "--diagnostics " in item["command"])
            html = re.search(r"^<html>.*</html>$", output, re.MULTILINE)
            assert html, "no rendered DOM"
            rendered = Result()
            rendered.feed(html[0])
            assert rendered.values == [expected], rendered.values
            assert "PROBE FAIL" not in output and "fstatFailures=0" in output
            assert re.search(r"PROBE proc-root domain=1 changes=[1-9]\d*", output)
            assert re.search(r"PROBE identity admittedImages=[1-9]\d* changes=[1-9]\d* hostUid=2000", output)
            assert "PROBE installed-filter" in output
            entry.update(verified=True, rendered=expected,
                         filtersInstalled=len(re.findall(r"PROBE installed-filter", output)),
                         childSignalExits=re.findall(r"PROBE exit pid=\d+ status=1(?:3[1-9]|[4-9]\d).*", output),
                         diagnostics=[line for line in output.splitlines() if "FATAL:" in line or "ERROR:" in line])
            print("PASS stock Chromium JS", index, expected, flush=True)
        report["completed"] = True
    finally:
        path = args.build / ("browser-stage-results-" + uuid.uuid4().hex + ".json")
        path.write_text(json.dumps(report, indent=2) + "\n")
        print("Stage report:", path, flush=True)


if __name__ == "__main__":
    main()
