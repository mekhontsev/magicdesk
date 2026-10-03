"""Installer orchestration without modifying the host or downloading packages."""
import errno
import json
import os
from pathlib import Path
import selectors
import shutil
import subprocess
import sys
import tempfile
import time
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "install_linux.sh"


class InstallLinuxTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="magicdesk-install-")
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.log = self.directory / "calls.jsonl"
        self.payload = self.directory / "guest.sh"
        stub = self.directory / "magicdesk-guest"
        stub.write_text("#!" + sys.executable + r'''
import json, os, sys
from pathlib import Path
if Path(sys.argv[0]).name == "id":
    print(os.environ.get("TEST_UID", "2000"))
    sys.exit(0)
args = sys.argv[1:]
with open(os.environ["TEST_LOG"], "a") as log:
    log.write(json.dumps(args) + "\n")
stage = args[0]
if stage == "exec":
    stage = "setup" if "-s" in args else "detect" if len(args) == 8 else "validate"
    if stage == "detect":
        print(os.environ.get("TEST_DISTRO", "debian"))
    if stage == "setup":
        payload = sys.stdin.read()
        if args[-4] in ("turnip", "software"):
            stage = "graphics"
        Path(os.environ["TEST_PAYLOAD"] + (".gpu" if stage == "graphics" else "")).write_text(payload)
if os.environ.get("TEST_FAIL") == stage:
    print("test failure: " + stage, file=sys.stderr)
    sys.exit(23)
''')
        stub.chmod(0o755)
        shutil.copyfile(stub, self.directory / "id")
        (self.directory / "id").chmod(0o755)
        self.environment = dict(os.environ, PATH=str(self.directory) + os.pathsep + os.environ["PATH"],
                                TEST_LOG=str(self.log), TEST_PAYLOAD=str(self.payload))

    def run_script(self, *args, **environment):
        # EVENT_WAIT: installer exit; timeout fails a stuck subprocess.
        return subprocess.run(["sh", str(SCRIPT), *args], input="", text=True, capture_output=True,
                              env=dict(self.environment, **environment), timeout=15)

    def calls(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def test_apps_install_uses_existing_commands_and_keeps_guest_setup_on_stdin(self):
        result = self.run_script("--yes", "--name", "work")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["--probe"], self.calls()[0])
        self.assertEqual(["install", "debian:trixie-slim", "--name", "work", "--dns", "system"], self.calls()[1])
        self.assertEqual(["dns", "work", "system", "--replace"], self.calls()[2])
        self.assertEqual(["exec", "work", "--user", "root", "--env", "DEBIAN_FRONTEND=noninteractive",
                          "--", "/bin/sh", "-s", "--", "apps", "both", "keep", "keep", "", "basic", "keep", "keep", ""], self.calls()[-1])
        self.assertIn("Linux is ready: work", result.stdout)
        payload = self.payload.read_text()
        self.assertIn('apt-get install -y --no-install-recommends "$@" </dev/null', payload)
        self.assertIn("X-MagicDesk-Graphics=wayland", payload)
        self.assertIn("X-MagicDesk-GraphicsMode=desktop", payload)
        self.assertIn("printf 'unset LC_ALL\\nexport LANG=%s\\n'", payload)
        self.assertIn('path-include /usr/share/locale/*', payload)
        self.assertNotIn("/data/local/tmp", payload)
        self.assertEqual(0, subprocess.run(["sh", "-n", str(self.payload)]).returncode)

    def test_xfce_profile_and_root_executor(self):
        result = self.run_script("--yes", "--gui", "xfce", TEST_UID="0")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("xfce", self.calls()[-1][-9])

    def test_resume_validates_without_reinstalling(self):
        result = self.run_script("--yes", "--name", "existing", "--resume")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["exec", "--probe", "exec", "exec"], [args[0] for args in self.calls()])
        self.assertEqual(["sh", "debian", "13"], self.calls()[2][-3:])
        self.assertEqual(["keep", "both", "keep", "keep", "", "keep", "keep", "keep", ""], self.calls()[-1][-9:])

    def test_distribution_profiles_share_setup_and_selected_image(self):
        images = {"debian": "debian:trixie-slim", "ubuntu": "ubuntu:24.04", "alpine": "alpine:3.23",
                  "fedora": "registry.fedoraproject.org/fedora:44", "arch": "menci/archlinuxarm:base"}
        for distro, source in images.items():
            for gui in ("none", "apps", "xfce", "weston"):
                with self.subTest(distro=distro, gui=gui):
                    self.log.unlink(missing_ok=True)
                    result = self.run_script("--yes", "--distro", distro, "--gui", gui)
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertEqual(["install", source, "--name", distro, "--dns", "system"], self.calls()[1])
                    self.assertEqual(gui, self.calls()[-1][-9])
                    self.assertEqual(0, subprocess.run(["sh", "-n", str(self.payload)]).returncode)

    def test_settings_are_literal_arguments_and_additional_packages_accumulate(self):
        result = self.run_script("--yes", "--distro", "ubuntu", "--protocol", "wayland", "--locale", "ru_RU.UTF-8",
                                 "--timezone", "Asia/Krasnoyarsk", "--create-user", "developer", "--fonts", "cjk",
                                 "--cache", "clean", "--package", "git", "--package", "gdb")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["apps", "wayland", "ru_RU.UTF-8", "Asia/Krasnoyarsk", "developer", "cjk", "clean", "keep", "git gdb"],
                         self.calls()[-1][-9:])
        self.assertIn("--user developer", result.stdout)
        self.assertIn("--bind", result.stdout)
        self.assertIn("--magicdesk", result.stdout)

    def test_resume_detects_distribution_but_rejects_mismatch(self):
        result = self.run_script("--yes", "--name", "work", "--resume", TEST_DISTRO="alpine")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["sh", "alpine", "3.23"], self.calls()[2][-3:])
        self.log.unlink()
        result = self.run_script("--yes", "--name", "work", "--resume", "--distro", "debian", TEST_DISTRO="alpine")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(1, len(self.calls()))
        self.assertFalse("-s" in self.calls()[0])

    def test_invalid_settings_fail_before_any_environment_call(self):
        for args in [("--resume",), ("--distro", "unknown"), ("--timezone", "../etc"),
                     ("--timezone", "/tmp"), ("--timezone", "UTC;id"), ("--locale", "$(id)"),
                     ("--create-user", "root"), ("--create-user", "--root"), ("--package", "-y"),
                     ("--package", "curl;id"), ("--package", "../pkg"), ("--protocol", "auto"),
                     ("--cache", "all"), ("--arch-sandbox", "disable-filesystem"),
                     ("--gui", "none", "--gpu", "turnip")]:
            with self.subTest(args=args):
                result = self.run_script("--yes", *args)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual([], self.calls())

    def test_arch_sandbox_opt_out_is_explicit_and_does_not_disable_signature_checks(self):
        result = self.run_script("--yes", "--distro", "arch", "--arch-sandbox", "disable-filesystem")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("WARNING", result.stdout)
        self.assertEqual("disable-filesystem", self.calls()[-1][-2])
        self.assertIn("--disable-sandbox-filesystem", self.payload.read_text())
        self.assertNotIn("SigLevel", self.payload.read_text())

    def test_list_is_read_only_and_names_all_sources(self):
        result = self.run_script("--list", TEST_UID="10001")
        self.assertEqual(0, result.returncode)
        self.assertIn("menci/archlinuxarm:base (community)", result.stdout)
        self.assertIn("registry.fedoraproject.org/fedora:44", result.stdout)
        self.assertEqual([], self.calls())

    def test_dns_is_explicit_and_resume_does_not_replace_it(self):
        result = self.run_script("--yes", "--dns", "192.0.2.1,192.0.2.2")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["--dns", "192.0.2.1,192.0.2.2"], self.calls()[1][-2:])
        self.assertEqual(["dns", "debian", "192.0.2.1,192.0.2.2", "--replace"], self.calls()[2])
        self.log.unlink()
        result = self.run_script("--yes", "--name", "work", "--resume", "--dns", "192.0.2.1")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], self.calls())
        result = self.run_script("--yes", "--dns", "preserve")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("dns", [a[0] for a in self.calls()])

    def test_failure_is_not_reported_as_success_or_followed_by_more_work(self):
        for stage, count in [("--probe", 1), ("install", 2), ("dns", 3), ("validate", 4), ("setup", 5)]:
            with self.subTest(stage=stage):
                self.log.unlink(missing_ok=True)
                self.payload.unlink(missing_ok=True)
                result = self.run_script("--yes", TEST_FAIL=stage)
                self.assertEqual(23, result.returncode)
                self.assertEqual(count, len(self.calls()))
                self.assertNotIn("Linux is ready", result.stdout)
                self.assertIn("Installation stopped", result.stderr)
                if stage == "setup":
                    self.assertIn("--resume", result.stderr)

    def test_failed_resume_never_installs_over_an_existing_environment(self):
        result = self.run_script("--yes", "--resume", "--name", "work", TEST_FAIL="validate")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(23, result.returncode)
        self.assertFalse(self.payload.exists())
        self.assertNotIn("install", [args[0] for args in self.calls()])

    def test_bad_names_and_options_do_not_mutate_any_environment(self):
        for args in [("--name", "../bad"), ("--name", "bad;id"), ("--name", "-bad"),
                     ("--name", "a" * 65), ("--gui", "gnome"), ("--unknown",), ("--gui",),
                     ("--gpu", "auto"), ("--jobs", "0"), ("--jobs", "9"), ("--jobs", "1;id"),
                     ("--print-mesa-patch", "--yes")]:
            with self.subTest(args=args):
                self.assertNotEqual(0, self.run_script("--yes", *args).returncode)
                self.assertEqual([], self.calls())

    def test_regular_app_uid_is_rejected_without_elevation(self):
        self.assertNotEqual(0, self.run_script("--yes", TEST_UID="10001").returncode)
        self.assertEqual([], self.calls())

    def test_noninteractive_stdin_requires_explicit_consent(self):
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("--yes", result.stderr)
        self.assertEqual([], self.calls())

    def test_help_needs_no_privileged_executor(self):
        result = self.run_script("--help", TEST_UID="10001")
        self.assertEqual(0, result.returncode)
        self.assertIn("software rendering", result.stdout)
        self.assertEqual([], self.calls())

    def test_patch_export_needs_no_executor_and_has_both_egl_changes(self):
        result = self.run_script("--print-mesa-patch", TEST_UID="10001")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2, result.stdout.count("+   if ("))
        self.assertIn("disp->Options.ForceSoftware || disp->Options.Zink", result.stdout)
        self.assertEqual([], self.calls())

    def test_gpu_recipe_is_fingerprinted_and_independent_of_parallelism(self):
        import hashlib
        for gpu, jobs in [("turnip", "2"), ("turnip", "4"), ("software", "2")]:
            with self.subTest(gpu=gpu, jobs=jobs):
                result = self.run_script("--yes", "--name", "debian", "--resume", "--gpu", gpu, "--jobs", jobs)
                self.assertEqual(0, result.returncode, result.stderr)
                payload = Path(str(self.payload) + ".gpu")
                digest = hashlib.sha256(payload.read_bytes()).hexdigest()
                self.assertEqual([gpu, jobs, digest, "keep"], self.calls()[-1][-4:])
                self.assertEqual(0, subprocess.run(["sh", "-n", str(payload)]).returncode)
                self.assertIn(self.run_script("--print-mesa-patch").stdout, payload.read_text())
                self.assertIn("--fuzz=0", payload.read_text())
                self.assertLess(payload.read_text().index("vulkaninfo --summary"),
                                payload.read_text().index('mv -f "$candidate" "$profile"'))

    def test_gpu_failure_preserves_environment_and_reports_exact_resume_command(self):
        result = self.run_script("--yes", "--resume", "--name", "gpu-work", "--gpu", "turnip",
                                 "--jobs", "3", TEST_FAIL="graphics")
        self.assertEqual(23, result.returncode, result.stderr)
        self.assertIn("--name gpu-work --resume", result.stderr)
        self.assertIn("GPU=turnip jobs=3", result.stderr)
        self.assertNotIn("Linux is ready", result.stdout)
        self.assertNotIn("install", [args[0] for args in self.calls()])

    def profile_recipe(self):
        result = self.run_script("--yes", "--name", "debian", "--resume", "--gpu", "turnip")
        self.assertEqual(0, result.returncode, result.stderr)
        payload = Path(str(self.payload) + ".gpu").read_text()
        return payload[payload.index("# Remove only our previously exported library directory"):]

    def test_profile_activation_requires_successful_turnip_probe(self):
        recipe = self.profile_recipe()
        profile = self.directory / "graphics.sh"
        probe = self.directory / "vulkaninfo"
        for body, accepted in [("exit 1", False), ("echo driverID = DRIVER_ID_MESA_LLVMPIPE", False),
                               ("echo driverID = DRIVER_ID_MESA_TURNIP", True)]:
            with self.subTest(body=body):
                probe.write_text("#!/bin/sh\n" + body + "\n")
                probe.chmod(0o755)
                profile.write_text("previous profile\n")
                environment = dict(self.environment, gpu="turnip", prefix="/opt/magicdesk/mesa/test",
                                   candidate=str(profile) + ".new", profile=str(profile), work=str(self.directory))
                result = subprocess.run(["sh", "-eu", "-c", recipe], env=environment,
                                        capture_output=True, text=True, timeout=5)
                self.assertEqual(accepted, result.returncode == 0, result.stderr)
                if accepted:
                    self.assertIn("MESA_LOADER_DRIVER_OVERRIDE=zink", profile.read_text())
                else:
                    self.assertEqual("previous profile\n", profile.read_text())

    def test_software_profile_removes_only_previous_mesa_library_path(self):
        recipe = self.profile_recipe()
        profile = self.directory / "software.sh"
        environment = dict(self.environment, gpu="software", candidate=str(profile) + ".new", profile=str(profile))
        subprocess.run(["sh", "-eu", "-c", recipe], env=environment, check=True,
                       capture_output=True, timeout=5)
        environment.update(MAGICDESK_MESA_PREFIX="/opt/old", LD_LIBRARY_PATH="/custom:/opt/old/lib:/other",
                           VK_DRIVER_FILES="/old/icd.json", MESA_LOADER_DRIVER_OVERRIDE="zink")
        result = subprocess.run(["sh", "-eu", "-c", '. "$1"; . "$1"; printf "%s|%s|%s|%s|%s" '
                                 '"$LD_LIBRARY_PATH" "$LIBGL_ALWAYS_SOFTWARE" "$GALLIUM_DRIVER" '
                                 '"${VK_DRIVER_FILES-unset}" "${MAGICDESK_MESA_PREFIX-unset}"', "sh", str(profile)],
                                env=environment, capture_output=True, text=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("/custom:/other|1|llvmpipe|unset|unset", result.stdout)

    @unittest.skipUnless(hasattr(os, "openpty"), "PTY required")
    def test_questions_use_saved_script_stdin(self):
        self.questions(b"debian\ninteractive\nxfce\nkeep\nkeep\nkeep\nbasic\nn\ny\n")
        self.assertEqual(["install", "debian:trixie-slim", "--name", "interactive", "--dns", "system"], self.calls()[1])
        self.assertEqual("xfce", self.calls()[-1][-9])

    @unittest.skipUnless(hasattr(os, "openpty"), "PTY required")
    def test_advanced_questions_select_real_options_without_command_flags(self):
        output = self.questions(b"ubuntu\ninteractive\napps\nsoftware\nru_RU.UTF-8\nAsia/Krasnoyarsk\ncjk\ny\n"
                                b"developer\nwayland\ngit gdb\n192.0.2.1\nclean\ny\n")
        self.assertEqual(["install", "ubuntu:24.04", "--name", "interactive", "--dns", "192.0.2.1"], self.calls()[1])
        self.assertEqual(["apps", "wayland", "ru_RU.UTF-8", "Asia/Krasnoyarsk", "developer", "cjk", "clean", "keep", "git gdb"],
                         self.calls()[-2][-9:])
        self.assertIn("Guest account=developer | extra packages=git gdb", output)

    def questions(self, answers):
        master, slave = os.openpty()
        self.addCleanup(os.close, master)
        process = subprocess.Popen(["sh", str(SCRIPT)], stdin=slave, stdout=slave, stderr=slave,
                                   env=self.environment)
        os.close(slave)
        def cleanup():
            if process.poll() is None:
                process.kill()
            process.wait(timeout=5)
        self.addCleanup(cleanup)
        os.write(master, answers)
        output = bytearray()
        deadline = time.monotonic() + 15
        with selectors.DefaultSelector() as selector:
            selector.register(master, selectors.EVENT_READ)
            # EVENT_WAIT: PTY output/EOF; timeout detects a question/input deadlock.
            while True:
                self.assertTrue(selector.select(max(0, deadline - time.monotonic())), "Installer PTY stalled")
                try:
                    data = os.read(master, 4096)
                except OSError as error:
                    if error.errno == errno.EIO:
                        break
                    raise
                if not data:
                    break
                output.extend(data)
        self.assertEqual(0, process.wait(timeout=5), output.decode())
        return output.decode()


if __name__ == "__main__":
    unittest.main()
