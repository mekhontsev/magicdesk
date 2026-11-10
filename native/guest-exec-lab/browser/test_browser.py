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

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fixture_executor import FixtureExecutor
from test_oci_services import add_machine_entry_argument


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
    parser.add_argument("--installed", action="store_true")
    FixtureExecutor.add_argument(parser)
    add_machine_entry_argument(parser)
    parser.add_argument('--software', action='store_true', help='CPU rasterization without Turnip or a render node')
    parser.add_argument('--user', default='2000:2000', help='Non-root guest account, independent of executor UID')
    parser.add_argument("--setuid-route", action="store_true",
                        help="Select Chromium's set-ID sandbox instead of its user/PID namespace sandbox; seccomp remains enabled")
    parser.add_argument("--helper", default="/usr/lib/chromium/chrome-sandbox",
                        help="Guest path of the helper actually selected by Chromium")
    args = parser.parse_args()
    args.build.mkdir(parents=True, exist_ok=True)
    if not args.helper.startswith("/"):
        parser.error("helper must be an absolute guest path")
    report = {"completed": False, "sandboxSupportEstablished": False, "layerOneEstablished": False, "runs": []}
    try:
        for index in range(4):
            rejected = index == 3 and args.setuid_route
            token = uuid.uuid4().hex
            expected = "MD-JS-" + str(sum(range(50000 + index)))
            page = ('<body style="background:white;color:black;font:28px sans-serif">'
                    '<h1 id="result">NOT EXECUTED</h1><script>'
                    'let total=0;for(let n=0;n<' + str(50000 + index) + ';n++)total+=n;'
                    'document.getElementById("result").textContent="MD-JS-"+total;'
                    '</script></body>')
            artifact = "/tmp/md-chrome-" + token + ".png"
            program = ["/usr/bin/env", "CHROME_DEVEL_SANDBOX=" + args.helper,
                       "/usr/lib/chromium/chromium", "--headless", "--no-first-run", "--enable-logging=stderr",
                       "--user-data-dir=/tmp/md-chrome-profile-" + token, "--window-size=800,600", "--dump-dom"]
            if args.setuid_route:
                program.append("--disable-namespace-sandbox")
            if args.software:
                program.append('--disable-gpu')
            if index == 2:
                program.append("--screenshot=" + artifact)
            program.append("data:text/html," + quote(page))
            if rejected:
                program = ["/usr/bin/env", "setpriv", "--no-new-privs", "--", *program]
            command = [sys.executable, str(Path(__file__).with_name("run.py")),
                       "--build", str(args.build), "--store", args.store,
                       '--executor', args.executor,
                       '--cleanup-path', '/tmp/md-chrome-profile-' + token,
                       "--user", args.user, "--deadline-seconds", "60", "--quiet",
                       "--expect-output", "The setuid sandbox is not running as root."
                       if rejected else ">" + expected + "</h1>"]
            if index == 2:
                command += ["--expect-artifact", artifact, '--cleanup-path', artifact]
            if args.installed:
                command += ["--installed"]
            if args.machine_entry is not None:
                command += ['--machine-entry' if args.machine_entry else '--base']
            command += ["--", *program]
            # EVENT_WAIT: owned browser completion and cleanup; expiry fails.
            result = subprocess.run(command, text=True, capture_output=True)
            print(result.stdout, end="", flush=True)
            print(result.stderr, end="", file=sys.stderr, flush=True)
            paths = re.findall(r"^Report: (.+)$", result.stdout, re.MULTILINE)
            assert len(paths) == 1, "missing exact run report"
            run = json.loads(Path(paths[0]).read_text())
            entry = {"case": "helper-no-new-privs" if rejected else f"javascript-{index}",
                     "report": paths[0], "verified": False, "noSandbox": False,
                     "selectedSandboxRoute": "setuid" if args.setuid_route else "automatic"}
            report["runs"].append(entry)
            assert run['executor']['mode'] == args.executor
            assert run["consoleClosed"] and run["guestUser"] == args.user
            output = next(item["output"] for item in run["commands"] if "--diagnostics " in item["command"])
            assert "PROBE FAIL" not in output and "fstatFailures=0" in output
            if rejected:
                assert result.returncode == run["exitCode"] == 134 and not run["workloadCompleted"]
                assert run["admittedElf"] is None and run["expectedOutput"]["found"]
                # Chromium versions differ in the parent diagnostic; require
                # the actual child failure as well as the helper's denial.
                assert re.search(r"PROBE exit pid=\d+ status=1 ready=1\b", output)
                assert "PROBE proc-root domain=1 changes=0" in output
                assert "PROBE identity admittedImages=0" in output
                assert "PROBE installed-filter" not in output
                assert not re.search(r"^<html>", output, re.MULTILINE)
                entry["verified"] = True
                print("PASS stock Chromium cannot elevate its helper under no_new_privs", flush=True)
                continue
            assert result.returncode == run["exitCode"] == 0 and run["workloadCompleted"]
            assert run["admittedElf"] is None
            assert "The setuid sandbox is not running as root" not in output
            html = re.search(r"^<html>.*</html>$", output, re.MULTILINE)
            assert html, "no rendered DOM"
            rendered = Result()
            rendered.feed(html[0])
            assert rendered.values == [expected], rendered.values
            assert re.search(r"PROBE proc-root domain=1 changes=[1-9]\d*", output)
            if args.setuid_route:
                assert re.search(r"PROBE stored-setid-image pid=\d+ euid=0 egid=\d+ secure=1", output)
                assert re.search(r"PROBE identity admittedImages=0 changes=[1-9]\d* hostUid=" + str(run['uid']), output)
            else:
                assert re.search(r"PROBE pid-namespace init=\d+ visible=1 depth=1", output)
                assert re.search(r"PROBE identity admittedImages=0 changes=\d+ hostUid=" + str(run['uid']), output)
            assert "PROBE installed-filter" in output
            if args.machine_entry:
                ready = {int(pid) for pid in re.findall(r"PROBE filter-entry-ready pid=(\d+)", output)}
                counters = [json.loads(value) for value in re.findall(r"^SVC_RUNTIME_STATS (.+)$", output, re.MULTILINE)]
                accelerated = [value for value in counters
                               if value['pid'] in ready and value['filteredCompleted'] > 0]
                assert accelerated, "no completed accelerated calls after Chromium's filter installation"
                entry['filteredEntry'] = accelerated
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
