"""Kernel-profile selection, launch propagation and evidence admission."""
import argparse
import base64
import importlib.util
import json
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

lab = Path(__file__).resolve().parents[2] / 'native/guest-exec-lab'
spec = importlib.util.spec_from_file_location('kernel_profile', lab / 'kernel_profile.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
KernelProfile = module.KernelProfile
with patch.object(sys, 'path', [str(lab), *sys.path]):
    from test_oci_services import Suite


def evidence(**changes):
    record = dict(profile='linux-5.15', scope='host', verified=True, rules=14, kernel='6.12-test')
    record.update(changes)
    return KernelProfile.marker + json.dumps(record) + '\n'


class KernelProfileTest(unittest.TestCase):
    def test_native_has_no_effect(self):
        profile = KernelProfile('native')
        command = Mock()
        profile.prepare(command, '/supervisor')
        self.assertEqual('unset MAGICDESK_TEST_KERNEL_PROFILE\n/run', profile.shell('/run'))
        self.assertEqual([], profile.options)
        command.assert_not_called()

    def test_argument_and_environment(self):
        with patch.dict('os.environ', SHROOT_TEST_KERNEL_PROFILE='linux-5.15'):
            parser = argparse.ArgumentParser()
            KernelProfile.add_argument(parser)
            self.assertEqual('linux-5.15', parser.parse_args([]).kernel_profile)
            self.assertEqual('native', parser.parse_args(['--kernel-profile', 'native']).kernel_profile)
            self.assertEqual('linux-5.15', KernelProfile().name)
        with self.assertRaises(ValueError):
            KernelProfile('5.15-typo')

    def test_probe_requires_verified_evidence(self):
        profile = KernelProfile('linux-5.15')
        command = Mock(return_value=evidence())
        profile.prepare(command, '/supervisor')
        command.assert_called_once_with(['sh', '-c', 'export MAGICDESK_TEST_KERNEL_PROFILE=linux-5.15\nexec "$@"', 'kernel-profile',
                                         '/supervisor', '--test-kernel-profile'])
        self.assertEqual('6.12-test', profile.evidence['probe']['kernel'])
        self.assertEqual([], profile.evidence['launches'])
        for invalid in ('', evidence(verified=False), evidence(scope='guest'), evidence(profile='native')):
            with self.subTest(output=invalid), self.assertRaises(AssertionError):
                profile.prepare(lambda _: invalid, '/supervisor')

    def test_executable_with_equals_and_spaces(self):
        profile = KernelProfile('linux-5.15')
        with tempfile.TemporaryDirectory(prefix='kernel= profile ') as temporary:
            executable = Path(temporary) / 'python=fixture'
            executable.symlink_to(sys.executable)
            command = [*profile.environment, str(executable), '-c',
                       'import os,sys; print(os.environ[sys.argv[1]]); print(sys.argv[2])',
                       profile.variable, 'argument=with spaces']
            result = subprocess.run(command, text=True, capture_output=True, check=True)
            self.assertEqual('linux-5.15\nargument=with spaces\n', result.stdout)

    def test_profile_does_not_require_an_executable_wrapper(self):
        profile = KernelProfile('linux-5.15')
        command = shlex.join(['/fixture with spaces/run', '--', 'guest argument'])
        selector, body = profile.shell(command).split('\n', 1)
        self.assertEqual(['export', 'MAGICDESK_TEST_KERNEL_PROFILE=linux-5.15'], shlex.split(selector))
        self.assertEqual(command, body)

    def test_guest_output_is_preserved(self):
        output = '{"guest":true}\n' + evidence() + 'last line'
        profile = KernelProfile('linux-5.15')
        profile.observe(output, required=True)
        self.assertEqual(1, len(profile.evidence['launches']))
        self.assertEqual('{"guest":true}\nlast line', profile.output(output))
        with self.assertRaises(AssertionError):
            profile.observe('{"guest":true}', required=True)

    def test_image_commands_inject_only_launch_options(self):
        suite = Suite.__new__(Suite)
        suite.kernel_profile = KernelProfile('linux-5.15')
        suite.image = '/image'
        suite.runner = '/run'
        suite.entry_options = ['--env', 'SHROOT_MACHINE_ENTRY=1']
        for action in ('run', 'exec', 'login'):
            self.assertEqual(['/image', action, '/store', *suite.kernel_profile.options, '--', '/bin/true'],
                             suite.image_command(action, '/store', '--', '/bin/true'))
        self.assertEqual(['/image', 'inspect', '/store'], suite.image_command('inspect', '/store'))
        self.assertEqual(['/run', '--store', '/store', *suite.entry_options, '--', '/bin/true'],
                         suite.guest('/store', '--', '/bin/true'))

    def test_installed_helpers_cannot_silently_ignore_profile(self):
        with self.assertRaisesRegex(ValueError, 'fixture build'):
            Suite(Path('/unused'), kernel_profile='linux-5.15')

    def test_retained_session_requires_its_live_profiled_owner(self):
        state = dict(id='session', ready=True, nativeBuild='build/fixtures', directory='/fixture',
                     environment={'store': '/store'}, kernelProfile={'profile': 'linux-5.15'})
        command = Mock(side_effect=[json.dumps({'label': 'session', 'launchId': 'launch'}) + '\n', evidence()])
        profile = KernelProfile('linux-5.15')
        profile.bind_session(command, state)
        self.assertEqual('launch', profile.evidence['owner']['launch']['launchId'])
        for changes in ({'closed': True}, {'ready': False}, {'nativeBuild': None},
                        {'kernelProfile': {'profile': 'native'}}):
            with self.subTest(changes=changes), self.assertRaises(AssertionError):
                profile.bind_session(Mock(), {**state, **changes})
        for launches, output in (('', evidence()),
                                 (json.dumps({'label': 'other'}), evidence()),
                                 (json.dumps({'label': 'session'}), 'no profile')):
            with self.subTest(launches=launches, output=output), self.assertRaises(AssertionError):
                profile.bind_session(Mock(side_effect=[launches, output]), state)

    def test_stdout_separates_diagnostics_without_trimming_data(self):
        suite = Suite.__new__(Suite)
        suite.base = '/fixture'
        suite.executor = Mock()
        suite.executor.native_path.side_effect = lambda path: path
        data = ' leading space\n{"guest":true}\n'
        framed = 'warning\nSHROOT_TEST_STDOUT ' + base64.b64encode(data.encode()).decode() + '\n'
        suite.command = Mock(return_value=framed)
        self.assertEqual(data, suite.stdout(['/run', '--store', '/store', '--', '/bin/cat', '/file']))
        for invalid in ('warning only', framed + framed):
            suite.command.return_value = invalid
            with self.assertRaises(ValueError):
                suite.stdout(['/run'])


if __name__ == '__main__':
    unittest.main()
