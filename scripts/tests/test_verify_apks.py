from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile


VERIFY = Path(__file__).resolve().parents[1] / "verify-apks.sh"
HELPERS = ("uinput_bridge", "pty_bridge", "service_launcher", "process_signal", "guest_files",
           "wayland_executor", "wayland_client", "wayland_host")


class VerifyApksTest(unittest.TestCase):
    def verify(self, *extra_entries):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "fixture.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                for helper in HELPERS:
                    archive.writestr(f"lib/arm64-v8a/libmagicdesk_{helper}.so", b"")
                archive.writestr("lib/arm64-v8a/libXlorie.so", b"")
                for entry in extra_entries:
                    archive.writestr(entry, b"")
            return subprocess.run(["sh", str(VERIFY), str(apk)],
                                  capture_output=True, text=True)

    def test_accepts_arm64_apk(self):
        result = self.verify("assets/licenses/x86_64.txt")
        self.assertEqual(0, result.returncode, result.stderr)

    def test_rejects_other_native_abis(self):
        for abi in ("x86_64", "x86", "armeabi-v7a"):
            with self.subTest(abi=abi):
                result = self.verify(f"lib/{abi}/libXlorie.so")
                self.assertNotEqual(0, result.returncode)
                self.assertIn(f"unsupported native ABIs: {abi}", result.stderr)


if __name__ == "__main__":
    unittest.main()
