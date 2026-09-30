#!/usr/bin/env python3
"""Run owned native hybrid ptrace/notification boundary controls under UID 2000."""
import argparse
import importlib.util
import json
from pathlib import Path
import shlex
import tomllib
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("build", type=Path)
    parser.add_argument("--runs", type=int, default=1, help="Fresh native processes, 1-100; stop at the first failure")
    args = parser.parse_args()
    if not 1 <= args.runs <= 100:
        parser.error("--runs must be 1-100")
    repo = Path(__file__).resolve().parents[2]
    spec = importlib.util.spec_from_file_location("transport", repo / "scripts/mcp-client.py")
    transport = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(transport)
    config = tomllib.loads((Path.home() / ".codex/config.toml").read_text())["mcp_servers"]["magicdesk"]
    client = transport.Client(config["url"], config["http_headers"]["Authorization"].removeprefix("Bearer "))
    state = client.call("get_state")
    assert state["shell"]["uid"] == 2000, "Requires the already selected UID 2000"
    tag = uuid.uuid4().hex
    directory = "/data/local/tmp/md-hybrid-" + tag
    report = {"id": tag, "directory": directory, "app": state["app"], "device": state["device"],
              "uid": 2000, "productionAdapter": False, "sandboxSupportEstablished": False,
              "observationsComplete": False, "consoleClosed": False, "commands": [],
              "requestedRuns": args.runs, "completedRuns": 0}
    console = client.call("console.open", {"directory": "/data/local/tmp"})["sessionId"]

    def command(value):
        result = client.call("console.execute", {"sessionId": console, "command": value})
        report["commands"].append({"command": value, **result})
        assert result["exitCode"] == 0, result
        return result["output"]

    try:
        command("mkdir " + shlex.quote(directory))
        report["kernel"] = command("uname -a")
        report["identity"] = command("id")
        target = directory + "/md-hybrid-test"
        report["upload"] = transport.upload(client, str(args.build / "md-hybrid-test"), target)
        command("chmod 700 " + shlex.quote(target))
        # EVENT_WAIT: native signalfd/notification events have failure deadlines;
        # the outer bound cancels a hung fixture, never establishes readiness.
        for _ in range(args.runs):
            output = command("timeout -k 3 60 " + shlex.quote(target))
            report["completedRuns"] += 1
            if args.runs == 1:
                print(output, end="", flush=True)
            else:
                print("Completed run", report["completedRuns"], "of", args.runs, flush=True)
        report["observationsComplete"] = True
    except Exception as error:
        report["failure"] = str(error)
        raise
    finally:
        try:
            client.call("console.close", {"sessionId": console})
            report["consoleClosed"] = True
        finally:
            path = args.build / ("hybrid-results-" + tag + ".json")
            path.write_text(json.dumps(report, indent=2) + "\n")
            print("Report:", path, flush=True)


if __name__ == "__main__":
    main()
