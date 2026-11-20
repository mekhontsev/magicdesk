#!/usr/bin/env python3
"""Failed GUI-fixture admission must leave prior browser evidence intact."""
import _bootstrap
import fcntl
from collections import OrderedDict, deque
import json
import os
from pathlib import Path
import runpy
import shutil
import socket
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

import benchmark_browser as benchmark
from installed_desktop import runner_connections


class InstalledDesktopConnections(unittest.TestCase):
    def test_installed_shell_keeps_wrapper_as_the_single_owner(self):
        with mock.patch('installed_desktop.subprocess.check_output') as compile_command:
            self.assertEqual(runner_connections(SimpleNamespace(image=None), Path('classes')), '')
        compile_command.assert_not_called()

    def test_raw_apk_and_staged_runners_share_production_forwarding(self):
        for image in ('/apk/lib/libshroot_guest_image.so', '/staged/libshroot_guest_image.so'):
            with self.subTest(image=image), mock.patch('installed_desktop.subprocess.check_output',
                    return_value='production connections\n') as compile_command:
                self.assertEqual(runner_connections(SimpleNamespace(image=image), Path('classes')),
                    'production connections\n')
                compile_command.assert_called_once_with(['java', '-cp', 'classes',
                    'io.github.mekhontsev.magicdesk.GraphicalRecipe', 'connections'], text=True)


