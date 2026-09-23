from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


VERIFY = Path(__file__).resolve().parents[2] / "wayland-runtime/native-deps/verify-runtime.cmake"
ANDROID = """ELF Header:
  Class: ELF64
  Type: DYN (Shared object file)
  Machine: AArch64
Dynamic section:
  (NEEDED) Shared library: [libc.so]
  (NEEDED) Shared library: [libm.so]
  (NEEDED) Shared library: [libdl.so]
"""


@unittest.skipUnless(shutil.which("cmake"), "CMake required")
class WaylandBuildTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which("pkg-config") and shutil.which("cc"), "Host C toolchain required")
    def test_private_archives_remain_visible_with_ndk_root_only_lookup(self):
        source = VERIFY.parent.parent / "src/main/cpp"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            prefix = root / "android-deps"
            pc = prefix / "lib/pkgconfig"
            pc.mkdir(parents=True)
            (prefix / "include").mkdir()
            protocol = prefix / "share/wayland-protocols/stable/xdg-shell"
            protocol.mkdir(parents=True)
            (protocol / "xdg-shell.xml").write_text("", encoding="utf-8")
            wlr_protocol = root / "wlroots/protocol"
            wlr_protocol.mkdir(parents=True)
            (wlr_protocol / "wlr-layer-shell-unstable-v1.xml").write_text("", encoding="utf-8")
            libraries = {"wlroots-0.18": "0.18.2", "wayland-server": "1.25.0",
                         "wayland-client": "1.25.0", "xkbcommon": "1.13.2"}
            for name, version in libraries.items():
                (prefix / f"lib/lib{name}.a").write_bytes(b"!<arch>\n")
                (pc / f"{name}.pc").write_text(
                    f"prefix={prefix.as_posix()}\nName: {name}\nDescription: fixture\n"
                    f"Version: {version}\nLibs: -L${{prefix}}/lib -l{name}\n"
                    "Cflags: -I${prefix}/include\n", encoding="utf-8")
            (pc / "wayland-protocols.pc").write_text(
                f"pkgdatadir={prefix.as_posix()}/share/wayland-protocols\n"
                "Name: wayland-protocols\nDescription: fixture\nVersion: 1.49\n", encoding="utf-8")
            build = root / "build"
            result = subprocess.run([
                "cmake", "-S", str(source), "-B", str(build),
                f"-DMDW_DEPENDENCY_PREFIX={prefix}", f"-DCMAKE_FIND_ROOT_PATH={root / 'sysroot'}",
                f"-DMDW_WLR_PROTOCOL_DIR={wlr_protocol}",
                "-DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=ONLY", f"-DWAYLAND_SCANNER={sys.executable}"],
                capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            cache = (build / "CMakeCache.txt").read_text(encoding="utf-8")
            for name in libraries:
                self.assertIn(f"/lib/lib{name}.a", cache)
            self.assertNotIn("-NOTFOUND", "\n".join(
                line for line in cache.splitlines() if line.startswith("pkgcfg_lib_")))

    def verify(self, dump, exit_code=0):
        with tempfile.TemporaryDirectory() as directory:
            reader = Path(directory) / "readelf.py"
            reader.write_text(f"print({dump!r})\nraise SystemExit({exit_code})\n", encoding="utf-8")
            return subprocess.run([
                "cmake", "-DLIBRARY=fixture.so", f"-DREADELF={sys.executable};{reader}",
                "-P", str(VERIFY)], capture_output=True, text=True)

    def test_accepts_standalone_android_elf(self):
        result = self.verify(ANDROID)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_rejects_loader_search_paths(self):
        for tag in ("RPATH", "RUNPATH"):
            with self.subTest(tag=tag):
                result = self.verify(ANDROID + f"  ({tag}) Library runpath: [/local/build/lib]\n")
                self.assertNotEqual(0, result.returncode)
                self.assertIn("loader search path", result.stderr)

    def test_rejects_termux_or_linux_libraries(self):
        for name in ("libwayland-server.so", "libc.so.6", "libandroid-shmem.so"):
            with self.subTest(name=name):
                result = self.verify(ANDROID + f"  (NEEDED) Shared library: [{name}]\n")
                self.assertNotEqual(0, result.returncode)
                self.assertIn("Non-system runtime dependency", result.stderr)

    def test_rejects_wrong_abi_and_static_objects(self):
        for old, new in (("AArch64", "X86-64"), ("ELF64", "ELF32"), ("DYN", "REL")):
            with self.subTest(value=new):
                result = self.verify(ANDROID.replace(old, new))
                self.assertNotEqual(0, result.returncode)
                self.assertIn("ARM64 Android", result.stderr)

    def test_rejects_missing_dependencies_and_unreadable_files(self):
        self.assertNotEqual(0, self.verify(ANDROID.split("Dynamic section:")[0]).returncode)
        self.assertNotEqual(0, self.verify(ANDROID, exit_code=1).returncode)


if __name__ == "__main__":
    unittest.main()
