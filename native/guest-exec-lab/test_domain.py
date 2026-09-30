#!/usr/bin/env python3
"""Check external domain/identity contracts, not browser compatibility."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shlex
import tomllib
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("build", type=Path)
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--fixture", choices=("domain", "suid-context", "identity"), default="domain")
    parser.add_argument("--suid-main", action="store_true")
    args = parser.parse_args()
    assert 1 <= args.repeat <= 100
    assert not args.suid_main or args.fixture == "suid-context"
    repo = Path(__file__).resolve().parents[2]
    spec = importlib.util.spec_from_file_location("transport", repo / "scripts/mcp-client.py")
    transport = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(transport)
    config = tomllib.loads((Path.home() / ".codex/config.toml").read_text())["mcp_servers"]["magicdesk"]
    client = transport.Client(config["url"], config["http_headers"]["Authorization"].removeprefix("Bearer "))
    state = client.call("get_state")
    assert state["shell"]["uid"] == 2000
    tag = uuid.uuid4().hex
    directory = "/data/local/tmp/md-" + args.fixture + "-" + tag
    binary = args.build / ("md-browser-identity" if args.fixture == "identity"
                           else "md-" + args.fixture + "-test")
    report = {"id": tag, "directory": directory, "app": state["app"], "device": state["device"],
              "fixture": args.fixture, "suidMain": args.suid_main,
              "sha256": hashlib.sha256(binary.read_bytes()).hexdigest(), "completed": False,
              "sandboxSupportEstablished": False, "consoleClosed": False, "commands": []}
    if args.fixture == "suid-context":
        report["sources"] = json.loads((args.build / "suid-source.json").read_text())
    console = client.call("console.open", {"directory": "/data/local/tmp"})["sessionId"]

    def command(value):
        result = client.call("console.execute", {"sessionId": console, "command": value})
        report["commands"].append({"command": value, **result})
        assert result["exitCode"] == 0, result
        return result

    try:
        command("mkdir " + shlex.quote(directory))
        report["identity"] = command("id")["output"]
        report["kernel"] = command("uname -a")["output"]
        target = directory + "/" + binary.name
        transport.upload(client, str(binary), target)
        command("chmod 700 " + shlex.quote(target))
        for i in range(args.repeat):
            test_root = directory + "/run-" + str(i)
            command("mkdir " + shlex.quote(test_root))
            # EVENT_WAIT: owned fixture completion; deadline is cancellation, not success.
            result = command("timeout -k 3 30 " + shlex.quote(target)
                             + (" " + shlex.quote(test_root) if args.fixture != "identity" else "")
                             + (" --main" if args.suid_main else ""))
            expected = {"domain": "PASS domain fixture;", "suid-context": "PASS suid context:",
                        "identity": "PASS external identity contract;"}[args.fixture]
            assert expected in result["output"]
            print(result["output"], end="", flush=True)
        report["completed"] = True
    finally:
        try:
            client.call("console.close", {"sessionId": console})
            report["consoleClosed"] = True
        finally:
            path = args.build / (args.fixture + "-results-" + tag + ".json")
            path.write_text(json.dumps(report, indent=2) + "\n")
            print("Report:", path, flush=True)


if __name__ == "__main__":
    main()
