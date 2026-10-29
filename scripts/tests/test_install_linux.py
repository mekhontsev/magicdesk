"""Installer orchestration without modifying the host or downloading packages."""
import errno
import gzip
import io
import json
import lzma
import os
from pathlib import Path
import re
import selectors
import shlex
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "install_linux.sh"
MIN_VERSION_CODE = int(re.search(r"^MIN_MAGICDESK_VERSION_CODE=(\d+)$", SCRIPT.read_text(), re.M).group(1))
MIN_VERSION_NAME = re.search(r"^MIN_MAGICDESK_VERSION_NAME=([\d.]+)$", SCRIPT.read_text(), re.M).group(1)
VERSION_QUERY = ["get_state", "--field", "data.app.versionCode"]


class InstallLinuxTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="magicdesk-install-")
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.log = self.directory / "calls.jsonl"
        self.version_log = self.directory / "version.jsonl"
        self.dns_log = self.directory / "dns.jsonl"
        self.payload = self.directory / "guest.sh"
        stub = self.directory / "shroot"
        stub.write_text("#!" + sys.executable + r'''
import json, os, sys
from pathlib import Path
if Path(sys.argv[0]).name == "id":
    print(os.environ.get("TEST_UID", "2000"))
    sys.exit(0)
args = sys.argv[1:]
if Path(sys.argv[0]).name == "settings":
    assert args == ["--user", "current", "get", "system", "system_locales"]
    print(os.environ.get("TEST_ANDROID_LOCALES", "null"))
    sys.exit(int(os.environ.get("TEST_SETTINGS_STATUS", "0")))
if Path(sys.argv[0]).name == "getprop":
    values = {"persist.sys.locale": os.environ.get("TEST_ANDROID_LOCALE", ""),
              "persist.sys.timezone": "UTC"}
    print(values[args[0]])
    sys.exit(int(os.environ.get("TEST_GETPROP_STATUS", "0")))
if Path(sys.argv[0]).name == "magicdesk" and args and args[0] == "get_state":
    with open(os.environ["TEST_VERSION_LOG"], "a") as log:
        log.write(json.dumps(args) + "\n")
    if args != ["get_state", "--field", "data.app.versionCode"]:
        sys.exit(2)
    print(os.environ["TEST_VERSION_CODE"])
    sys.exit(int(os.environ.get("TEST_VERSION_STATUS", "0")))
if args[:2] == ["dns", "--check"]:
    import ipaddress
    with open(os.environ["TEST_DNS_LOG"], "a") as log:
        log.write(json.dumps(args) + "\n")
    if args[2] == "system":
        error = os.environ.get("TEST_DNS_ERROR", "")
        if error:
            print(error, file=sys.stderr)
            sys.exit(1)
    elif args[2] != "preserve":
        try:
            addresses = args[2].split(",")
            assert 1 <= len(addresses) <= 3
            for address in addresses:
                ipaddress.ip_address(address)
        except (ValueError, AssertionError):
            print("Invalid DNS server addresses", file=sys.stderr)
            sys.exit(1)
    sys.exit(0)
with open(os.environ["TEST_LOG"], "a") as log:
    log.write(json.dumps(args) + "\n")
stage = args[0]
if stage == "--probe":
    print(os.environ.get("TEST_RUNTIME_FEATURES", "machine-entry=1"))
if stage == "path":
    print(str(Path(os.environ["HOME"]) / "source-store"))
    sys.exit(0)
if args[:2] == ["image", "exec"]:
    args = args[1:]
    stage = "exec"
if Path(sys.argv[0]).name == "magicdesk":
    if os.environ.get("TEST_FAIL") == "download":
        print("test download/checksum failure", file=sys.stderr)
        sys.exit(23)
    if args[1].endswith('.sha256'):
        default = 'a' * 64 + '  ' + Path(args[1][:-7]).name + '\n'
        Path(args[2]).write_text(os.environ.get('TEST_CHECKSUM', default))
    else:
        Path(args[2]).write_bytes(b"test download")
    sys.exit(0)
if stage == "list":
    print(os.environ.get("TEST_ENVIRONMENTS", ""))
if stage in ("exec", "login"):
    if args[-3:] == ['/bin/sh', '-c', ':'] and os.environ.get('TEST_NIXOS_UNACTIVATED'):
        sys.exit(1)
    if stage == 'exec' and args[-2].endswith('-bash-interactive-5.3p9/bin/bash'):
        Path(os.environ['TEST_PAYLOAD'] + '.bootstrap').write_text(sys.stdin.read())
        sys.exit(0)
    if args[1] == "unpack":
        if args[-1] == "/mnt":
            Path(os.environ["TEST_PAYLOAD"] + ".reader").write_text(sys.stdin.read())
        sys.exit(23 if os.environ.get("TEST_FAIL") == "converter" else 0)
    stage = "setup" if "-s" in args else "detect" if len(args) == 8 else "validate"
    if stage == "detect":
        print(os.environ.get("TEST_DISTRO", "debian"))
    if stage == "setup":
        payload = sys.stdin.read()
        if args[-3] in ("turnip", "software", "auto"):
            stage = "graphics"
        if "version=6.3.6" in payload:
            stage = "kwin"
        if "version=13.0.4" in payload:
            stage = "weston"
        suffix = {"graphics": ".gpu", "kwin": ".kwin", "weston": ".weston"}.get(stage, "")
        Path(os.environ["TEST_PAYLOAD"] + suffix).write_text(payload)
        if "MAGICDESK_BUILD_PHASE=dependencies" in args:
            if os.environ.get("TEST_FAIL_DEPENDENCIES") == stage:
                sys.exit(23)
        if stage == "setup" and os.environ.get("TEST_SETUP_FAILURES"):
            attempts = Path(os.environ["TEST_PAYLOAD"] + ".attempts")
            count = int(attempts.read_text()) + 1 if attempts.exists() else 1
            attempts.write_text(str(count))
            if count <= int(os.environ["TEST_SETUP_FAILURES"]):
                print("test package download failure", file=sys.stderr)
                sys.exit(23)
if os.environ.get("TEST_FAIL") == stage and "MAGICDESK_BUILD_PHASE=dependencies" not in args:
    print("test failure: " + stage, file=sys.stderr)
    sys.exit(23)
''')
        stub.chmod(0o755)
        shutil.copyfile(stub, self.directory / "id")
        (self.directory / "id").chmod(0o755)
        shutil.copyfile(stub, self.directory / "magicdesk")
        (self.directory / "magicdesk").chmod(0o755)
        for command in ("settings", "getprop"):
            shutil.copyfile(stub, self.directory / command)
            (self.directory / command).chmod(0o755)
        self.environment = dict(os.environ, HOME=str(self.directory), PATH=str(self.directory) + os.pathsep + os.environ["PATH"],
                                TEST_LOG=str(self.log), TEST_PAYLOAD=str(self.payload), TMPDIR=str(self.directory),
                                TEST_DNS_LOG=str(self.dns_log),
                                TEST_VERSION_LOG=str(self.version_log), TEST_VERSION_CODE=str(MIN_VERSION_CODE))

    def run_script(self, *args, **environment):
        # EVENT_WAIT: installer exit; timeout fails a stuck subprocess.
        return subprocess.run(["sh", str(SCRIPT), *args], input="", text=True, capture_output=True,
                              env=dict(self.environment, **environment), timeout=15)

    def calls(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def system_setup_call(self):
        return next(c for c in self.calls() if c[0] == 'login' and '-s' in c)

    def version_calls(self):
        return [json.loads(line) for line in self.version_log.read_text().splitlines()] if self.version_log.exists() else []

    def dns_checks(self):
        return [json.loads(line) for line in self.dns_log.read_text().splitlines()] if self.dns_log.exists() else []

    def test_dns_failure_stops_unattended_install_before_any_image_work(self):
        for distro in ('alpine', 'steamos', 'void'):
            with self.subTest(distro=distro):
                result = self.run_script('--yes', '--distro', distro, TEST_DNS_ERROR='No active-network DNS servers')
                self.assertEqual(1, result.returncode)
                self.assertIn('No active-network DNS servers', result.stderr)
                self.assertIn('Retry with --dns', result.stderr)
                self.assertNotIn('GUI=', result.stdout)
                self.assertEqual([], self.calls())

    def test_current_network_alias_uses_existing_dns_owner(self):
        for policy in ('auto', 'system'):
            with self.subTest(policy=policy):
                self.log.unlink(missing_ok=True)
                self.dns_log.unlink(missing_ok=True)
                result = self.run_script('--yes', '--dns', policy)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual([['dns', '--check', 'system']], self.dns_checks())
                install = next(c for c in self.calls() if c[0] == 'install')
                self.assertEqual(['--dns', 'system'], install[-2:])
                self.assertIn(['dns', 'debian', 'system', '--replace'], self.calls())
                self.assertIn('Network: Current network (DNS saved once; editable in Linux)', result.stdout)

    @unittest.skipUnless(hasattr(os, 'openpty'), 'PTY required')
    def test_current_network_is_the_default_advanced_choice(self):
        output = self.questions(b'alpine\nwork\nnone\nkeep\nkeep\nkeep\nkeep\ny\n\nboth\n\n\nkeep\ny\n')
        self.assertIn('auto      Current network (recommended)', output)
        self.assertIn('preserve  Keep the Linux image/current Linux DNS settings', output)
        self.assertIn('Network [auto]', output)
        self.assertGreater(output.index('Network [auto]'), output.index('Additional settings ('))
        self.assertEqual([['dns', '--check', 'system']], self.dns_checks())
        self.assertIn('Linux is ready: work', output)

    def test_invalid_explicit_dns_is_checked_without_touching_an_image(self):
        result = self.run_script('--yes', '--dns', 'invalid')
        self.assertEqual(1, result.returncode)
        self.assertEqual([['dns', '--check', 'invalid']], self.dns_checks())
        self.assertEqual([], self.calls())

    def test_resume_keeps_resolver_without_requiring_android_network(self):
        result = self.run_script('--yes', '--resume', '--name', 'work', TEST_DNS_ERROR='offline')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], self.dns_checks())

    def test_installer_keeps_stock_libc_and_rejects_retired_controls(self):
        for resume in (False, True):
            self.log.unlink(missing_ok=True)
            result = self.run_script('--yes', '--distro', 'alpine', '--name', 'work',
                                     *(['--resume'] if resume else []), TEST_DISTRO='alpine')
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertFalse(any(c[0] == 'libc' for c in self.calls()))
            self.assertFalse((self.directory / 'libc').exists())
        for options in (('--libc', 'shroot'), ('--prepare-libc', 'work'),
                        ('--print-libc-setup',)):
            self.log.unlink(missing_ok=True)
            result = self.run_script(*options)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual([], self.calls())

    def test_source_builds_run_dependencies_before_compilers(self):
        for distro, gpu in (('steamos', 'auto'), ('debian', 'turnip')):
            with self.subTest(distro=distro, gpu=gpu):
                self.log.unlink(missing_ok=True)
                result = self.run_script('--yes', '--resume', '--distro', distro,
                                         '--name', 'work', '--gui', 'plasma', '--gpu', gpu,
                                         TEST_DISTRO=distro)
                self.assertEqual(0, result.returncode, result.stderr)
                calls = self.calls()
                phases = [(i, c) for i, c in enumerate(calls)
                          if any(a.startswith('MAGICDESK_BUILD_PHASE=') for a in c)]
                self.assertEqual(4, len(phases))
                for start in (0, 2):
                    dep_index, dependencies = phases[start]
                    build_index, build = phases[start + 1]
                    self.assertIn('MAGICDESK_BUILD_PHASE=dependencies', dependencies)
                    self.assertIn('MAGICDESK_BUILD_PHASE=build', build)
                    self.assertEqual(dependencies[-1], build[-1])
                    self.assertEqual(dep_index + 1, build_index)
                self.assertFalse(any(c[0] == 'image' for c in calls))

    def test_failed_build_dependencies_do_not_start_compilers(self):
        result = self.run_script('--yes', '--resume', '--name', 'work', '--gpu', 'turnip',
                                 TEST_FAIL_DEPENDENCIES='graphics')
        self.assertEqual(23, result.returncode, result.stderr)
        self.assertIn('installing build dependencies', result.stderr)
        self.assertFalse(any(c[:2] == ['image', 'backup'] for c in self.calls()))
        self.assertFalse(any('MAGICDESK_BUILD_PHASE=build' in c for c in self.calls()))

    def test_source_build_phases_separate_packages_from_compilers(self):
        result = self.run_script('--yes', '--resume', '--name', 'work', '--gui', 'plasma', '--gpu', 'turnip')
        self.assertEqual(0, result.returncode, result.stderr)
        for suffix in ('.gpu', '.kwin'):
            script = Path(str(self.payload) + suffix).read_text()
            start = script.index('    if [ "$build_phase" != build ]; then')
            end_marker = '    [ "$build_phase" != dependencies ] || exit 0'
            end = script.index(end_marker, start) + len(end_marker)
            fragment = ('set -eu\npackages() { printf "packages\\n"; }\n'
                        + script[start:end] + '\nprintf "compile\\n"\n')
            for family in ('apt', 'recovery'):
                for phase, expected in (('all', 'packages\ncompile\n'),
                                        ('dependencies', 'packages\n'), ('build', 'compile\n')):
                    with self.subTest(recipe=suffix, family=family, phase=phase):
                        result = subprocess.run(['sh', '-c', fragment], capture_output=True,
                                                text=True, timeout=5,
                                                env={**os.environ, 'family': family, 'build_phase': phase})
                        self.assertEqual(0, result.returncode, result.stderr)
                        self.assertEqual(expected, result.stdout)

    @unittest.skipUnless(hasattr(os, 'getuid'), 'POSIX file ownership required')
    def test_source_extraction_ignores_archive_owners_but_preserves_build_inputs(self):
        self.check_source_extraction(hardlinks=False)

    @unittest.skipUnless(hasattr(os, 'getuid'), 'POSIX file ownership required')
    @unittest.skipUnless(hasattr(os, 'link'), 'Native hardlink API required')
    def test_source_extraction_preserves_hardlinks(self):
        source = self.directory / 'hardlink-probe-source'
        source.write_bytes(b'probe')
        try:
            os.link(source, self.directory / 'hardlink-probe-target')
        except OSError as error:
            if error.errno in (errno.EACCES, errno.EPERM, errno.EOPNOTSUPP, errno.ENOSYS):
                self.skipTest(f'Native host hardlinks unavailable: {error}')
            raise
        self.check_source_extraction(hardlinks=True)

    def check_source_extraction(self, *, hardlinks):
        result = self.run_script('--yes', '--resume', '--name', 'work',
                                 '--gui', 'plasma', '--gpu', 'turnip')
        self.assertEqual(0, result.returncode, result.stderr)
        recipes = {}
        for name, suffix in (('mesa', '.gpu'), ('kwin', '.kwin')):
            recipes[name] = Path(str(self.payload) + suffix).read_text()
        archive = self.directory / 'foreign owners.tar.xz'
        body = b'#!/bin/sh\nprintf "source fixture\\n"\n'
        with tarfile.open(archive, 'w:xz') as output:
            entry = tarfile.TarInfo('fixture/configure')
            entry.size, entry.mode = len(body), 0o755
            entry.uid, entry.gid = 123456, 123457
            entry.uname, entry.gname = 'foreign-builder', 'foreign-builders'
            output.addfile(entry, io.BytesIO(body))
            links = [('link', tarfile.SYMTYPE, 'configure')]
            if hardlinks:
                links.append(('hardlink', tarfile.LNKTYPE, 'fixture/configure'))
            for name, kind, target in links:
                entry = tarfile.TarInfo('fixture/' + name)
                entry.type, entry.linkname = kind, target
                entry.mode = 0o755
                entry.uid, entry.gid = 123456, 123457
                output.addfile(entry)
        for name, recipe in recipes.items():
            with self.subTest(recipe=name):
                commands = re.findall(r'^\s*(tar [^\n]+)$', recipe, re.M)
                expected = 4 if name == 'mesa' else 1
                self.assertEqual(expected, len(commands), commands)
                for command in commands:
                    self.assertIn('--no-same-owner', shlex.split(command))
                command = next(command for command in commands if '"$archive"' in command)
                self.assertIn('--no-same-owner', shlex.split(command))
                work = self.directory / ('build ' + name)
                (work / 'source').mkdir(parents=True)
                (work / 'unpack').mkdir()
                environment = dict(os.environ, archive=str(archive), work=str(work))
                extracted = subprocess.run(['sh', '-ec', command], cwd=work,
                                           env=environment, capture_output=True, text=True, timeout=10)
                self.assertEqual(0, extracted.returncode, extracted.stderr)
                root = work / 'unpack/fixture'
                self.assertEqual(body, (root / 'configure').read_bytes())
                self.assertEqual(os.getuid(), (root / 'configure').stat().st_uid)
                self.assertTrue(os.access(root / 'configure', os.X_OK))
                self.assertEqual('configure', os.readlink(root / 'link'))
                if hardlinks:
                    self.assertTrue(os.path.samefile(root / 'configure', root / 'hardlink'))
                # Disabling owner restoration must not hide corrupt-archive failures.
                broken = work / 'broken.tar.xz'
                broken.write_bytes(b'not an archive')
                failed = subprocess.run(['sh', '-ec', command], cwd=work,
                                        env=dict(environment, archive=str(broken)),
                                        capture_output=True, text=True, timeout=10)
                self.assertNotEqual(0, failed.returncode)

    @unittest.skipUnless(hasattr(os, 'openpty'), 'PTY required')
    def test_interactive_dns_repair_follows_configuration_and_retries_only_dns(self):
        output = self.questions(b'alpine\nwork\nxfce\nsoftware\nkeep\nkeep\nbasic\nn\nauto\ninvalid\n192.0.2.1\ny\n',
                                TEST_DNS_ERROR='No active-network DNS servers')
        self.assertGreater(output.index('Cannot prepare guest DNS'), output.index('Additional settings ('))
        self.assertEqual(1, output.count('Distribution ['))
        self.assertEqual([['dns', '--check', value] for value in ('system', 'system', 'invalid', '192.0.2.1')], self.dns_checks())
        install = next(c for c in self.calls() if c[0] == 'install')
        self.assertEqual(['--rootfs', '--name', 'work', '--dns', '192.0.2.1'], install[2:])
        self.assertIn('Linux is ready: work', output)

    @unittest.skipUnless(hasattr(os, 'openpty'), 'PTY required')
    def test_unavailable_dns_can_be_cancelled_without_installing(self):
        output = self.questions(b'alpine\nwork\nxfce\nsoftware\nkeep\nkeep\nbasic\nn\n\n',
                                TEST_DNS_ERROR='Android Private DNS is active')
        self.assertIn('Cancelled.', output)
        self.assertIn('Distribution [', output)
        self.assertEqual([], self.calls())

    @unittest.skipUnless(hasattr(os, 'openpty'), 'PTY required')
    def test_advanced_invalid_dns_retains_other_answers(self):
        output = self.questions(b'alpine\nwork\nxfce\nsoftware\nkeep\nkeep\nbasic\ny\n\nboth\n\ninvalid\nkeep\npreserve\ny\n')
        self.assertEqual([['dns', '--check', value] for value in ('invalid', 'preserve')], self.dns_checks())
        self.assertEqual(1, output.count('Distribution ['))
        install = next(c for c in self.calls() if c[0] == 'install')
        self.assertEqual(['--rootfs', '--name', 'work', '--dns', 'preserve'], install[2:])

    @unittest.skipUnless(hasattr(os, 'openpty'), 'PTY required')
    def test_package_failure_can_change_dns_without_reinstalling_or_repeating_answers(self):
        output = self.questions(b'alpine\nwork\nxfce\nsoftware\nkeep\nkeep\nbasic\nn\ny\ndns\ninvalid\n192.0.2.1\n',
                                TEST_SETUP_FAILURES='1')
        self.assertIn('test package download failure', output)
        self.assertIn('Linux is ready: work', output)
        self.assertEqual(1, output.count('Distribution ['))
        self.assertEqual(1, sum(c[0] == 'install' for c in self.calls()))
        self.assertIn(['dns', 'work', '192.0.2.1', '--replace'], self.calls())
        setups = [c for c in self.calls() if c[0] == 'login' and '-s' in c and c[-8] == 'xfce']
        self.assertEqual(2, len(setups))
        self.assertEqual(setups[0], setups[1])

    @unittest.skipUnless(hasattr(os, 'openpty'), 'PTY required')
    def test_package_retry_can_switch_to_current_network(self):
        output = self.questions(b'alpine\nwork\nnone\nkeep\nkeep\nkeep\nkeep\ny\n\nboth\n\n192.0.2.1\nkeep\ny\ndns\nauto\n',
                                TEST_SETUP_FAILURES='1')
        self.assertEqual([['dns', '--check', p] for p in ('192.0.2.1', 'system')], self.dns_checks())
        self.assertIn(['dns', 'work', 'system', '--replace'], self.calls())
        self.assertEqual(1, sum(c[0] == 'install' for c in self.calls()))
        self.assertIn('Linux is ready: work', output)

    @unittest.skipUnless(hasattr(os, 'openpty'), 'PTY required')
    def test_package_retry_and_cancel_keep_the_environment(self):
        output = self.questions(b'alpine\nwork\nxfce\nsoftware\nkeep\nkeep\nbasic\nn\ny\ninvalid\nretry\n\n',
                                expected_exit=23, TEST_SETUP_FAILURES='2')
        self.assertIn('Environment retained.', output)
        self.assertEqual(1, sum(c[0] == 'install' for c in self.calls()))
        self.assertFalse(any(c[0] == 'remove' for c in self.calls()))

    def test_unattended_package_failure_never_retries(self):
        result = self.run_script('--yes', '--distro', 'alpine', TEST_SETUP_FAILURES='1')
        self.assertEqual(23, result.returncode)
        self.assertIn('--resume', result.stderr)
        self.assertEqual('1', Path(str(self.payload) + '.attempts').read_text())
        self.assertNotIn('Package setup: retry', result.stdout)

    def test_minimum_and_newer_versions_are_accepted_with_one_query(self):
        for version in (MIN_VERSION_CODE, MIN_VERSION_CODE + 1, 2147483647):
            with self.subTest(version=version):
                self.version_log.unlink(missing_ok=True)
                result = self.run_script("--yes", TEST_VERSION_CODE=str(version))
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual([VERSION_QUERY], self.version_calls())

    def test_old_versions_stop_before_questions_resume_or_recovery_work(self):
        for args in ((), ("--yes",), ("--yes", "--resume", "--name", "existing"),
                     ("--yes", "--distro", "steamos")):
            with self.subTest(args=args):
                self.version_log.unlink(missing_ok=True)
                result = self.run_script(*args, TEST_VERSION_CODE=str(MIN_VERSION_CODE - 1))
                self.assertEqual(1, result.returncode, result.stderr)
                self.assertIn("requires MagicDesk " + MIN_VERSION_NAME, result.stderr)
                self.assertIn("installed " + str(MIN_VERSION_CODE - 1), result.stderr)
                self.assertIn("Update MagicDesk", result.stderr)
                self.assertNotIn("Interactive input", result.stderr)
                self.assertEqual([VERSION_QUERY], self.version_calls())
                self.assertEqual([], self.calls())
                self.assertFalse(self.payload.exists())

    def test_unknown_or_failed_version_query_never_runs_shroot(self):
        for version, status in (("", "0"), ("null", "0"), ("2.0.3", "0"), ("218-dev", "0"),
                                ("218\n219", "0"), ("-1", "0"), ("+218", "0"),
                                (" 218", "0"), ("9" * 100, "0"), ("2147483648", "0"),
                                (str(MIN_VERSION_CODE), "1"), (str(MIN_VERSION_CODE), "3")):
            with self.subTest(version=version, status=status):
                result = self.run_script("--yes", TEST_VERSION_CODE=version, TEST_VERSION_STATUS=status)
                self.assertEqual(1, result.returncode, result.stderr)
                self.assertIn("MagicDesk version", result.stderr)
                self.assertEqual([], self.calls())
                self.assertFalse(self.payload.exists())

    def test_missing_magicdesk_fails_but_offline_commands_still_work(self):
        (self.directory / "magicdesk").unlink()
        # Restrict PATH after the host shell (including launcher wrappers) starts.
        # EVENT_WAIT: installer rejection; timeout fails a stuck subprocess.
        result = subprocess.run(["sh", "-c", 'PATH=$1; script=$2; shift 2; . "$script"',
                                 "installer-test", str(self.directory), str(SCRIPT), "--yes"],
                                input="", text=True, capture_output=True, env=self.environment, timeout=15)
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertIn("magicdesk is unavailable", result.stderr)
        for option in ("--help", "--list", "--print-mesa-patch", "--print-kwin-patch",
                       "--print-system-setup", "--print-nixos-setup", "--print-nixos-module",
                       "--print-graphics-setup", "--print-weston-setup",
                       "--print-recovery-reader", "--print-desktop-session", "--print-audio-session", "--print-steam-game"):
            with self.subTest(option=option):
                result = self.run_script(option, TEST_UID="10001", TEST_VERSION_STATUS="3")
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertTrue(result.stdout)
        self.assertEqual([], self.version_calls())
        self.assertEqual([], self.calls())

    def test_version_floor_does_not_exceed_this_apk(self):
        properties = dict(line.split("=", 1) for line in (SCRIPT.parents[1] / "gradle.properties").read_text().splitlines()
                          if "=" in line and not line.startswith("#"))
        self.assertLessEqual(MIN_VERSION_CODE, int(properties["magicDeskVersionCode"]))
        if MIN_VERSION_CODE == int(properties["magicDeskVersionCode"]):
            self.assertEqual(properties["magicDeskVersionName"], MIN_VERSION_NAME)
        result = self.run_script("--help")
        self.assertIn("Requires MagicDesk " + MIN_VERSION_NAME, result.stdout)
        self.assertIn("versionCode " + str(MIN_VERSION_CODE), result.stdout)

    def test_apps_install_uses_existing_commands_and_keeps_guest_setup_on_stdin(self):
        result = self.run_script("--yes", "--name", "work")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["--probe"], self.calls()[0])
        install = next(c for c in self.calls() if c[0] == 'install')
        self.assertEqual(['--rootfs', '--name', 'work', '--dns', 'system'], install[2:])
        self.assertIn(["dns", "work", "system", "--replace"], self.calls())
        self.assertEqual(["login", "work", "--user", "root", "--env", "DEBIAN_FRONTEND=noninteractive",
                          "--env", "SYSTEMD_IN_CHROOT=1", "--env", "LC_ALL=C",
                          "--", "/bin/sh", "-s", "--", "apps", "both", "keep", "keep", "", "basic", "keep", ""], self.system_setup_call())
        self.assertIn("Linux is ready: work", result.stdout)
        payload = self.payload.read_text()
        self.assertIn('apt-get install -y --no-install-recommends "$@" </dev/null', payload)
        self.assertIn('backend=wayland', payload)
        self.assertIn("X-MagicDesk-GraphicsMode=desktop", payload)
        self.assertIn("printf 'unset LC_ALL\\nexport LANG=%s\\n'", payload)
        self.assertIn('path-include /usr/share/locale/*', payload)
        self.assertNotIn("/data/local/tmp", payload)
        self.assertEqual(0, subprocess.run(["sh", "-n", str(self.payload)]).returncode)

    def test_explicit_xbps_network_preference_reaches_each_setup_stage(self):
        result = self.run_script('--yes', '--distro', 'void', '--image', 'example/void:test',
                                 '--gpu', 'turnip', FORCE_IPV4='1')
        self.assertEqual(0, result.returncode, result.stderr)
        stages = [call for call in self.calls() if call[0] == 'login' and '-s' in call]
        self.assertEqual(5, len(stages))
        for call in stages:
            self.assertIn('FORCE_IPV4=1', call)
        self.assertNotIn('FORCE_IPV4=1', next(call for call in self.calls() if call[0] == 'install'))

    def test_print_system_setup_matches_installed_payload_without_host_operations(self):
        printed = self.run_script('--print-system-setup')
        self.assertEqual(0, printed.returncode, printed.stderr)
        self.assertEqual([], self.calls())
        self.assertEqual([], self.version_calls())
        installed = self.run_script('--yes', '--gui', 'none')
        self.assertEqual(0, installed.returncode, installed.stderr)
        self.assertEqual(self.payload.read_text(), printed.stdout)

    def test_xfce_profile_and_root_executor(self):
        result = self.run_script("--yes", "--gui", "xfce", TEST_UID="0")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("xfce", self.system_setup_call()[-8])

    def test_arch_mirror_fallback_preserves_custom_lists_and_is_idempotent(self):
        setup = self.run_script('--print-system-setup').stdout
        start = setup.index('arch_mirror_fallback() {')
        function = setup[start:setup.index('\ngui=$1', start)]
        default = 'Server = http://mirror.archlinuxarm.org/$arch/$repo\n'
        fallback = 'Server = https://de3.mirror.archlinuxarm.org/$arch/$repo\n'
        mirrorlist = self.directory / 'mirrorlist'
        minimal_bin = self.directory / 'mirror-tools'
        minimal_bin.mkdir()
        for program in ('awk', 'mv'):
            (minimal_bin / program).symlink_to(shutil.which(program))
        for original, expected in (
                ('# Official mirrors\n' + default, '# Official mirrors\n' + fallback + default),
                ('Server=http://mirror.archlinuxarm.org/$arch/$repo\n',
                 fallback + 'Server=http://mirror.archlinuxarm.org/$arch/$repo\n'),
                (fallback + default, fallback + default),
                (default + 'Server = https://custom/$arch/$repo\n',
                 default + 'Server = https://custom/$arch/$repo\n'),
                ('# No active mirrors\n', '# No active mirrors\n')):
            with self.subTest(original=original):
                mirrorlist.write_text(original)
                # EVENT_WAIT: isolated recipe function exits or fails its deadline.
                checked = subprocess.run([shutil.which('sh'), '-eu', '-c', function + '\narch_mirror_fallback "$1"',
                                          'fixture', str(mirrorlist)], capture_output=True,
                                         text=True, timeout=5, env={**os.environ, 'PATH': str(minimal_bin)})
                self.assertEqual(0, checked.returncode, checked.stderr)
                self.assertEqual(expected, mirrorlist.read_text())

    def test_gui_installs_gl_dispatch_libraries_with_mesa_drivers(self):
        result = self.run_script('--yes', '--gui', 'apps')
        self.assertEqual(0, result.returncode, result.stderr)
        setup = self.payload.read_text()
        start = setup.index('    case "$family" in\n        apt) packages dbus-x11')
        end = setup.index('\n    dbus-uuidgen --ensure', start)
        command = 'family=apt\npackages() { printf "%s\\n" "$@"; }\n' + setup[start:end]
        # EVENT_WAIT: package selection exits; timeout fails a stuck recipe.
        selected = subprocess.run(['sh', '-eu', '-c', command], capture_output=True,
                                  text=True, timeout=5)
        self.assertEqual(0, selected.returncode, selected.stderr)
        packages = set(selected.stdout.splitlines())
        self.assertTrue({'libgl1-mesa-dri', 'libegl-mesa0', 'libglx-mesa0',
                         'libegl1', 'libgl1', 'libgles2'} <= packages)

    def test_gui_initializes_only_missing_flatpak_repository_and_propagates_errors(self):
        result = self.run_script('--print-system-setup')
        self.assertEqual(0, result.returncode, result.stderr)
        setup = result.stdout
        start = setup.index('    # Recovery images can ship Flatpak')
        end = setup.index('    # Scheduling policy', start)
        self.assertTrue(setup[:start].endswith('if [ "$gui_services" = yes ]; then\n'))
        config = self.directory / 'flatpak-config'
        command = setup[start:end].replace('/var/lib/flatpak/repo/config', shlex.quote(str(config)))
        for present, existing, status in ((False, False, 0), (True, False, 0),
                                           (True, True, 0), (True, False, 23)):
            with self.subTest(present=present, existing=existing, status=status):
                config.unlink(missing_ok=True)
                if existing:
                    config.write_text('existing repository')
                stub = f'flatpak() {{ printf "CALL:%s\\n" "$*"; return {status}; }}\n' if present else ''
                # EVENT_WAIT: the setup fragment exits; timeout fails a stuck recipe.
                isolated = 'PATH=' + shlex.quote(str(self.directory)) + '\n' + stub + command
                checked = subprocess.run(['sh', '-eu', '-c', isolated], env=self.environment,
                                         capture_output=True, text=True, timeout=5)
                self.assertEqual(status, checked.returncode, checked.stderr)
                self.assertEqual(int(present and not existing), checked.stdout.count('CALL:repair --system'))
                if existing:
                    self.assertEqual('existing repository', config.read_text())

    def test_audio_session_owns_graph_and_keeps_host_transport_separate(self):
        result = self.run_script('--print-audio-session')
        self.assertEqual(0, result.returncode, result.stderr)
        helper = self.directory / 'audio-session'
        helper.write_text(result.stdout)
        self.assertEqual(0, subprocess.run(['bash', '-n', str(helper)]).returncode)
        self.assertIn('libpipewire-module-pulse-tunnel', result.stdout)
        self.assertIn('node.name = magicdesk.android', result.stdout)
        self.assertIn('node.virtual = false', result.stdout)
        self.assertIn('default.clock.min-quantum = 1024', result.stdout)
        self.assertIn('pulse.min.req = 1024/48000', result.stdout)
        self.assertIn('pulse.min.quantum = 1024/48000', result.stdout)
        self.assertIn('pulse.latency = 60', result.stdout)
        self.assertIn('pw-link --input --monitor', result.stdout)
        self.assertLess(result.stdout.index('Guest Android audio sink was not configured.'),
                        result.stdout.index('"$@" <&0'))
        self.assertLess(result.stdout.index('pipewire-pulse.conf.d/90-magicdesk.conf'),
                        result.stdout.index('libpipewire-module-pulse-tunnel'))
        self.assertIn('MAGICDESK_AUDIO_SESSION=active', result.stdout)
        self.assertIn('export DISABLE_RTKIT=1', result.stdout)
        self.assertIn('loop_config > "$work/config/$config.d/90-magicdesk-loop.conf"', result.stdout)
        self.assertIn('loop_config > "$work/wp/wireplumber.conf.d/90-magicdesk-loop.conf"', result.stdout)
        self.assertNotIn('module.rt.args', result.stdout)
        self.assertIn('EVENT_WAIT:', result.stdout)
        self.assertNotIn('sleep ', result.stdout)
        env = dict(self.environment)
        env.pop('MAGICDESK_AUDIO_PULSE', None)
        # EVENT_WAIT: without an audio capability, the original command exits normally.
        bypass = subprocess.run(['bash', str(helper), 'sh', '-c', 'exit 17'], env=env, timeout=5)
        self.assertEqual(17, bypass.returncode)
        nested = subprocess.run(['bash', str(helper), 'sh', '-c', 'exit 19'],
                                env=dict(env, MAGICDESK_AUDIO_PULSE='/unused',
                                         MAGICDESK_AUDIO_SESSION='active'), timeout=5)
        self.assertEqual(19, nested.returncode)
        config = subprocess.run(['bash', str(helper), '--print-loop-config'],
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(0, config.returncode, config.stderr)
        self.assertIn('context.data-loops = [', config.stdout)
        self.assertIn('thread.name = data-loop.0 loop.prio-inherit = false', config.stdout)
        self.assertNotIn('context.num-data-loops = 0', config.stdout)
        result = self.run_script('--yes', '--gui', 'plasma')
        self.assertEqual(0, result.returncode, result.stderr)
        setup = self.payload.read_text()
        self.assertIn('packages pipewire pipewire-bin pipewire-pulse wireplumber pipewire-alsa', setup)
        self.assertIn('Exec=magicdesk-desktop-session %s', setup)

    def test_audio_loop_policy_is_shared_by_clients_servers_and_nixos(self):
        setup = self.run_script('--print-system-setup').stdout
        start = setup.index('install_audio_session() {\n')
        end = setup.index('install_steam_game() {\n', start)
        root = self.directory / 'audio-install'
        (root / 'bin').mkdir(parents=True)
        command = setup[start:end].replace('/usr/local/bin', str(root / 'bin'))
        command = command.replace('/etc/pipewire', str(root / 'pipewire'))
        command = command.replace('/etc/wireplumber', str(root / 'wireplumber'))
        result = subprocess.run(['sh', '-eu', '-c', command + '\ninstall_audio_session\n'],
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        helper = root / 'bin/magicdesk-audio-session'
        expected = subprocess.check_output(['bash', str(helper), '--print-loop-config'], text=True)
        for name in ('client.conf', 'client-rt.conf', 'pipewire.conf', 'pipewire-pulse.conf'):
            self.assertEqual(expected, (root / 'pipewire' / (name + '.d') /
                                        '90-magicdesk-loop.conf').read_text())
        self.assertEqual(expected, (root / 'wireplumber/wireplumber.conf.d/90-magicdesk-loop.conf').read_text())
        nixos = self.run_script('--print-nixos-module').stdout
        self.assertIn('$out/bin/magicdesk-audio-session --print-loop-config', nixos)
        self.assertIn('for name in client.conf client-rt.conf pipewire.conf pipewire-pulse.conf;', nixos)
        self.assertIn('ln -s ../../pipewire-loop.conf', nixos)
        self.assertIn('services.pipewire.configPackages = lib.mkIf graphical [ tools ];', nixos)
        self.assertIn('lib.optionalAttrs (!config.services.pipewire.enable)', nixos)
        self.assertIn('"pipewire".source = "${tools}/share/pipewire";', nixos)
        self.assertNotIn('"pipewire/${name}.d/', nixos)
        self.assertIn('source = "${tools}/share/pipewire-loop.conf"', nixos)
        self.assertIn('wireplumber/wireplumber.conf.d/90-magicdesk-loop.conf', nixos)

    def test_fedora_prepares_guest_nftables_frontend_after_extra_packages(self):
        result = self.run_script('--print-system-setup')
        self.assertEqual(0, result.returncode, result.stderr)
        setup = result.stdout
        self.assertRegex(setup, r'dnf\) packages [^\n]* iptables-nft ;;')
        selection = 'alternatives --set iptables /usr/bin/iptables-nft'
        self.assertGreater(setup.index(selection), setup.index('packages $extras'))

    def test_opensuse_uses_packaged_nftables_alternative(self):
        setup = self.run_script('--print-system-setup').stdout
        start = setup.index('    zypper)\n        refresh_packages')
        end = setup.index(';;', start)
        self.assertIn('iptables-backend-nft', setup[start:end])

    def test_pacman_selects_nft_provider_after_container_packages(self):
        setup = self.run_script('--print-system-setup').stdout
        selection = 'pacman -S --needed --noconfirm --ask=4 iptables-nft </dev/null'
        self.assertIn('elif [ "$family" = pacman ]; then', setup)
        self.assertGreater(setup.index(selection), setup.index('packages $extras'))

    def test_void_selects_packaged_nft_alternative_after_extra_packages(self):
        setup = self.run_script('--print-system-setup').stdout
        start = setup.index('    xbps)\n        refresh_packages')
        end = setup.index(';;', start)
        self.assertIn('iptables-nft', setup[start:end])
        selection = 'xbps-alternatives -s iptables-nft -g iptables'
        self.assertGreater(setup.index(selection), setup.index('packages $extras'))

    def test_module_services_are_conditional_not_masked_in_containers(self):
        setup = self.run_script('--print-system-setup').stdout
        start = setup.index('# Module loading belongs to the host')
        end = setup.index('if [ -d /etc/runit ]', start)
        self.assertGreater(start, setup.index('packages $extras'))
        root = self.directory / 'systemd-root'
        root.mkdir()
        fragment = re.sub(r'/(?:usr/local/lib|usr/lib|lib|etc)/systemd/(?:system-generators|system)|/etc/(?:runit|sv)',
                          lambda match: str(root) + match[0], setup[start:end])
        # EVENT_WAIT: the isolated recipe fragment exits without host changes.
        def prepare():
            subprocess.run(['sh', '-eu', '-c', fragment], check=True, timeout=5)
        prepare()
        self.assertFalse((root / 'etc').exists())
        (root / 'usr/lib/systemd/system').mkdir(parents=True)
        prepare()
        for unit in ('systemd-modules-load.service', 'modprobe@.service'):
            path = root / 'etc/systemd/system' / (unit + '.d')
            owned = path / '50-magicdesk-container.conf'
            self.assertEqual('[Unit]\nConditionVirtualization=!container\n', owned.read_text())
            (path / '90-user.conf').write_text('[Unit]\nDescription=Retained\n')
        prepare()
        self.assertEqual('[Unit]\nDescription=Retained\n', (path / '90-user.conf').read_text())
        units = root / 'usr/lib/systemd/system'
        (units / 'deckard-fpga.service').write_text('[Service]\nExecStart=/bin/false\n')
        (units / 'home.mount').write_text('[Mount]\nWhat=/dev/disk/by-partsets/shared/home\nWhere=/home\n')
        override = root / 'etc/systemd/system/home.mount'
        override.write_text('[Mount]\nWhat=/dev/my-home\nWhere=/home\n')
        generator = root / 'usr/local/lib/systemd/system-generators/magicdesk-container-hardware'
        output = root / 'generated'
        output.mkdir()
        commands = root / 'bin'
        commands.mkdir()
        detect = commands / 'systemd-detect-virt'
        detect.write_text('#!/bin/sh\ntest "$*" = "--container --quiet" || exit 2\nexit "$TEST_NATIVE"\n')
        detect.chmod(0o755)
        env = {**os.environ, 'PATH': str(commands) + ':' + os.environ['PATH']}
        # EVENT_WAIT: generator completion; native boot must retain vendor units.
        subprocess.run(['sh', str(generator), str(output)], check=True,
                       env={**env, 'TEST_NATIVE': '1'}, timeout=5)
        self.assertEqual([], list(output.iterdir()))
        for _ in range(2):
            subprocess.run(['sh', str(generator), str(output)], check=True,
                           env={**env, 'TEST_NATIVE': '0'}, timeout=5)
        self.assertEqual({'home.mount', 'deckard-fpga.service'}, {p.name for p in output.iterdir()})
        self.assertTrue(all(p.is_symlink() and os.readlink(p) == '/dev/null' for p in output.iterdir()))
        self.assertEqual('[Mount]\nWhat=/dev/my-home\nWhere=/home\n', override.read_text())
        (output / 'home.mount').unlink()
        (units / 'home.mount').write_text('[Mount]\nWhat=/dev/other\nWhere=/home\n')
        subprocess.run(['sh', str(generator), str(output)], check=True,
                       env={**env, 'TEST_NATIVE': '0'}, timeout=5)
        self.assertFalse((output / 'home.mount').is_symlink())
        (units / 'proc-sys-fs-binfmt_misc.mount').write_text('[Mount]\nType=binfmt_misc\n')
        (units / 'systemd-binfmt.service').write_text('[Service]\nType=oneshot\n')
        for _ in range(2):
            subprocess.run(['sh', str(generator), str(output)], check=True,
                           env={**env, 'TEST_NATIVE': '0'}, timeout=5)
        self.assertEqual(str(units / 'proc-sys-fs-binfmt_misc.mount'),
                         os.readlink(output / 'sysinit.target.wants/proc-sys-fs-binfmt_misc.mount'))
        self.assertEqual('/dev/null', os.readlink(output / 'proc-sys-fs-binfmt_misc.automount'))
        self.assertFalse((output / 'systemd-binfmt.service').exists())
        self.assertFalse((output / 'proc-sys-fs-binfmt_misc.mount').exists())

    def test_pacman_keeps_installed_dependency_providers(self):
        setup = self.run_script('--print-system-setup').stdout
        fragment = setup[setup.index('packages() {'):setup.index('refresh_packages() {')]
        for family in ('pacman', 'recovery'):
            for code, missing, expected in ((0, '', ''), (127, 'meson\npkgconf', 'meson\npkgconf\n'),
                                            (42, '', '')):
                with self.subTest(family=family, code=code):
                    script = fragment + f'''\nfamily={family}
pacman() {{
    case "$1" in
        -T) printf %s {shlex.quote(missing)}; return {code} ;;
        -S) shift 3; printf '%s\\n' "$@" ;;
        *) return 99 ;;
    esac
}}
packages ninja meson pkgconf
'''
                    result = subprocess.run(['sh', '-eu', '-c', script], capture_output=True,
                                            text=True, timeout=5)
                    self.assertEqual(code if code not in (0, 127) else 0, result.returncode, result.stderr)
                    self.assertEqual(expected, result.stdout)

    def test_portable_initramfs_preserves_hooks_and_native_boot(self):
        setup = self.run_script('--print-system-setup').stdout
        start = setup.index('prepare_initramfs() {')
        fragment = setup[start:setup.index('refresh_packages() {', start)]
        root = self.directory / 'initramfs-root'
        root.mkdir()
        fragment = re.sub(r'/etc/(?:magicdesk|pacman.d|mkinitcpio.conf)|/usr/local/libexec',
                          lambda match: str(root) + match[0], fragment)
        def prepare():
            subprocess.run(['sh', '-eu', '-c', fragment + '\nprepare_initramfs'],
                           check=True, timeout=5)
        prepare()
        config = root / 'etc/mkinitcpio.conf'
        self.assertFalse(config.exists())
        original = 'HOOKS=(base autodetect custom fsck)\nMODULES=(btrfs)\n'
        config.write_text(original)
        config.chmod(0o640)
        prepare()
        once = config.read_text()
        self.assertTrue(once.startswith(original))
        self.assertEqual(0o640, config.stat().st_mode & 0o777)
        prepare()
        self.assertEqual(once, config.read_text())
        self.assertFalse(config.with_suffix('.conf.magicdesk-new').exists())
        hook = root / 'etc/pacman.d/hooks/80-magicdesk-initramfs-config.hook'
        self.assertIn('Target = etc/mkinitcpio.conf', hook.read_text())
        self.assertIn('When = PostTransaction', hook.read_text())
        helper = next(line.removeprefix('Exec = ') for line in hook.read_text().splitlines()
                      if line.startswith('Exec = '))
        config.write_text(original.replace('custom', 'updated'))
        subprocess.run(['sh', '-eu', helper], check=True, timeout=5)
        self.assertIn('updated', config.read_text())
        self.assertEqual(1, config.read_text().count(' || . '))
        for container, filesystem, expected in (
                ('shroot', 'ext4', 'base updated fsck'),
                ('', 'guestfs', 'base updated fsck'),
                ('', 'ext4', 'base autodetect updated fsck')):
            with self.subTest(container=container, filesystem=filesystem):
                script = ('findmnt() { printf %s ' + shlex.quote(filesystem) + '; };\n'
                          '. ' + shlex.quote(str(config)) + '\n'
                          'printf "%s\\n" "${HOOKS[*]}" "${MODULES[*]}"\n')
                result = subprocess.run(['bash', '-eu', '-c', script], capture_output=True,
                                        text=True, timeout=5,
                                        env={**os.environ, 'container': container})
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(expected + '\nbtrfs\n', result.stdout)
        refresh = setup[setup.index('refresh_packages() {'):setup.index('package_cleanup() {')]
        self.assertIn('pacman|recovery) prepare_initramfs', refresh)
        self.assertLess(refresh.index('prepare_initramfs'), refresh.index('pacman -Syu'))

    def test_runit_module_preparation_retains_native_boot_and_daemon(self):
        setup = self.run_script('--print-system-setup').stdout
        start = setup.index('if [ -d /etc/runit ]')
        fragment = setup[start:setup.index('if [ "$family" = dnf ]; then', start)]
        root = self.directory / 'runit-root'
        (root / 'etc/runit').mkdir(parents=True)
        service = root / 'etc/sv/example/run'
        service.parent.mkdir(parents=True)
        service.write_text('#!/bin/sh\nmodprobe -q loop || exit 1\nexec printf daemon-ok\n')
        service.chmod(0o750)
        alternate = root / 'etc/runit/sv/example/run'
        alternate.parent.mkdir(parents=True)
        alternate.write_text(service.read_text())
        alternate.chmod(0o750)
        hardware = root / 'usr/lib/rc/sv.d/hwclock'
        hardware.parent.mkdir(parents=True)
        hardware.write_text('#!/bin/bash\noptions=(--utc --noadjfile)\nprintf "hardware:%s" "${options[0]}"\n')
        hardware.chmod(0o750)
        application = hardware.with_name('sysctl')
        application.write_text('#!/bin/sh\nprintf real-application\n')
        fragment = re.sub(r'/etc/(?:runit|sv|pacman.d)|/usr/local/libexec|/usr/lib/rc/sv.d',
                          lambda match: str(root) + match[0], fragment)
        fragment = 'family=pacman\n' + fragment
        init_environment = root / 'init-environment'
        fragment = fragment.replace('/proc/1/environ', str(init_environment))
        for _ in range(2):
            subprocess.run(['sh', '-eu', '-c', fragment], check=True, timeout=5)
        self.assertEqual(1, service.read_text().count('# MagicDesk container module policy'))
        self.assertEqual(0o750, service.stat().st_mode & 0o777)
        self.assertEqual(service.read_text(), alternate.read_text())
        self.assertEqual(0o750, alternate.stat().st_mode & 0o777)
        self.assertEqual(1, hardware.read_text().count('# MagicDesk host hardware policy'))
        self.assertEqual(0o750, hardware.stat().st_mode & 0o777)
        self.assertEqual('#!/bin/sh\nprintf real-application\n', application.read_text())
        hook = (root / 'etc/pacman.d/hooks/95-magicdesk-container-services.hook').read_text()
        self.assertIn('When = PostTransaction', hook)
        self.assertIn('Operation = Install\nOperation = Upgrade', hook)
        self.assertIn('Type = Path', hook)
        for path in ('etc/sv/*/run', 'etc/runit/sv/*/run', 'usr/lib/rc/sv.d/*'):
            self.assertIn('Target = ' + path, hook)
        # A package replacement must receive the same policy without rerunning
        # the installer or replacing the package's new daemon command.
        alternate.write_text('#!/bin/sh\nmodprobe -q loop || exit 1\nexec printf updated-daemon\n')
        helper = next(line.removeprefix('Exec = ') for line in hook.splitlines()
                      if line.startswith('Exec = '))
        subprocess.run(['sh', '-eu', helper], check=True, timeout=5)
        self.assertIn('exec printf updated-daemon', alternate.read_text())
        self.assertEqual(1, alternate.read_text().count('# MagicDesk container module policy'))
        self.assertEqual(0o750, alternate.stat().st_mode & 0o777)
        marker = root / 'module-called'
        for container, init, in_container in (
                ('shroot', b'PATH=/bin\0', True),
                ('', b'PATH=/bin\0container=shroot\0TERM=linux\0', True),
                ('', b'PATH=/bin\0not_container=shroot\0', False),
                ('', b'container=\0', False),
                ('', None, False)):
            marker.unlink(missing_ok=True)
            if init is None:
                init_environment.unlink(missing_ok=True)
            else:
                init_environment.write_bytes(init)
            script = 'modprobe() { touch ' + shlex.quote(str(marker)) + '; return 1; };\n' + service.read_text()
            result = subprocess.run(['sh', '-c', script], env={**os.environ, 'container': container},
                                    capture_output=True, text=True, timeout=5)
            self.assertEqual(0 if in_container else 1, result.returncode, result.stderr)
            self.assertEqual('daemon-ok' if in_container else '', result.stdout)
            self.assertEqual(not in_container, marker.exists())
            result = subprocess.run(['bash', str(hardware)], env={**os.environ, 'container': container},
                                    capture_output=True, text=True, timeout=5)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual('' if in_container else 'hardware:--utc', result.stdout)

    def test_all_package_families_supply_audio_gamepads_and_wayland_decorations(self):
        self.assertEqual(0, self.run_script('--yes').returncode)
        setup = self.payload.read_text()
        start = setup.index('    case "$family" in\n        apt) packages pipewire')
        end = setup.index('\n    install_audio_session', start)
        expected = {
            'apt': {'pipewire-bin', 'pipewire-pulse', 'wireplumber', 'pipewire-alsa',
                    'libsdl2-2.0-0', 'libudev1', 'libdecor-0-plugin-1-cairo'},
            'apk': {'pipewire-tools', 'pipewire-pulse', 'wireplumber', 'pipewire-alsa',
                    'sdl2', 'eudev-libs', 'libdecor'},
            'dnf': {'pipewire-utils', 'pipewire-pulseaudio', 'wireplumber', 'pipewire-alsa',
                    'sdl2-compat', 'systemd-libs', 'systemd-pam', 'libdecor'},
            'pacman': {'pipewire-pulse', 'wireplumber', 'pipewire-alsa', 'libpulse',
                       'sdl2', 'systemd-libs', 'libdecor'},
            'zypper': {'pipewire-pulseaudio', 'pipewire-tools', 'wireplumber', 'pipewire-alsa',
                       'libSDL2-2_0-0', 'libudev1', 'libdecor'},
            'xbps': {'pipewire', 'wireplumber', 'alsa-pipewire', 'SDL2', 'eudev-libudev', 'libdecor'},
        }
        expected['recovery'] = expected['pacman']
        for family, libraries in expected.items():
            with self.subTest(family=family):
                command = 'ID=arch\nfamily=' + family + '\npackages() { printf "%s\\n" "$@"; }\n' + setup[start:end]
                # EVENT_WAIT: distribution dependency selection must exit without host changes.
                result = subprocess.run(['sh', '-eu', '-c', command], capture_output=True, text=True, timeout=5)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertTrue(libraries <= set(result.stdout.splitlines()), result.stdout)
        command = 'ID=artix\nfamily=pacman\npackages() { printf "%s\\n" "$@"; }\n' + setup[start:end]
        result = subprocess.run(['sh', '-eu', '-c', command], capture_output=True, text=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('libudev', result.stdout.splitlines())
        self.assertIn('sdl2-compat', result.stdout.splitlines())
        self.assertNotIn('systemd-libs', result.stdout.splitlines())
        self.assertNotIn('SDL_JOYSTICK_HIDAPI=', setup)
        self.assertNotIn('SDL_AUDIODRIVER=', setup)
        self.assertNotIn('QML_DISABLE', setup)
        self.assertNotIn('KWIN_NO_TIMER_QUERY', setup)

    def test_package_families_declare_mesa_tools_and_python_packaging(self):
        self.assertEqual(0, self.run_script('--yes', '--gpu', 'turnip').returncode)
        setup = Path(str(self.payload) + '.gpu').read_text()
        start = setup.index('    case "$family" in\n    apt) packages build-essential')
        end = setup.index('\n    esac', start) + len('\n    esac')
        for family, required in [('apt', {'python3-packaging'}),
                                 ('apk', {'py3-packaging'}),
                                 ('dnf', {'python3-packaging'}),
                                 ('pacman', {'python-packaging'}),
                                 ('recovery', {'python-packaging'}),
                                 ('zypper', {'python3-packaging', 'glslang-devel', 'python3-Mako', 'vulkan-tools'}),
                                 ('xbps', {'python3-packaging', 'glslang', 'python3-Mako', 'Vulkan-Headers', 'Vulkan-Tools'})]:
            with self.subTest(family=family):
                command = 'family=' + family + '\npackages() { printf "%s\\n" "$@"; }\n' + setup[start:end]
                # EVENT_WAIT: dependency selection exits without installing host packages.
                result = subprocess.run(['sh', '-eu', '-c', command], capture_output=True, text=True, timeout=5)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertTrue(required <= set(result.stdout.splitlines()), result.stdout)

    def test_desktop_entry_refresh_preserves_selection_and_distribution_entries(self):
        self.assertEqual(0, self.run_script('--yes').returncode)
        setup = self.payload.read_text()
        start = setup.index('if [ "$gui_services" = yes ]; then\n    # Refresh only')
        end = setup.index('\nif [ "$fonts" != keep ]; then', start)
        applications = self.directory / 'local/share/applications'
        applications.mkdir(parents=True)
        for program in ('xfce4-session', 'weston', 'gnome-shell', 'startplasma-x11', 'startplasma-wayland'):
            executable = self.directory / program
            executable.write_text('#!/bin/sh\nexit 0\n')
            executable.chmod(0o755)
        old = applications / 'magicdesk-xfce.desktop'
        old.write_text('installer entry')
        custom = applications / 'user.desktop'
        custom.write_text('user entry')
        command = setup[start:end].replace('/usr/local/', str(self.directory / 'local') + '/')
        titles = {'xfce': 'Xfce Desktop', 'weston': 'Weston Desktop', 'gnome': 'GNOME Shell (development kit)',
                  'plasma-x11': 'Plasma Desktop (x11)', 'plasma-wayland': 'Plasma Desktop (wayland)'}
        for gui, modes in [('keep', ['xfce']), ('plasma', ['xfce', 'plasma-x11', 'plasma-wayland']),
                           ('weston', ['xfce', 'plasma-x11', 'plasma-wayland', 'weston']),
                           ('gnome', list(titles))]:
            # EVENT_WAIT: entry refresh must exit and preserve unrelated files.
            result = subprocess.run(['sh', '-eu', '-c', command],
                                    env=dict(self.environment, gui=gui, gui_services='yes', NAME='Alpine Linux', ID='alpine'),
                                    capture_output=True, text=True, timeout=5)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual('user entry', custom.read_text())
            self.assertEqual({f'magicdesk-{mode}.desktop' for mode in modes},
                             {p.name for p in applications.glob('magicdesk-*.desktop')})
            for mode in modes:
                entry = (applications / f'magicdesk-{mode}.desktop').read_text()
                self.assertIn(f'Name=Alpine Linux - {titles[mode]}\n', entry)
                self.assertIn('Exec=magicdesk-desktop-session ' + mode + '\n', entry)
                self.assertIn('X-MagicDesk-GraphicsMode=desktop\n', entry)
        # Repeated refresh reads OS identity, not the previous label or the instance name.
        for name in ('Debian GNU/Linux', 'Ubuntu', 'Fedora Linux', 'Arch Linux ARM',
                     'openSUSE Tumbleweed', 'openKylin', 'Deepin', 'Manjaro ARM',
                     'Void', 'Armtix', 'SteamOS', '', None):
            with self.subTest(os_name=name):
                environment = dict(self.environment, gui='keep', gui_services='yes', ID='alpine')
                environment.pop('NAME', None)
                if name is not None:
                    environment['NAME'] = name
                result = subprocess.run(['sh', '-eu', '-c', command], env=environment,
                                        capture_output=True, text=True, timeout=5)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual('user entry', custom.read_text())
                for mode, title in titles.items():
                    entry = (applications / f'magicdesk-{mode}.desktop').read_text()
                    self.assertIn(f'Name={name or "alpine"} - {title}\n', entry)

    def test_desktop_launch_owns_audio_and_preserves_gamepad_for_each_protocol(self):
        audio = self.directory / 'magicdesk-audio-session'
        audio.write_text('#!' + sys.executable + '\nimport json, os, sys\n'
                         'print(json.dumps({"argv": sys.argv[1:], "env": dict(os.environ)}))\n'
                         'sys.exit(23)\n')
        audio.chmod(0o755)
        bus = self.directory / 'dbus-run-session'
        bus.write_text('#!/bin/sh\nshift\nexport DBUS_SESSION_BUS_ADDRESS=unix:path=/test-bus\nexec "$@"\n')
        bus.chmod(0o755)
        config = self.directory / 'kwriteconfig6'
        config.write_text('#!/bin/sh\nexit 0\n')
        config.chmod(0o755)
        home = self.directory / 'home'
        home.mkdir()
        environment = dict(self.environment, HOME=str(home))
        environment.pop('MAGICDESK_MESA_PREFIX', None)
        launcher = self.run_script('--print-desktop-session').stdout
        launcher = launcher.replace('/etc/profile.d/', str(self.directory / 'profiles') + '/')
        launcher = launcher.replace('/tmp/.X11-unix', str(self.directory / 'x11'))
        modes = {'xfce': 'xfce4-session', 'weston': 'weston', 'gnome': 'gnome-shell',
                 'plasma-x11': 'startplasma-x11', 'plasma-wayland': 'startplasma-wayland'}
        for mode, program in modes.items():
            for inherited in (False, True):
                with self.subTest(mode=mode, inherited=inherited):
                    environment.pop('DBUS_SESSION_BUS_ADDRESS', None)
                    if inherited:
                        environment['DBUS_SESSION_BUS_ADDRESS'] = 'unix:path=/existing-bus'
                    environment.update(MAGICDESK_GAMEPAD_ENDPOINT='abstract-gamepad',
                                       MAGICDESK_AUDIO_PULSE='/tmp/android-pulse')
                    # EVENT_WAIT: launcher reaches its audio owner; expiry fails the fixture.
                    launched = subprocess.run(['sh', '-c', launcher, 'desktop', mode, 'a b'],
                                              env=environment, capture_output=True, text=True, timeout=5)
                    self.assertEqual(23, launched.returncode, launched.stderr)
                    captured = json.loads(launched.stdout)
                    self.assertEqual(program, captured['argv'][0])
                    self.assertEqual('a b', captured['argv'][-1])
                    self.assertEqual('abstract-gamepad', captured['env']['MAGICDESK_GAMEPAD_ENDPOINT'])
                    self.assertEqual('/tmp/android-pulse', captured['env']['MAGICDESK_AUDIO_PULSE'])
                    self.assertEqual('unix:path=/existing-bus' if inherited else 'unix:path=/test-bus',
                                     captured['env']['DBUS_SESSION_BUS_ADDRESS'])

    def test_desktop_scope_owns_only_the_matching_user_bus(self):
        capture = self.directory / 'magicdesk-audio-session'
        capture.write_text('#!' + sys.executable + '\nimport json, os, sys\n'
                           'print(json.dumps({"argv": sys.argv[1:], "scope": os.environ.get("SCOPE")}))\n'
                           'sys.exit(23)\n')
        capture.chmod(0o755)
        scope = self.directory / 'systemd-run'
        scope.write_text('#!' + sys.executable + '\nimport json, os, sys\n'
                         'i = sys.argv.index("--")\n'
                         'os.environ["SCOPE"] = json.dumps(sys.argv[1:i])\n'
                         'os.execvp(sys.argv[i+1], sys.argv[i+1:])\n')
        scope.chmod(0o755)
        launcher = self.run_script('--print-desktop-session').stdout
        launcher = launcher.replace('/etc/profile.d/', str(self.directory / 'profiles') + '/')
        for address in ('unix:path=/run/user/1000/bus', 'unix:path=/private-bus'):
            environment = dict(self.environment, XDG_RUNTIME_DIR='/run/user/1000',
                               DBUS_SESSION_BUS_ADDRESS=address)
            result = subprocess.run(['sh', '-c', launcher, 'desktop', 'xfce', 'a b'],
                                    env=environment, capture_output=True, text=True, timeout=5)
            self.assertEqual(23, result.returncode, result.stderr)
            value = json.loads(result.stdout)
            self.assertEqual(['xfce4-session', 'a b'], value['argv'])
            if address.endswith('/1000/bus'):
                self.assertEqual(['--user', '--scope', '--collect', '--quiet',
                                  '--property=BindsTo=graphical-session.target'], json.loads(value['scope']))
            else:
                self.assertIsNone(value['scope'])

    def test_desktop_ready_publishes_only_present_display_and_audio_variables(self):
        publisher = self.directory / 'dbus-update-activation-environment'
        publisher.write_text('#!' + sys.executable + '\nimport json, sys\nprint(json.dumps(sys.argv[1:]))\n')
        publisher.chmod(0o755)
        manager = self.directory / 'systemd-run'
        manager.write_text('#!/bin/sh\nexit 1\n')
        manager.chmod(0o755)
        launcher = self.run_script('--print-desktop-session').stdout
        for systemd in (False, True):
            environment = dict(self.environment, XDG_RUNTIME_DIR='/run/user/1000',
                               DBUS_SESSION_BUS_ADDRESS='unix:path=/run/user/1000/bus' if systemd else 'unix:path=/private-bus',
                               WAYLAND_DISPLAY='wayland-1', PULSE_SERVER='unix:/session/audio')
            for key in ('DISPLAY', 'XDG_CURRENT_DESKTOP', 'XDG_SESSION_TYPE', 'PIPEWIRE_REMOTE', 'PIPEWIRE_RUNTIME_DIR'):
                environment.pop(key, None)
            result = subprocess.run(['sh', '-c', launcher, 'desktop', '--ready'],
                                    env=environment, capture_output=True, text=True, timeout=5)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual((['--systemd'] if systemd else []) + ['WAYLAND_DISPLAY', 'PULSE_SERVER'],
                             json.loads(result.stdout))
        setup = self.run_script('--print-system-setup').stdout
        self.assertIn('path=/usr/local/libexec/magicdesk-desktop-ready\n', setup)
        self.assertIn('exec magicdesk-desktop-session --ready\n', setup)

    def test_root_desktop_autostart_preserves_user_and_distribution_settings(self):
        setup = self.run_script('--print-system-setup').stdout
        start = setup.index('mkdir -p /etc/magicdesk/root-session/autostart')
        end = setup.index('\n}\n', start)
        profile = self.directory / 'root-session'
        command = setup[start:end].replace('/etc/magicdesk/root-session', str(profile))
        subprocess.run(['sh', '-eu', '-c', command], check=True, timeout=5)
        entry = (profile / 'autostart/xfce-polkit.desktop').read_text()
        self.assertIn('Hidden=true\n', entry)
        launcher = self.run_script('--print-desktop-session').stdout
        launcher = launcher.replace('/etc/profile.d/', str(self.directory / 'profiles') + '/')
        capture = self.directory / 'magicdesk-audio-session'
        capture.write_text('#!' + sys.executable + '\nimport json, os\nprint(json.dumps(dict(os.environ)))\n')
        capture.chmod(0o755)
        identity = self.directory / 'id'
        for uid in (0, 1000):
            identity.write_text('#!/bin/sh\nprintf "%s\\n" ' + str(uid) + '\n')
            identity.chmod(0o755)
            for directories in (None, '/custom/xdg:/etc/xdg'):
                environment = dict(self.environment, DBUS_SESSION_BUS_ADDRESS='unix:path=/existing-bus',
                                   XDG_CONFIG_HOME='/user/config')
                environment.pop('XDG_CONFIG_DIRS', None)
                if directories is not None:
                    environment['XDG_CONFIG_DIRS'] = directories
                result = subprocess.run(['sh', '-c', launcher, 'desktop', 'xfce'],
                                        env=environment, capture_output=True, text=True, timeout=5)
                self.assertEqual(0, result.returncode, result.stderr)
                values = json.loads(result.stdout)
                self.assertEqual('/user/config', values['XDG_CONFIG_HOME'])
                expected = '/etc/magicdesk/root-session:' + (directories or '/etc/xdg') if uid == 0 else directories
                self.assertEqual(expected, values.get('XDG_CONFIG_DIRS'))

    def test_desktop_profile_selects_renderer_and_private_kwin_without_driver_overrides(self):
        capture = self.directory / 'magicdesk-audio-session'
        capture.write_text('#!' + sys.executable + '\nimport json, os, sys\n'
                           'print(json.dumps({"argv": sys.argv[1:], "env": dict(os.environ)}))\n')
        capture.chmod(0o755)
        config = self.directory / 'kwriteconfig6'
        config.write_text('#!/bin/sh\nexit 0\n')
        config.chmod(0o755)
        profiles = self.directory / 'profiles'
        profiles.mkdir()
        kwin = self.directory / 'kwin'
        (kwin / 'bin').mkdir(parents=True)
        executable = kwin / 'bin/kwin_wayland'
        executable.write_text('#!/bin/sh\nexit 0\n')
        executable.chmod(0o755)
        selection = self.directory / 'plasma-kwin.sh'
        selection.write_text('kwin_prefix=' + shlex.quote(str(kwin)) + '\n')
        launcher = self.run_script('--print-desktop-session').stdout
        launcher = launcher.replace('/etc/profile.d/', str(profiles) + '/')
        launcher = launcher.replace('/etc/magicdesk/plasma-kwin.sh', str(selection))
        launcher = launcher.replace('/tmp/.X11-unix', str(self.directory / 'x11'))
        environment = dict(self.environment, HOME=str(self.directory / 'home'),
                           DBUS_SESSION_BUS_ADDRESS='unix:path=/existing-bus')
        for key in ('MAGICDESK_MESA_PREFIX', 'KWIN_COMPOSE', 'QT_QUICK_BACKEND'):
            environment.pop(key, None)
        for gpu in ('software', 'turnip'):
            (profiles / 'magicdesk-graphics.sh').write_text(
                'export MAGICDESK_MESA_PREFIX=/opt/private-mesa\n' if gpu == 'turnip' else
                'export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe\n')
            for mode in ('weston', 'plasma-wayland', 'plasma-x11'):
                with self.subTest(gpu=gpu, mode=mode):
                    # EVENT_WAIT: the launch reaches its audio owner with the selected graphics profile.
                    result = subprocess.run(['sh', '-c', launcher, 'desktop', mode],
                                            env=environment, capture_output=True, text=True, timeout=5)
                    self.assertEqual(0, result.returncode, result.stderr)
                    captured = json.loads(result.stdout)
                    env = captured['env']
                    if mode == 'weston':
                        self.assertIn('--renderer=' + ('gl' if gpu == 'turnip' else 'pixman'), captured['argv'])
                    elif mode == 'plasma-wayland' and gpu == 'software':
                        self.assertEqual('Q', env['KWIN_COMPOSE'])
                        self.assertEqual('software', env['QT_QUICK_BACKEND'])
                    elif mode == 'plasma-x11' and gpu == 'software':
                        self.assertNotIn('KWIN_COMPOSE', env)
                        self.assertEqual('software', env['QT_QUICK_BACKEND'])
                    else:
                        self.assertNotIn('KWIN_COMPOSE', env)
                        self.assertNotIn('QT_QUICK_BACKEND', env)
                    self.assertEqual(mode == 'plasma-wayland' and gpu == 'turnip',
                                     env['PATH'].startswith(str(kwin / 'bin') + ':'))
                    if gpu == 'turnip':
                        self.assertEqual('/opt/private-mesa', env['MAGICDESK_MESA_PREFIX'])
        selection.unlink()
        rejected = subprocess.run(['sh', '-c', launcher, 'desktop', 'plasma-wayland'],
                                  env=environment, capture_output=True, text=True, timeout=5)
        self.assertNotEqual(0, rejected.returncode)
        self.assertIn('Complete Plasma Turnip setup', rejected.stderr)

    def test_software_profile_uses_verified_lavapipe_on_steamos_only(self):
        payload = self.run_script('--print-graphics-setup', TEST_UID='10001').stdout
        root = self.directory / 'graphics-root'
        root.mkdir()
        release = root / 'os-release'
        payload = payload.replace('/etc/os-release', str(release))
        for original, name in (('/etc/profile.d', 'profiles'), ('/var/lib/magicdesk', 'state'),
                               ('/var/cache/magicdesk', 'cache'), ('/etc/magicdesk', 'config'),
                               ('/etc/shroot', 'shroot'),
                               ('/etc/vulkan', 'vulkan'), ('/etc/glvnd', 'glvnd'),
                               ('/usr/local', 'local'), ('/usr/share/flatpak/triggers', 'triggers')):
            payload = payload.replace(original, str(root / name))
        package_log = root / 'packages'
        for name, body in {
            'pacman': 'printf "%s\\n" "$*" >> ' + shlex.quote(str(package_log)),
            'gpgconf': 'exit 0',
            'vulkaninfo': 'test "${TEST_PROBE:-}" != fail; echo DRIVER_ID_MESA_LLVMPIPE',
            'eglinfo': ('test "$GALLIUM_DRIVER" = zink; test "$LIBGL_ALWAYS_SOFTWARE" = 1; '
                        'test "${MESA_VK_WSI_DEBUG-unset}" = unset; '
                        'test "$VK_DRIVER_FILES" = /usr/share/vulkan/icd.d/lvp_icd.aarch64.json; '
                        'test "${TEST_PROBE:-}" != wrong || { echo "OpenGL core profile renderer: hardware"; exit 0; }; '
                        'echo "OpenGL core profile renderer: zink Vulkan 1.3(llvmpipe)"'),
        }.items():
            script = self.directory / name
            script.write_text('#!/bin/sh\nset -eu\n' + body + '\n')
            script.chmod(0o755)
        profile = root / 'profiles/magicdesk-graphics.sh'
        for distro, outcome in (('steamos', 'pass'), ('steamos', 'fail'),
                                 ('steamos', 'wrong'), ('debian', 'pass')):
            with self.subTest(distro=distro, outcome=outcome):
                release.write_text('ID=' + distro + '\n')
                profile.parent.mkdir(exist_ok=True)
                profile.write_text('original profile\n')
                selection = root / 'shroot/render-node'
                selection.parent.mkdir(exist_ok=True)
                selection.write_text('turnip\n')
                package_log.write_text('')
                result = subprocess.run(['sh', '-c', payload, 'graphics', 'software', '1', 'fixture'],
                                        env=dict(self.environment, TEST_PROBE=outcome),
                                        capture_output=True, text=True, timeout=5)
                if outcome != 'pass':
                    self.assertNotEqual(0, result.returncode, result.stdout)
                    self.assertEqual('original profile\n', profile.read_text())
                    self.assertEqual('turnip\n', selection.read_text())
                    self.assertIn('profile is unchanged', result.stderr)
                else:
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertFalse(selection.exists())
                    self.assertIn('GALLIUM_DRIVER=' + ('zink' if distro == 'steamos' else 'llvmpipe'),
                                  profile.read_text())
                self.assertEqual(distro == 'steamos', 'vulkan-swrast llvm17-libs' in package_log.read_text())
                self.assertFalse(profile.with_name(profile.name + '.new').exists())

    def test_mixer_dependencies_refresh_for_existing_desktops(self):
        self.assertEqual(0, self.run_script('--yes').returncode)
        setup = self.payload.read_text()
        start = setup.index('    case "$ID" in\n        steamos) ;;')
        end = setup.index('\nfi\n', start)
        applications = self.directory / 'apps'
        applications.mkdir()
        snippet = setup[start:end].replace('/usr/local/share/applications', str(applications))
        command = 'packages() { printf "%s\\n" "$@"; }\n' + snippet
        for gui, existing, expected in (
                ('apps', (), {'pavucontrol'}),
                ('xfce', (), {'pavucontrol', 'xfce4-pulseaudio-plugin'}),
                ('keep', ('xfce', 'plasma-wayland'), {'pavucontrol', 'xfce4-pulseaudio-plugin', 'plasma-pa'})):
            for mode in existing:
                (applications / f'magicdesk-{mode}.desktop').touch()
            # EVENT_WAIT: dependency selection exits without starting a package manager.
            result = subprocess.run(['sh', '-eu', '-c', command],
                                    env=dict(self.environment, gui=gui, family='apt', ID='debian'),
                                    capture_output=True, text=True, timeout=5)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(expected, set(result.stdout.splitlines()))

    def test_gui_pipewire_clients_do_not_request_unavailable_realtime_services(self):
        result = self.run_script('--yes', '--gui', 'plasma')
        self.assertEqual(0, result.returncode, result.stderr)
        setup = self.payload.read_text()
        start = setup.index('    for config in client.conf client-rt.conf; do\n')
        end = setup.index('    done\n', start) + len('    done\n')
        directory = self.directory / 'pipewire'
        snippet = setup[start:end].replace('/etc/pipewire', str(directory))
        for config in ('client.conf', 'client-rt.conf'):
            folder = directory / (config + '.d')
            folder.mkdir(parents=True)
            (folder / '90-magicdesk-scheduling.conf').write_text('module.rt.args = {}\n')
            (folder / 'user.conf').write_text('retained')
        # EVENT_WAIT: owned configuration cleanup; timeout fails a stuck recipe.
        written = subprocess.run(['sh', '-eu', '-c', snippet], capture_output=True,
                                 text=True, timeout=5)
        self.assertEqual(0, written.returncode, written.stderr)
        for config in ('client.conf', 'client-rt.conf'):
            folder = directory / (config + '.d')
            self.assertFalse((folder / '90-magicdesk-scheduling.conf').exists())
            self.assertEqual('retained', (folder / 'user.conf').read_text())
        self.assertIn('export DISABLE_RTKIT=1', setup)
        self.assertLess(setup.index('if [ "$gui_services" = yes ]; then'), start)
        self.assertNotIn('rlimits.enabled = false', setup)
        self.assertNotIn('rt.time.hard =', setup)

    def test_application_paths_follow_guest_layout_and_keep_explicit_values(self):
        result = self.run_script('--yes', '--gui', 'plasma')
        self.assertEqual(0, result.returncode, result.stderr)
        setup = self.payload.read_text()
        profile = setup.split("<<'APPLICATIONS'\n", 1)[1].split('\nAPPLICATIONS\n', 1)[0]
        root = self.directory / 'application-paths'
        profile = profile.replace('/usr/lib', str(root / 'usr/lib'))
        command = profile + '\nprintf "%s" "${VLC_PLUGIN_PATH-unset}"'
        environment = dict(self.environment)
        environment.pop('VLC_PLUGIN_PATH', None)
        def run():
            # EVENT_WAIT: profile process exit; timeout fails a stuck recipe.
            value = subprocess.run(['sh', '-eu', '-c', command], env=environment,
                                   capture_output=True, text=True, timeout=5)
            self.assertEqual(0, value.returncode, value.stderr)
            return value.stdout
        self.assertEqual('unset', run())
        multiarch = root / 'usr/lib/aarch64-linux-gnu/vlc/plugins'
        multiarch.mkdir(parents=True)
        self.assertEqual(str(multiarch), run())
        native = root / 'usr/lib/vlc/plugins'
        native.mkdir(parents=True)
        self.assertEqual(str(native), run())
        for value in ('/custom/plugins', ''):
            environment['VLC_PLUGIN_PATH'] = value
            self.assertEqual(value, run())
        self.assertIn('. /etc/profile.d/magicdesk-applications.sh', setup)

    def test_steamos_uses_pinned_helper_and_local_oci_with_separate_library(self):
        result = self.run_script('--yes', '--distro', 'steamos', '--downloads', str(self.directory / 'downloads'))
        self.assertEqual(0, result.returncode, result.stderr)
        calls = self.calls()
        downloads = [c for c in calls if c[0] == 'download']
        self.assertEqual(4, len(downloads))
        self.assertTrue(all('--sha256' in c for c in downloads))
        installs = [c for c in calls if c[0] == 'install']
        self.assertIn('python@sha256:', installs[0][1])
        self.assertEqual(['--name', 'unpack', '--dns', 'system'], installs[0][2:])
        self.assertEqual(['--oci', '--name', 'steamos', '--dns', 'system'], installs[1][2:])
        reader = Path(str(self.payload) + '.reader').read_text()
        self.assertIn("'User': 'steamos'", reader)
        self.assertIn("parts['rootfs-A']", reader)
        setup = self.payload.read_text()
        self.assertIn("Updating SteamOS packages from the image repositories", setup)
        self.assertIn('refresh_packages\n        dbus-uuidgen --ensure', setup)
        self.assertIn('pacman -Syu --noconfirm </dev/null', setup)
        setup_index = next(i for i, c in enumerate(calls) if '-s' in c)
        steam_index = next(i for i, c in enumerate(calls) if any('steam.tar.zst' in a for a in c))
        self.assertLess(setup_index, steam_index)
        self.assertIn(['remove', 'unpack'], calls)
        self.assertEqual(['prune'], calls[-1])
        self.assertFalse(list(self.directory.glob('magicdesk-import-*')))

    def steam_game_fixture(self):
        helper = self.directory / 'steam-game.py'
        helper.write_text(self.run_script('--print-steam-game').stdout)
        tool = self.directory / 'FEX tool'
        tool.mkdir()
        manifest = {'emulator_v0': {
            'argv': './usr/bin/FEX', 'container_argv': ['./usr/bin/FEX', '--option'],
            'main_argv': './FEXCompatTool', 'server_argv': './usr/bin/FEXServerManager',
            'environment': {'FEX_PORTABLE': '1'}, 'container_environment': {'FEX_ROOTFS': ''},
            'required_architectures': ['aarch64-linux-gnu'], 'required_libraries': ['libc.so.6'],
        }}
        source = tool / 'emulator.json'
        source.write_text(json.dumps(manifest))
        capture = tool / 'capture.py'
        capture.write_text('import json, os, sys\nprint(json.dumps({"argv": sys.argv[1:], '
                           '"manifest": os.environ.get("PRESSURE_VESSEL_EMULATOR")}))\n')
        environment = dict(self.environment, XDG_CACHE_HOME=str(self.directory / 'cache'),
                           STEAM_COMPAT_TOOL_PATHS=str(tool))
        environment.pop('PRESSURE_VESSEL_EMULATOR', None)
        return helper, source, capture, environment

    def test_steam_game_derives_manifest_without_modifying_valve_or_arguments(self):
        helper, source, capture, environment = self.steam_game_fixture()
        original = source.read_bytes()
        command = [sys.executable, str(helper), sys.executable, str(capture), 'a b', '--test']
        # EVENT_WAIT: launcher replaces itself with the capture process; timeout fails the test.
        result = subprocess.run(command, env=environment, text=True, capture_output=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        captured = json.loads(result.stdout)
        self.assertEqual(['a b', '--test'], captured['argv'])
        target = Path(captured['manifest'])
        wrapped = json.loads(target.read_text())['emulator_v0']
        self.assertEqual([str(source.parent / 'usr/bin/FEX')], wrapped['argv'])
        self.assertEqual(['/usr/bin/sh', '-c'], wrapped['main_argv'][:2])
        self.assertEqual(str(source.parent / 'FEXCompatTool'), wrapped['main_argv'][-1])
        self.assertEqual('--option', wrapped['container_argv'][-1])
        self.assertEqual(['libc.so.6', 'libreadline.so.8'], wrapped['required_libraries'])
        self.assertEqual(original, source.read_bytes())
        repeated = subprocess.run(command, env=environment, text=True, capture_output=True, timeout=5)
        self.assertEqual(str(target), json.loads(repeated.stdout)['manifest'])
        updated = json.loads(original)
        updated['emulator_v0']['main_argv'] = './NewCompatTool'
        source.write_text(json.dumps(updated))
        changed = subprocess.run(command, env=environment, text=True, capture_output=True, timeout=5)
        self.assertNotEqual(str(target), json.loads(changed.stdout)['manifest'])

    def test_steam_game_respects_custom_emulator_and_native_commands(self):
        helper, source, capture, environment = self.steam_game_fixture()
        command = [sys.executable, str(helper), sys.executable, str(capture)]
        environment['PRESSURE_VESSEL_EMULATOR'] = '/custom/emulator.json'
        result = subprocess.run(command, env=environment, text=True, capture_output=True, timeout=5)
        self.assertEqual('/custom/emulator.json', json.loads(result.stdout)['manifest'])
        environment.pop('PRESSURE_VESSEL_EMULATOR')
        source.unlink()
        result = subprocess.run(command, env=environment, text=True, capture_output=True, timeout=5)
        self.assertIsNone(json.loads(result.stdout)['manifest'])

    def test_steam_game_finds_selected_tool_in_arguments(self):
        helper, source, capture, environment = self.steam_game_fixture()
        environment.pop('STEAM_COMPAT_TOOL_PATHS')
        result = subprocess.run([sys.executable, str(helper), sys.executable, str(capture)],
                                env=environment, text=True, capture_output=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        target = Path(json.loads(result.stdout)['manifest'])
        wrapped = json.loads(target.read_text())['emulator_v0']
        self.assertEqual(str(source.parent / 'FEXCompatTool'), wrapped['main_argv'][-1])

    def test_steam_container_environment_is_scoped_and_preserves_search_paths(self):
        helper, source, capture, environment = self.steam_game_fixture()
        result = subprocess.run([sys.executable, str(helper), sys.executable, str(capture)],
                                env=environment, text=True, capture_output=True, timeout=5)
        target = Path(json.loads(result.stdout)['manifest'])
        wrapper = json.loads(target.read_text())['emulator_v0']['main_argv'][2]
        capture.write_text('import json, os, sys\nprint(json.dumps([os.environ.get(k) for k in sys.argv[1:]]))\n')
        command = ['sh', '-c', wrapper, 'container', sys.executable, str(capture),
                   'FEX_ROOTFS', 'LD_LIBRARY_PATH', 'LIBGL_DRIVERS_PATH']
        for root, expected in [('', '/'), ('/selected', '/selected'), (None, None)]:
            env = dict(environment, MAGICDESK_MESA_PREFIX='/private mesa',
                       LD_LIBRARY_PATH='/runtime/lib', LIBGL_DRIVERS_PATH='/runtime/dri')
            env.pop('FEX_ROOTFS', None)
            if root is not None:
                env['FEX_ROOTFS'] = root
            result = subprocess.run(command, env=env, text=True, capture_output=True, timeout=5)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual([expected, '/private mesa/lib:/runtime/lib',
                              '/private mesa/lib/dri:/runtime/dri'], json.loads(result.stdout))
        env = dict(environment, FEX_ROOTFS='', LD_LIBRARY_PATH='/runtime/lib')
        env.pop('MAGICDESK_MESA_PREFIX', None)
        env.pop('LIBGL_DRIVERS_PATH', None)
        result = subprocess.run(command, env=env, text=True, capture_output=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['/', '/runtime/lib', None], json.loads(result.stdout))

    def test_steamos_rejects_package_recipes_before_download(self):
        for option in (('--gui', 'apps'), ('--fonts', 'cjk'),
                       ('--image', 'arbitrary:latest'), ('--package', 'firefox')):
            with self.subTest(option=option):
                result = self.run_script('--yes', '--distro', 'steamos', *option)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual([], self.calls())

    def test_recovery_failure_retains_work_and_never_imports_partial_output(self):
        result = self.run_script('--yes', '--distro', 'steamos', '--downloads', str(self.directory / 'downloads'),
                                 TEST_FAIL='converter')
        self.assertEqual(23, result.returncode)
        self.assertIn('retained for inspection', result.stderr)
        self.assertEqual(1, len([c for c in self.calls() if c[0] == 'install']))
        self.assertNotIn(['prune'], self.calls())

    def test_setup_marks_only_package_processes_as_unbooted(self):
        result = self.run_script('--yes', '--resume', '--name', 'steam',
                                 '--gui', 'plasma', '--gpu', 'software', TEST_DISTRO='steamos')
        self.assertEqual(0, result.returncode, result.stderr)
        executions = [call for call in self.calls() if call[0] in ('exec', 'login')]
        self.assertTrue(any('-s' in call for call in executions))
        self.assertTrue(any('-s' not in call for call in executions))
        for call in executions:
            package_setup = '-s' in call
            self.assertEqual(package_setup, 'SYSTEMD_IN_CHROOT=1' in call)
        for export in ('--print-system-setup', '--print-graphics-setup',
                       '--print-desktop-session', '--print-audio-session'):
            self.assertNotIn('SYSTEMD_IN_CHROOT', self.run_script(export).stdout)
        setup = self.run_script('--print-system-setup').stdout
        self.assertIn('install -d -o root -g root -m 1777 /tmp/.X11-unix /tmp/.ICE-unix', setup)

    def test_all_recipes_use_account_home_for_administrative_launches(self):
        for distro in ('debian', 'ubuntu', 'alpine', 'arch', 'fedora', 'opensuse',
                       'manjaro', 'void', 'armtix', 'openkylin', 'deepin', 'steamos'):
            with self.subTest(distro=distro):
                self.log.unlink(missing_ok=True)
                result = self.run_script('--yes', '--resume', '--name', 'work',
                                         '--gpu', 'turnip', TEST_DISTRO=distro)
                self.assertEqual(0, result.returncode, result.stderr)
                calls = self.calls()
                admin = [c for c in calls if '--user' in c and c[c.index('--user') + 1] == 'root']
                self.assertTrue(admin)
                for call in admin:
                    self.assertEqual(['login', 'work'], call[:2], call)
                    self.assertFalse(any(arg.startswith('HOME=') for arg in call), call)
                    self.assertEqual('/bin/sh', call[call.index('--') + 1], call)
                self.assertTrue(any('-s' in c and c[-3] == 'turnip' for c in admin))
                if distro == 'steamos':
                    steam = next(c for c in calls if c[0] == 'exec' and '.steam/installer.lock' in ' '.join(c))
                    self.assertNotIn('--user', steam)

    def test_steamos_resume_does_not_download_or_convert(self):
        result = self.run_script('--yes', '--resume', '--name', 'steam', TEST_DISTRO='steamos')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(all(c[0] not in ('install', 'download', 'prune') for c in self.calls()))

    def test_steamos_desktop_bootstrap_supplies_sdk_links_and_preserves_existing_paths(self):
        result = self.run_script('--yes', '--resume', '--name', 'steam', TEST_DISTRO='steamos')
        self.assertEqual(0, result.returncode, result.stderr)
        command = next(c[-1] for c in self.calls() if any('steam.tar.zst' in a for a in c))
        self.assertIn('tar --no-same-owner --zstd', command)
        command = command.replace('[ "$(id -un)" = steamos ]', ':')
        home = self.directory / 'steam-home'
        client = home / '.local/share/Steam'
        client.mkdir(parents=True)
        config = home / '.steam'
        config.mkdir()
        (config / 'sdk32').symlink_to('custom-missing-sdk')
        (config / 'sdk64').write_text('user-owned')
        for _ in range(2):
            # EVENT_WAIT: bootstrap exit; timeout fails a stuck client setup.
            setup = subprocess.run(['sh', '-c', command],
                                   env=dict(self.environment, HOME=str(home)),
                                   capture_output=True, text=True, timeout=5)
            self.assertEqual(0, setup.returncode, setup.stderr)
        self.assertEqual(client, (config / 'root').resolve())
        for link, target in (('sdkarm64', 'linuxarm64'), ('binarm64', 'steamrtarm64'),
                             ('bin32', 'ubuntu12_32'), ('bin64', 'ubuntu12_64')):
            self.assertEqual(client / target, (config / link).resolve())
        self.assertEqual('custom-missing-sdk', os.readlink(config / 'sdk32'))
        self.assertEqual('user-owned', (config / 'sdk64').read_text())

    def test_steamos_plasma_uses_matching_kwin_and_shared_gpu_recipe(self):
        result = self.run_script('--yes', '--resume', '--name', 'steam', '--gui', 'plasma',
                                 '--gpu', 'auto', TEST_DISTRO='steamos')
        self.assertEqual(0, result.returncode, result.stderr)
        kwin = Path(str(self.payload) + '.kwin').read_text()
        self.assertIn('version=6.2.5', kwin)
        self.assertIn('5cc450a6e41105c8c49929b72550b331237f96aafb294690f4707bdc5f776848', kwin)
        self.assertIn('m_presentationBuffer = m_backend->backend()->importBuffer', kwin)
        self.assertNotIn('bool WaylandCompositor::createRenderer()', kwin)
        self.assertIn('KGSL is unavailable', Path(str(self.payload) + '.gpu').read_text())
        self.assertIn('Plasma Desktop (wayland)', result.stdout)
        self.assertNotIn('Individual apps:', result.stdout)
        self.assertEqual(0, subprocess.run(['sh', '-n'], input=kwin, text=True).returncode)

    def test_steamos_defaults_to_plasma_and_automatic_gpu(self):
        result = self.run_script('--yes', '--distro', 'steamos', '--downloads', str(self.directory / 'downloads'))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('GUI=plasma | graphics=auto', result.stdout)
        self.assertTrue(Path(str(self.payload) + '.kwin').is_file())
        self.assertTrue(Path(str(self.payload) + '.gpu').is_file())

    def test_kwin_patch_versions_share_fence_contract(self):
        for version in ('6.2.5', '6.3.6', '6.6.6'):
            result = self.run_script('--print-kwin-patch', version, TEST_UID='10001')
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn('DMA_BUF_IOCTL_EXPORT_SYNC_FILE', result.stdout)
            self.assertIn('DMA_BUF_IOCTL_IMPORT_SYNC_FILE', result.stdout)
            self.assertIn('QCoreApplication::exit(1)', result.stdout)
            self.assertEqual([], self.calls())
        self.assertNotEqual(0, self.run_script('--print-kwin-patch', '6.4.0').returncode)

    def test_kwin_patch_versions_forward_client_capture_and_retain_host_request(self):
        for version in ('6.2.5', '6.3.6', '6.6.6'):
            with self.subTest(version=version):
                result = self.run_script('--print-kwin-patch', version, TEST_UID='10001')
                self.assertEqual(0, result.returncode, result.stderr)
                additions = '\n'.join(line[1:] for line in result.stdout.splitlines()
                                      if line.startswith('+') and not line.startswith('+++'))
                self.assertIn('&PointerInterface::focusedSurfaceChanged', additions)
                self.assertIn('&LockedPointerV1Interface::lockedChanged', additions)
                self.assertIn('PointerConstraints::LifeTime::Persistent', additions)
                self.assertIn('!locked && m_automaticPointerLock', additions)
                self.assertIn('m_automaticPointerLock = false;', additions)
                self.assertIn('KWayland::Client::TouchPoint', additions)
                self.assertEqual([], self.calls())

    def test_kwin_patch_versions_use_enter_coordinates_without_waiting_for_motion(self):
        for version in ('6.2.5', '6.3.6', '6.6.6'):
            with self.subTest(version=version):
                result = self.run_script('--print-kwin-patch', version, TEST_UID='10001')
                self.assertEqual(0, result.returncode, result.stderr)
                enter = result.stdout.split('connect(pointer, &Pointer::entered,', 1)[1]
                enter = enter.split('connect(pointer, &Pointer::left,', 1)[0]
                if version == '6.6.6':
                    self.assertIn('findSubSurface(m_pointer->enteredSurface())', enter)
                    self.assertIn('output->position() + relativeToSurface', enter)
                    self.assertIn('subsurface ? subsurface->position() : QPoint()', enter)
                    self.assertNotIn('output->geometry()', enter)
                else:
                    self.assertIn('+        const QPointF absolutePos = output->geometry().topLeft() + relativeToSurface;', enter)
                    self.assertNotIn('findSubSurface', enter)
                self.assertIn('std::chrono::steady_clock::now()', enter)
                self.assertIn('+        Q_EMIT pointerMotionAbsolute(absolutePos, timestamp, this);', enter)
                self.assertEqual([], self.calls())

    def test_steamos_update_failure_retains_environment_before_client_setup(self):
        result = self.run_script('--yes', '--resume', '--name', 'steam',
                                 TEST_DISTRO='steamos', TEST_FAIL='setup')
        self.assertEqual(23, result.returncode)
        self.assertIn('Environment retained', result.stderr)
        self.assertNotIn('Linux is ready', result.stdout)
        self.assertTrue(all(not any('steam.tar.zst' in a for a in c) for c in self.calls()))

    def test_steamos_package_failure_releases_already_imported_conversion(self):
        result = self.run_script('--yes', '--distro', 'steamos',
                                 '--downloads', str(self.directory / 'downloads'), TEST_FAIL='setup')
        self.assertEqual(23, result.returncode)
        self.assertIn('Environment retained', result.stderr)
        self.assertIn(['remove', 'unpack'], self.calls())
        self.assertEqual(['prune'], self.calls()[-1])
        self.assertFalse(list(self.directory.glob('magicdesk-import-*')))

    def test_steamos_existing_name_is_rejected_before_fetching(self):
        result = self.run_script('--yes', '--distro', 'steamos', TEST_ENVIRONMENTS='steamos\tsource\t/store')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('already exists', result.stderr)
        self.assertEqual([['--probe'], ['list']], self.calls())

    def test_steamos_work_cannot_overlap_read_only_cache(self):
        result = self.run_script('--yes', '--distro', 'steamos', '--downloads', str(self.directory),
                                 TMPDIR=str(self.directory / 'tmp'))
        self.assertNotEqual(0, result.returncode)
        self.assertIn('outside the read-only download cache', result.stderr)
        self.assertTrue(all(c[0] not in ('install', 'download') for c in self.calls()))

    def test_recovery_dns_failure_does_not_fetch_recovery_archive(self):
        result = self.run_script('--yes', '--distro', 'steamos', '--downloads', str(self.directory / 'downloads'),
                                 TEST_FAIL='install')
        self.assertEqual(23, result.returncode)
        self.assertTrue(all(c[0] != 'download' for c in self.calls()))

    def test_app_launch_hints_match_selected_protocol(self):
        for protocol in ("x11", "wayland", "both"):
            with self.subTest(protocol=protocol):
                result = self.run_script("--yes", "--name", "work", "--protocol", protocol)
                self.assertEqual(0, result.returncode, result.stderr)
                hints = result.stdout.split("Next steps:\n", 1)[1]
                self.assertIn("open Apps (Start)", hints)
                self.assertIn("Refresh beside the search field", hints)
                for backend in ("x11", "wayland"):
                    for title in ("Mousepad", "Thunar", "Xfce Terminal"):
                        self.assertEqual(protocol in (backend, "both"), f"{title} ({backend})" in hints)
                self.assertIn("not a whole Linux desktop", hints)
                self.assertNotIn("Whole desktop:", hints)
                self.assertIn("Shroot environments > work > New session", hints)
                self.assertIn("Console: shroot login work\n", hints)

    def test_desktop_launch_hints_match_generated_entry_not_app_protocol(self):
        profiles = (("xfce", "debian", "wayland", "Xfce Desktop"),
                    ("weston", "debian", "x11", "Weston Desktop"),
                    ("gnome", "fedora", "x11", "GNOME Shell (development kit)"))
        for gui, distro, protocol, title in profiles:
            with self.subTest(gui=gui):
                result = self.run_script("--yes", "--distro", distro, "--gui", gui, "--protocol", protocol)
                self.assertEqual(0, result.returncode, result.stderr)
                hints = result.stdout.split("Next steps:\n", 1)[1]
                self.assertIn(f"title='{title}'", self.payload.read_text())
                self.assertIn(title, hints)
                for _, _, _, other_title in profiles:
                    if other_title != title:
                        self.assertNotIn(other_title, hints)
                if gui == "gnome":
                    self.assertIn("requires a Linux system bus", hints)

    def test_console_only_hints_do_not_offer_uninstalled_gui(self):
        result = self.run_script("--yes", "--name", "tools", "--gui", "none")
        self.assertEqual(0, result.returncode, result.stderr)
        hints = result.stdout.split("Next steps:\n", 1)[1]
        self.assertIn("No GUI was installed by this run", hints)
        self.assertNotIn("open Apps", hints)
        self.assertNotIn("Individual apps:", hints)
        self.assertNotIn("Whole desktop:", hints)
        self.assertIn("Console: shroot login tools\n", hints)

    def test_plasma_recipe_is_explicit_pinned_and_separate_from_mesa(self):
        result = self.run_script("--yes", "--gui", "plasma", "--gpu", "turnip", "--jobs", "4")
        self.assertEqual(0, result.returncode, result.stderr)
        kwin = Path(str(self.payload) + ".kwin").read_text()
        gpu = Path(str(self.payload) + ".gpu").read_text()
        self.assertIn("version=6.3.6", kwin)
        self.assertIn("27f2205f06d58f1d1f480d2a94ae24022c2f95b9c1fdc5a549f8e143713fce12", kwin)
        self.assertIn('patch --batch --forward --fuzz=0', kwin)
        self.assertIn('mv "$work/stage$prefix" "$prefix"', kwin)
        self.assertIn('mv "$candidate" /etc/magicdesk/plasma-kwin.sh', kwin)
        self.assertIn('-DCMAKE_AUTOGEN_PARALLEL="$jobs"', kwin)
        self.assertIn('[ -n "${MAGICDESK_MESA_PREFIX:-}" ] || exit 0', kwin)
        self.assertNotIn("version=6.3.6", gpu)
        self.assertIn('export KWIN_COMPOSE=Q QT_QUICK_BACKEND=software', self.payload.read_text())
        plasma = self.run_script('--print-desktop-session').stdout
        self.assertLess(plasma.index('mkdir -p -m 1777 /tmp/.X11-unix'),
                        plasma.index('set -- startplasma-wayland'))
        self.assertIn('Plasma Desktop (wayland)', result.stdout)
        self.assertEqual("4", self.calls()[-1][-2])
        self.assertEqual(0, subprocess.run(["sh", "-n"], input=kwin, text=True).returncode)

    def test_plasma_rejects_unsupported_distributions_before_mutation(self):
        for distro in ("ubuntu", "alpine", "fedora", "arch"):
            result = self.run_script("--yes", "--distro", distro, "--gui", "plasma")
            self.assertNotEqual(0, result.returncode)
            self.assertIn("requires Debian 13", result.stderr)
            self.assertEqual([], self.calls())

    def test_kwin_recipe_identity_does_not_depend_on_build_parallelism(self):
        fingerprints = []
        for jobs in ("1", "4"):
            result = self.run_script("--yes", "--name", "plasma", "--resume",
                                     "--gui", "plasma", "--gpu", "turnip", "--jobs", jobs)
            self.assertEqual(0, result.returncode, result.stderr)
            fingerprints.append(self.calls()[-1][-1])
            self.assertEqual(jobs, self.calls()[-1][-2])
        self.assertRegex(fingerprints[0], r"^[0-9a-f]{64}$")
        self.assertEqual(*fingerprints)

    def test_compiler_jobs_default_to_four_for_all_distributions(self):
        for distro in ('debian', 'ubuntu', 'alpine', 'arch', 'steamos', 'fedora',
                       'opensuse', 'void', 'armtix', 'openkylin', 'deepin', 'manjaro', 'nixos'):
            with self.subTest(distro=distro):
                self.log.unlink(missing_ok=True)
                result = self.run_script('--yes', '--resume', '--name', 'work',
                                         '--gpu', 'turnip', TEST_DISTRO=distro)
                self.assertEqual(0, result.returncode, result.stderr)
                if distro == 'nixos':
                    self.assertEqual('4', self.system_setup_call()[-1])
                else:
                    builds = [c for c in self.calls() if 'MAGICDESK_BUILD_PHASE=build' in c]
                    self.assertTrue(builds)
                    self.assertTrue(all(c[-2] == '4' for c in builds), builds)

    def test_kwin_build_failure_preserves_environment_and_reports_resume(self):
        result = self.run_script("--yes", "--name", "plasma", "--resume", "--gui", "plasma",
                                 "--gpu", "turnip", TEST_FAIL="kwin")
        self.assertEqual(23, result.returncode, result.stderr)
        self.assertIn("preparing the nested Plasma compositor", result.stderr)
        self.assertIn("--name plasma --resume", result.stderr)
        self.assertIn("GUI=plasma GPU=turnip", result.stderr)
        self.assertNotIn("Linux is ready", result.stdout)
        self.assertNotIn("install", [args[0] for args in self.calls()])

    def test_kwin_patch_export_is_read_only_and_contains_buffer_sync(self):
        result = self.run_script("--print-kwin-patch", TEST_UID="10001")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], self.calls())
        self.assertIn('deviceFeedback ? 4 : 3', result.stdout)
        self.assertIn('DMA_HEAP_IOCTL_ALLOC', result.stdout)
        self.assertIn('DMA_BUF_IOCTL_EXPORT_SYNC_FILE', result.stdout)
        self.assertIn('DMA_BUF_IOCTL_IMPORT_SYNC_FILE', result.stdout)
        self.assertIn('m_dmaBufReads', result.stdout)
        self.assertIn('EglSwapchain::create', result.stdout)
        self.assertIn('device && device->supportsSyncObjTimelines()', result.stdout)
        self.assertIn('backend->graphicsBufferAllocator()', result.stdout)
        self.assertIn('m_eglDisplay->acquireDmaBuf(buffer, true)', result.stdout)
        self.assertIn('m_eglDisplay->releaseDmaBuf(m_current->buffer', result.stdout)
        self.assertIn('if (!compositor->createRenderer())', result.stdout)
        self.assertIn('QCoreApplication::exit(1)', result.stdout)
        self.assertNotIn('glFinish(', result.stdout)

    def test_resume_validates_without_reinstalling(self):
        result = self.run_script("--yes", "--name", "existing", "--resume")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["login", "--probe", "login", "login", "login", "login"], [args[0] for args in self.calls()])
        self.assertEqual(["sh", "debian", "13"], self.calls()[2][-3:])
        self.assertEqual(["keep", "both", "keep", "keep", "", "keep", "keep", ""], self.system_setup_call()[-8:])
        self.assertTrue(Path(str(self.payload) + '.weston').is_file())
        hints = result.stdout.split("Next steps:\n", 1)[1]
        self.assertIn("Existing GUI entries were preserved", hints)
        self.assertNotIn("Individual apps:", hints)
        self.assertNotIn("Whole desktop:", hints)
        self.assertIn("Console: shroot login existing\n", hints)

    def test_weston_maintenance_recipe_is_shared_and_installs_dependencies_first(self):
        result = self.run_script('--yes', '--resume', '--name', 'work', '--gui', 'weston',
                                 '--jobs', '3')
        self.assertEqual(0, result.returncode, result.stderr)
        calls = self.calls()
        phases = [(i, c) for i, c in enumerate(calls)
                  if any(a.startswith('MAGICDESK_BUILD_PHASE=') for a in c)]
        self.assertEqual(2, len(phases))
        dep_index, dependencies = phases[0]
        build_index, build = phases[1]
        self.assertIn('MAGICDESK_BUILD_PHASE=dependencies', dependencies)
        self.assertIn('MAGICDESK_BUILD_PHASE=build', build)
        self.assertEqual('3', build[-2])
        self.assertEqual(dependencies[-1], build[-1])
        self.assertEqual(dep_index + 1, build_index)
        recipe = Path(str(self.payload) + '.weston').read_text()
        self.assertEqual(0, subprocess.run(['sh', '-n'], input=recipe, text=True).returncode)
        self.assertIn('66252c9c5bbc45ab402e2518b76e6d824f09ebc1d0040ee2f51b096a1bc585b1', recipe)
        self.assertIn('gitlab.freedesktop.org/wayland/weston/-/releases/', recipe)
        self.assertIn('mv "$work/stage$prefix" "$prefix"', recipe)
        self.assertNotIn('LD_PRELOAD', recipe)

    def test_weston_maintenance_only_selects_installed_older_13_0(self):
        recipe = self.run_script('--print-weston-setup').stdout
        gate = recipe.split('[ -f /usr/local/share/applications/', 1)[1].split('version=13.0.4', 1)[0]
        gate = '[ -f /usr/local/share/applications/' + gate
        entry = self.directory / 'magicdesk-weston.desktop'
        selection = self.directory / 'weston.sh'
        version = self.directory / 'weston-version'
        executable = self.directory / 'weston'
        executable.write_text('#!/bin/sh\ncat ' + shlex.quote(str(version)) + '\n')
        executable.chmod(0o755)
        gate = gate.replace('/usr/local/share/applications/magicdesk-weston.desktop', str(entry))
        gate = gate.replace('/etc/magicdesk/weston.sh', str(selection)).replace('/usr/bin/weston', str(executable))
        for installed in (False, True):
            if installed: entry.touch()
            for release in ('13.0.0', '13.0.3', '13.0.4', '12.0.0', '14.0.0'):
                with self.subTest(installed=installed, release=release):
                    selection.touch()
                    version.write_text('weston ' + release + '\n')
                    result = subprocess.run(['sh', '-c', gate + '\nprintf build'],
                                            text=True, capture_output=True, timeout=5)
                    self.assertEqual(0, result.returncode, result.stderr)
                    selected = installed and release in ('13.0.0', '13.0.3')
                    self.assertEqual('build' if selected else '', result.stdout)
                    self.assertEqual(not installed or selected, selection.exists())

    def test_weston_build_failure_retains_environment_and_resume(self):
        result = self.run_script('--yes', '--resume', '--name', 'work', '--gui', 'weston',
                                 TEST_FAIL='weston')
        self.assertEqual(23, result.returncode, result.stderr)
        self.assertIn('Environment retained', result.stderr)
        self.assertIn('--resume', result.stderr)
        self.assertFalse(any(c[0] in ('install', 'remove') for c in self.calls()))

    def test_distribution_profiles_share_setup_and_selected_image(self):
        images = {"fedora": "registry.fedoraproject.org/fedora:44", "openkylin": "openkylin/openkylin:latest",
                  "deepin": "linuxdeepin/deepin:crimson-arm64"}
        for distro, source in images.items():
            for gui in ("none", "apps", "xfce", "weston"):
                with self.subTest(distro=distro, gui=gui):
                    self.log.unlink(missing_ok=True)
                    result = self.run_script("--yes", "--distro", distro, "--gui", gui)
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertEqual(["install", source, "--name", distro, "--dns", "system"], self.calls()[1])
                    self.assertEqual(gui, self.system_setup_call()[-8])
                    self.assertEqual(0, subprocess.run(["sh", "-n", str(self.payload)]).returncode)

    def test_official_rootfs_sources_are_verified_and_share_setup(self):
        profiles = ((distro, gui)
                    for distro in ('debian', 'ubuntu', 'alpine', 'arch', 'opensuse', 'manjaro', 'void', 'armtix')
                    for gui in ('none', 'apps', 'xfce', 'weston'))
        for distro, gui in profiles:
            with self.subTest(distro=distro, gui=gui):
                self.log.unlink(missing_ok=True)
                result = self.run_script('--yes', '--distro', distro, '--gui', gui,
                                         '--downloads', str(self.directory / 'downloads'))
                self.assertEqual(0, result.returncode, result.stderr)
                calls = self.calls()
                downloads = [c for c in calls if c[0] == 'download']
                if distro == 'opensuse':
                    self.assertEqual(2, len(downloads))
                    checksum = downloads.pop(0)
                    self.assertEqual(checksum[1], downloads[0][1] + '.sha256')
                    self.assertEqual(downloads[0][-1], 'a' * 64)
                self.assertEqual(1, len(downloads))
                self.assertTrue(downloads[0][1].startswith('https://'))
                self.assertEqual('--sha256', downloads[0][-2])
                self.assertRegex(downloads[0][-1], r'^[0-9a-f]{64}$')
                install = next(c for c in calls if '--rootfs' in c)
                self.assertEqual(['--rootfs', '--name', distro, '--dns', 'system'], install[2:])
                self.assertEqual(gui, next(c for c in calls if '-s' in c)[-8])
                self.assertEqual(0, subprocess.run(['sh', '-n', str(self.payload)]).returncode)
                if distro in ('debian', 'ubuntu', 'alpine', 'arch', 'manjaro'):
                    self.assertFalse(any(c[0] == 'exec' and c[1] == 'unpack' for c in calls))
                    self.assertEqual(downloads[0][2], install[1])
                else:
                    helper = next(c for c in calls if c[0] == 'install' and '--rootfs' not in c)
                    self.assertTrue(helper[1].startswith('python@sha256:'))
                    converter = next(c for c in calls if c[:2] == ['exec', 'unpack'])
                    self.assertIn('--bind-ro', converter)
                    self.assertIn('/mnt/rootfs.tar.gz', converter)
                    self.assertEqual([['remove', 'unpack'], ['prune']], calls[-2:])
                self.assertFalse(list(self.directory.glob('magicdesk-import-*')))

    def test_nixos_pinned_rootfs_uses_declarative_setup_once(self):
        result = self.run_script('--yes', '--distro', 'nixos', '--gui', 'xfce',
                                 '--gpu', 'turnip', '--jobs', '3', '--package', 'ripgrep')
        self.assertEqual(0, result.returncode, result.stderr)
        download = next(c for c in self.calls() if c[0] == 'download')
        self.assertIn('https://hydra.nixos.org/build/350465271/download/1/', download[1])
        self.assertEqual('333b1d718db3d99147ca2b5fb6d533b4aabaac98420d48e9a5369799433352ae', download[-1])
        setup = [c for c in self.calls() if c[0] == 'login' and '-s' in c]
        self.assertEqual(1, len(setup))
        self.assertEqual(['xfce', 'both', 'keep', 'keep', '', 'basic', 'keep', 'ripgrep', 'turnip', '3'], setup[0][-10:])
        script = self.payload.read_text()
        self.assertIn('nix-store --load-db < /nix-path-registration', script)
        self.assertIn('nixos-rebuild boot --no-reexec', script)
        self.assertIn('--option sandbox false --option filter-syscalls false', script)
        self.assertEqual(1, script.count('--option sandbox false'))
        self.assertIn('sandbox = true;', script)
        self.assertNotIn('export NIX_CONFIG=', script)
        self.assertIn('patchShebangs --build .', script)
        self.assertEqual(1, script.count('unshare --mount'))
        self.assertIn('"$system/sw/bin/bash" -s -- "$system" "$@"', script)
        pts = script.index('mount -t devpts -o newinstance,ptmxmode=0666,mode=0620 devpts /dev/pts')
        activate = script.index('\n/run/current-system/activate\n')
        rebuild = script.index('nixos-rebuild boot --no-reexec')
        self.assertLess(pts, activate)
        self.assertLess(activate, rebuild)
        self.assertLess(rebuild, script.index('\n/nix/var/nix/profiles/system/activate\n'))
        self.assertLess(rebuild, script.index('\nNIXOS_ENVIRONMENT\n'))
        self.assertGreater(script.index('current=/nix/var/nix/profiles/system'),
                           script.index('\nNIXOS_ENVIRONMENT\n'))
        self.assertTrue(script.rstrip().endswith('ln -sfn "$(readlink -f "$current")" /run/current-system'))
        self.assertNotIn('apt-get', script)
        self.assertEqual(0, subprocess.run(['sh', '-n', str(self.payload)]).returncode)
        self.assertIn(self.run_script('--print-mesa-patch').stdout.strip(), script)
        self.assertIn(self.run_script('--print-desktop-session').stdout.strip(), script)
        self.assertIn(self.run_script('--print-audio-session').stdout.strip(), script)

    def test_nixos_resume_retains_configuration_and_does_not_run_fhs_builders(self):
        result = self.run_script('--yes', '--resume', '--name', 'existing', TEST_DISTRO='nixos')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(any(c[0] in ('install', 'download', 'dns') for c in self.calls()))
        setups = [c for c in self.calls() if c[0] == 'login' and '-s' in c]
        self.assertEqual(1, len(setups))
        self.assertEqual('keep', setups[0][-10])
        script = self.payload.read_text()
        self.assertIn('old.desktops ++', script)
        self.assertIn('old.packages ++', script)
        self.assertIn('old.users ++', script)
        self.assertIn('grep -Fxq "$stock" configuration.nix', script)
        self.assertIn('Add ./magicdesk.nix to the imports', script)
        self.assertNotIn('cat > configuration.nix', script)
        self.assertNotIn('nix-collect-garbage -', script)

    def test_nixos_module_uses_native_generations_and_shared_helpers(self):
        module = self.run_script('--print-nixos-module').stdout
        self.assertIn('boot.isContainer = true;', module)
        self.assertIn('sandbox = true;', module)
        self.assertIn('settings.packages', module)
        self.assertIn('settings.users', module)
        self.assertIn('hardware.graphics', module)
        self.assertIn('lib.makeLibraryPath [ pkgs.libglvnd pkgs.vulkan-loader mesa ]', module)
        for option in ('autostart', 'icons', 'mime', 'sounds'):
            self.assertIn(f'xdg.{option}.enable = lib.mkOverride 900 graphical;', module)
        self.assertIn('X-MagicDesk-GraphicsMode = "desktop";', module)
        self.assertIn('/nix/var/nix/profiles/system/sw/share/$name', module)
        self.assertIn('. /etc/profile.d/magicdesk-graphics.sh', module)
        self.assertIn('${./magicdesk-files/magicdesk-desktop-session}', module)
        self.assertIn('${./magicdesk-files/magicdesk-audio-session}', module)
        self.assertNotIn('files = ./magicdesk-files;', module)
        self.assertNotIn('mkForce', module)

    def test_nixos_flatpak_uses_shared_publisher_and_packaged_triggers(self):
        module = self.run_script('--print-nixos-module').stdout
        setup = self.run_script('--print-nixos-setup').stdout
        self.assertIn(self.run_script('--print-flatpak-gl-helper').stdout.strip(), setup)
        self.assertIn('${./magicdesk-files/mesa-probe.c}', module)
        self.assertIn('++ lib.optional turnip pkgs.stdenv.cc;', module)
        self.assertIn('${lib.optionalString turnip', module)
        self.assertIn('${pkgs.bubblewrap}/bin/bwrap', module)
        self.assertIn('${config.services.flatpak.package}/share/flatpak/triggers/.', module)
        self.assertIn('systemd.services.flatpak-system-helper.environment = lib.mkIf', module)
        self.assertIn('environment.sessionVariables = lib.mkIf', module)
        self.assertIn('export FLATPAK_TRIGGERSDIR=${flatpakTriggers}', module)
        self.assertIn('graphical && config.services.flatpak.enable', module)
        self.assertNotIn('graphical && turnip && config.services.flatpak.enable', module)
        self.assertNotIn('flatpak.overrideAttrs', module)
        self.assertIn('index=$1/exports/share/icons/hicolor/index.theme', module)
        self.assertIn('test -f "$index" && ! test -L "$index"', module)
        self.assertIn('${pkgs.coreutils}/bin/chmod u+w -- "$index"', module)
        self.assertIn('exec ${config.services.flatpak.package}/share/flatpak/triggers/gtk-icon-cache.trigger "$@"', module)
        graphics = self.run_script('--print-graphics-setup').stdout
        source = graphics.split('cat > "$work/flatpak-gl-probe.c" <<\'PROBE\'\n', 1)[1].split('\nPROBE\n', 1)[0]
        self.assertIn(source, setup)

    def test_nixos_graphics_profile_uses_shared_owned_path_cleanup(self):
        graphics = self.run_script('--print-graphics-setup').stdout
        reset = graphics.split("reset_profile() {\ncat <<'PROFILE'\n", 1)[1].split('\nPROFILE\n', 1)[0]
        setup = self.run_script('--print-nixos-setup').stdout
        exported = setup.split("<<'NIXOS_GRAPHICS_RESET'\n", 1)[1].split('\nNIXOS_GRAPHICS_RESET\n', 1)[0]
        self.assertEqual(reset, exported)
        module = self.run_script('--print-nixos-module').stdout
        self.assertIn('builtins.readFile ./magicdesk-files/graphics-profile-reset.sh', module)
        self.assertIn('export MAGICDESK_GRAPHICS_LIBRARY_PATH=${lib.makeLibraryPath', module)
        environment = dict(self.environment,
            MAGICDESK_GRAPHICS_LIBRARY_PATH='/old/glvnd:/old/vulkan:/old/mesa/lib',
            MAGICDESK_MESA_PREFIX='/old/mesa',
            LD_LIBRARY_PATH='/custom:/old/glvnd:/old/vulkan:/old/mesa/lib:/custom/lib[*]',
            VK_DRIVER_FILES='/old/turnip.json', VK_ICD_FILENAMES='/old/turnip.json',
            PRESSURE_VESSEL_FILESYSTEMS_RO='/custom:/old/mesa')
        result = subprocess.run(['sh', '-eu', '-c', reset + '\n' + reset + '\n'
            'printf "%s|%s|%s|%s|%s" "$LD_LIBRARY_PATH" "$PRESSURE_VESSEL_FILESYSTEMS_RO" '
            '"${MAGICDESK_GRAPHICS_LIBRARY_PATH-unset}" "${VK_DRIVER_FILES-unset}" "${VK_ICD_FILENAMES-unset}"'],
            env=environment, capture_output=True, text=True, check=True, timeout=5)
        self.assertEqual('/custom:/custom/lib[*]|/custom|unset|unset|unset', result.stdout)

    def test_nixos_mesa_retains_relative_loader_paths_for_export(self):
        setup = self.run_script('--print-nixos-setup').stdout
        for option in ('-Dc_link_args=-Wl,-rpath,$ORIGIN:$ORIGIN/..',
                       '-Dcpp_link_args=-Wl,-rpath,$ORIGIN:$ORIGIN/..',
                       '-Dgbm-backends-path=$ORIGIN/gbm'):
            self.assertIn('"' + option + '"', setup)

    def test_nixos_defaults_to_the_complete_plasma_module(self):
        result = self.run_script('--yes', '--distro', 'nixos')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('GUI=plasma', result.stdout)
        module = self.run_script('--print-nixos-module').stdout
        self.assertIn('services.desktopManager.plasma6.enable = selected "plasma";', module)
        self.assertIn('(desktop "plasma-x11"', module)
        self.assertIn('(desktop "plasma-wayland"', module)
        self.assertNotIn('environment.plasma6.excludePackages', module)
        self.assertIn('pkgs.kdePackages.kwin.overrideAttrs', module)
        self.assertIn('kwin_prefix=${kwin}', module)
        self.assertIn('patch --batch --forward --fuzz=0 -p1 < ${./magicdesk-files/kwin.patch}', module)
        self.assertIn('cmakeFlagsArray+=("-DCMAKE_AUTOGEN_PARALLEL=$NIX_BUILD_CORES")', module)
        self.assertNotIn('patchFlags =', module)
        self.assertIn('cat > magicdesk-files/kwin.patch', self.payload.read_text())
        self.assertFalse(Path(str(self.payload) + '.kwin').exists())

    def test_nixos_unactivated_image_uses_stock_activation_in_private_mounts(self):
        result = self.run_script('--yes', '--distro', 'nixos', '--resume', '--name', 'fresh',
                                 TEST_DISTRO='nixos', TEST_NIXOS_UNACTIVATED='1')
        self.assertEqual(0, result.returncode, result.stderr)
        bootstrap = Path(str(self.payload) + '.bootstrap').read_text()
        self.assertIn('[ "$ID" = nixos ] && [ "$VERSION_ID" = 26.05 ]', bootstrap)
        self.assertIn('unshare --mount', bootstrap)
        self.assertIn('mount -t proc proc /proc', bootstrap)
        self.assertIn('mount -t devpts -o newinstance,ptmxmode=0666,mode=0620 devpts /dev/pts', bootstrap)
        self.assertIn('ln -s pts/ptmx /dev/ptmx', bootstrap)
        self.assertIn('exec "$system/activate"', bootstrap)
        self.assertLess(bootstrap.index('exec "$system/activate"'),
                        bootstrap.index('\nNIXOS_ENVIRONMENT\n'))
        self.assertTrue(bootstrap.rstrip().endswith('ln -sfn "$(readlink -f "$current")" /run/current-system'))
        self.assertNotIn('|| true', bootstrap)
        self.assertNotIn('cat > /etc/passwd', bootstrap)
        self.assertNotIn('ln -s /nix/store', bootstrap)
        self.assertEqual(0, subprocess.run(['sh', '-n'], input=bootstrap, text=True).returncode)

    def test_rootfs_converter_preserves_uncompressed_tar_bytes(self):
        result = self.run_script('--yes', '--distro', 'void', '--downloads', str(self.directory / 'downloads'))
        self.assertEqual(0, result.returncode, result.stderr)
        converter = next(c for c in self.calls() if c[:2] == ['exec', 'unpack'])
        code = converter[converter.index('-c') + 1]
        original = bytes(range(256)) * 5000
        source, target = self.directory / 'source.xz', self.directory / 'target.gz'
        source.write_bytes(lzma.compress(original))
        # EVENT_WAIT: streaming decompressor exits; timeout fails the conversion test.
        converted = subprocess.run([sys.executable, '-c', code, str(source), str(target)], timeout=10)
        self.assertEqual(0, converted.returncode)
        self.assertEqual(original, gzip.decompress(target.read_bytes()))

    def test_rootfs_download_failure_never_imports_or_starts_converter(self):
        for distro in ('debian', 'arch', 'opensuse'):
            with self.subTest(distro=distro):
                self.log.unlink(missing_ok=True)
                result = self.run_script('--yes', '--distro', distro, TEST_FAIL='download')
                self.assertEqual(23, result.returncode)
                self.assertIn('test download/checksum failure', result.stderr)
                self.assertNotIn('Linux is ready', result.stdout)
                self.assertEqual(['--probe', 'list', 'download'], [c[0] for c in self.calls()])

    def test_rootfs_cache_with_wrong_digest_is_not_imported(self):
        cache = self.directory / 'downloads'
        cache.mkdir()
        (cache / 'rootfs.tar.gz').write_bytes(b'corrupt cached rootfs')
        result = self.run_script('--yes', TEST_FAIL='download')
        self.assertEqual(23, result.returncode)
        self.assertTrue(any(c[0] == 'download' for c in self.calls()))
        self.assertFalse(any(c[0] == 'install' for c in self.calls()))

    def test_rolling_rootfs_uses_upstream_checksum_before_archive(self):
        result = self.run_script('--yes', '--distro', 'opensuse')
        self.assertEqual(0, result.returncode, result.stderr)
        downloads = [c for c in self.calls() if c[0] == 'download']
        self.assertEqual(2, len(downloads))
        self.assertEqual(downloads[1][1] + '.sha256', downloads[0][1])
        self.assertEqual(['--sha256', 'a' * 64], downloads[1][-2:])
        self.assertNotIn('Snapshot', downloads[1][1])

    def test_rolling_rootfs_rejects_invalid_or_ambiguous_checksums(self):
        name = 'opensuse-tumbleweed-image.aarch64-lxc.tar.xz'
        line = 'b' * 64 + '  ' + name + '\n'
        for checksum in ('bad', 'c' * 64 + '  other.tar.xz\n', line + line, 'g' * 64 + '  ' + name):
            with self.subTest(checksum=checksum):
                self.log.unlink(missing_ok=True)
                result = self.run_script('--yes', '--distro', 'opensuse', TEST_CHECKSUM=checksum)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('Invalid official checksum', result.stderr)
                self.assertEqual(1, len([c for c in self.calls() if c[0] == 'download']))
                self.assertFalse(any(c[0] == 'install' for c in self.calls()))

    def test_rootfs_failure_retains_conversion_but_does_not_import_partial_output(self):
        result = self.run_script('--yes', '--distro', 'void', '--downloads', str(self.directory / 'downloads'),
                                 TEST_FAIL='converter')
        self.assertEqual(23, result.returncode)
        self.assertIn('work retained', result.stderr)
        self.assertFalse(any('--rootfs' in c or c[0] == 'remove' for c in self.calls()))

    def test_rootfs_recipes_accept_explicit_oci_override_without_converter(self):
        for distro in ('debian', 'ubuntu', 'alpine', 'arch', 'opensuse', 'manjaro', 'void', 'armtix'):
            with self.subTest(distro=distro):
                self.log.unlink(missing_ok=True)
                source = 'example/' + distro + ':custom'
                result = self.run_script('--yes', '--distro', distro, '--image', source)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(['install', source, '--name', distro, '--dns', 'system'], self.calls()[1])
                self.assertFalse(any(c[0] == 'download' for c in self.calls()))

    def test_resume_normalizes_distribution_ids_without_downloading(self):
        for os_id, recipe in (('ubuntu', 'ubuntu'), ('alpine', 'alpine'), ('archarm', 'arch'),
                              ('opensuse-tumbleweed', 'opensuse'), ('openkylin', 'openkylin'),
                              ('deepin', 'deepin'), ('manjaro-arm', 'manjaro'), ('void', 'void'), ('artix', 'armtix')):
            with self.subTest(os_id=os_id):
                self.log.unlink(missing_ok=True)
                result = self.run_script('--yes', '--resume', '--name', 'existing', '--distro', recipe,
                                         TEST_DISTRO=os_id)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(recipe, self.calls()[2][-2])
                self.assertFalse(any(c[0] in ('download', 'install') for c in self.calls()))

    def test_settings_are_literal_arguments_and_additional_packages_accumulate(self):
        result = self.run_script("--yes", "--distro", "ubuntu", "--protocol", "wayland", "--locale", "fr_FR.UTF-8",
                                 "--timezone", "Europe/Paris", "--create-user", "developer", "--fonts", "cjk",
                                 "--cache", "clean", "--package", "git", "--package", "gdb")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["apps", "wayland", "fr_FR.UTF-8", "Europe/Paris", "developer", "cjk", "clean", "git gdb"],
                         self.system_setup_call()[-8:])
        self.assertIn("--user developer", result.stdout)
        self.assertIn("--bind", result.stdout)
        self.assertIn("--magicdesk", result.stdout)

    def test_new_install_uses_first_current_android_locale_for_every_recipe(self):
        for distro in ('debian', 'ubuntu', 'alpine', 'fedora', 'arch', 'opensuse',
                       'openkylin', 'deepin', 'manjaro', 'void', 'armtix', 'steamos', 'nixos'):
            with self.subTest(distro=distro):
                self.log.unlink(missing_ok=True)
                result = self.run_script('--yes', '--distro', distro,
                                         TEST_ANDROID_LOCALES='pt-BR,en-US', TEST_ANDROID_LOCALE='de-DE')
                self.assertEqual(0, result.returncode, result.stderr)
                setup = self.system_setup_call()
                self.assertEqual('pt_BR.UTF-8', setup[setup.index('-s') + 4])
                self.assertIn('Locale=pt_BR.UTF-8', result.stdout)

    def test_android_locale_property_is_only_a_missing_settings_fallback(self):
        for locales, status in (('null', '0'), ('', '0'), ('ja-JP', '1')):
            with self.subTest(locales=locales, status=status):
                self.log.unlink(missing_ok=True)
                result = self.run_script('--yes', TEST_ANDROID_LOCALES=locales,
                                         TEST_SETTINGS_STATUS=status, TEST_ANDROID_LOCALE='fr-CA')
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual('fr_CA.UTF-8', self.system_setup_call()[-6])

    def test_locale_does_not_guess_region_script_or_another_preferred_language(self):
        for tag in ('', 'null', 'de', 'sr-Latn-RS', 'es-419', 'en-US-POSIX', 'bad,en-US', '$(id)'):
            with self.subTest(tag=tag):
                self.log.unlink(missing_ok=True)
                result = self.run_script('--yes', TEST_ANDROID_LOCALES=tag)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual('keep', self.system_setup_call()[-6])
                self.assertIn('retaining the guest locale', result.stderr)

    def test_locale_ignores_android_formatting_extensions(self):
        result = self.run_script('--yes', TEST_ANDROID_LOCALES='en-GB-u-hc-h12')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('en_GB.UTF-8', self.system_setup_call()[-6])

    def test_explicit_locale_and_resume_do_not_follow_android_changes(self):
        for options, expected in ((('--locale', 'keep'), 'keep'),
                                  (('--locale', 'C.UTF-8'), 'C.UTF-8'),
                                  (('--locale', 'de_DE.UTF-8'), 'de_DE.UTF-8'),
                                  (('--resume', '--name', 'work'), 'keep'),
                                  (('--resume', '--name', 'work', '--locale', 'system'), 'ja_JP.UTF-8')):
            with self.subTest(options=options):
                self.log.unlink(missing_ok=True)
                result = self.run_script('--yes', *options, TEST_ANDROID_LOCALES='ja-JP')
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(expected, self.system_setup_call()[-6])

    @unittest.skipUnless(hasattr(os, 'openpty'), 'PTY required')
    def test_interactive_locale_defaults_to_android_but_resume_preserves_guest(self):
        output = self.questions(b'debian\nwork\nnone\nkeep\n\nkeep\nkeep\nn\ny\n',
                                TEST_ANDROID_LOCALES='de-DE')
        self.assertIn('Locale: system (Android), keep, or UTF-8 locale [system]', output)
        self.assertEqual('de_DE.UTF-8', self.system_setup_call()[-6])
        self.log.unlink()
        output = self.questions(b'\n\n\nkeep\nkeep\nn\ny\n',
                                args=('--resume', '--name', 'work'), TEST_ANDROID_LOCALES='ja-JP')
        self.assertIn('Locale: system (Android), keep, or UTF-8 locale [keep]', output)
        self.assertEqual('keep', self.system_setup_call()[-6])

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

    def test_arch_uses_package_sandbox_without_opt_out_or_signature_changes(self):
        result = self.run_script("--yes", "--distro", "arch")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('pacman -Syu --noconfirm </dev/null', self.payload.read_text())
        self.assertNotIn("--disable-sandbox", self.payload.read_text())
        self.assertNotIn("SigLevel", self.payload.read_text())
        self.assertNotIn('Glycin/Bubblewrap image loader', result.stdout)

    def test_list_is_read_only_and_names_all_sources(self):
        result = self.run_script("--list", TEST_UID="10001")
        self.assertEqual(0, result.returncode)
        self.assertIn("Arch Linux ARM official rootfs", result.stdout)
        self.assertNotIn("menci/archlinuxarm", result.stdout)
        self.assertIn("registry.fedoraproject.org/fedora:44", result.stdout)
        self.assertEqual([], self.calls())

    def test_dns_is_explicit_and_resume_does_not_replace_it(self):
        result = self.run_script("--yes", "--dns", "192.0.2.1,192.0.2.2")
        self.assertEqual(0, result.returncode, result.stderr)
        install = next(c for c in self.calls() if c[0] == 'install')
        self.assertEqual(["--dns", "192.0.2.1,192.0.2.2"], install[-2:])
        self.assertIn(["dns", "debian", "192.0.2.1,192.0.2.2", "--replace"], self.calls())
        self.log.unlink()
        result = self.run_script("--yes", "--name", "work", "--resume", "--dns", "192.0.2.1")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], self.calls())
        result = self.run_script("--yes", "--dns", "preserve")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("dns", [a[0] for a in self.calls()])

    def test_failure_is_not_reported_as_success_or_followed_by_more_work(self):
        for stage, count in [("--probe", 1), ("download", 3), ("install", 4), ("dns", 5), ("validate", 6), ("setup", 7)]:
            with self.subTest(stage=stage):
                self.log.unlink(missing_ok=True)
                self.payload.unlink(missing_ok=True)
                result = self.run_script("--yes", TEST_FAIL=stage)
                self.assertEqual(23, result.returncode)
                self.assertEqual(count, len(self.calls()))
                self.assertNotIn("Linux is ready", result.stdout)
                self.assertNotIn("Next steps:", result.stdout)
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
                     ("--gpu", "invalid"), ("--jobs", "0"), ("--jobs", "9"), ("--jobs", "1;id"),
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

    def test_patch_export_needs_no_executor_and_includes_egl_and_kgsl_capabilities(self):
        result = self.run_script("--print-mesa-patch", TEST_UID="10001")
        self.assertEqual(0, result.returncode, result.stderr)
        parsed = subprocess.run(['git', 'apply', '--numstat'], input=result.stdout,
                                text=True, capture_output=True)
        self.assertEqual(0, parsed.returncode, parsed.stderr)
        self.assertIn("disp->Options.ForceSoftware || disp->Options.Zink", result.stdout)
        self.assertNotIn("+   if (!dri2_setup_device(disp, disp->Options.ForceSoftware ||", result.stdout)
        self.assertIn("screen->caps.accelerated", result.stdout)
        self.assertIn("screen->get_device_uuid(screen, (char *)uuid)", result.stdout)
        self.assertIn("_eglFindNonDrmDevice(uuid)", result.stdout)
        self.assertIn("dev->extensions = \"\"", result.stdout)
        self.assertIn("+   if (!dri2_setup_device(disp, true))", result.stdout)
        self.assertIn("disp->Device != requested_device", result.stdout)
        self.assertIn("+   priv->driver = psc->kopper ? GLX_DRIVER_ZINK_YES : GLX_DRIVER_SW;", result.stdout)
        self.assertIn("+   if (psc->kopper)", result.stdout)
        self.assertIn("+   if (!psc->kopper)", result.stdout)
        self.assertIn('XQueryExtension(priv->dpy, "DRI3"', result.stdout)
        self.assertIn("zink_present_readback(screen, pctx, pres, level, layer,", result.stdout)
        self.assertIn("!ws || !ws->displaytarget_display", result.stdout)
        self.assertIn("displaytarget_display = lf ? dri_sw_displaytarget_display : NULL", result.stdout)
        self.assertIn("pipe_texture_map(pipe, pres, level, layer, PIPE_MAP_READ,", result.stdout)
        self.assertIn("displaytarget_destroy(screen->winsys, res->sw_dt)", result.stdout)
        self.assertIn("IOCTL_KGSL_READ_CALIBRATED_TIMESTAMPS", result.stdout)
        self.assertIn("dev->local_fd, &timestamp) == 0", result.stdout)
        self.assertIn("kgsl_read_gpu_timestamp(dev->fd, ts)", result.stdout)
        self.assertIn('-   if (instance->vk.enabled_extensions.KHR_display)', result.stdout)
        self.assertIn('+   /* KGSL is render-only.', result.stdout)
        for extension in ("KHR_calibrated_timestamps", "EXT_calibrated_timestamps"):
            self.assertIn(f"+      .{extension} = has_calibrated_timestamps,", result.stdout)
        self.assertEqual([], self.calls())

    def test_vulkan_readback_keeps_present_events_and_gpu_rendering(self):
        patch = self.run_script('--print-mesa-patch').stdout
        self.assertIn('chain->cpu_present = wsi_device->sw || !wsi_conn->has_dri3;', patch)
        self.assertIn('xcb_create_pixmap_checked(chain->conn, chain->depth,', patch)
        self.assertIn('return x11_present_to_x11_dri3(chain, image_index, target_msc, present_mode);', patch)
        self.assertIn('chain->image_info.image_type != WSI_IMAGE_TYPE_DRM', patch)
        parsed = subprocess.run(['git', 'apply', '--numstat'], input=patch,
                                text=True, capture_output=True, check=True)
        self.assertIn('3\t0\tsrc/vulkan/wsi/wsi_common_drm.c', parsed.stdout)
        self.assertIn('(chain->cpu_present || x11_needs_wait_for_fences(', patch)
        self.assertIn('if (image->shm_fence)', patch)
        self.assertNotIn('MDWSI', patch)
        self.assertNotIn('+   wsi_device->sw =', patch)

    def test_gpu_recipe_is_fingerprinted_and_independent_of_parallelism(self):
        import hashlib
        for gpu, jobs in [("turnip", "2"), ("turnip", "4"), ("software", "2")]:
            with self.subTest(gpu=gpu, jobs=jobs):
                self.log.unlink(missing_ok=True)
                result = self.run_script("--yes", "--name", "debian", "--resume", "--gpu", gpu, "--jobs", jobs)
                self.assertEqual(0, result.returncode, result.stderr)
                payload = Path(str(self.payload) + ".gpu")
                digest = hashlib.sha256(payload.read_bytes()).hexdigest()
                stages = [c for c in self.calls() if '-s' in c and c[-3] == gpu]
                self.assertEqual(2, len(stages))
                for call in stages:
                    self.assertEqual([gpu, jobs, digest], call[-3:])
                self.assertEqual(0, subprocess.run(["sh", "-n", str(payload)]).returncode)
                self.assertIn(self.run_script("--print-mesa-patch").stdout, payload.read_text())
                self.assertIn("--fuzz=0", payload.read_text())
                self.assertLess(payload.read_text().index("vulkaninfo --summary"),
                                payload.read_text().index('mv -f "$candidate" "$profile"'))

    def test_all_distributions_share_gpu_patch_and_publishers(self):
        expected = self.run_script('--print-graphics-setup').stdout
        for distro in ('debian', 'ubuntu', 'alpine', 'fedora', 'arch', 'opensuse',
                       'openkylin', 'deepin', 'manjaro', 'void', 'armtix', 'steamos'):
            with self.subTest(distro=distro):
                self.log.unlink(missing_ok=True)
                result = self.run_script('--yes', '--resume', '--distro', distro,
                                         '--name', 'work', '--gpu', 'turnip', TEST_DISTRO=distro)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(expected, Path(str(self.payload) + '.gpu').read_text())
        self.assertIn('_eglFindNonDrmDevice', expected)
        self.assertIn('/usr/local/bin/magicdesk-snap-gpu', expected)
        self.assertIn('/usr/local/bin/magicdesk-flatpak-gl', expected)
        for language in ('c', 'cpp'):
            self.assertIn(f'"-D{language}_link_args=-Wl,-rpath,\\$ORIGIN:\\$ORIGIN/..:$prefix/lib"', expected)
        self.assertIn("'-Dgbm-backends-path=$ORIGIN/gbm'", expected)

    def test_alpine_graphics_uses_available_development_packages(self):
        graphics = self.run_script('--print-graphics-setup').stdout
        alpine = graphics.split('apk) packages build-base')[1].split(';;')[0]
        self.assertIn('mesa-dev', alpine)
        self.assertNotIn('libglvnd-dev', alpine)
        self.assertIn('prepare_glvnd\n', graphics)
        self.assertIn('cp -a "$private_glvnd/lib/." "$work/stage$prefix/lib/"', graphics)

    def protocols_helper(self):
        payload = self.run_script('--print-graphics-setup').stdout
        return payload[payload.index('prepare_wayland_protocols() {'):payload.index('\nprepare_glvnd() {')]

    def test_protocols_bootstrap_keeps_sufficient_system_version(self):
        tool = self.directory / 'pkg-config'
        tool.write_text('#!/bin/sh\ntest "$1" = --atleast-version=1.41 && test "$2" = wayland-protocols\n')
        tool.chmod(0o755)
        run = subprocess.run(['sh', '-eu', '-c', self.protocols_helper() +
                              '\nprepare_wayland_protocols\nprintf "%s" "$PKG_CONFIG_PATH"'],
                             env=dict(self.environment, PKG_CONFIG_PATH='/previous'),
                             capture_output=True, text=True, timeout=5)
        self.assertEqual(0, run.returncode, run.stderr)
        self.assertEqual('/previous', run.stdout)

    def test_protocols_bootstrap_reuses_private_build_inputs(self):
        tool = self.directory / 'pkg-config'
        prefix = self.directory / 'tools/wayland-protocols-1.41'
        prefix.mkdir(parents=True)
        (prefix / '.complete').touch()
        tool.write_text('#!/bin/sh\ncase "$PKG_CONFIG_PATH" in */share/pkgconfig:/previous) exit 0;; *) exit 1;; esac\n')
        tool.chmod(0o755)
        run = subprocess.run(['sh', '-eu', '-c', self.protocols_helper() +
                              '\nprepare_wayland_protocols\nprintf "%s" "$PKG_CONFIG_PATH"'],
                             env=dict(self.environment, work=str(self.directory), PKG_CONFIG_PATH='/previous'),
                             capture_output=True, text=True, timeout=5)
        self.assertEqual(0, run.returncode, run.stderr)
        self.assertEqual(str(prefix / 'share/pkgconfig') + ':/previous', run.stdout)

    def test_protocols_bootstrap_rejects_invalid_archive_before_extracting(self):
        for name, body in [('pkg-config', 'exit 1'), ('curl', 'exit 0'),
                           ('tar', 'touch "$work/unexpected-extraction"')]:
            tool = self.directory / name
            tool.write_text('#!/bin/sh\n' + body + '\n')
            tool.chmod(0o755)
        (self.directory / 'wayland-protocols-1.41.tar.xz.part').write_text('invalid archive')
        helper = self.protocols_helper().replace('/var/cache/magicdesk/mesa/downloads', str(self.directory))
        run = subprocess.run(['sh', '-eu', '-c', helper + '\nprepare_wayland_protocols'],
                             env=dict(self.environment, work=str(self.directory)),
                             capture_output=True, text=True, timeout=5)
        self.assertNotEqual(0, run.returncode)
        self.assertFalse((self.directory / 'unexpected-extraction').exists())

    def glvnd_helper(self):
        payload = self.run_script('--print-graphics-setup').stdout
        return payload[payload.index('prepare_glvnd() {'):payload.index('\nautomatic=no')]

    def test_glvnd_bootstrap_preserves_system_dispatch(self):
        tool = self.directory / 'pkg-config'
        tool.write_text('#!/bin/sh\ntest "$1" = --exists && test "$2" = libglvnd\n')
        tool.chmod(0o755)
        run = subprocess.run(['sh', '-eu', '-c', self.glvnd_helper() +
                              '\nprepare_glvnd\ntest -z "$private_glvnd"\n'],
                             env=self.environment, capture_output=True, text=True, timeout=5)
        self.assertEqual(0, run.returncode, run.stderr)

    def test_glvnd_bootstrap_reuses_complete_private_dispatch(self):
        tool = self.directory / 'pkg-config'
        tool.write_text('#!/bin/sh\nexit 1\n')
        tool.chmod(0o755)
        root = self.directory / 'glvnd'
        prefix = root / '1.7.0-test'
        prefix.mkdir(parents=True)
        (prefix / '.complete').write_text('test\n')
        helper = self.glvnd_helper().replace('/opt/magicdesk/glvnd', str(root))
        run = subprocess.run(['sh', '-eu', '-c', helper +
                              '\nprepare_glvnd\nprintf "%s" "$PKG_CONFIG_PATH"\n'],
                             env=dict(self.environment, recipe='test', PKG_CONFIG_PATH='/previous'),
                             capture_output=True, text=True, timeout=5)
        self.assertEqual(0, run.returncode, run.stderr)
        self.assertEqual(str(prefix / 'lib/pkgconfig') + ':/previous', run.stdout)

    def test_glvnd_bootstrap_rejects_invalid_archive_before_extracting(self):
        for name, body in [('pkg-config', 'exit 1'), ('curl', 'exit 0'),
                           ('tar', 'touch "$work/unexpected-extraction"')]:
            tool = self.directory / name
            tool.write_text('#!/bin/sh\n' + body + '\n')
            tool.chmod(0o755)
        (self.directory / 'libglvnd-1.7.0.tar.gz.part').write_text('invalid archive')
        helper = self.glvnd_helper().replace('/opt/magicdesk/glvnd', str(self.directory / 'glvnd')) \
            .replace('/var/cache/magicdesk/mesa/downloads', str(self.directory))
        run = subprocess.run(['sh', '-eu', '-c', helper + '\nprepare_glvnd'],
                             env=dict(self.environment, recipe='test', work=str(self.directory)),
                             capture_output=True, text=True, timeout=5)
        self.assertNotEqual(0, run.returncode)
        self.assertFalse((self.directory / 'unexpected-extraction').exists())

    def test_gpu_failure_preserves_environment_and_reports_exact_resume_command(self):
        result = self.run_script("--yes", "--resume", "--name", "gpu-work", "--gpu", "turnip",
                                 "--jobs", "3", TEST_FAIL="graphics")
        self.assertEqual(23, result.returncode, result.stderr)
        self.assertIn("--name gpu-work --resume", result.stderr)
        self.assertIn("GPU=turnip jobs=3", result.stderr)
        self.assertNotIn("Linux is ready", result.stdout)
        self.assertNotIn("install", [args[0] for args in self.calls()])

    def test_meson_bootstrap_preserves_supported_system_tool(self):
        result = self.run_script('--yes', '--resume', '--name', 'work', '--gpu', 'turnip')
        self.assertEqual(0, result.returncode, result.stderr)
        payload = Path(str(self.payload) + '.gpu').read_text()
        helper = payload[payload.index('prepare_meson() {'):payload.index('\nautomatic=no')]
        system_meson = self.directory / 'meson'
        tools = self.directory / 'tools' / 'meson-1.7.2'
        tools.mkdir(parents=True)
        (tools / '.complete').touch()
        for version, system in [('1.3.2', False), ('1.4.0', True), ('1.7.2', True),
                                ('1.10.0', True), ('unknown', False)]:
            with self.subTest(version=version):
                system_meson.write_text('#!/bin/sh\nprintf "%s\\n" "' + version + '"\n')
                system_meson.chmod(0o755)
                run = subprocess.run(['sh', '-eu', '-c', helper + '\nprepare_meson\nprintf "%s" "$meson_tool"'],
                    env=dict(self.environment, work=str(self.directory)), capture_output=True, text=True, timeout=5)
                self.assertEqual(0, run.returncode, run.stderr)
                self.assertEqual(str(system_meson if system else tools / 'meson.py'), run.stdout)
                self.assertIn(version, system_meson.read_text())

    def test_meson_bootstrap_rejects_invalid_archive_before_extracting(self):
        result = self.run_script('--yes', '--resume', '--name', 'work', '--gpu', 'turnip')
        self.assertEqual(0, result.returncode, result.stderr)
        payload = Path(str(self.payload) + '.gpu').read_text()
        helper = payload[payload.index('prepare_meson() {'):payload.index('\nautomatic=no')]
        for name, body in [('meson', 'echo 1.3.2'), ('curl', 'exit 0'),
                           ('tar', 'touch "$work/unexpected-extraction"')]:
            tool = self.directory / name
            tool.write_text('#!/bin/sh\n' + body + '\n')
            tool.chmod(0o755)
        tools = self.directory / 'tools'
        tools.mkdir()
        (tools / 'meson-1.7.2.tar.gz.part').write_text('invalid archive')
        run = subprocess.run(['sh', '-eu', '-c', helper + '\nprepare_meson'],
            env=dict(self.environment, work=str(self.directory)), capture_output=True, text=True, timeout=5)
        self.assertNotEqual(0, run.returncode)
        self.assertFalse((self.directory / 'unexpected-extraction').exists())

    def profile_recipe(self):
        result = self.run_script("--yes", "--name", "debian", "--resume", "--gpu", "turnip")
        self.assertEqual(0, result.returncode, result.stderr)
        payload = Path(str(self.payload) + ".gpu").read_text()
        recipe = payload[payload.index("reset_profile() {"):]
        # Keep profile activation tests confined to their temporary filesystem.
        (self.directory / 'etc-magicdesk').mkdir(exist_ok=True)
        return recipe.replace('/etc/shroot', str(self.directory / 'etc-shroot')) \
            .replace('/etc/vulkan', str(self.directory / 'etc-vulkan')) \
            .replace('/etc/glvnd', str(self.directory / 'etc-glvnd')) \
            .replace('/etc/magicdesk/', str(self.directory / 'etc-magicdesk') + '/') \
            .replace('/lib/ld-musl-aarch64.so.1', str(self.directory / 'musl-loader')) \
            .replace('/etc/ld-musl-aarch64.path', str(self.directory / 'musl-path')) \
            .replace('/usr/share/flatpak/triggers', str(self.directory / 'triggers'))

    def private_mesa_prefix(self):
        prefix = self.directory / 'mesa'
        (prefix / 'lib').mkdir(parents=True, exist_ok=True)
        for relative, name in (('vulkan/icd.d/freedreno_icd.aarch64.json', 'libvulkan_freedreno.so'),
                               ('glvnd/egl_vendor.d/50_mesa.json', 'libEGL_mesa.so.0')):
            (prefix / 'lib' / name).touch()
            path = prefix / 'share' / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps({'file_format_version': '1.0.0', 'ICD': {'library_path': name}}))
        return prefix

    def test_profile_activation_requires_successful_turnip_probe(self):
        recipe = self.profile_recipe()
        prefix = self.private_mesa_prefix()
        profile = self.directory / "graphics.sh"
        probe = self.directory / "vulkaninfo"
        for body, accepted in [("exit 1", False), ("echo driverID = DRIVER_ID_MESA_LLVMPIPE", False),
                               ("echo driverID = DRIVER_ID_MESA_TURNIP", True)]:
            with self.subTest(body=body):
                probe.write_text("#!/bin/sh\n" + body + "\n")
                probe.chmod(0o755)
                profile.write_text("previous profile\n")
                environment = dict(self.environment, gpu="turnip", ID="debian", automatic="no",
                                   prefix=str(prefix),
                                   candidate=str(profile) + ".new", profile=str(profile), work=str(self.directory))
                result = subprocess.run(["sh", "-eu", "-c", recipe], env=environment,
                                        capture_output=True, text=True, timeout=5)
                self.assertEqual(accepted, result.returncode == 0, result.stderr)
                if accepted:
                    self.assertIn("MESA_LOADER_DRIVER_OVERRIDE=zink", profile.read_text())
                    self.assertEqual('turnip\n', (self.directory / 'etc-shroot/render-node').read_text())
                    for path, name in (('etc-vulkan/icd.d/magicdesk-turnip.aarch64.json', 'libvulkan_freedreno.so'),
                                       ('etc-glvnd/egl_vendor.d/10_magicdesk.json', 'libEGL_mesa.so.0')):
                        manifest = self.directory / path
                        self.assertEqual(str(prefix / 'lib' / name),
                                         json.loads(manifest.read_text())['ICD']['library_path'])
                        self.assertEqual(0o644, manifest.stat().st_mode & 0o777)
                    activated = subprocess.run(
                        ["sh", "-eu", "-c", '. "$1"; printf "%s|%s|%s" '
                         '"$MESA_LOADER_DRIVER_OVERRIDE" "${GALLIUM_DRIVER-unset}" "$GBM_BACKENDS_PATH"',
                         "sh", str(profile)],
                        env=dict(environment, GALLIUM_DRIVER="zink"),
                        capture_output=True, text=True, timeout=5)
                    self.assertEqual(0, activated.returncode, activated.stderr)
                    self.assertEqual("zink|zink|" + str(prefix / 'lib/gbm'), activated.stdout)
                else:
                    self.assertEqual("previous profile\n", profile.read_text())

    def test_musl_graphics_system_lookup_follows_profile(self):
        recipe = self.profile_recipe()
        prefix = self.private_mesa_prefix()
        profile = self.directory / 'graphics.sh'
        lookup = self.directory / 'musl-path'
        marker = self.directory / 'etc-magicdesk/mesa-prefix'
        probe = self.directory / 'vulkaninfo'
        probe.write_text('#!/bin/sh\necho driverID = DRIVER_ID_MESA_TURNIP\n')
        probe.chmod(0o755)
        environment = dict(self.environment, gpu='turnip', ID='alpine', automatic='no',
                           prefix=str(prefix), candidate=str(profile) + '.new',
                           profile=str(profile), work=str(self.directory))

        def activate(**changes):
            subprocess.run(['sh', '-eu', '-c', recipe], env=dict(environment, **changes),
                           capture_output=True, text=True, check=True, timeout=5)

        # glibc must not acquire a musl configuration, regardless of distro ID.
        activate()
        self.assertFalse(lookup.exists())
        (self.directory / 'musl-loader').touch()
        defaults = ['/lib', '/usr/local/lib', '/usr/lib']
        activate()
        self.assertEqual([str(prefix / 'lib'), *defaults], lookup.read_text().splitlines())
        self.assertEqual(0o644, lookup.stat().st_mode & 0o777)
        activate()
        self.assertEqual([str(prefix / 'lib'), *defaults], lookup.read_text().splitlines())

        marker.write_text('/opt/previous\n')
        lookup.write_text('/custom:/opt/previous/lib:/lib\n/other:/usr/lib\n')
        activate()
        self.assertEqual([str(prefix / 'lib'), '/custom', '/lib', '/other', '/usr/lib'],
                         lookup.read_text().splitlines())
        activate(gpu='software')
        self.assertEqual(['/custom', '/lib', '/other', '/usr/lib'], lookup.read_text().splitlines())
        self.assertFalse(marker.exists())
        self.assertFalse(lookup.with_name(lookup.name + '.new').exists())

        # A failed driver probe cannot publish a new system lookup path.
        probe.write_text('#!/bin/sh\nexit 1\n')
        before = lookup.read_bytes()
        result = subprocess.run(['sh', '-eu', '-c', recipe], env=environment,
                                capture_output=True, text=True, timeout=5)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(before, lookup.read_bytes())

    def test_profile_missing_driver_preserves_previous_selection(self):
        recipe = self.profile_recipe()
        prefix = self.private_mesa_prefix()
        profile = self.directory / 'graphics.sh'
        profile.write_text('previous profile\n')
        marker = self.directory / 'etc-shroot/render-node'
        marker.parent.mkdir()
        marker.write_text('turnip\n')
        manifests = [self.directory / 'etc-vulkan/icd.d/magicdesk-turnip.aarch64.json',
                     self.directory / 'etc-glvnd/egl_vendor.d/10_magicdesk.json']
        for path in manifests:
            path.parent.mkdir(parents=True)
            path.write_text('previous manifest\n')
        probe = self.directory / 'vulkaninfo'
        probe.write_text('#!/bin/sh\necho driverID = DRIVER_ID_MESA_TURNIP\n')
        probe.chmod(0o755)
        environment = dict(self.environment, gpu='turnip', ID='debian', automatic='no',
                           prefix=str(prefix), candidate=str(profile) + '.new',
                           profile=str(profile), work=str(self.directory))
        for missing in ('lib/libEGL_mesa.so.0', 'share/glvnd/egl_vendor.d/50_mesa.json'):
            with self.subTest(missing=missing):
                path = prefix / missing
                saved = path.read_bytes()
                path.unlink()
                try:
                    result = subprocess.run(['sh', '-eu', '-c', recipe], env=environment,
                                            capture_output=True, text=True, timeout=5)
                    self.assertNotEqual(0, result.returncode)
                    self.assertEqual('previous profile\n', profile.read_text())
                    self.assertEqual('turnip\n', marker.read_text())
                    for manifest in manifests:
                        self.assertEqual('previous manifest\n', manifest.read_text())
                        self.assertFalse(Path(str(manifest) + '.new').exists())
                finally:
                    path.write_bytes(saved)

    def test_staged_egl_manifest_uses_installed_private_library(self):
        result = self.run_script('--yes', '--name', 'debian', '--resume', '--gpu', 'turnip')
        self.assertEqual(0, result.returncode, result.stderr)
        payload = Path(str(self.payload) + '.gpu').read_text()
        script = payload.split("<<'EGL_MANIFEST'\n", 1)[1].split('\nEGL_MANIFEST', 1)[0]
        manifest = self.private_mesa_prefix() / 'share/glvnd/egl_vendor.d/50_mesa.json'
        library = '/opt/magicdesk/mesa/test/lib/libEGL_mesa.so.0'
        subprocess.run([sys.executable, '-', str(manifest), library], input=script,
                       capture_output=True, text=True, check=True, timeout=5)
        data = json.loads(manifest.read_text())
        self.assertEqual(library, data['ICD']['library_path'])
        self.assertEqual('1.0.0', data['file_format_version'])

    def test_software_profile_removes_only_previous_mesa_library_path(self):
        recipe = self.profile_recipe()
        owned = [self.directory / 'etc-vulkan/icd.d/magicdesk-turnip.aarch64.json',
                 self.directory / 'etc-glvnd/egl_vendor.d/10_magicdesk.json']
        others = []
        for path in owned:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('owned')
            other = path.with_name('distribution.json')
            other.write_text('distribution')
            others.append(other)
        profile = self.directory / "software.sh"
        environment = dict(self.environment, gpu="software", ID="debian",
                           candidate=str(profile) + ".new", profile=str(profile))
        subprocess.run(["sh", "-eu", "-c", recipe], env=environment, check=True,
                       capture_output=True, timeout=5)
        self.assertTrue(all(not path.exists() for path in owned))
        self.assertTrue(all(path.read_text() == 'distribution' for path in others))
        environment.update(MAGICDESK_MESA_PREFIX="/opt/old", LD_LIBRARY_PATH="/custom:/opt/old/lib:/other",
                           VK_DRIVER_FILES="/old/icd.json", MESA_LOADER_DRIVER_OVERRIDE="zink",
                           MESA_VK_WSI_DEBUG="noshm", GBM_BACKENDS_PATH="/opt/old/lib/gbm")
        result = subprocess.run(["sh", "-eu", "-c", '. "$1"; . "$1"; printf "%s|%s|%s|%s|%s|%s|%s" '
                                 '"$LD_LIBRARY_PATH" "$LIBGL_ALWAYS_SOFTWARE" "$GALLIUM_DRIVER" '
                                 '"${VK_DRIVER_FILES-unset}" "${MAGICDESK_MESA_PREFIX-unset}" '
                                 '"${MESA_VK_WSI_DEBUG-unset}" "${GBM_BACKENDS_PATH-unset}"', "sh", str(profile)],
                                env=environment, capture_output=True, text=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("/custom:/other|1|llvmpipe|unset|unset|unset|unset", result.stdout)

    def test_fex_graphics_defaults_preserve_overrides_and_follow_profile(self):
        recipe = self.profile_recipe()
        prefix = str(self.private_mesa_prefix())
        config = self.directory / 'fex-thunks.json'
        config.write_text('{"ThunksDB":{"GL":"1","Vulkan":"1"}}')
        recipe = recipe.replace('/opt/magicdesk/fex-thunks.json', str(config))
        probe = self.directory / 'vulkaninfo'
        probe.write_text('#!/bin/sh\necho driverID = DRIVER_ID_MESA_TURNIP\n')
        probe.chmod(0o755)
        profiles = []
        for gpu in ('turnip', 'software'):
            profile = self.directory / (gpu + '.sh')
            env = dict(self.environment, gpu=gpu, ID='debian', automatic='no',
                       prefix=prefix,
                       candidate=str(profile) + '.new', profile=str(profile), work=str(self.directory))
            subprocess.run(['sh', '-eu', '-c', recipe], env=env, capture_output=True,
                           check=True, timeout=5)
            profiles.append(str(profile))
        script = '. "$1"; . "$1"; printf "%s|%s|%s|%s\\n" ' \
                 '"$FEX_THUNKCONFIG" "${FEX_HOSTENV-unset}" "$PRESSURE_VESSEL_FILESYSTEMS_RO" "$FEX_APP_CONFIG"; ' \
                 '. "$2"; printf "%s|%s|%s|%s\\n" ' \
                 '"${FEX_THUNKCONFIG-unset}" "${FEX_HOSTENV-unset}" ' \
                 '"$PRESSURE_VESSEL_FILESYSTEMS_RO" "$FEX_APP_CONFIG"'
        for custom in (False, True):
            env = dict(self.environment, FEX_APP_CONFIG='/user/game.json',
                       PRESSURE_VESSEL_FILESYSTEMS_RO='/user/files')
            if custom:
                env.update(FEX_THUNKCONFIG='/user/thunks.json', FEX_HOSTENV='TEST=1')
            result = subprocess.run(['sh', '-eu', '-c', script, 'sh', *profiles],
                                    env=env, capture_output=True, text=True, timeout=5)
            self.assertEqual(0, result.returncode, result.stderr)
            chosen = '/user/thunks.json|TEST=1' if custom else str(config) + '|unset'
            retained = '/user/thunks.json|TEST=1' if custom else 'unset|unset'
            self.assertEqual(chosen + '|' + prefix + ':' + str(config) + ':/user/files|/user/game.json\n' +
                             retained + '|/user/files|/user/game.json\n', result.stdout)

    @unittest.skipUnless(hasattr(os, "openpty"), "PTY required")
    def test_questions_use_saved_script_stdin(self):
        output = self.questions(b"debian\ninteractive\nxfce\nkeep\nkeep\nkeep\nbasic\nn\ny\n")
        install = next(c for c in self.calls() if c[0] == 'install')
        self.assertEqual(['--rootfs', '--name', 'interactive', '--dns', 'system'], install[2:])
        setup = next(c for c in self.calls() if c[0] == 'login' and '-s' in c)
        self.assertEqual("xfce", setup[-8])
        self.assertNotIn('Linux libc:', output)
        self.assertNotIn('Compiler jobs', output)

    @unittest.skipUnless(hasattr(os, "openpty"), "PTY required")
    def test_advanced_questions_select_real_options_without_command_flags(self):
        output = self.questions(b"ubuntu\ninteractive\napps\nsoftware\nfr_FR.UTF-8\nEurope/Paris\ncjk\ny\n"
                            b"developer\nwayland\ngit gdb\n192.0.2.1\nclean\ny\n")
        install = next(c for c in self.calls() if c[0] == 'install')
        self.assertEqual(['--rootfs', '--name', 'interactive', '--dns', '192.0.2.1'], install[2:])
        self.assertEqual(["apps", "wayland", "fr_FR.UTF-8", "Europe/Paris", "developer", "cjk", "clean", "git gdb"],
                         self.system_setup_call()[-8:])
        self.assertIn("Guest account=developer | extra packages=git gdb", output)

    @unittest.skipUnless(hasattr(os, 'openpty'), 'PTY required')
    def test_steamos_main_jobs_apply_to_mesa_and_kwin(self):
        output = self.questions(b'steamos\n\n\nturnip\n\nkeep\nkeep\nn\ny\n',
                                TEST_DISTRO='steamos')
        self.assertEqual(1, output.count('Compiler jobs for Mesa/KWin/Weston (1-8) [4]'))
        self.assertIn('Compiler jobs for Mesa/KWin/Weston: 4', output)
        builds = [c for c in self.calls() if 'MAGICDESK_BUILD_PHASE=build' in c]
        self.assertEqual(2, len(builds))
        self.assertTrue(all(c[-2] == '4' for c in builds))

    @unittest.skipUnless(hasattr(os, 'openpty'), 'PTY required')
    def test_explicit_jobs_are_not_asked_again(self):
        output = self.questions(b'steamos\n\n\nturnip\nkeep\nkeep\nn\ny\n',
                                args=('--jobs', '2'), TEST_DISTRO='steamos')
        self.assertNotIn('Compiler jobs for Mesa/KWin/Weston (1-8)', output)
        builds = [c for c in self.calls() if 'MAGICDESK_BUILD_PHASE=build' in c]
        self.assertEqual(2, len(builds))
        self.assertTrue(all(c[-2] == '2' for c in builds))

    def questions(self, answers, expected_exit=0, args=(), **environment):
        master, slave = os.openpty()
        self.addCleanup(os.close, master)
        process = subprocess.Popen(["sh", str(SCRIPT), *args], stdin=slave, stdout=slave, stderr=slave,
                                   env=dict(self.environment, **environment))
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
        self.assertEqual(expected_exit, process.wait(timeout=5), output.decode())
        return output.decode()


if __name__ == "__main__":
    unittest.main()
