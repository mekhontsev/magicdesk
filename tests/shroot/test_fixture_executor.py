#!/usr/bin/env python3
"""Native diagnostic evidence must not inherit console preview truncation."""
import _bootstrap
import argparse
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock

from magicdesk_executor import MagicDeskExecutor
from fixture_executor import FixtureExecutor, bounded_shell


class CapturedCommands(unittest.TestCase):
    def test_android_host_tools_remain_on_the_selected_device(self):
        executor = self.executor(Path('/unused'))
        for mode in ('shell', 'app'):
            executor.mode = mode
            self.assertEqual(executor.host_tool('time'), '/system/bin/time')
            self.assertEqual(executor.host_tool('taskset'), '/system/bin/taskset')
        executor.client.call.assert_not_called()

    def test_capture_survives_removing_the_workload_root(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'evidence'
            output.mkdir()
            root = Path(directory) / 'workload'
            root.mkdir()
            executor = self.executor(output)
            executor.root = executor.directory = str(root)

            def command(text, timeout=120):
                result = subprocess.run(['sh', '-c', text], capture_output=True, text=True, timeout=timeout)
                return dict(exitCode=result.returncode, output=result.stdout + result.stderr)

            executor._shell_command = command
            executor._transport_command = command
            executor.download = shutil.copyfile
            result = executor.execute('cd / && rm -rf -- ' + shlex.quote(str(root)) + '; printf cleaned', capture=True)
            self.assertEqual(result, dict(exitCode=0, output='cleaned'))
            self.assertFalse(root.exists())
            self.assertEqual(list(Path(directory).iterdir()), [output])

    def test_packaged_alias_checks_exact_elf_before_admission(self):
        import hashlib
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'md-sockets-test'
            source.write_bytes(b'\x7fELFfixture')
            executor = self.executor(Path(directory))
            executor.mode, executor.libraries = 'app', '/apk/lib'
            executor.packaged, executor.executables = {}, {}
            executor.files = mock.Mock()
            executor.files.command.return_value = dict(output=hashlib.sha256(source.read_bytes()).hexdigest() + '  file')
            name = 'libshroot_guest_prepared_sockets_test.so'
            executor.upload(source, '/owned/driver', packaged_name=name)
            self.assertEqual(executor.native_path('/owned/driver'), '/apk/lib/' + name)
            executor.files.transfer.assert_not_called()
            source.write_bytes(b'\x7fELFchanged')
            with self.assertRaisesRegex(RuntimeError, 'exact build'):
                executor.upload(source, '/owned/other', packaged_name=name)
            self.assertNotIn('/owned/other', executor.executables)
            with self.assertRaises(ValueError):
                executor.upload(source, '/owned/other', packaged_name='../invalid')

    def test_deadline_wrapper_keeps_the_executor_cancellation_owner(self):
        command = "sh -c 'printf ready; wait'"
        for mode in FixtureExecutor.choices:
            executor = mock.Mock(mode=mode)
            with self.subTest(mode=mode):
                expected = command if mode == 'local' else 'timeout -k 5 30 ' + command
                self.assertEqual(bounded_shell(executor, command, 30, kill_after=5), expected)
                with self.assertRaises(argparse.ArgumentTypeError):
                    bounded_shell(executor, command, 0)

    def executor(self, output):
        executor = object.__new__(MagicDeskExecutor)
        executor.mode, executor.root, executor.output = 'shell', '/owned fixture', output
        executor.console = 'console'
        executor.client = mock.Mock()
        executor.client.call.return_value = dict(exitCode=7, output='preview')
        executor._transport_command = mock.Mock()
        return executor

    def test_full_output_preserves_exit_status_and_runs_once(self):
        with tempfile.TemporaryDirectory() as directory:
            executor = self.executor(Path(directory))
            payload = b'begin\n' + b'x' * 500000 + b'\nPASS middle\n' + b'y' * 500000 + b'\nend\n'
            executor.download = mock.Mock(side_effect=lambda remote, local: local.write_bytes(payload))
            result = executor.execute('test --diagnostics', capture=True)
            self.assertEqual(result, dict(exitCode=7, output=payload.decode()))
            executor.client.call.assert_called_once()
            self.assertIn('test --diagnostics', executor.client.call.call_args.args[1]['command'])
            executor._transport_command.assert_called_once()
            self.assertEqual(next(Path(directory).glob('*.log')).read_bytes(), payload)

    def test_download_failure_cleans_remote_without_reexecution(self):
        executor = self.executor(Path('/unused'))
        executor.download = mock.Mock(side_effect=OSError('transfer failed'))
        with self.assertRaisesRegex(OSError, 'transfer failed'):
            executor.execute('test', capture=True)
        executor.client.call.assert_called_once()
        executor._transport_command.assert_called_once()

    def test_app_keeps_its_existing_full_log_transport(self):
        executor = self.executor(Path('/unused'))
        executor.mode = 'app'
        executor._app_command = mock.Mock(return_value=dict(exitCode=0, output='complete'))
        self.assertEqual(executor.execute('test', 45, capture=True)['output'], 'complete')
        executor._app_command.assert_called_once_with('test', 45)
        executor.client.call.assert_not_called()

    def test_shell_deadline_is_scoped_for_plain_and_captured_commands(self):
        for capture in (False, True):
            with self.subTest(capture=capture), tempfile.TemporaryDirectory() as directory:
                executor = self.executor(Path(directory))
                executor.client.timeout, executor.client.request_timeout = 270, 240
                executor.download = mock.Mock(side_effect=lambda remote, local: local.write_text('complete'))
                with mock.patch('magicdesk_executor.time.monotonic', return_value=100):
                    executor.execute('long workload', 1245, capture=capture)
                self.assertEqual(executor.client.call.call_args.kwargs,
                                 dict(deadline=1345, request_timeout=1245))
                self.assertEqual((executor.client.timeout, executor.client.request_timeout), (270, 240))
                executor.client.call.assert_called_once()

    def test_shell_transport_timeout_does_not_replay(self):
        executor = self.executor(Path('/unused'))
        executor.client.call.side_effect = TimeoutError('command outcome unknown')
        with self.assertRaises(TimeoutError):
            executor.execute('long workload', 1245)
        executor.client.call.assert_called_once()

    def test_invalid_shell_deadline_is_rejected_before_launch(self):
        for capture in (False, True):
            with self.subTest(capture=capture):
                executor = self.executor(Path('/unused'))
                with self.assertRaises(argparse.ArgumentTypeError):
                    executor.execute('true', 0, capture=capture)
                executor.client.call.assert_not_called()
                executor._transport_command.assert_not_called()

    def test_selected_connection_reaches_initial_app_launch(self):
        client = mock.Mock()
        client.call.side_effect = [dict(app={}, device={}), dict(sessionId='selected-console')]
        observed = []

        def launch(executor, command, timeout):
            observed.append((executor.config, executor.server, executor.console))
            return dict(exitCode=0, output='/data/user/0/package/files\n')

        with tempfile.TemporaryDirectory() as output, \
                mock.patch('magicdesk_executor.connect', return_value=(client, mock.Mock())) as connect, \
                mock.patch('app_files.AppFiles'), \
                mock.patch.object(MagicDeskExecutor, '_app_command', launch):
            executor = FixtureExecutor.connect(output, 'app', config=Path('/selected/config'), server='selected')
        connect.assert_called_once_with(Path('/selected/config'), 'selected')
        self.assertEqual(observed, [(Path('/selected/config'), 'selected', 'selected-console')] * 2)
        self.assertEqual(executor.mode, 'app')

    def test_close_releases_both_consoles_after_cancellation_failure(self):
        executor = self.executor(Path('/unused'))
        executor.closed = False
        executor.files = mock.Mock()
        executor.cancel = mock.Mock(side_effect=RuntimeError('cancellation failed'))
        with self.assertRaisesRegex(RuntimeError, 'cancellation failed'):
            executor.close()
        executor.files.close.assert_called_once()
        self.assertEqual(executor.client.call.call_args.args,
                         ('console.close', dict(sessionId='console')))
        self.assertTrue(executor.closed)
        executor.close()
        executor.cancel.assert_called_once()


if __name__ == '__main__':
    unittest.main()
