#!/usr/bin/env python3
"""Run queue and lazy-read boundary fixtures, not a production watch certification."""
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
    assert state["shell"]["uid"] == 2000, "Requires the already selected shell UID 2000"
    tag = uuid.uuid4().hex
    directory = "/data/local/tmp/md-watch-" + tag
    report = {"id": tag, "directory": directory, "app": state["app"], "device": state["device"],
              "uid": state["shell"]["uid"], "productionAdapter": False, "checks": [], "passed": False}
    console = client.call("console.open", {"directory": "/data/local/tmp"})["sessionId"]

    def command(value):
        result = client.call("console.execute", {"sessionId": console, "command": value})
        assert result["exitCode"] == 0, result
        return result["output"]

    try:
        command("mkdir " + shlex.quote(directory))
        report["kernel"] = command("uname -a")
        for name in ("md-watch-queue-test", "md-watch-read-test", "md-watch-activation-test"):
            target = directory + "/" + name
            receipt = transport.upload(client, str(args.build / name), target)
            command("chmod 700 " + shlex.quote(target))
            # EVENT_WAIT: native descriptor deadlines bound progress. This outer
            # limit cancels an unexpected fixture hang, never asserts readiness.
            result = client.call("console.execute", {"sessionId": console,
                "command": "timeout 60 " + shlex.quote(target)})
            report["checks"].append({"fixture": name, "upload": receipt, **result})
            print(result["output"], end="", flush=True)
            assert result["exitCode"] == 0, result
        report["passed"] = True
    except Exception as error:
        report["failure"] = str(error)
        raise
    finally:
        try:
            client.call("console.close", {"sessionId": console})
        finally:
            path = args.build / ("watch-results-" + tag + ".json")
            path.write_text(json.dumps(report, indent=2) + "\n")
            print("Report:", path, flush=True)


if __name__ == "__main__":
    main()
