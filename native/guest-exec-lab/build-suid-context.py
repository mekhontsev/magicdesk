#!/usr/bin/env python3
"""Build white-box controls of Chromium's unmodified sandbox helper."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import urllib.request

VERSION = "154.0.8037.57"
FILES = {
    "LICENSE": "368cca1106be99d39ecd32a38d8305585d802a475effb66380b91ffc9bcf709b",
    "sandbox/linux/suid/sandbox.c": "525f14566d1f0042315b589a87f750fc0f3bab940f42df0bc29c94be79d9ccea",
    "sandbox/linux/suid/common/sandbox.h": "d4b0e77505b21e36dedf9050473cb9419741b03c3c0595c372a8d97ef1e31143",
    "sandbox/linux/suid/process_util.h": "71e59f6f5a6e85518bc50326d5ed8fa01be4056762e1aa2dda5cf9fb31b79cee",
    "sandbox/linux/suid/process_util_linux.c": "10d1542bfbd9ba2985e413ce8a5f6ce3848f8e86fc2546f476b023ad2616f77b",
    "sandbox/linux/suid/common/suid_unsafe_environment_variables.h": "17ab30a707116f005f7779ec80f6611e6ff96654aa36c101f03cf55c8664b52c",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("build", type=Path)
    args = parser.parse_args()
    args.build.mkdir(parents=True, exist_ok=True)
    vendor = args.build / ("chromium-" + VERSION)
    hashes = {}
    for name in FILES:
        path = vendor / name
        if not path.exists():
            with urllib.request.urlopen("https://raw.githubusercontent.com/chromium/chromium/"
                                        + VERSION + "/" + name, timeout=30) as response:
                data = response.read()
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        hashes[name] = hashlib.sha256(path.read_bytes()).hexdigest()
        if hashes[name] != FILES[name]:
            raise ValueError("Chromium source checksum mismatch: " + name)
    (args.build / "suid-source.json").write_text(json.dumps({"version": VERSION, "sha256": hashes}, indent=2) + "\n")
    src = Path(__file__).resolve().parent
    runtime = src.parent / "guest-runtime/src"
    subprocess.run([os.environ.get("CC", "clang"), "--target=aarch64-linux-android34",
                    "-fno-termux-rpath", "-static", "-DMD_NO_START", "-DMD_USE_LIBC",
                    "-std=gnu17", "-O2", "-Wall", "-Wextra", "-Werror",
                    "-ffunction-sections", "-fdata-sections", "-Wl,--gc-sections",
                    "-I" + str(vendor), str(src / "test_suid_context.c"), str(src / "test_hybrid_copy.S"),
                    str(src / "test_suid_client.S"),
                    str(vendor / "sandbox/linux/suid/process_util_linux.c"),
                    *[str(runtime / f) for f in ("event_wait.c", "raw.c", "memory.c", "raw.S")],
                    "-o", str(args.build / "md-suid-context-test")], check=True)


if __name__ == "__main__":
    main()
