#!/usr/bin/env python3
"""Install and run ABI fixtures in an explicitly selected disposable guest store."""
import argparse
from pathlib import Path
import tarfile
import uuid
from fixture_executor import FixtureExecutor
from test_oci_services import Suite, add_machine_entry_argument


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("build", type=Path)
    parser.add_argument("--libc-build", type=Path, required=True)
    parser.add_argument("--static-build", type=Path, required=True)
    parser.add_argument("--libc", choices=["glibc", "musl"], required=True)
    parser.add_argument("--store", required=True)
    parser.add_argument("--user", default="2000:2000", help="Logical guest account for ABI execution")
    FixtureExecutor.add_argument(parser)
    add_machine_entry_argument(parser)
    args = parser.parse_args()
    try:
        fixture_uid, fixture_gid = map(int, args.user.split(":"))
    except ValueError:
        parser.error("--user requires numeric UID:GID")
    tag = uuid.uuid4().hex
    files = [args.libc_build / ("md-" + name + "-fixture") for name in ["exec", "execfd", "process_image", "arguments", "ipc"]]
    files += [args.static_build / ("md-static-" + name) for name in ["exec", "pie", "exec-2m", "pie-2m"]]
    files += [args.build / "md-freestanding-fixture"]
    archive = args.build / ("abi-" + args.libc + "-" + tag + ".tar")
    def fixture_metadata(info):
        info.uid, info.gid = fixture_uid, fixture_gid
        info.uname = info.gname = ""
        info.mode = 0o755 if info.name.startswith("usr/bin/") else 0o644
        return info

    with tarfile.open(archive, "w") as output:
        for path in files:
            output.add(path, arcname="usr/bin/" + path.name, recursive=False, filter=fixture_metadata)
        output.add(Path(__file__).with_name("fixtures") / "md-guest-fixture",
                   arcname="etc/md-guest-fixture", recursive=False, filter=fixture_metadata)
    suite = Suite(args.build, args.build, executor=args.executor, machine_entry=args.machine_entry)
    result, command = suite.report, suite.command
    result.update(libc=args.libc, store=args.store, user=args.user, checks=[])
    mount = '/md-abi-' + tag
    root = suite.guest(args.store, '--user', '0:0')
    created = False
    try:
        result['bundle'] = suite.upload(archive, suite.base + '/fixtures.tar')
        command(root + ['--', '/bin/mkdir', mount])
        created = True
        command(root + ['--bind-ro', suite.base, mount, '--', '/bin/tar', '-xf', mount + '/fixtures.tar', '-C', '/'])
        for path in files:
            # EVENT_WAIT: console command completion; timeout terminates a hung fixture tree.
            arguments = ["/usr/bin/" + path.name]
            if path.name == "md-ipc-fixture": arguments += ["local", "/tmp/md-ipc-" + tag]
            value = command(suite.guest(args.store, '--user', args.user,
                '--deadline-seconds', '60', '--', *arguments), timeout=75)
            result["checks"].append({"fixture": path.name, "output": value})
            print(args.libc, path.name, value.strip(), flush=True)
        result["passed"] = True
    except Exception as error:
        result["failure"] = str(error)
        raise
    finally:
        try:
            if created:
                command(root + ['--', '/bin/rmdir', mount])
        finally:
            archive.unlink(missing_ok=True)
            try:
                command(['rm', '-rf', '--', suite.base], quiet=True)
            finally:
                suite.finish()


if __name__ == "__main__":
    main()
