#!/usr/bin/env python3
"""Failed GUI-fixture admission must leave prior browser evidence intact."""
import fcntl
import json
import os
from pathlib import Path
import runpy
import socket
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

from browser import benchmark


class BrowserBenchmark(unittest.TestCase):
    def test_display_clock_snapshot_is_read_only_and_bounded(self):
        client = mock.Mock()
        mode = dict(modeId=3, refreshRate=120)
        client.call.return_value = dict(generatedAtMillis=17, catalogError=None,
            displays=[dict(id=0, state=2, mode=mode, supportedModes=[mode], profile='unused')])
        self.assertEqual(benchmark.display_clocks(client), dict(generatedAtMillis=17, error=None,
            displays=[dict(id=0, state=2, mode=mode)]))
        client.call.assert_called_once_with('list_displays')
        client.call.return_value = dict(catalogError='unavailable')
        self.assertEqual(benchmark.display_clocks(client),
                         dict(generatedAtMillis=None, error='unavailable', displays=[]))

    def test_instrumentation_reports_the_execution_owner(self):
        args = SimpleNamespace(statistics=False, diagnostics=False)
        self.assertEqual(benchmark.instrumentation(args), dict(statistics=False, diagnostics=False))
        self.assertEqual(benchmark.instrumentation(args, dict(statistics=True, diagnostics=True)),
                         dict(statistics=True, diagnostics=True))
        self.assertEqual(benchmark.instrumentation(args, {}), dict(statistics=None, diagnostics=None))
        for name in ('statistics', 'diagnostics'):
            selected = SimpleNamespace(statistics=False, diagnostics=False)
            setattr(selected, name, True)
            self.assertTrue(benchmark.instrumentation(selected)[name])
            self.assertTrue(benchmark.instrumentation(selected, {name: True})[name])
            for state in ({}, {name: False}):
                with self.assertRaisesRegex(ValueError, 'retained desktop session'):
                    benchmark.instrumentation(selected, state)

    def test_thread_counters_preserve_comm_spaces_and_parentheses(self):
        fields = ['0'] * 37
        fields[0], fields[11], fields[12], fields[19], fields[36] = 'S', '17', '23', '1000', '6'
        row = '123 (worker ) name) ' + ' '.join(fields)
        self.assertEqual(benchmark.thread_snapshot(row, 123),
                         {123: dict(ticks=40, start=1000, cpu=6)})
        with self.assertRaisesRegex(AssertionError, 'Supervisor exited'):
            benchmark.thread_snapshot(row, 124)

    def protocol(self, replies):
        protocol = object.__new__(benchmark.Protocol)
        protocol.serial = 0
        protocol.socket = mock.Mock()
        protocol.socket.recv.side_effect = [json.dumps(reply) for reply in replies]
        return protocol

    def test_cdp_events_do_not_replace_the_exact_reply(self):
        protocol = self.protocol([dict(method='Page.loadEventFired'),
                                  dict(id=99, result={}), dict(id=1, result=dict(value=7))])
        self.assertEqual(protocol.call('test', session='page'), dict(value=7))
        self.assertEqual(json.loads(protocol.socket.send.call_args.args[0]),
                         dict(id=1, method='test', params={}, sessionId='page'))

    def test_cdp_events_do_not_extend_the_reply_deadline(self):
        protocol = self.protocol([dict(method='Page.loadEventFired')])
        with mock.patch.object(benchmark.time, 'monotonic', side_effect=[0, 1, 31]):
            with self.assertRaises(TimeoutError):
                protocol.call('test')
        protocol.socket.settimeout.assert_called_once_with(29)

    def test_cdp_rejects_protocol_and_javascript_errors(self):
        protocol = self.protocol([dict(id=1, error=dict(message='bad request'))])
        with self.assertRaises(AssertionError):
            protocol.call('test')
        protocol = self.protocol([dict(id=1, result=dict(exceptionDetails=dict(text='error')))])
        with self.assertRaises(AssertionError):
            protocol.evaluate('test()', 'page')


class BrowserFixture(unittest.TestCase):
    def test_webgl_requires_readback_and_retained_context(self):
        checks = runpy.run_path(str(Path(__file__).with_name('fixtures') /
                                   'sandbox-browser-gui.py'))['webgl_checks']
        rendered = {'type': 'gpu', 'value': {'pixel': [0, 255, 0, 255]}}
        for receipts, expected in (
                ([], False),
                ([{'type': 'gpu', 'value': None}], False),
                ([rendered], True),
                ([rendered, {'type': 'gpu-context-lost', 'value': True}], False),
                ([rendered, {'type': 'gpu', 'value': None}], False),
                ([{'type': 'gpu', 'value': {'pixel': [0, 0, 0, 0]}}], False)):
            with self.subTest(receipts=receipts):
                self.assertEqual(expected, all(checks(receipts).values()))

    def check_rejected(self, work, *options):
        result = subprocess.run([sys.executable,
            str(Path(__file__).with_name('fixtures') / 'sandbox-browser-gui.py'),
            '--reuse-work', str(work), '--common', str(work),
            '--output', str(work / 'report'), *options], capture_output=True, text=True, timeout=10)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((work / 'shroot-snap.txt').read_bytes(), b'Shroot browser file round trip\n')
        return result.stderr

    def test_relaunch_cannot_change_active_run(self):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            (work / 'profile').mkdir()
            (work / 'shroot-snap.txt').write_bytes(b'Shroot browser file round trip\n')
            with (work / '.fixture.lock').open('a') as ownership:
                fcntl.flock(ownership, fcntl.LOCK_EX | fcntl.LOCK_NB)
                self.assertIn('BlockingIOError', self.check_rejected(work))

    def test_busy_port_does_not_delete_previous_download(self):
        with tempfile.TemporaryDirectory() as directory, socket.socket() as listener:
            work = Path(directory)
            (work / 'profile').mkdir()
            (work / 'shroot-snap.txt').write_bytes(b'Shroot browser file round trip\n')
            listener.bind(('127.0.0.1', 0))
            listener.listen()
            self.assertIn('Address already in use', self.check_rejected(
                work, '--port', str(listener.getsockname()[1])))


if __name__ == '__main__':
    unittest.main()