class BrowserBenchmark(unittest.TestCase):
    @unittest.skipUnless(shutil.which('node'), 'requires a JavaScript runtime')
    def test_frame_snapshot_rejects_sampling_errors(self):
        script = '''
            import assert from 'node:assert/strict';
            let frame;
            globalThis.requestAnimationFrame = callback => { frame = callback };
            globalThis.window = globalThis;
            globalThis.shrootSnapshot = () => { throw new Error('sampling failed') };
            const pending = ''' + benchmark.FRAME_SNAPSHOT + ''';
            frame(123);
            await assert.rejects(pending, /sampling failed/);
            globalThis.shrootSnapshot = () => ({inputs: 1});
            const next = ''' + benchmark.FRAME_SNAPSHOT + ''';
            frame(456);
            assert.deepEqual(await next, {inputs: 1});
        '''
        subprocess.run(['node', '--input-type=module', '-e', script], check=True, timeout=10)

    def test_measurement_never_combines_documents_or_players(self):
        first = dict(probeId='page:1', source='player-a', frames=10)
        for current in (None, dict(first, probeId='page:2'), dict(first, source='player-b')):
            with self.subTest(current=current), self.assertRaises(AssertionError):
                benchmark.require_same_video(first, current)
        last = dict(first, frames=20)
        self.assertEqual(benchmark.require_same_video(first, last), last)

    def test_retained_session_owns_executor_unless_explicitly_matched(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            session = directory / 'session.json'
            argv = ['benchmark', '--desktop-session', str(session), '--url', 'http://localhost/',
                    '--output', tmp]
            for mode in ('app', 'shell'):
                session.write_text(json.dumps({'executor': {'mode': mode}, 'environment': {'store': 'fixture'}}))
                for selected in (None, mode, 'local'):
                    with self.subTest(mode=mode, selected=selected), \
                            mock.patch.dict(os.environ, SHROOT_TEST_EXECUTOR='local'), \
                            mock.patch.object(sys, 'argv', argv + (['--executor', selected] if selected else [])), \
                            mock.patch.object(benchmark.FixtureExecutor, 'connect', side_effect=RuntimeError('connected')) as connect:
                        if selected == 'local':
                            with self.assertRaisesRegex(ValueError, 'executor cannot be changed'):
                                benchmark.main()
                            connect.assert_not_called()
                        else:
                            with self.assertRaisesRegex(RuntimeError, 'connected'):
                                benchmark.main()
                            self.assertEqual(connect.call_args.args[1], mode)
                        summary = json.loads((directory / 'summary.json').read_text())
                        self.assertFalse(summary['passed'])
                        self.assertEqual(len(summary['samples']), 1)
                        self.assertFalse(summary['samples'][0]['passed'])
                        self.assertIn('error', summary['samples'][0])

    def test_debug_endpoint_does_not_enable_automation_control(self):
        args = SimpleNamespace(desktop_session=None, output=Path('unused'), build=None,
            executor='app', store='store', browser_arg=[], statistics=False, diagnostics=False,
            seconds=10, chromium='/usr/bin/chromium')
        suite = mock.Mock()
        suite.report = dict(id='fixture', passed=False)
        suite.guest.return_value = []
        with mock.patch.object(benchmark, 'Suite', return_value=suite), \
                mock.patch.object(benchmark, 'port', return_value=54321), \
                mock.patch.object(benchmark, 'Server', side_effect=RuntimeError('launch receipt')) as server:
            with self.assertRaisesRegex(RuntimeError, 'launch receipt'):
                benchmark.run(args, 'http://127.0.0.1/', 'lazy', 0, None)
        command = server.call_args.args[1]
        self.assertIn('--remote-debugging-address=127.0.0.1', command)
        self.assertEqual([arg for arg in command if arg.startswith('--remote-debugging-port=')],
                         ['--remote-debugging-port=54321'])
        self.assertNotIn('--enable-automation', command)
        self.assertNotIn('--disable-blink-features=AutomationControlled', command)
        self.assertNotIn('--no-sandbox', command)
        suite.finish.assert_called_once_with()

    def test_cleanup_failure_preserves_the_failed_run(self):
        args = SimpleNamespace(desktop_session=None, output=Path('unused'), build=None,
            executor='app', store='store', browser_arg=[], statistics=False, diagnostics=False)
        suite = mock.Mock()
        suite.report = dict(id='fixture', passed=False)
        suite.guest.return_value = []
        suite.command.side_effect = [RuntimeError('launch failed'), RuntimeError('cleanup failed')]
        with mock.patch.object(benchmark, 'Suite', return_value=suite):
            with self.assertRaisesRegex(RuntimeError, 'cleanup failed'):
                benchmark.run(args, 'http://127.0.0.1/', 'lazy', 0, None)
        row = suite.report['browserVideo']
        self.assertFalse(row['passed'])
        self.assertFalse(suite.report['passed'])
        self.assertIn('launch failed', row['error'])
        self.assertIn('cleanup failed', row['cleanupError'])
        suite.finish.assert_called_once_with()

    def test_video_start_follows_navigation_without_extending_deadline(self):
        protocol = mock.Mock()
        protocol.evaluate.side_effect = [None, benchmark.ProtocolError(dict(code=-32000,
            message='Inspected target navigated or closed')),
            dict(error='', presented=30, paused=False, ready=4)]
        with mock.patch.object(benchmark.time, 'monotonic', side_effect=[0, 1, 2, 3]), \
                mock.patch.object(benchmark.threading, 'Event'):
            benchmark.wait_video(protocol, 'page')
        self.assertEqual(protocol.evaluate.call_count, 3)
        expression = protocol.evaluate.call_args.args[0]
        self.assertIn('input?.isConnected', expression)
        self.assertIn('video?.isConnected', expression)
        self.assertIn('input.focus()', expression)
        protocol.evaluate.side_effect = [benchmark.ProtocolError(dict(code=-32000,
            message='Execution context was destroyed.'))]
        with mock.patch.object(benchmark.time, 'monotonic', side_effect=[0, 1, 31]), \
                mock.patch.object(benchmark.threading, 'Event'):
            with self.assertRaises(TimeoutError):
                benchmark.wait_video(protocol, 'page')

    def test_video_start_does_not_hide_renderer_or_player_failures(self):
        for failure in (benchmark.ProtocolError(dict(code=-32000, message='Target crashed')),
                        dict(error='Media unavailable', presented=0, paused=True, ready=0)):
            protocol = mock.Mock()
            protocol.evaluate.side_effect = [failure]
            with self.assertRaises(AssertionError):
                benchmark.wait_video(protocol, 'page')

    def test_video_start_does_not_accept_an_advertisement(self):
        protocol = mock.Mock()
        frame = dict(error='', presented=30, paused=False, ready=4)
        protocol.evaluate.side_effect = [dict(frame, advertisement=True), dict(frame, advertisement=False)]
        with mock.patch.object(benchmark.time, 'monotonic', side_effect=[0, 1, 2]), \
                mock.patch.object(benchmark.threading, 'Event'):
            benchmark.wait_video(protocol, 'page')
        self.assertEqual(protocol.evaluate.call_count, 2)

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
        protocol.events = deque(maxlen=256)
        protocol.requests = OrderedDict()
        protocol.socket = mock.Mock()
        protocol.socket.recv.side_effect = [json.dumps(reply) for reply in replies]
        return protocol

    def test_process_cpu_includes_retired_threads_without_attributing_reused_tids(self):
        before = dict(start=1, ticks=100), {123: dict(start=1, ticks=3), 124: dict(start=2, ticks=80)}
        after = dict(start=1, ticks=180), {123: dict(start=1, ticks=53), 124: dict(start=3, ticks=4)}
        with mock.patch.object(benchmark.os, 'sysconf', return_value=100):
            self.assertEqual(benchmark.thread_cost(123, before, after, 2), dict(
                threadCpuSeconds={'123': 0.5}, supervisorPercent=25, processPercent=40))
            with self.assertRaises(AssertionError):
                benchmark.thread_cost(124, before, after, 2)
            with self.assertRaisesRegex(AssertionError, 'Supervisor changed'):
                benchmark.thread_cost(123, before, (dict(start=2, ticks=180), after[1]), 2)

    def test_profile_keeps_intervals_and_thread_scopes_separate(self):
        with tempfile.TemporaryDirectory() as work:
            args = SimpleNamespace(profile=True, wait_timeline=False, wait_profile=False, output=Path(work))
            with mock.patch.object(benchmark.subprocess, 'Popen') as record, \
                    mock.patch.object(benchmark.subprocess, 'run') as report:
                record.return_value.wait.return_value = 0
                for ident, seconds in (('run-idle', 10), ('run', 25)):
                    row = {}
                    with benchmark.sample_profile(args, row, 123, ident, seconds):
                        self.assertIn('--duration ' + str(seconds), record.call_args.args[0][-1])
                    self.assertEqual(Path(row['samplingProfile']).name, 'profile-' + ident + '.data')
                    commands = [call.args[0][-1] for call in report.call_args_list[-2:]]
                    self.assertIn('--sort tid,dso,symbol', commands[0])
                    self.assertIn('--tids 123 --children --sort symbol', commands[1])
                    self.assertTrue(all(row['samplingProfile'] in command for command in commands))

    def test_wait_timeline_counts_only_the_supervisor_and_delivered_statuses(self):
        with tempfile.TemporaryDirectory() as work:
            args = SimpleNamespace(profile=False, wait_timeline=True, wait_profile=False, output=Path(work))
            with mock.patch.object(benchmark.subprocess, 'Popen') as record:
                record.return_value.wait.return_value = 0
                for ident, seconds in (('run-idle', 10), ('run', 25)):
                    row = {}
                    with benchmark.sample_profile(args, row, 123, ident, seconds):
                        cmd = benchmark.shlex.split(record.call_args.args[0][-1])
                    self.assertEqual(cmd[1:5], ['stat', '-t', '123', '--no-inherit'])
                    self.assertEqual(cmd[cmd.index('--tp-filter')+1], 'id == 260 && ret > 0')
                    self.assertIn('task-clock,context-switches', cmd)
                    self.assertEqual(cmd[cmd.index('--duration')+1], str(seconds))
                    self.assertEqual(Path(row['waitTimelineCsv']).name, 'wait-' + ident + '.csv')

    def test_wait_profile_keeps_negative_results_and_the_syscall_caller(self):
        with tempfile.TemporaryDirectory() as work:
            args = SimpleNamespace(profile=False, wait_timeline=False, wait_profile=True, output=Path(work))
            with mock.patch.object(benchmark.subprocess, 'Popen') as record:
                record.return_value.wait.return_value = 0
                row = {}
                with benchmark.sample_profile(args, row, 123, 'video', 20):
                    cmd = benchmark.shlex.split(record.call_args.args[0][-1])
                self.assertEqual(cmd[1:5], ['record', '-t', '123', '--no-inherit'])
                self.assertEqual(cmd[cmd.index('--tp-filter')+1], 'id == 260')
                self.assertIn('--no-unwind', cmd)
                self.assertIn('--size-limit', cmd)
                self.assertEqual(cmd[-1], row['waitProfile'])

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

    def test_cdp_retains_bounded_media_and_network_failures(self):
        events = [dict(method='Media.playerErrorsRaised', params=dict(index=i)) for i in range(300)]
        failure = dict(method='Network.loadingFailed', params=dict(requestId='1', errorText='net::ERR_CONNECTION_RESET'))
        protocol = self.protocol(events + [failure, dict(method='Page.loadEventFired'), dict(id=1, result={})])
        protocol.call('test')
        self.assertEqual(len(protocol.events), 256)
        self.assertEqual(protocol.events[0]['params']['index'], 45)
        self.assertEqual(protocol.events[-1], dict(failure, params=dict(failure['params'], url=None)))

    def test_cdp_network_evidence_is_bounded_and_excludes_query_and_headers(self):
        protocol = self.protocol([])
        for index in range(513):
            protocol.observe(dict(method='Network.requestWillBeSent', sessionId='page', params=dict(
                requestId=str(index), request=dict(url='https://example.org/video?token=secret#fragment',
                                                  headers={'Authorization': 'secret'}))))
        self.assertEqual(len(protocol.requests), 512)
        self.assertNotIn(('page', '0'), protocol.requests)
        protocol.observe(dict(method='Network.loadingFinished', sessionId='page', params=dict(requestId='1')))
        self.assertEqual(len(protocol.requests), 511)
        protocol.observe(dict(method='Network.loadingFailed', sessionId='page', params=dict(
            requestId='2', errorText='net::ERR_CONNECTION_RESET')))
        self.assertEqual(protocol.events[-1]['params']['url'], 'https://example.org/video')
        protocol.observe(dict(method='Network.responseReceived', params=dict(requestId='3', response=dict(
            status=403, url='https://example.org/video?token=secret#fragment', headers={'Set-Cookie': 'secret'}))))
        self.assertEqual(protocol.events[-1]['params'], dict(requestId='3', status=403, url='https://example.org/video'))
        self.assertNotIn('secret', json.dumps(list(protocol.events)))

    def test_cdp_rejects_protocol_and_javascript_errors(self):
        protocol = self.protocol([dict(id=1, error=dict(message='bad request'))])
        with self.assertRaises(AssertionError):
            protocol.call('test')
        protocol = self.protocol([dict(id=1, result=dict(exceptionDetails=dict(text='error')))])
        with self.assertRaises(AssertionError):
            protocol.evaluate('test()', 'page')


class BrowserFixture(unittest.TestCase):
    def test_gpu_report_requires_sandbox_composition_and_no_crashes(self):
        checks = runpy.run_path(str((_bootstrap.SHROOT_TESTS / 'fixtures') /
                                   'sandbox-browser-gui.py'))['chromium_gpu_checks']
        report = '*   Compositing: Hardware accelerated\nSandboxed : true\nGPU process crash count: 0\n'
        self.assertTrue(all(checks(report).values()))
        for invalid in ('', report.replace('true', 'false'), report.replace('count: 0', 'count: 1'),
                        report.replace('Hardware accelerated', 'Software only'),
                        report.replace('Sandboxed : true\n', ''), report + 'Sandboxed: false\n'):
            with self.subTest(report=invalid):
                self.assertFalse(all(checks(invalid).values()))

    def test_gpu_report_snapshot_rejects_reused_evidence(self):
        snapshot = runpy.run_path(str((_bootstrap.SHROOT_TESTS / 'fixtures') /
                                     'sandbox-browser-gui.py'))['gpu_reports']
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            report = work / 'about-gpu.txt'
            report.write_text('previous report')
            before = snapshot(work)
            self.assertEqual(before, snapshot(work))
            report.write_text('new report with different size')
            self.assertNotEqual(before[report], snapshot(work)[report])
            other = work / 'about-gpu (1).txt'
            other.write_text('new export')
            self.assertNotIn(other, before)
            self.assertIn(other, snapshot(work))

    def test_webgl_requires_readback_and_retained_context(self):
        checks = runpy.run_path(str((_bootstrap.SHROOT_TESTS / 'fixtures') /
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
            str((_bootstrap.SHROOT_TESTS / 'fixtures') / 'sandbox-browser-gui.py'),
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
