#!/usr/bin/env python3
"""Run the opt-in guest executor fixture through existing authorized MCP services."""
import argparse
import importlib.util
import io
import json
from pathlib import Path
import shlex
import subprocess
import tarfile
import tomllib
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("build", type=Path)
    parser.add_argument("--config", type=Path, default=Path.home() / ".codex/config.toml")
    parser.add_argument("--server", default="magicdesk")
    parser.add_argument("--packages", action="store_true", help="Observe actual dpkg transactions and known shell limits")
    args = parser.parse_args()
    manifest = json.loads((args.build / "manifest.json").read_text())
    archive = args.build / Path(next(p["file"] for p in manifest["packages"] if p["name"] == "gzip")).name
    with tarfile.open(fileobj=io.BytesIO(subprocess.check_output(["dpkg-deb", "--fsys-tarfile", str(archive)]))) as package:
        package_links = [member for member in package.getmembers() if member.islnk()]
        uncompress = next(member for member in package.getmembers() if member.name.endswith("/uncompress"))
        gunzip = next(member for member in package.getmembers() if member.name.endswith("/gunzip"))
    repo = Path(__file__).resolve().parents[2]
    spec = importlib.util.spec_from_file_location("md_mcp", repo / "scripts/mcp-client.py")
    transport = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(transport)
    config = tomllib.loads(args.config.read_text())["mcp_servers"][args.server]
    client = transport.Client(config["url"], config["http_headers"]["Authorization"].removeprefix("Bearer "))
    state = client.call("get_state")
    if state.get("shell", {}).get("uid") != 2000:
        raise RuntimeError("Select shell access explicitly; this fixture never changes identity")
    session = client.call("console.open", {"directory": "/data/local/tmp"})["sessionId"]
    root = "/data/local/tmp/md-guest-lab-" + uuid.uuid4().hex
    results = {"device": state["device"], "app": state["app"], "manifest": manifest,
               "directory": root, "checks": []}

    def command(value):
        return client.call("console.execute", {"sessionId": session, "command": value})

    def check(name, value, code=0, contains=None, limitation=False):
        result = command(value)
        fragments = [] if contains is None else contains if isinstance(contains, list) else [contains]
        ok = result["exitCode"] == code and all(fragment in result["output"] for fragment in fragments)
        results["checks"].append({"name": name, "passed": ok, "limitation": limitation, "command": value,
                                  "exitCode": result["exitCode"], "output": result["output"],
                                  "fixtureLimitations": [line[6:] for line in result["output"].splitlines()
                                                         if line.startswith("LIMIT ")]})
        print(("LIMIT " if ok and limitation else "PASS " if ok else "FAIL ") + name + "\n" + result["output"], flush=True)
        if not ok:
            raise RuntimeError("Fixture check failed: " + name)

    try:
        check("create isolated shell-owned directory", "mkdir -m 700 " + shlex.quote(root))
        transport.upload(client, args.build / "bundle.tar.gz", root + "/bundle.tar.gz")
        check("extract without package installation", "tar -xzf " + root + "/bundle.tar.gz -C " + root)
        launch = ("env PATH=/usr/sbin:/usr/bin:/sbin:/bin HOME=/tmp TMPDIR=/tmp LANG=C "
                  + root + "/libmagicdesk_guest_supervisor.so " + root + "/libmagicdesk_guest_bootstrap.so " + root + "/rootfs ")
        check("Android shell identity", "id", contains=["uid=2000(shell)", "context=u:r:shell:s0"])
        check("kernel identity", "uname -a", contains="aarch64")
        check("page size observation", "getconf PAGE_SIZE")
        check("optional kernel capabilities do not disable ordinary shell", "timeout 30 " + root
              + "/md-capabilities-test " + root + "/libmagicdesk_guest_supervisor.so " + root + "/libmagicdesk_guest_bootstrap.so",
              contains="PASS capability isolation")
        # Copy outside the source first, then move inside the executor's root.
        # Relative dirfd operations outside that root remain deliberately rejected.
        check("prepare immutable rootfs import source", "cp -a " + root + "/rootfs " + root + "/prepared-rootfs")
        check("place import source inside laboratory root", "mv " + root + "/prepared-rootfs " + root + "/rootfs/prepared-rootfs")
        check("offline prepared rootfs import, rollback and source integrity",
              "timeout 180 " + launch + "/usr/bin/md-import-fixture /tmp/md-import-tests "
              + "/prepared-rootfs /tmp/imported-rootfs",
              contains=["PASS prepared Debian rootfs import:",
                        "PASS import: three deterministic SIGKILL boundaries",
                        "PASS offline import fixture (not execution from the imported namespace)"])
        namespace = ("env PATH=/usr/sbin:/usr/bin:/sbin:/bin HOME=/tmp TMPDIR=/tmp LANG=C "
                     + root + "/libmagicdesk_guest_run.so --store " + root + "/rootfs/tmp/imported-rootfs -- ")
        check("import independent second guest store", "timeout 180 " + root
              + "/libmagicdesk_guest_service.so --import " + root + "/rootfs/prepared-rootfs " + root + "/second-store",
              contains="Imported entries=")
        for store, marker in [(root + "/rootfs/tmp/imported-rootfs", "first"), (root + "/second-store", "second")]:
            check("distinct guest root " + marker, "timeout 30 " + root + "/libmagicdesk_guest_run.so --store "
                  + store + " -- /bin/sh -c " + shlex.quote("printf " + marker + " > /etc/md-environment"))
        check("namespace process-tree ownership and cancellation", "timeout 150 " + root
              + "/md-lifecycle-test " + root + "/libmagicdesk_guest_run.so " + root + "/rootfs/tmp/imported-rootfs "
              + root + "/second-store",
              contains=["PASS lifecycle: double-fork/setsid descendant uses namespace after root exit",
                        "PASS lifecycle: TERM escalation",
                        "PASS lifecycle: frontend SIGKILL",
                        "PASS lifecycle: namespace owner failure",
                        "PASS lifecycle: launch failures and final ECHILD"])
        check("stock Debian shell executing from inode namespace", "timeout 45 " + namespace + "/bin/dash -c "
              + shlex.quote("/bin/cat /etc/md-guest-fixture | /usr/bin/wc -c"), contains="12\n")
        check("namespace syscalls, shared inode semantics and fork/exec", "timeout 60 " + namespace
              + "/usr/bin/md-namespace-fixture",
              contains=["PASS namespace exec preserves cwd, real umask and open-unlinked FD",
                        "PASS namespace syscalls: atomic open, hard links, stat/statx, mmap/flock, symlinks and directory cursors"])
        for backend, prefix in [("direct", launch), ("namespace", namespace)]:
            check(backend + " proc descriptor aliases", "timeout 60 " + prefix
                  + "/usr/bin/md-proc-fixture /tmp/md-proc-" + backend + " " + backend,
                  contains=["PASS proc: file aliases", "PASS proc: posix_spawn", "PASS proc: renamed directory",
                            "PASS proc: native pipe descriptors", "PASS proc: inherited open-unlinked",
                            "PASS proc: deleted inode remains accessible"])
        check("native shell pathname socket creation control", "timeout 15 " + root
              + "/md-sockets-test probe " + root + "/rootfs", code=77,
              contains="LIMIT native shell denies pathname bind", limitation=True)
        for backend in ["direct", "namespace"]:
            check(backend + " abstract Unix sockets and descriptor exchange", "timeout 60 " + root
                  + "/md-sockets-test " + backend + " " + root + "/rootfs abstract",
                  contains=["PASS sockets: stream/seqpacket/datagram",
                            "PASS sockets: binary abstract names",
                            "PASS sockets: native permission/type failures",
                            "PASS sockets: descriptor counts unchanged"])
        check("namespace state survives a fresh service", "timeout 30 " + namespace
              + "/usr/bin/stat /tmp/ns-moved/child", contains="Size: 0")
        check("explicit Unix routes survive exec and environment replacement", "timeout 60 " + root
              + "/md-sockets-test routes " + root + "/rootfs",
              contains="PASS socket routes: independent connections, shell/exec/env-i, credentials, SCM_RIGHTS and shared mmap")
        check("namespace signal, stack and exec regressions", "timeout 90 " + namespace
              + "/usr/bin/md-exec-fixture", contains=["PASS posix_spawn file actions, cwd and full signal mask", "PASS 64 execs"])
        check("namespace path and descriptor extended attributes", "timeout 45 " + namespace
              + "/usr/bin/md-xattrs-fixture /tmp/md-ns-xattrs",
              contains="PASS xattrs: binary values, path/FD identity, flags, permissions, symlinks and errors")
        check("direct-backend extended attributes", "timeout 30 " + launch
              + "/usr/bin/md-xattrs-fixture /tmp/md-direct-xattrs native",
              contains="PASS xattrs: binary values, path/FD identity, flags, permissions, symlinks and errors")
        check("direct strict attribute copy retains SELinux denial", "timeout 30 " + launch
              + "/bin/cp --preserve=xattr /tmp/md-direct-xattrs/archive-source /tmp/md-direct-xattrs/copied",
              code=1, contains=["setting attribute 'security.selinux'", "Permission denied"], limitation=True)
        check("namespace strict attribute copy retains SELinux denial", "timeout 30 " + namespace
              + "/bin/cp --preserve=xattr /tmp/md-ns-xattrs/archive-source /tmp/md-ns-xattrs/copied",
              code=1, contains=["setting attribute 'security.selinux'", "Permission denied"], limitation=True)
        check("namespace copied attributes persist", "timeout 30 " + namespace
              + "/usr/bin/md-xattrs-fixture /tmp/md-ns-xattrs/copied verify", contains="PASS copied/archive xattrs")
        check("namespace coreutils archive-mode copy", "timeout 30 " + namespace
              + "/bin/cp -a /tmp/md-ns-xattrs/archive-source /tmp/md-ns-xattrs/copied-best-effort")
        check("namespace archive-mode copied attributes persist", "timeout 30 " + namespace
              + "/usr/bin/md-xattrs-fixture /tmp/md-ns-xattrs/copied-best-effort verify", contains="PASS copied/archive xattrs")
        check("namespace tar stores extended attributes", "timeout 30 " + namespace
              + "/bin/tar --xattrs --xattrs-include='user.*' -cf /tmp/md-xattrs.tar -C /tmp/md-ns-xattrs archive-source")
        check("namespace prepare archive extraction", "timeout 30 " + namespace + "/bin/mkdir /tmp/md-xattrs-out")
        check("namespace tar restores extended attributes", "timeout 30 " + namespace
              + "/bin/tar --xattrs --xattrs-include='user.*' -xf /tmp/md-xattrs.tar -C /tmp/md-xattrs-out")
        check("namespace archived attributes persist", "timeout 30 " + namespace
              + "/usr/bin/md-xattrs-fixture /tmp/md-xattrs-out/archive-source verify", contains="PASS copied/archive xattrs")
        if args.packages:
            check("prepare namespace dpkg database", "timeout 30 " + namespace + "/bin/mkdir -p /tmp/md-dpkg")
            check("namespace dpkg installation", "timeout 60 " + namespace
                  + "/usr/bin/dpkg --admindir=/tmp/md-dpkg --force-not-root --install /tmp/md-package-fixture.deb")
            check("namespace dpkg committed status", "timeout 30 " + namespace
                  + "/usr/bin/dpkg --admindir=/tmp/md-dpkg --status md-package-fixture",
                  contains=["Status: install ok installed", "Version: 1.0"])
            check("namespace dpkg upgrade", "timeout 60 " + namespace
                  + "/usr/bin/dpkg --admindir=/tmp/md-dpkg --force-not-root --install /tmp/md-package-update.deb")
            check("namespace upgraded database committed", "timeout 30 " + namespace
                  + "/usr/bin/dpkg --admindir=/tmp/md-dpkg --status md-package-fixture",
                  contains=["Status: install ok installed", "Version: 2.0"])
            check("namespace upgraded payload and maintainer script", "timeout 30 " + namespace
                  + "/bin/cat /usr/share/md-package-fixture/value /usr/share/md-package-fixture/configured",
                  contains=["package-value-v2\n", "configured-v2\n"])
            check("namespace writes do not modify direct rootfs", "timeout 30 " + launch
                  + "/bin/dash -c " + shlex.quote("test ! -e /usr/share/md-package-fixture"))
            check("namespace dpkg purge", "timeout 60 " + namespace
                  + "/usr/bin/dpkg --admindir=/tmp/md-dpkg --force-not-root --purge md-package-fixture")
            check("namespace package absent from committed database", "timeout 30 " + namespace
                  + "/usr/bin/dpkg --admindir=/tmp/md-dpkg --status md-package-fixture", code=1,
                  contains="is not installed")
            check("namespace removed payload and postrm marker", "timeout 30 " + namespace
                  + "/bin/dash -c " + shlex.quote("test ! -e /usr/share/md-package-fixture"))
            check("namespace official gzip archive extraction", "timeout 60 " + namespace
                  + "/usr/bin/dpkg-deb --extract /tmp/md-hardlinks.deb /tmp/md-gzip")
            one = shlex.quote("/tmp/md-gzip/" + gunzip.name.removeprefix("./"))
            two = shlex.quote("/tmp/md-gzip/" + uncompress.name.removeprefix("./"))
            identity = ('a=$(/usr/bin/stat -c %d:%i ' + one + '); b=$(/usr/bin/stat -c %d:%i ' + two + '); test "$a" = "$b"'
                if uncompress.islnk() else 'test -L ' + two + '; test "$(readlink ' + two + ')" = ' + shlex.quote(uncompress.linkname))
            assert uncompress.islnk() or uncompress.issym()
            check("namespace archive preserves declared link type", "timeout 30 " + namespace + "/bin/dash -c "
                  + shlex.quote("set -eu; test -f " + one + "; " + identity))
        check("Debian identity", "timeout 20 " + launch + "/usr/bin/id -u", contains="2000\n")
        check("Debian shell and child cat", "timeout 20 " + launch + "/bin/dash -c "
              + shlex.quote("/bin/cat /etc/md-guest-fixture"), contains="guest-value\n")
        check("pipeline and fork/exec", "timeout 20 " + launch + "/bin/dash -c "
              + shlex.quote("/bin/cat /etc/md-guest-fixture | /usr/bin/wc -c"), contains="12\n")
        check("guest regression fixture", "timeout 30 " + launch + "/usr/bin/md-fixture",
              contains="PASS inherited Unix socket")
        check("exec inheritance and signal/pointer regressions", "timeout 30 " + launch + "/usr/bin/md-exec-fixture",
              contains="PASS 64 execs")
        check("file metadata and notifications", "timeout 20 " + launch + "/usr/bin/md-files-fixture",
              contains="PASS atomic rename flags and inotify namespace mapping")
        check("isolated inode model, descriptor coherence and crash recovery", "timeout 45 " + launch
              + "/usr/bin/md-inodes-fixture /tmp/md-inode-store",
              contains=["PASS 11 deterministic SIGKILL recovery boundaries",
                        "PASS 12 hierarchy SIGKILL boundaries",
                    "PASS inode-store fixture (hierarchical model, not guest syscall integration)"])
        check("filesystem service transport, native FD transfer and failure boundaries", "timeout 60 " + launch
              + "/usr/bin/md-rpc-fixture /tmp/md-fs-service",
              contains=["PASS nested signal-handler RPC during pending outer call; errno unchanged",
                        "PASS directory RPC: paging, shared dup/fork/exec cursor, service restart, cookies and concurrent readers",
                        "PASS server death after commit is UNCONFIRMED, no replay; namespace recovered",
                        "PASS filesystem service fixture (explicit RPC, not guest syscall integration)"])
        check("statx translation inside coreutils", "timeout 20 " + launch
              + "/usr/bin/stat /etc/md-guest-fixture", contains="Size: 12")
        check("shell vfork child", "timeout 20 " + launch + "/bin/dash /usr/bin/md-script", code=23,
              contains="guest-value\n")
        check("host hard-link restriction control", "ln " + root + "/rootfs/etc/md-guest-fixture "
              + root + "/host-hard-link", code=1, contains="Permission denied", limitation=True)
        if args.packages:
            version = next(p["version"] for p in manifest["packages"] if p["name"] == "dpkg").split(":")[-1]
            check("Debian package manager identity", "timeout 20 " + launch + "/usr/bin/dpkg --version",
                  contains=version + " (arm64)")
            check("package metadata and decompression", "timeout 20 " + launch
                  + "/usr/bin/dpkg-deb --info /tmp/md-package-fixture.deb", contains="Package: md-package-fixture")
            check("prepare isolated dpkg database", "timeout 20 " + launch + "/bin/mkdir -p /tmp/md-dpkg")
            check("dpkg setting transaction", "timeout 20 " + launch
                  + "/usr/bin/dpkg --admindir=/tmp/md-dpkg --force-not-root --add-architecture armhf")
            check("dpkg database backup needs hard links", "timeout 20 " + launch
                  + "/usr/bin/dpkg --admindir=/tmp/md-dpkg --force-not-root --install /tmp/md-package-fixture.deb",
                  code=2, contains="error creating new backup file '/tmp/md-dpkg/status-old': Permission denied", limitation=True)
            check("postinst executed despite failed database commit", "timeout 20 " + launch
                  + "/bin/cat /usr/share/md-package-fixture/configured", contains="configured\n", limitation=True)
            check("dpkg transaction remains uncommitted", "timeout 20 " + launch + "/bin/dash -c "
                  + shlex.quote("test ! -s /tmp/md-dpkg/status && test -s /tmp/md-dpkg/status-new"), limitation=True)
            check("official gzip archive retains declared link semantics", "timeout 20 " + launch
                  + "/usr/bin/dpkg-deb --extract /tmp/md-hardlinks.deb /tmp/md-hardlinks",
                  code=2 if package_links else 0, contains="Cannot hard link" if package_links else None,
                  limitation=bool(package_links))
        check("missing executable", "timeout 20 " + launch + "/usr/bin/not-installed", code=127)
        check("child exit status", "timeout 20 " + launch + "/bin/dash -c 'exit 42'", code=42)
        # Cancellation bound for a deliberately nonterminating guest, not a settling delay.
        check("bounded cancellation of running guest", "timeout -s TERM 1 " + launch
              + "/bin/dash -c 'while :; do :; done'", code=124)
        check("executor remains usable after cancellation", "timeout 20 " + launch
              + "/bin/cat /etc/md-guest-fixture", contains="guest-value\n")
    finally:
        try:
            client.call("console.close", {"sessionId": session})
        finally:
            (args.build / "device-results.json").write_text(json.dumps(results, indent=2) + "\n")
            print("Retained fixture files: " + root)


if __name__ == "__main__":
    main()
