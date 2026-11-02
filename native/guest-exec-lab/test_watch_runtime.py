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
from kernel_profile import KernelProfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("build", type=Path)
    parser.add_argument("--rootfs", type=Path, help="Prepared fixture rootfs.tar.gz for glibc or musl")
    parser.add_argument("--userspace", type=Path, help="Prepared Debian GIO/D-Bus fixture archive")
    parser.add_argument("--gio", type=Path, help="Linux GIO fixture binary; requires --userspace")
    parser.add_argument("--contracts", action="store_true", help="Also run inode, importer and filesystem RPC regressions")
    parser.add_argument("--user", default="2000:2000", help="Guest UID:GID for all guest launches")
    KernelProfile.add_argument(parser)
    args = parser.parse_args()
    profile = KernelProfile(args.kernel_profile)
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
    runner_path = directory + '/libmagicdesk_guest_run.so'
    runner = shlex.join([runner_path, '--user', args.user])
    report = {"id": tag, "directory": directory, "app": state["app"], "device": state["device"],
              "uid": 2000, "guestUser": args.user, "productionAdapter": True,
              "checks": [], "uploads": [], "passed": False}
    console = client.call("console.open", {"directory": "/data/local/tmp"})["sessionId"]
    report['kernelProfile'] = profile.evidence

    def command(value):
        result = client.call("console.execute", {"sessionId": console, "command": value})
        report["checks"].append({"command": value, **result})
        print(result["output"], end="", flush=True)
        assert result["exitCode"] == 0, result
        if '--test-kernel-profile' not in value:
            profile.observe(result['output'], required=runner_path in value and '--store' in value)
        return result['output'] if '--test-kernel-profile' in value else profile.output(result['output'])

    try:
        command("mkdir -p " + directory + "/rootfs/bin " + directory + "/rootfs/tmp")
        controls = ("watch_queue", "watch_read", "watch_activation")
        binaries = ["bootstrap", "supervisor", "run", "service", "watch_guest", "watch_launch", "watch_store", *controls]
        if args.contracts:
            binaries.extend(("test_inodes", "test_import", "test_rpc"))
        for name in binaries:
            binary = "libmagicdesk_guest_" + name + ".so"
            target = directory + ("/rootfs/bin/watch" if name == "watch_guest" else "/" + binary)
            report["uploads"].append(transport.upload(client, args.build / binary, target))
            command("chmod 700 " + target)
        profile.prepare(lambda argv: command(shlex.join(argv)), directory + '/libmagicdesk_guest_supervisor.so')
        runner_path = profile.stage_launcher(lambda source, target: transport.upload(client, source, target),
                                            lambda argv: command(shlex.join(argv)), runner_path)
        runner = shlex.join([runner_path, '--user', args.user])
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
        for name in controls:
            command("timeout 60 " + directory + "/libmagicdesk_guest_" + name + ".so")
        command("timeout 30 " + directory + "/libmagicdesk_guest_service.so --import "
                + directory + "/rootfs " + directory + "/store")
        output = command("timeout 60 " + runner + " --statistics --store "
                + directory + "/store --home /tmp --cwd / -- /bin/watch /tmp/test")
        reads = re.search(r"MD_SYSCALL nr=63 trace=(\d+)", output)
        assert reads and int(reads[1]) < 1000, "Ordinary reads acquired a process-wide interception path"
        command("timeout 60 " + runner + " --store "
                + directory + "/store -- /bin/watch protected /tmp/protected")
        command("timeout 60 " + runner + " --store "
                + directory + "/store -- /bin/watch protected-all /tmp/protected-all")
        command("timeout 60 " + runner + " --store "
                + directory + "/store -- /bin/watch receive-filtered")
        command("timeout 60 " + runner + " --store "
                + directory + "/store -- /bin/watch churn")
        command("timeout 60 " + directory + "/libmagicdesk_guest_watch_launch.so "
                + shlex.quote(runner_path) + " " + directory + "/store --user " + shlex.quote(args.user))
        command("timeout 30 " + directory + "/libmagicdesk_guest_watch_store.so " + directory + "/store-unit")
        for entry in (0, 1):
            command("timeout 60 " + shlex.quote(runner_path) + " --user 0:0"
                    + f" --env SHROOT_MACHINE_ENTRY={entry} --store " + directory
                    + "/store -- /bin/watch quotas")
        if args.contracts:
            for name in ("inodes", "import", "rpc"):
                command("timeout 60 " + directory + "/libmagicdesk_guest_test_" + name + ".so "
                        + directory + "/contract-" + name)
        if args.gio:
            command("timeout 30 " + directory + "/libmagicdesk_guest_watch_launch.so "
                    + shlex.quote(runner_path) + " " + directory + "/store gio --user " + shlex.quote(args.user))
        if args.userspace:
            output = command("timeout 30 env PATH=/usr/bin:/bin " + runner + " --statistics --store "
                    + directory + "/store --home /tmp --cwd / -- /usr/bin/dbus-run-session -- "
                    + "/usr/bin/dbus-send --session --print-reply --dest=org.freedesktop.DBus / org.freedesktop.DBus.ListNames")
            # MD_FS_WATCH_PATH resolves and registers on the namespace worker.
            assert "org.freedesktop.DBus" in output and re.search(
                r"MD_FS operation=\d+ calls=[1-9]\d* ns=\d+ name=WATCH_PATH(?:\n|$)", output), output
            # Guest root cannot raise Android's hard nofile limit. Keep this
            # exact upstream warning visible; all other failures remain errors.
            limit = re.compile(r"^dbus-daemon\[\d+\]: \[session uid=0 pid=\d+\] "
                               r"org\.freedesktop\.DBus\.Error\.AccessDenied: "
                               r"Failed to set fd limit to 65536: Operation not permitted$", re.MULTILINE)
            report["limits"] = limit.findall(output)
            checked = limit.sub("", output)
            assert "Unable to" not in checked and "Failed to" not in checked, output
        command("rm -rf " + directory)
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
