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
    stage = "setup" if "-s" in args else "validate"
    if stage == "setup":
        Path(os.environ["TEST_PAYLOAD"]).write_text(sys.stdin.read())
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
        self.assertEqual(["exec", "work", "--user", "root", "--env", "DEBIAN_FRONTEND=noninteractive",
                          "--", "/bin/sh", "-s", "--", "apps"], self.calls()[-1])
        self.assertIn("Debian is ready: work", result.stdout)
        payload = self.payload.read_text()
        self.assertIn('apt-get install -y --no-install-recommends "$@" </dev/null', payload)
        self.assertIn("X-MagicDesk-Graphics=wayland", payload)
        self.assertIn("X-MagicDesk-GraphicsMode=desktop", payload)
        self.assertNotIn("/data/local/tmp", payload)
        self.assertEqual(0, subprocess.run(["sh", "-n", str(self.payload)]).returncode)

    def test_xfce_profile_and_root_executor(self):
        result = self.run_script("--yes", "--gui", "xfce", TEST_UID="0")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("xfce", self.calls()[-1][-1])

    def test_resume_validates_without_reinstalling(self):
        result = self.run_script("--yes", "--name", "existing", "--resume")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["--probe", "exec", "exec"], [args[0] for args in self.calls()])
        self.assertIn('"$ID" = debian', self.calls()[1][-1])
        self.assertIn('"$VERSION_ID" = 13', self.calls()[1][-1])

    def test_dns_is_explicit_and_resume_does_not_replace_it(self):
        result = self.run_script("--yes", "--dns", "192.0.2.1,192.0.2.2")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["--dns", "192.0.2.1,192.0.2.2"], self.calls()[1][-2:])
        self.log.unlink()
        result = self.run_script("--yes", "--resume", "--dns", "192.0.2.1")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], self.calls())

    def test_failure_is_not_reported_as_success_or_followed_by_more_work(self):
        for stage, count in [("--probe", 1), ("install", 2), ("validate", 3), ("setup", 4)]:
            with self.subTest(stage=stage):
                self.log.unlink(missing_ok=True)
                self.payload.unlink(missing_ok=True)
                result = self.run_script("--yes", TEST_FAIL=stage)
                self.assertEqual(23, result.returncode)
                self.assertEqual(count, len(self.calls()))
                self.assertNotIn("Debian is ready", result.stdout)
                self.assertIn("Installation stopped", result.stderr)
                if stage == "setup":
                    self.assertIn("--resume", result.stderr)

    def test_failed_resume_never_installs_over_an_existing_environment(self):
        result = self.run_script("--yes", "--resume", TEST_FAIL="validate")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.payload.exists())
        self.assertNotIn("install", [args[0] for args in self.calls()])

    def test_bad_names_and_options_do_not_mutate_any_environment(self):
        for args in [("--name", "../bad"), ("--name", "bad;id"), ("--name", "-bad"),
                     ("--name", "a" * 65), ("--gui", "gnome"), ("--unknown",), ("--gui",)]:
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

    @unittest.skipUnless(hasattr(os, "openpty"), "PTY required")
    def test_questions_use_saved_script_stdin(self):
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
        os.write(master, b"interactive\nxfce\n\ny\n")
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
        self.assertEqual(["install", "debian:trixie-slim", "--name", "interactive", "--dns", "system"], self.calls()[1])
        self.assertEqual("xfce", self.calls()[-1][-1])


if __name__ == "__main__":
    unittest.main()
