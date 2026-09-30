#!/usr/bin/env python3
"""Run native no-ptrace notification controls under the selected shell identity."""
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
    assert state["shell"]["uid"] == 2000, "This control requires the already selected shell UID 2000"
    tag = uuid.uuid4().hex
    directory = "/data/local/tmp/md-notifications-" + tag
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
        for name in ("md-notification-test", "md-notification-lifecycle-test"):
            target = directory + "/" + name
            receipt = transport.upload(client, str(args.build / "bundle" / name), target)
            command("chmod 700 " + shlex.quote(target))
            # EVENT_WAIT: each native fixture has descriptor deadlines; this outer
            # command bound cancels an unexpected fixture hang, not a readiness wait.
            output = command("timeout 45 " + shlex.quote(target))
            report["checks"].append({"fixture": name, "upload": receipt, "output": output})
            print(output, end="", flush=True)
        report["passed"] = True
    except Exception as error:
        report["failure"] = str(error)
        raise
    finally:
        try:
            client.call("console.close", {"sessionId": console})
        finally:
            path = args.build / ("notification-results-" + tag + ".json")
            path.write_text(json.dumps(report, indent=2) + "\n")
            print("Report:", path, flush=True)


if __name__ == "__main__":
    main()
