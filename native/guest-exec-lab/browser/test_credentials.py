#!/usr/bin/env python3
"""Check admitted ELF execution and guest credentials, not a complete sandbox."""
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
    helper = "/tmp/md-chrome-helper-ordinary"
    cases = [("catalogue", helper, [control, "catalogue"], "PASS immutable catalogue")]
    for index in range(3):
        cases += [(f"identity-{index}", control, [control, "identity-parent"],
                   "PASS integrated guest identity controls")]
        cases += [(f"renderer-{index}", helper, ["/usr/bin/python3", "/tmp/zygote_stage.py",
                   helper, "/usr/lib/chromium/chromium", "--fork-renderer"],
                   "PASS stock Chromium renderer filter and nonempty Mojo startup")]
    cases += [("helper-retained", helper, ["/usr/bin/env", "SBX_CHROME_API_RQ=1",
               helper, control, "helper-retained"], "PASS retained proc capabilities")]
    try:
        for name, admitted, program, marker in cases:
            command = [sys.executable, str(Path(__file__).with_name("run.py")),
                       "--build", str(args.build), "--store", args.store,
                       "--admit-elf", admitted,
                       "--deadline-seconds", "60", "--quiet", "--scope",
                       "zygote-ipc-only" if name.startswith("renderer-") else "application",
                       "--expect-output", marker, "--", *program]
            # EVENT_WAIT: exact owned-tree completion; runner deadlines fail and cancel hangs.
            result = subprocess.run(command, text=True, capture_output=True)
            print(result.stdout, end="", flush=True)
            print(result.stderr, end="", file=sys.stderr, flush=True)
            paths = re.findall(r"^Report: (.+)$", result.stdout, re.MULTILINE)
            assert len(paths) == 1, "missing exact report"
            run = json.loads(Path(paths[0]).read_text())
            entry = {"case": name, "report": paths[0], "verified": False}
            report["runs"].append(entry)
            assert result.returncode == run["exitCode"] == 0 and run["consoleClosed"], entry
            assert run["workloadCompleted"] and run["expectedOutput"]["found"], entry
            assert run["uid"] == 2000 and run["admittedElf"] == admitted
            output = run["commands"][-1]["output"]
            assert "PROBE FAIL" not in output and "fstatFailures=0" in output
            if name != "catalogue":
                assert re.search(r"PROBE admitted-image pid=\d+ fd=\d+ euid=0 secure=1", output)
                assert re.search(r"PROBE entered-image pid=\d+ uid=2000 euid=0 secure=1", output)
                assert re.search(r"PROBE identity admittedImages=[1-9]\d* changes=[1-9]\d* hostUid=2000", output)
            if name == "catalogue":
                assert "PASS" in output and run["productionAdapter"]
            elif name.startswith("identity-"):
                assert "PASS guest no_new_privs suppresses admitted set-ID exec" in output
                assert "PASS admitted ELF auxv, secure loader, failed exec, fork, permanent drop and kernel denial" in output
            else:
                assert "The setuid sandbox is not running as root" not in output
                assert re.search(r"PROBE proc-root domain=1 changes=[1-9]\d*", output)
            if name.startswith("renderer-"):
                pid = int(re.search(r"RENDERER_PID (\d+)", output)[1])
                assert re.search(rf"PROBE installed-filter pid={pid} instructions=-?\d+", output)
                assert "RENDERER_KERNEL" in output and "RENDERER_MOJO" in output
                assert "retained requests=" in output
                entry["rendererPid"] = pid
            entry["verified"] = True
            print("PASS", name, flush=True)
        report["completed"] = True
    finally:
        path = args.build / ("credentials-stage-results-" + uuid.uuid4().hex + ".json")
        path.write_text(json.dumps(report, indent=2) + "\n")
        print("Stage report:", path, flush=True)


if __name__ == "__main__":
    main()
