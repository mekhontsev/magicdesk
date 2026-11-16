#!/usr/bin/env python3
"""Native diagnostic evidence must not inherit console preview truncation."""
import argparse
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from fixture_executor import FixtureExecutor


class CapturedCommands(unittest.TestCase):
    def executor(self, output):
        executor = object.__new__(FixtureExecutor)
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
                with mock.patch('fixture_executor.time.monotonic', return_value=100):
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


if __name__ == '__main__':
    unittest.main()
