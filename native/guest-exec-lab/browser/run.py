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

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fixture_executor import FixtureExecutor
from test_oci_services import add_machine_entry_argument


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", type=Path, required=True)
    parser.add_argument("--store", required=True, help="Prepared disposable guest store")
    parser.add_argument("--installed", action="store_true", help="Use the installed APK's immutable guest bundle")
    FixtureExecutor.add_argument(parser)
    add_machine_entry_argument(parser)
    parser.add_argument("--deadline-seconds", type=int, default=120,
                        help="Owned process-tree event deadline, 5 to 120 seconds")
    parser.add_argument("--scope", choices=("application", "renderer-seccomp-only", "zygote-ipc-only"), default="application",
                        help="Label a partial layer-two experiment separately from full browser startup")
    parser.add_argument("--quiet", action="store_true", help="Keep full process output in the JSON report")
    parser.add_argument("--admit-elf", help="Explicit sealed-ELF logical credential admission")
    parser.add_argument("--user", help="Explicit guest identity, independent of image admission")
    parser.add_argument("--expect-artifact", help="Fresh guest PNG path; require its signature after exit")
    parser.add_argument("--expect-output", help="Require this literal in the output, e.g. a rendered DOM marker")
    parser.add_argument('--cleanup-path', action='append', default=[], help='Exact fixture-owned guest path to remove after observation')
    parser.add_argument("program", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    args.build.mkdir(parents=True, exist_ok=True)
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
    tag = uuid.uuid4().hex
    executor = FixtureExecutor(client, transport, args.build, args.executor, 'browser')
    remote = executor.root
    report = {"id": tag, "directory": remote, "app": state["app"], "device": state["device"],
              "uid": executor.uid, "adapter": "selective-TRACE-USER_NOTIF", "productionAdapter": True,
              "scope": args.scope, "layerOneEstablished": False,
              "procRootDomain": True,
              "admittedElf": args.admit_elf,
              "guestUser": args.user,
              "entryMode": 'default' if args.machine_entry is None else 'lazy' if args.machine_entry else 'base',
              "sandboxSupportEstablished": False, "consoleClosed": False,
              "workloadCompleted": False, "commands": [], "uploads": []}
    def command(value, check=True):
        result = executor.execute(value, args.deadline_seconds + 20)
        report["commands"].append({"command": value, **result})
        if check:
            assert result["exitCode"] == 0, result
        return result

    try:
        report["identity"] = command("id")["output"]
        report["kernel"] = command("uname -a")["output"]
        if not args.installed:
            for name in ("libmagicdesk_guest_supervisor.so", "libmagicdesk_guest_run.so",
                         "libmagicdesk_guest_bootstrap.so", "libmagicdesk_guest_service.so"):
                report["uploads"].append(executor.upload(args.build / name, remote + "/" + name))
            if args.executor == 'shell':
                command("chmod 700 " + remote + "/*")
            report["hashes"] = command("sha256sum " + remote + "/*")["output"]
        report["installed"] = args.installed
        runner = ((executor.libraries + '/libmagicdesk_guest_run.so' if args.executor == 'app' else 'shroot')
                  if args.installed else executor.native_path(remote + "/libmagicdesk_guest_run.so"))
        baseline = [runner, "--store", args.store, *(['--user', args.user] if args.user else []), "--"]
        if args.expect_artifact:
            command(shlex.join(baseline + ["/usr/bin/test", "!", "-e", args.expect_artifact]))
        # EVENT_WAIT: owned tracee events; outer deadline bounds cancellation.
        # Neither deadline is a startup settling delay or evidence of readiness.
        launch = shlex.join(["timeout", "-k", "5", str(args.deadline_seconds + 10), runner,
                             "--diagnostics",
                             *(['--statistics'] if args.machine_entry else []),
                             *([] if args.machine_entry is None else
                               ['--env', 'SHROOT_MACHINE_ENTRY=' + str(int(args.machine_entry))]),
                             "--deadline-seconds", str(args.deadline_seconds),
                             *(["--user", args.user] if args.user else []),
                             *(["--admit-elf", args.admit_elf] if args.admit_elf else []),
                             "--store", args.store, "--"] + args.program)
        log = remote + "/browser.log"
        result = command(launch + " >" + shlex.quote(log) + " 2>&1", check=False)
        local_log = args.build / ("browser-" + tag + ".log")
        executor.download(log, local_log)
        result["output"] = local_log.read_text(errors="replace")
        report["commands"][-1]["output"] = result["output"]
        report["log"] = str(local_log)
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
            if checks[-1]:
                staged = remote + '/screenshot.png'
                command(shlex.join(baseline + ['/bin/cat', args.expect_artifact]) + ' >' + shlex.quote(staged))
                target = args.build / ('browser-' + tag + '.png')
                executor.download(staged, target)
                report['artifact']['local'] = str(target)
        report["workloadCompleted"] = result["exitCode"] == 0 and bool(checks) and all(checks)
        return result["exitCode"] or (0 if not checks or all(checks) else 1)
    finally:
        try:
            try:
                if args.cleanup_path and 'baseline' in locals():
                    command(shlex.join(baseline + ['/bin/rm', '-rf', '--', *args.cleanup_path]))
            finally:
                command('rm -rf ' + shlex.quote(remote))
        finally:
            executor.close()
            report["consoleClosed"] = executor.closed
            report['executor'] = executor.evidence
            path = args.build / ("browser-results-" + tag + ".json")
            path.write_text(json.dumps(report, indent=2) + "\n")
            print("Report:", path, flush=True)


if __name__ == "__main__":
    sys.exit(main())
