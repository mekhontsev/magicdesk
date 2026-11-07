"""Shared command transport preserves executor selection and test evidence."""
from pathlib import Path
import shlex
import sys
import unittest
from unittest.mock import Mock, patch

lab = Path(__file__).resolve().parents[2] / 'native/guest-exec-lab'
with patch.object(sys, 'path', [str(lab), *sys.path]):
    from kernel_profile import KernelProfile
    from test_oci_services import Suite
    from test_debugger_runtime import outcome
    from test_ipc_credentials_runtime import custody_cases


class GuestFixtureTest(unittest.TestCase):
    def test_ipc_custody_matrix_is_shared_and_complete(self):
        native, guest = ['/native', '--custody-native'], ['/runner', '--', '/guest', '--custody']
        cases = list(custody_cases(native, guest))
        self.assertEqual(20, len(cases))
        self.assertEqual(20, len({name for name, _ in cases}))
        for index, (name, argv) in enumerate(cases):
            receive, protected, is_guest = index // 4, index // 2 % 2, bool(index % 2)
            self.assertEqual(f'ancillary custody receive={receive} protected={protected} guest={is_guest}', name)
            self.assertEqual((guest if is_guest else native) + [str(receive), str(protected)], argv)
        self.assertEqual(['/native', '--custody-native'], native)
        self.assertEqual(['/runner', '--', '/guest', '--custody'], guest)

    def suite(self, mode='shell', code=0, output='output\n'):
        suite = Suite.__new__(Suite)
        suite.executor = Mock(mode=mode)
        suite.executor.native_path.side_effect = lambda path: '/apk/test' if path == '/fixture/test' else path
        suite.executor.execute.return_value = dict(exitCode=code, output=output)
        suite.client = Mock()
        suite.report = dict(commands=[])
        suite.kernel_profile = KernelProfile('native')
        suite.runner, suite.image = '/run', '/image'
        return suite

    def test_observe_uses_the_selected_executor(self):
        for mode in ('app', 'shell'):
            with self.subTest(mode=mode):
                suite = self.suite(mode, 19)
                result = suite.observe(['/fixture/test', 'argument with spaces'], timeout=42, quiet=True)
                self.assertEqual(dict(exitCode=19, output='output\n'), result)
                command, timeout = suite.executor.execute.call_args.args
                self.assertEqual(57, timeout)
                self.assertIn(shlex.quote(shlex.join(['/apk/test', 'argument with spaces'])), command)
                self.assertIn('"$MAGICDESK_ANDROID_SHELL" -c ', command)
                suite.client.call.assert_not_called()
                self.assertEqual(19, suite.report['commands'][0]['exitCode'])

    def test_command_requires_the_expected_exit(self):
        suite = self.suite(code=19)
        with self.assertRaises(AssertionError):
            suite.command(['/fixture/test'], quiet=True)
        self.assertEqual('output\n', suite.command(['/fixture/test'], expected=19, quiet=True))
        self.assertEqual(2, len(suite.report['commands']))

    def test_transport_failure_is_not_a_command_result(self):
        suite = self.suite()
        suite.executor.execute.side_effect = TimeoutError('incomplete command')
        with self.assertRaises(TimeoutError):
            suite.observe(['/fixture/test'], quiet=True)
        self.assertEqual([], suite.report['commands'])

    def test_failure_keeps_full_report_without_repeating_large_output(self):
        output = 'begin\n' + 'diagnostic\n' * 4000 + 'end\n'
        suite = self.suite(code=19, output=output)
        with self.assertRaises(AssertionError) as error:
            suite.command(['/fixture/test'], quiet=True)
        self.assertLess(len(str(error.exception)), 10000)
        self.assertEqual(output, suite.report['commands'][0]['output'])
        self.assertIn('end', str(error.exception))

    def test_native_capability_does_not_mask_guest_failures(self):
        for mode in ('app', 'shell'):
            for code in (0, 77, 159):
                with self.subTest(mode=mode, code=code):
                    suite = self.suite(mode, code)
                    with patch('builtins.print'):
                        self.assertEqual(code == 0, suite.native_capability('/fixture/test', 'openat2'))
                    evidence = suite.report['nativeCapabilities'][0]
                    self.assertEqual(code, evidence['exitCode'])
                    self.assertIn('--probe-openat2', suite.report['commands'][0]['command'])
                    if code:
                        with self.assertRaises(AssertionError):
                            suite.command(['/fixture/test', 'resolution'], quiet=True)
        for code in (1, 124, 134, 139):
            with self.subTest(code=code), self.assertRaises(AssertionError):
                self.suite(code=code).native_capability('/fixture/test', 'openat2')

    def test_profile_keeps_raw_evidence_in_report(self):
        suite = self.suite()
        suite.kernel_profile = Mock(options=['--profile'], shell=lambda command: command)
        suite.kernel_profile.output.return_value = 'guest output\n'
        result = suite.observe(['/run', '--store', '/store'], quiet=True)
        suite.kernel_profile.observe.assert_called_once_with('output\n', required=True)
        self.assertEqual('guest output\n', result['output'])
        self.assertEqual('output\n', suite.report['commands'][0]['output'])
        self.assertEqual('output\n', suite.command(['/fixture/test'], quiet=True, raw=True))

    def test_debugger_missing_toolchain_is_not_a_pass(self):
        missing = 'UNAVAILABLE gprof: compiler does not supply gcrt1.o'
        self.assertEqual('unavailable', outcome('gprof', missing)['status'])
        self.assertIsNone(outcome('gprof', missing)['passed'])
        self.assertEqual('failed', outcome('gprof', 'gcc: compilation failed')['status'])
        self.assertEqual('failed', outcome('gdb-attach', missing)['status'])
        self.assertEqual('passed', outcome('gprof', 'PASS gprof')['status'])


if __name__ == '__main__':
    unittest.main()
