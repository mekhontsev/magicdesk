#!/usr/bin/env python3
"""Cross-build test-owned Linux Mesa libraries; never install a driver into Android."""
import argparse
from pathlib import Path
import subprocess
import json
import hashlib
import shutil


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("source", type=Path)
parser.add_argument("sysroot", type=Path)
parser.add_argument("output", type=Path)
parser.add_argument("--driver", choices=["turnip", "zink"], default="turnip")
parser.add_argument("--wayland-zink", action="store_true",
                    help="Apply the explicit Mesa 26.2.3 non-DRM EGL test patch to a private source copy")
args = parser.parse_args()
source, root, output = (p.resolve() for p in (args.source, args.sysroot, args.output))
output.mkdir(parents=True, exist_ok=True)
origin = source
patch_id = None
if args.wayland_zink:
    if args.driver != "zink" or (source / "VERSION").read_text().strip() != "26.2.3":
        parser.error("--wayland-zink requires the Zink driver and Mesa 26.2.3")
    patch = Path(__file__).resolve().parents[2] / "wayland-runtime/tests/mesa-wayland-zink.patch"
    patch_id = hashlib.sha256(patch.read_bytes()).hexdigest()
    source = output / "patched-source"
    receipt = output / "source-patch.json"
    identity = {"source": str(origin), "patch": str(patch), "sha256": patch_id}
    if source.exists():
        if not receipt.exists() or json.loads(receipt.read_text()) != identity:
            parser.error("Patched source identity differs; choose a new output directory")
    else:
        shutil.copytree(origin, source)
        subprocess.run(["patch", "--batch", "--forward", "-p1", "-d", str(source), "-i", str(patch)], check=True)
        receipt.write_text(json.dumps(identity, indent=2) + "\n")
common = ["--target=aarch64-linux-gnu", "--sysroot=" + str(root),
          "--gcc-toolchain=" + str(root / "usr")]
cross = output / "linux-aarch64.ini"
cross.write_text("\n".join([
    "[binaries]",
    "c = " + repr(["clang", *common]),
    "cpp = " + repr(["clang++", *common, "-stdlib=libstdc++"]),
    "ar = 'llvm-ar'", "strip = 'llvm-strip'", "pkg-config = 'pkg-config'",
    "[host_machine]", "system = 'linux'", "cpu_family = 'aarch64'", "cpu = 'aarch64'", "endian = 'little'",
    "[properties]", "needs_exe_wrapper = true", "sys_root = " + repr(str(root)),
    "pkg_config_libdir = " + repr([str(root / "usr/lib/aarch64-linux-gnu/pkgconfig"), str(root / "usr/share/pkgconfig")]),
    "[built-in options]", "prefix = '/opt/md-gpu'", "libdir = 'lib'", "buildtype = 'release'",
    "c_link_args = ['-fuse-ld=lld']", "cpp_link_args = ['-fuse-ld=lld']",
    "c_args = " + repr(["-isystem", str(root / "usr/include/aarch64-linux-gnu")]),
    "cpp_args = " + repr(["-isystem", str(root / "usr/include/aarch64-linux-gnu")]),
    "",
]))
build = output / "build"
options = ["--wrap-mode=nodownload", "-Dplatforms=x11,wayland", "-Dllvm=disabled",
           "-Dxmlconfig=disabled", "-Dbuild-tests=false", "-Dgallium-rusticl=false"]
if args.driver == "turnip":
    # Wayland DMA-BUF WSI uses Mesa's DRM image allocator even with a KGSL GPU.
    options += ["-Dvulkan-drivers=freedreno", "-Dfreedreno-kmds=msm,kgsl", "-Dgallium-drivers=[]",
                "-Dopengl=false", "-Dglx=disabled", "-Degl=disabled", "-Dgbm=disabled"]
    targets = ["src/freedreno/vulkan/libvulkan_freedreno.so"]
else:
    options += ["-Dvulkan-drivers=[]", "-Dgallium-drivers=zink,softpipe", "-Dopengl=true",
                "-Dglx=dri", "-Degl=enabled", "-Dgbm=enabled", "-Dglvnd=enabled", "-Dgallium-va=disabled"]
    targets = []
if not (build / "build.ninja").exists():
    subprocess.run(["meson", "setup", str(build), str(source), "--cross-file", str(cross), *options], check=True)
else:
    subprocess.run(["meson", "setup", "--reconfigure", str(build), str(source), *options], check=True)
(output / "configuration.json").write_text(json.dumps({"source": str(source), "sysroot": str(root),
    "sourceOrigin": str(origin), "patchSha256": patch_id,
    "driver": args.driver, "options": options}, indent=2) + "\n")
subprocess.run(["ninja", "-C", str(build), "-j2", *targets], check=True)
if args.driver == "zink":
    subprocess.run(["meson", "install", "-C", str(build), "--no-rebuild", "--destdir", str(output / "install")], check=True)
