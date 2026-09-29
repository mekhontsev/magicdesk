#!/usr/bin/env python3
"""Cross-build a test-owned Linux Turnip ICD; never install a driver into Android."""
import argparse
from pathlib import Path
import subprocess


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("source", type=Path)
parser.add_argument("sysroot", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
source, root, output = (p.resolve() for p in (args.source, args.sysroot, args.output))
output.mkdir(parents=True, exist_ok=True)
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
if not (build / "build.ninja").exists():
    subprocess.run(["meson", "setup", str(build), str(source), "--cross-file", str(cross),
        "--wrap-mode=nodownload", "-Dplatforms=x11,wayland", "-Dvulkan-drivers=freedreno",
        # Wayland DMA-BUF WSI uses Mesa's DRM image allocator even with a KGSL GPU.
        "-Dfreedreno-kmds=msm,kgsl", "-Dgallium-drivers=[]", "-Dopengl=false", "-Dglx=disabled",
        "-Degl=disabled", "-Dgbm=disabled", "-Dllvm=disabled", "-Dxmlconfig=disabled",
        "-Dbuild-tests=false", "-Dgallium-rusticl=false"], check=True)
subprocess.run(["ninja", "-C", str(build), "-j2", "src/freedreno/vulkan/libvulkan_freedreno.so"], check=True)
