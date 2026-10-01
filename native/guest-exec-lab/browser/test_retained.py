#!/usr/bin/env python3
"""Check retained resources and real zygote/renderer startup, not full isolation."""
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
    args = parser.parse_args()
    report = {"completed": False, "sandboxSupportEstablished": False,
              "layerOneEstablished": False, "runs": []}
    control = "/tmp/guest-browser-controls/md-browser-control"
    cases = [(mode, [control, mode], "PASS", 0) for mode in
             ("broker-files", "broker-directories", "broker-shared-domain", "seek", "seek-protected", "transport-policy", "kernel-policy", "prepared-exec-failure", "memory", "children", "job-control", "group-exit", "group-exit-protected",
              "addressless", "self-exec", "exec-offset", "external-socket",
              "external-connect", "protected-metadata", "protected-exec",
              "protected-thread-exec", "protected-denial", "protected-denial-zero")]
    cases += [("helper-retained", ["/usr/bin/env", "SBX_CHROME_API_RQ=1",
                "/tmp/md-chrome-helper-ordinary", control, "helper-retained"],
               "PASS retained proc capabilities", 0)]
    cases += [("zygote-" + str(index), ["/usr/bin/python3", "/tmp/zygote_stage.py",
                "/tmp/md-chrome-helper-ordinary", "/usr/lib/chromium/chromium", "--fork-renderer"],
               "PASS stock Chromium renderer filter and nonempty Mojo startup", 0) for index in range(3)]
    # This ordinary file has no trusted set-ID admission. The full browser must
    # reject it, even when its separate IPC/renderer fixture can execute it.
    cases += [("helper-rejected", ["/usr/bin/env", "CHROME_DEVEL_SANDBOX=/tmp/md-chrome-helper-ordinary",
                "/usr/lib/chromium/chromium", "--headless", "--no-first-run",
                "--user-data-dir=/tmp/md-rejected-" + uuid.uuid4().hex,
                "--dump-dom", "data:text/html,<body>MD-UNADMITTED</body>"],
               "The SUID sandbox helper binary was found, but is not configured correctly.", 134)]
    try:
        for name, program, marker, expected_exit in cases:
            command = [sys.executable, str(Path(__file__).with_name("run.py")),
                       "--build", str(args.build), "--store", args.store,
                       "--deadline-seconds", "60", "--quiet",
                       "--scope", "zygote-ipc-only" if name.startswith("zygote-") else "application",
                       "--expect-output", marker, "--", *program]
            # EVENT_WAIT: runner completion with its exact owned-tree deadline.
            result = subprocess.run(command, text=True, capture_output=True)
            print(result.stdout, end="", flush=True)
            print(result.stderr, end="", file=sys.stderr, flush=True)
            paths = re.findall(r"^Report: (.+)$", result.stdout, re.MULTILINE)
            assert len(paths) == 1, "missing exact report"
            run = json.loads(Path(paths[0]).read_text())
            entry = {"case": name, "report": paths[0], "expectedExitCode": expected_exit, "verified": False}
            report["runs"].append(entry)
            assert result.returncode == expected_exit == run["exitCode"] and run["consoleClosed"], entry
            assert run["workloadCompleted"] == (expected_exit == 0), entry
            assert run["expectedOutput"]["found"], entry
            output = run["commands"][-1]["output"]
            assert "PROBE FAIL" not in output and "fstatFailures=0" in output
            if name == "helper-rejected":
                assert "<body>MD-UNADMITTED</body>" not in output and "PROBE complete status=134" in output
            if name == "job-control":
                stops, wakes = map(int, re.search(r"PROBE job-control stops=(\d+) wakes=(\d+)", output).groups())
                assert stops >= 64 and wakes >= 64, (stops, wakes)
            if name.startswith("zygote-"):
                pid = int(re.search(r"RENDERER_PID (\d+)", output)[1])
                assert re.search(rf"PROBE installed-filter pid={pid} instructions=-?\d+", output)
                assert "RENDERER_KERNEL" in output and "RENDERER_MOJO" in output
                retained = re.findall(r"PROBE retained requests=(\d+) errors=(\d+)", output)
                assert len(retained) == 1 and int(retained[0][0]) > 3 and int(retained[0][1]) == 0
                entry["rendererPid"] = pid
            entry["verified"] = True
            print("PASS", name, flush=True)
        report["completed"] = True
    finally:
        path = args.build / ("retained-stage-results-" + uuid.uuid4().hex + ".json")
        path.write_text(json.dumps(report, indent=2) + "\n")
        print("Stage report:", path, flush=True)


if __name__ == "__main__":
    main()
