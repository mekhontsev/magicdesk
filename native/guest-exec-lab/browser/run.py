#!/usr/bin/env python3
"""Exercise the production guest runtime with real browser workloads."""
import argparse
import importlib.util
import json
from pathlib import Path
import shlex
import sys
import tomllib
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", type=Path, required=True)
    parser.add_argument("--store", required=True, help="Prepared disposable guest store")
    parser.add_argument("--installed", action="store_true", help="Use the installed APK's immutable guest bundle")
    parser.add_argument("--deadline-seconds", type=int, default=120,
                        help="Owned process-tree event deadline, 5 to 120 seconds")
    parser.add_argument("--scope", choices=("application", "renderer-seccomp-only", "zygote-ipc-only"), default="application",
                        help="Label a partial layer-two experiment separately from full browser startup")
    parser.add_argument("--quiet", action="store_true", help="Keep full process output in the JSON report")
    parser.add_argument("--admit-elf", help="Explicit sealed-ELF logical credential admission")
    parser.add_argument("--expect-artifact", help="Fresh guest PNG path; require its signature after exit")
    parser.add_argument("--expect-output", help="Require this literal in the output, e.g. a rendered DOM marker")
    parser.add_argument("program", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if not 5 <= args.deadline_seconds <= 120:
        parser.error("deadline must be between 5 and 120 seconds")
    if args.program[:1] == ["--"]:
        args.program.pop(0)
    if not args.program:
        parser.error("missing guest program")
    repo = Path(__file__).resolve().parents[3]
    spec = importlib.util.spec_from_file_location("transport", repo / "scripts/mcp-client.py")
    transport = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(transport)
    config = tomllib.loads((Path.home() / ".codex/config.toml").read_text())["mcp_servers"]["magicdesk"]
    client = transport.Client(config["url"], config["http_headers"]["Authorization"].removeprefix("Bearer "))
    state = client.call("get_state")
    assert state["shell"]["uid"] == 2000
    tag = uuid.uuid4().hex
    remote = "/data/local/tmp/md-browser-" + tag
    report = {"id": tag, "directory": remote, "app": state["app"], "device": state["device"],
              "uid": 2000, "adapter": "selective-TRACE-USER_NOTIF", "productionAdapter": True,
              "scope": args.scope, "layerOneEstablished": False,
              "procRootDomain": True,
              "admittedElf": args.admit_elf,
              "sandboxSupportEstablished": False, "consoleClosed": False,
              "workloadCompleted": False, "commands": [], "uploads": []}
    console = client.call("console.open", {"directory": "/data/local/tmp"})["sessionId"]

    def command(value, check=True):
        result = client.call("console.execute", {"sessionId": console, "command": value})
        report["commands"].append({"command": value, **result})
        if check:
            assert result["exitCode"] == 0, result
        return result

    try:
        command("mkdir " + shlex.quote(remote))
        report["identity"] = command("id")["output"]
        report["kernel"] = command("uname -a")["output"]
        if not args.installed:
            for name in ("libmagicdesk_guest_supervisor.so", "libmagicdesk_guest_run.so",
                         "libmagicdesk_guest_bootstrap.so", "libmagicdesk_guest_service.so"):
                report["uploads"].append(transport.upload(client, str(args.build / name), remote + "/" + name))
            command("chmod 700 " + remote + "/*")
            report["hashes"] = command("sha256sum " + remote + "/*")["output"]
        report["installed"] = args.installed
        runner = "magicdesk-guest" if args.installed else remote + "/libmagicdesk_guest_run.so"
        baseline = [runner, "--store", args.store, "--"]
        if args.expect_artifact:
            command(shlex.join(baseline + ["/usr/bin/test", "!", "-e", args.expect_artifact]))
        # EVENT_WAIT: owned tracee events; outer deadline bounds cancellation.
        # Neither deadline is a startup settling delay or evidence of readiness.
        launch = shlex.join(["timeout", "-k", "5", str(args.deadline_seconds + 10), runner,
                             "--diagnostics",
                             "--deadline-seconds", str(args.deadline_seconds),
                             *(["--admit-elf", args.admit_elf] if args.admit_elf else []),
                             "--store", args.store, "--"] + args.program)
        result = command(launch, check=False)
        report["exitCode"] = result["exitCode"]
        if not args.quiet:
            print(result["output"], end="", flush=True)
        checks = []
        if args.expect_output:
            checks.append(args.expect_output in result["output"])
            report["expectedOutput"] = {"text": args.expect_output, "found": checks[-1]}
        if args.expect_artifact:
            artifact = command(shlex.join(baseline + ["/usr/bin/od", "-An", "-tx1", "-N8", args.expect_artifact]), check=False)
            checks.append(artifact["exitCode"] == 0 and artifact["output"].split() == ["89", "50", "4e", "47", "0d", "0a", "1a", "0a"])
            report["artifact"] = {"path": args.expect_artifact, "pngSignature": checks[-1]}
        report["workloadCompleted"] = result["exitCode"] == 0 and bool(checks) and all(checks)
        return result["exitCode"] or (0 if not checks or all(checks) else 1)
    finally:
        try:
            client.call("console.close", {"sessionId": console})
            report["consoleClosed"] = True
        finally:
            path = args.build / ("browser-results-" + tag + ".json")
            path.write_text(json.dumps(report, indent=2) + "\n")
            print("Report:", path, flush=True)


if __name__ == "__main__":
    sys.exit(main())
