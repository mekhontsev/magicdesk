#!/usr/bin/env python3
"""Exercise inotify through the production guest supervisor and syscall adapter."""
import argparse
import importlib.util
import json
import re
from pathlib import Path
import shlex
import tomllib
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("build", type=Path)
    parser.add_argument("--rootfs", type=Path, help="Prepared fixture rootfs.tar.gz for glibc or musl")
    parser.add_argument("--userspace", type=Path, help="Prepared Debian GIO/D-Bus fixture archive")
    parser.add_argument("--gio", type=Path, help="Linux GIO fixture binary; requires --userspace")
    parser.add_argument("--contracts", action="store_true", help="Also run inode, importer and filesystem RPC regressions")
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
    directory = "/data/local/tmp/md-watch-runtime-" + tag
    report = {"id": tag, "directory": directory, "app": state["app"], "device": state["device"],
              "uid": 2000, "productionAdapter": True, "checks": [], "uploads": [], "passed": False}
    console = client.call("console.open", {"directory": "/data/local/tmp"})["sessionId"]

    def command(value):
        result = client.call("console.execute", {"sessionId": console, "command": value})
        report["checks"].append({"command": value, **result})
        print(result["output"], end="", flush=True)
        assert result["exitCode"] == 0, result
        return result["output"]

    try:
        command("mkdir -p " + directory + "/rootfs/bin " + directory + "/rootfs/tmp")
        binaries = ["bootstrap", "supervisor", "run", "service", "watch_guest", "watch_launch", "watch_store"]
        if args.contracts:
            binaries.extend(("test_inodes", "test_import", "test_rpc"))
        for name in binaries:
            binary = "libmagicdesk_guest_" + name + ".so"
            target = directory + ("/rootfs/bin/watch" if name == "watch_guest" else "/" + binary)
            report["uploads"].append(transport.upload(client, args.build / binary, target))
            command("chmod 700 " + target)
        if args.rootfs:
            report["uploads"].append(transport.upload(client, args.rootfs, directory + "/rootfs.tar.gz"))
            command("tar -xzf " + directory + "/rootfs.tar.gz -C " + directory + "/rootfs")
        if args.userspace:
            report["uploads"].append(transport.upload(client, args.userspace, directory + "/userspace.tar.gz"))
            command("tar -xzf " + directory + "/userspace.tar.gz -C " + directory + "/rootfs")
        if args.gio:
            assert args.userspace, "--gio requires --userspace"
            report["uploads"].append(transport.upload(client, args.gio, directory + "/rootfs/bin/watch-gio"))
            command("chmod 700 " + directory + "/rootfs/bin/watch-gio")
        command("timeout 30 " + directory + "/libmagicdesk_guest_service.so --import "
                + directory + "/rootfs " + directory + "/store")
        output = command("timeout 60 " + directory + "/libmagicdesk_guest_run.so --statistics --store "
                + directory + "/store --home /tmp --cwd / -- /bin/watch /tmp/test")
        reads = re.search(r"MD_SYSCALL nr=63 trace=(\d+)", output)
        assert reads and int(reads[1]) < 1000, "Ordinary reads acquired a process-wide interception path"
        command("timeout 60 " + directory + "/libmagicdesk_guest_watch_launch.so "
                + directory + "/libmagicdesk_guest_run.so " + directory + "/store")
        command("timeout 30 " + directory + "/libmagicdesk_guest_watch_store.so " + directory + "/store-unit")
        if args.contracts:
            for name in ("inodes", "import", "rpc"):
                command("timeout 60 " + directory + "/libmagicdesk_guest_test_" + name + ".so "
                        + directory + "/contract-" + name)
        if args.gio:
            command("timeout 30 " + directory + "/libmagicdesk_guest_watch_launch.so "
                    + directory + "/libmagicdesk_guest_run.so " + directory + "/store gio")
        if args.userspace:
            output = command("timeout 30 env PATH=/usr/bin:/bin " + directory + "/libmagicdesk_guest_run.so --statistics --store "
                    + directory + "/store --home /tmp --cwd / -- /usr/bin/dbus-run-session -- "
                    + "/usr/bin/dbus-send --session --print-reply --dest=org.freedesktop.DBus / org.freedesktop.DBus.ListNames")
            assert "org.freedesktop.DBus" in output and re.search(r"MD_FS operation=23 calls=[1-9]", output)
            assert "Unable to" not in output and "Failed to" not in output, output
        report["passed"] = True
    except Exception as error:
        report["failure"] = str(error)
        raise
    finally:
        try:
            client.call("console.close", {"sessionId": console})
        finally:
            path = args.build / ("watch-runtime-" + tag + ".json")
            path.write_text(json.dumps(report, indent=2) + "\n")
            print("Report:", path, flush=True)


if __name__ == "__main__":
    main()
