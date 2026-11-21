"""Opt-in execution/history checks of the built mksh, never an installed/user shell."""

import argparse
import os
from pathlib import Path
import selectors
import subprocess
import tempfile
import time
import unittest
import uuid


SHELL = None


class Session:
    def __init__(self, home):
        self.process = subprocess.Popen(
            [str(SHELL), "-i"], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            env={**os.environ, "HOME": str(home), "ENV": str(home / "rc")})
        self.selector = selectors.DefaultSelector()
        self.selector.register(self.process.stdout, selectors.EVENT_READ)
        self.pending = b""

    def command(self, command):
        marker = f"__history_receipt_{uuid.uuid4().hex}__".encode()
        self.process.stdin.write(command.encode() + b"\nprint -r -- " + marker + b"\n")
        self.process.stdin.flush()
        # EVENT_WAIT: stdout receipt after execution; expiry fails this fixture.
        deadline = time.monotonic() + 5
        while marker + b"\n" not in self.pending:
            remaining = deadline - time.monotonic()
            if remaining <= 0 or not self.selector.select(remaining):
                raise AssertionError("mksh did not acknowledge the command")
            data = os.read(self.process.stdout.fileno(), 65536)
            if not data:
                raise AssertionError("mksh exited before the command receipt")
            self.pending += data
        output, self.pending = self.pending.split(marker + b"\n", 1)
        return output.decode()

    def close(self, abrupt=False):
        try:
            if self.process.poll() is None:
                if abrupt:
                    self.process.kill()
                else:
                    self.process.stdin.write(b"exit\n")
                    self.process.stdin.flush()
                self.process.wait(timeout=5)
        finally:
            if self.process.poll() is None:
                self.process.kill()
                self.process.wait(timeout=5)
            self.selector.close()
            self.process.stdin.close()
            self.process.stdout.close()


class HistoryTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="magicdesk-history-")
        self.addCleanup(self.directory.cleanup)
        self.home = Path(self.directory.name)
        (self.home / "rc").write_text(
            'HISTSIZE=32\nHISTFILE=$HOME/history\ntypeset +x HISTFILE HISTSIZE\nPS1=\n')

    def session(self):
        session = Session(self.home)
        self.addCleanup(session.close)
        return session

    def test_history_survives_process_death_with_owner_only_permissions(self):
        first = self.session()
        first.command("echo saved_before_kill")
        first.close(abrupt=True)
        history = self.home / "history"
        self.assertEqual(0o600, history.stat().st_mode & 0o777)
        second = self.session()
        self.assertIn("echo saved_before_kill", second.command("fc -ln -32"))

    def test_parallel_sessions_append_without_overwriting(self):
        first, second = self.session(), self.session()
        first.command("echo from_session_one")
        second.command("echo from_session_two")
        first.command("echo from_session_one_again")
        first.close()
        second.close()
        history = self.session().command("fc -ln -32")
        for command in ("from_session_one", "from_session_two", "from_session_one_again"):
            self.assertIn("echo " + command, history)

    def test_empty_history_header_retains_its_file_identity(self):
        history = self.home / "history"
        history.write_bytes(b"\xab\xcd")
        identity = history.stat().st_ino
        first = self.session()
        first.command("echo saved_to_empty_history")
        self.assertEqual(identity, history.stat().st_ino)
        second = self.session()
        self.assertIn("saved_to_empty_history", second.command("fc -ln -32"))

    def test_multiline_input_is_restored_without_being_executed(self):
        first = self.session()
        first.command("print -r -- 'first line\nsecond line'")
        first.command("echo once >> \"$HOME/executions\"")
        first.close()
        history = self.session().command("fc -ln -32")
        self.assertIn("first line", history)
        self.assertIn("second line", history)
        self.assertEqual("once\n", (self.home / "executions").read_text())

    def test_live_session_can_append_after_another_session_compacts(self):
        first = self.session()
        for index in range(40):
            first.command(f"echo previous_{index:03}")
        second = self.session()
        second.command("echo after_compaction_from_second")
        data = (self.home / "history").read_bytes()
        self.assertNotIn(b"echo previous_000\0", data)
        self.assertIn(b"echo previous_039\0", data)
        first.command("echo after_compaction_from_first")
        first.close()
        second.close()
        history = self.session().command("fc -ln -32")
        self.assertIn("after_compaction_from_first", history)
        self.assertIn("after_compaction_from_second", history)

    def test_reopen_compacts_history_and_different_homes_are_independent(self):
        first = self.session()
        for index in range(40):
            first.command(f"echo entry_{index:03}")
        first.close()
        history = self.session().command("fc -ln -32")
        self.assertNotIn("entry_000", history)
        self.assertIn("entry_039", history)
        other = self.home / "other-home"
        other.mkdir()
        (other / "rc").write_text((self.home / "rc").read_text())
        isolated = Session(other)
        self.addCleanup(isolated.close)
        self.assertNotIn("entry_039", isolated.command("fc -ln -32"))


class ExecutionTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="magicdesk-shell-exec-")
        self.addCleanup(self.directory.cleanup)
        self.home = Path(self.directory.name)
        for name in ("sh", "mksh"):
            (self.home / name).symlink_to(SHELL)
        self.environment = {key: value for key, value in os.environ.items()
                            if key not in {"ENV", "HISTFILE", "HISTSIZE", "EXECSHELL",
                                           "LD_PRELOAD", "LD_LIBRARY_PATH"}}
        self.environment.update(HOME=str(self.home), PATH=str(self.home), SHELL=str(SHELL))

    def run_command(self, command, *arguments):
        # EVENT_WAIT: isolated command exit; timeout kills it and fails the fixture.
        return subprocess.run([str(SHELL), "-c", command, "fixture", *map(str, arguments)],
                              env=self.environment, capture_output=True, text=True, timeout=5)

    def executable(self, name, text):
        path = self.home / name
        path.write_text(text)
        path.chmod(0o700)
        return path

    def test_sh_and_mksh_resolve_to_the_same_packaged_shell(self):
        result = self.run_command("""sh -c 'print -r -- "$KSH_VERSION"'; """
                                  """mksh -c 'print -r -- "$KSH_VERSION"'""")
        self.assertEqual(0, result.returncode, result.stderr)
        versions = result.stdout.splitlines()
        self.assertEqual(2, len(versions))
        self.assertEqual(versions[0], versions[1])
        self.assertIn("MagicDesk", versions[0])

    def test_text_executable_without_shebang_reenters_mksh(self):
        script = self.executable("no-shebang", 'print -r -- "$KSH_VERSION"; print -r -- "<$1>"\n')
        result = self.run_command('"$1" "$2"', script, "argument with spaces")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("MagicDesk", result.stdout)
        self.assertTrue(result.stdout.endswith("<argument with spaces>\n"))

    def test_explicit_shebang_is_respected(self):
        # The kernel does not support whitespace in a shebang interpreter path.
        shell_link = self.home / "mksh"
        interpreter = self.executable("chosen-interpreter", f'#!{shell_link}\nprint -r -- "chosen:<$1>:<$2>"\n')
        script = self.executable("explicit", f'#!{interpreter}\nprint -r -- "must not run"\n')
        result = self.run_command('"$1" "$2"', script, "literal argument")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(f"chosen:<{script}>:<literal argument>\n", result.stdout)

    def test_missing_explicit_interpreter_does_not_fall_back(self):
        script = self.executable("missing", f'#!{self.home}/absent\nprint -r -- "must not run"\n')
        result = self.run_command('"$1"', script)
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("must not run", result.stdout)

    def test_noninteractive_commands_do_not_load_rc_or_save_history(self):
        rc = self.home / "rc"
        rc.write_text('print -r -- "interactive rc loaded"\nHISTFILE=$HOME/history\n')
        self.environment["ENV"] = str(rc)
        result = self.run_command('print -r -- "background command"')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("background command\n", result.stdout)
        self.assertFalse((self.home / "history").exists())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--shell", type=Path, required=True, help="Built Android mksh executable")
    args, remaining = parser.parse_known_args()
    SHELL = args.shell.resolve(strict=True)
    unittest.main(argv=[__file__, *remaining])
