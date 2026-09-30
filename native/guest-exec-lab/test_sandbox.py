#!/usr/bin/env python3
"""Record native and guest sandbox boundaries, including expected incompatibilities."""
import argparse
import importlib.util
import json
from pathlib import Path
import shlex
import tarfile
import tomllib
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("build", type=Path)
    parser.add_argument("--runtime", required=True)
    parser.add_argument("--store", required=True, help="Prepared disposable glibc test store")
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
    directory = "/data/local/tmp/md-sandbox-" + tag
    report = {"id": tag, "directory": directory, "app": state["app"], "device": state["device"],
              "runtime": args.runtime, "store": args.store, "uid": 2000,
              "observationsComplete": False, "sandboxSupportEstablished": False, "checks": []}
    console = client.call("console.open", {"directory": "/data/local/tmp"})["sessionId"]

    def command(value):
        result = client.call("console.execute", {"sessionId": console, "command": value})
        report["checks"].append({"command": value, **result})
        assert result["exitCode"] == 0, result
        return result["output"]

    runner = shlex.join([args.runtime + "/libmagicdesk_guest_run.so", "--store", args.store, "--"])
    fixture_name = "md-sandbox-" + tag
    archive = args.build / (fixture_name + ".tar")
    with tarfile.open(archive, "w") as output:
        output.add(args.build / "md-sandbox-guest-fixture", arcname=fixture_name, recursive=False)
    installed = False
    try:
        command("mkdir " + shlex.quote(directory))
        report["kernel"] = command("uname -a")
        report["runtimeHashes"] = command("sha256sum " + shlex.join([
            args.runtime + "/libmagicdesk_guest_" + name + ".so" for name in ["run", "supervisor", "bootstrap", "service"]]))
        binary = directory + "/md-sandbox-native-test"
        report["nativeUpload"] = transport.upload(client, str(args.build / "bundle/md-sandbox-native-test"), binary)
        command("chmod 700 " + shlex.quote(binary))
        command("mkdir " + shlex.quote(directory + "/empty"))
        command(shlex.join([args.runtime + "/libmagicdesk_guest_service.so", "--import",
                            directory + "/empty", directory + "/rpc-store"]))
        # EVENT_WAIT: native tests await pidfds and service readiness. This outer
        # failure deadline bounds an unexpected hang, not successful readiness.
        native = command(shlex.join(["timeout", "45", binary,
                                     args.runtime + "/libmagicdesk_guest_service.so", directory + "/rpc-store"]))
        print(native, end="", flush=True)
        report["guestUpload"] = transport.upload(client, str(archive), directory + "/fixture.tar")
        command("cat " + shlex.quote(directory + "/fixture.tar") + " | " + runner + " /bin/tar -xf - -C /tmp")
        installed = True
        guest = command("timeout 45 " + runner + " " + shlex.quote("/tmp/" + fixture_name))
        print(guest, end="", flush=True)
        report["observationsComplete"] = True
    except Exception as error:
        report["failure"] = str(error)
        raise
    finally:
        try:
            if installed:
                command(runner + " /bin/rm -- " + shlex.quote("/tmp/" + fixture_name))
        finally:
            try:
                client.call("console.close", {"sessionId": console})
            finally:
                path = args.build / ("sandbox-results-" + tag + ".json")
                path.write_text(json.dumps(report, indent=2) + "\n")
                print("Report:", path, flush=True)


if __name__ == "__main__":
    main()
