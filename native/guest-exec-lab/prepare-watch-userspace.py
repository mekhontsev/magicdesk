#!/usr/bin/env python3
"""Package a bounded GIO/D-Bus fixture from an already verified Debian rootfs."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("rootfs", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    root = args.rootfs.resolve()
    pending = ["usr/bin/gio", "usr/bin/dbus-run-session", "usr/bin/dbus-daemon", "usr/bin/dbus-send", "bin/sh"]
    files = {}
    while pending:
        name = pending.pop()
        if name in files:
            continue
        source = (root / name).resolve(strict=True)
        assert source.is_relative_to(root), source
        data = source.read_bytes()
        files[name] = data
        info = json.loads(subprocess.check_output(["llvm-readobj", "--needed-libs", "--elf-output-style=JSON", str(source)]))
        for library in info[0]["NeededLibraries"]:
            candidates = ["lib/aarch64-linux-gnu/" + library, "usr/lib/aarch64-linux-gnu/" + library, "lib/" + library]
            dependency = next((path for path in candidates if (root / path).is_file()), None)
            assert dependency, library
            pending.append(dependency)
    files["lib/ld-linux-aarch64.so.1"] = (root / "lib/ld-linux-aarch64.so.1").read_bytes()
    files["usr/share/dbus-1/session.conf"] = (root / "usr/share/dbus-1/session.conf").read_bytes()
    files["etc/passwd"] = b"shell:x:2000:2000:Guest fixture:/tmp:/bin/sh\n"
    files["etc/group"] = b"shell:x:2000:\n"
    files["etc/nsswitch.conf"] = b"passwd: files\ngroup: files\nhosts: files dns\n"
    files["etc/machine-id"] = (uuid.uuid4().hex + "\n").encode()
    directories = {"tmp", "etc/dbus-1/session.d", "usr/share/dbus-1/session.d", "usr/share/dbus-1/services"}
    for name in files:
        directories.update(str(parent) for parent in Path(name).parents if str(parent) != ".")
    with tarfile.open(args.output, "w:gz") as archive:
        for name in sorted(directories):
            entry = tarfile.TarInfo(name)
            entry.type = tarfile.DIRTYPE
            entry.mode = 0o755
            archive.addfile(entry)
        for name, data in sorted(files.items()):
            entry = tarfile.TarInfo(name)
            entry.mode = 0o755 if data.startswith(b"\x7fELF") else 0o644
            entry.size = len(data)
            archive.addfile(entry, io.BytesIO(data))
    print(json.dumps({"archive": str(args.output), "sha256": hashlib.sha256(args.output.read_bytes()).hexdigest(),
                      "entries": len(files), "bytes": sum(map(len, files.values()))}))


if __name__ == "__main__":
    main()
