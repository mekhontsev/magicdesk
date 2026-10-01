#!/usr/bin/env python3
"""Interleaved metadata/spawn measurements with explicit watch ownership."""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import shlex
import statistics
import tomllib
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("build", type=Path)
    parser.add_argument("--store", required=True)
    parser.add_argument("--baseline", required=True)
    parser.add_argument("--current", required=True)
    parser.add_argument("--rounds", type=int, default=5)
    args = parser.parse_args()
    assert args.rounds > 0
    spec = importlib.util.spec_from_file_location("transport", Path(__file__).resolve().parents[2] / "scripts/mcp-client.py")
    transport = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(transport)
    config = tomllib.loads((Path.home() / ".codex/config.toml").read_text())["mcp_servers"]["magicdesk"]
    client = transport.Client(config["url"], config["http_headers"]["Authorization"].removeprefix("Bearer "))
    state = client.call("get_state")
    assert state["shell"]["uid"] == 2000
    tag = uuid.uuid4().hex
    report = {"id": tag, "app": state["app"], "device": state["device"], "store": args.store,
              "baseline": args.baseline, "current": args.current, "samples": [], "profiles": [], "passed": False,
              "timing": "CLOCK_MONOTONIC around workload only; watch setup/draining and launch excluded"}
    console = client.call("console.open", {"directory": "/data/local/tmp"})["sessionId"]
    output = args.build / ("watch-benchmark-" + tag + ".json")

    def shell(command):
        result = client.call("console.execute", {"sessionId": console, "command": command})
        assert result["exitCode"] == 0, result
        return result["output"]

    def launch(runtime, workload, watch, profile=False):
        return shell(shlex.join(["timeout", "60", runtime + "/libmagicdesk_guest_run.so",
            *(["--statistics"] if profile else []), "--store", args.store, "--",
            "/bench/runtime-workloads", workload, watch]))

    try:
        target = "/data/local/tmp/md-watch-workloads-" + tag
        report["fixture"] = transport.upload(client, args.build / "runtime-workloads", target)
        shell(shlex.join([args.current + "/libmagicdesk_guest_run.so", "--store", args.store, "--",
            "/bin/sh", "-c", "cat > /bench/runtime-workloads; chmod 700 /bench/runtime-workloads"]) + " < " + shlex.quote(target))
        launch(args.current, "prepare", "none")
        report["powerBefore"] = shell("dumpsys battery")
        report["thermalBefore"] = shell("dumpsys thermalservice")
        cases = [(workload, watch, label, runtime) for workload in ("metadata", "spawn")
                 for watch in ("none", "self", "external")
                 for label, runtime in (("baseline", args.baseline), ("current", args.current))]
        for round_number in range(args.rounds + 1):
            order = cases if not round_number % 2 else list(reversed(cases))
            for workload, watch, label, runtime in order:
                result = launch(runtime, workload, watch)
                match = re.search(r"MD_ELAPSED workload=" + workload + " watch=" + watch + r" ns=(\d+)", result)
                assert match and "MD_WORKLOAD_OK " + workload in result, result
                sample = {"round": round_number, "warmup": not round_number, "runtime": label,
                          "workload": workload, "watch": watch, "ns": int(match[1]), "output": result}
                report["samples"].append(sample)
                output.write_text(json.dumps(report, indent=2) + "\n")
                print(f"{round_number} {label} {workload}/{watch}: {int(match[1]) / 1e6:.2f} ms", flush=True)
        for workload, watch, label, runtime in cases:
            report["profiles"].append({"runtime": label, "workload": workload, "watch": watch,
                                       "output": launch(runtime, workload, watch, True)})
        report["powerAfter"] = shell("dumpsys battery")
        report["thermalAfter"] = shell("dumpsys thermalservice")
        report["mediansMs"] = {"/".join((label, workload, watch)): statistics.median(
            s["ns"] for s in report["samples"] if not s["warmup"] and s["runtime"] == label
            and s["workload"] == workload and s["watch"] == watch) / 1e6
            for workload, watch, label, _ in cases}
        report["passed"] = True
        print(json.dumps(report["mediansMs"], indent=2), flush=True)
    finally:
        client.call("console.close", {"sessionId": console})
        output.write_text(json.dumps(report, indent=2) + "\n")
        print("Report:", output, flush=True)


if __name__ == "__main__":
    main()
