#!/usr/bin/env python3
"""Repeat real renderer/DOM checks, explicitly excluding layer-one confinement."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", type=Path, required=True)
    parser.add_argument("--store", required=True)
    parser.add_argument("--repeat", type=int, default=3)
    args = parser.parse_args()
    if not 1 <= args.repeat <= 20:
        parser.error("repeat must be between 1 and 20")
    tag = uuid.uuid4().hex
    report = {"scope": "renderer-seccomp-only", "sandboxSupportEstablished": False,
              "layerOneEstablished": False, "completed": False, "runs": []}
    try:
        for index in range(args.repeat):
            marker = "MD-JS-500500-" + str(index)
            page = ('data:text/html,<body><script>let s=0;for(let i=1;i<=1000;i++)s+=i;'
                    'document.body.textContent="MD-JS-"+s+"-' + str(index) + '";</script>')
            command = [sys.executable, str(Path(__file__).with_name("run.py")),
                       "--build", str(args.build), "--store", args.store,
                       "--scope", "renderer-seccomp-only", "--deadline-seconds", "30", "--quiet",
                       "--expect-output", marker, "--", "/usr/lib/chromium/chromium", "--no-sandbox",
                       "--renderer-cmd-prefix=/usr/bin/python3 /tmp/renderer-prefix.py", "--headless",
                       "--disable-background-networking", "--no-first-run", "--disable-component-update",
                       "--user-data-dir=/tmp/md-renderer-" + tag + "-" + str(index), "--dump-dom", page]
            # EVENT_WAIT: subprocess completion; its owned-tree deadline is 30 s.
            # The runner remains responsible for cancellation and console cleanup.
            completed = subprocess.run(command, text=True, capture_output=True)
            print(completed.stdout, end="", flush=True)
            print(completed.stderr, end="", file=sys.stderr, flush=True)
            paths = re.findall(r"^Report: (.+)$", completed.stdout, re.MULTILINE)
            assert len(paths) == 1, "missing exact process report"
            run = json.loads(Path(paths[0]).read_text())
            entry = {"report": paths[0], "verified": False}
            report["runs"].append(entry)
            assert completed.returncode == 0 and run["workloadCompleted"] and run["consoleClosed"]
            assert run["adapter"] == "experimental-TRACE-USER_NOTIF-fstat"
            output = run["commands"][-1]["output"]
            renderers = {int(pid) for pid in re.findall(r"RENDERER_PROBE pid=(\d+) ", output)}
            filters = {int(pid): int(size) for pid, size in re.findall(
                r"PROBE installed-filter pid=(\d+) instructions=(\d+)", output)}
            assert renderers and all(filters.get(pid, 0) > 0 for pid in renderers)
            assert "<body>" + marker + "</body>" in output
            metadata = re.findall(r"PROBE external metadata calls=(\d+) errors=(\d+)", output)
            assert len(metadata) == 1 and int(metadata[0][0]) > 0 and int(metadata[0][1]) == 0
            assert "fstatFailures=0" in output and "PROBE FAIL" not in output
            entry.update({"verified": True, "rendererPids": sorted(renderers),
                          "kernelInstalledFilters": filters, "externalStats": int(metadata[0][0]),
                          "computedDOM": marker})
            print("PASS real renderer seccomp and computed DOM; full browser sandbox NOT established", flush=True)
        report["completed"] = True
    finally:
        path = args.build / ("renderer-stage-results-" + tag + ".json")
        path.write_text(json.dumps(report, indent=2) + "\n")
        print("Stage report:", path, flush=True)


if __name__ == "__main__":
    main()
