from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile


VERIFY = Path(__file__).resolve().parents[1] / "verify-apks.sh"
HELPERS = ("uinput_bridge", "pty_bridge", "mksh", "service_launcher", "process_signal", "guest_files",
           "command_client", "command_bridge",
           "wayland_executor", "wayland_client", "graphics_host")
SHROOT_HELPERS = ("guest_bootstrap", "guest_supervisor", "guest_run", "guest_service", "guest_image", "cli")


class VerifyApksTest(unittest.TestCase):
    def verify(self, *extra_entries, omit_helper=None, pty_bytes=0, addon_entries=None):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "fixture.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                for helper in HELPERS:
                    if helper == omit_helper:
                        continue
                    archive.writestr(f"lib/arm64-v8a/libmagicdesk_{helper}.so", bytes(pty_bytes) if helper == "pty_bridge" else b"")
                archive.writestr("lib/arm64-v8a/libXlorie.so", b"")
                for helper in SHROOT_HELPERS:
                    if helper != omit_helper:
                        archive.writestr(f"lib/arm64-v8a/libshroot_{helper}.so", b"")
                for entry in extra_entries:
                    archive.writestr(entry, b"")
            command = ["sh", str(VERIFY), str(apk)]
            if addon_entries is not None:
                addon = Path(directory) / "kernel-fixes.apk"
                module = "res/raw/dp_mode_reset.ko"
                with zipfile.ZipFile(addon, "w") as archive:
                    archive.write(VERIFY.parents[1] / "kernel-fixes/src/main" / module, module)
                    for entry in addon_entries:
                        archive.writestr(entry, b"")
                command.append(str(addon))
            return subprocess.run(command, capture_output=True, text=True)

    def test_accepts_reviewed_kernel_addon(self):
        result = self.verify(addon_entries=[])
        self.assertEqual(0, result.returncode, result.stderr)

    def test_rejects_helpers_in_kernel_addon(self):
        for library in ("libmagicdesk_pty_bridge.so", "libshroot_guest_run.so"):
            with self.subTest(library=library):
                result = self.verify(addon_entries=[f"lib/arm64-v8a/{library}"])
                self.assertEqual(1, result.returncode, result.stderr)
                self.assertIn("must not contain a shell helper", result.stderr)

    def test_accepts_arm64_apk(self):
        result = self.verify("assets/licenses/x86_64.txt")
        self.assertEqual(0, result.returncode, result.stderr)

    def test_rejects_other_native_abis(self):
        for abi in ("x86_64", "x86", "armeabi-v7a"):
            with self.subTest(abi=abi):
                result = self.verify(f"lib/{abi}/libXlorie.so")
                self.assertNotEqual(0, result.returncode)
                self.assertIn(f"unsupported native ABIs: {abi}", result.stderr)

    def test_rejects_missing_helpers(self):
        for helper in HELPERS:
            with self.subTest(helper=helper):
                result = self.verify(omit_helper=helper)
                self.assertNotEqual(0, result.returncode)
                self.assertIn(f"Core APK is missing libmagicdesk_{helper}.so", result.stderr)

    def test_rejects_missing_shroot_helpers(self):
        for helper in SHROOT_HELPERS:
            with self.subTest(helper=helper):
                result = self.verify(omit_helper=helper)
                self.assertNotEqual(0, result.returncode)
                self.assertIn(f"Core APK is missing libshroot_{helper}.so", result.stderr)

    def test_helper_size_is_not_a_binder_transfer_limit(self):
        result = self.verify(pty_bytes=512 * 1024 + 1)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_rejects_stale_engine_helpers_but_keeps_application_helpers(self):
        for helper in ('shroot', 'guest_run', 'guest_supervisor', 'guest_image'):
            with self.subTest(helper=helper):
                result = self.verify(f'lib/arm64-v8a/libmagicdesk_{helper}.so')
                self.assertNotEqual(0, result.returncode)
                self.assertIn('retired Shroot helper names', result.stderr)


if __name__ == "__main__":
    unittest.main()
