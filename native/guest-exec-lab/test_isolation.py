#!/usr/bin/env python3
"""Observe native isolation capabilities under shell, without changing Android policy."""
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
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    spec = importlib.util.spec_from_file_location("transport", repo / "scripts/mcp-client.py")
    transport = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(transport)
    config = tomllib.loads((Path.home() / ".codex/config.toml").read_text())["mcp_servers"]["magicdesk"]
    client = transport.Client(config["url"], config["http_headers"]["Authorization"].removeprefix("Bearer "))
    state = client.call("get_state")
    assert state["shell"]["uid"] == 2000
    tag = uuid.uuid4().hex
    directory = "/data/local/tmp/md-isolation-" + tag
    report = {"id": tag, "directory": directory, "app": state["app"], "device": state["device"],
              "observationsComplete": False, "sandboxSupportEstablished": False,
              "consoleClosed": False, "commands": []}
    console = client.call("console.open", {"directory": "/data/local/tmp"})["sessionId"]

    def command(value, check=True):
        result = client.call("console.execute", {"sessionId": console, "command": value})
        report["commands"].append({"command": value, **result})
        if check:
            assert result["exitCode"] == 0, result
        return result

    try:
        command("mkdir " + shlex.quote(directory))
        report["identity"] = command("id")["output"]
        report["kernel"] = command("uname -a")["output"]
        # Configuration is corroboration, not a substitute for actual syscalls.
        report["kernelConfig"] = command("zcat /proc/config.gz", check=False)
        target = directory + "/md-isolation-test"
        report["upload"] = transport.upload(client, str(args.build / "md-isolation-test"), target)
        command("chmod 700 " + shlex.quote(target))
        # EVENT_WAIT: exact child completion; timeout fails the observation.
        result = command("timeout -k 3 30 " + shlex.quote(target))
        print(result["output"], end="", flush=True)
        report["observationsComplete"] = True
    finally:
        try:
            client.call("console.close", {"sessionId": console})
            report["consoleClosed"] = True
        finally:
            path = args.build / ("isolation-results-" + tag + ".json")
            path.write_text(json.dumps(report, indent=2) + "\n")
            print("Report:", path, flush=True)


if __name__ == "__main__":
    main()
