#!/usr/bin/env python3
import importlib.util
import copy
from contextlib import redirect_stdout
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location('audio_continuity',
    Path(__file__).with_name('fixtures') / 'audio-continuity.py')
audio = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audio)


class ContinuityTest(unittest.TestCase):
    def graph(self, media_class='Stream/Output/Audio'):
        return [dict(id=10, type='PipeWire:Interface:Node', info=dict(props={
                    'object.serial': 120, 'node.name': 'source', 'media.class': media_class})),
                dict(id=20, type='PipeWire:Interface:Node', info=dict(props={'node.name': 'recorder'})),
                dict(id=30, type='PipeWire:Interface:Link', info={
                    'input-node-id': 20, 'output-node-id': 10, 'state': 'active'})]

    def test_resolve_and_verify_output_stream_and_sink(self):
        for kind in ('Stream/Output/Audio', 'Audio/Sink', 'Audio/Source'):
            graph = self.graph(kind)
            target = audio.resolve_target(graph, 'source')
            self.assertEqual(target, audio.resolve_target(graph, '120'))
            self.assertEqual(audio.verify_capture(graph, target, 'recorder')['links'], 1)

    def test_absent_ambiguous_or_non_audio_target_is_rejected(self):
        graph = self.graph()
        for name in ('missing', '10'):
            with self.assertRaises(ValueError):
                audio.resolve_target(graph, name)
        with self.assertRaises(ValueError):
            audio.resolve_target(graph + [copy.deepcopy(graph[0])], 'source')
        with self.assertRaises(ValueError):
            audio.resolve_target(self.graph('Stream/Input/Audio'), 'source')

    def test_default_sink_fallback_or_mixed_capture_is_rejected(self):
        graph = self.graph()
        target = audio.resolve_target(graph, 'source')
        for state in ('fallback', 'mixed', 'missing', 'inactive', 'recorder-missing', 'reused-id'):
            with self.subTest(state=state):
                changed = copy.deepcopy(graph)
                if state == 'fallback':
                    changed[-1]['info']['output-node-id'] = 41
                elif state == 'mixed':
                    changed.append(copy.deepcopy(changed[-1]))
                    changed[-1]['info']['output-node-id'] = 41
                elif state == 'missing':
                    changed.pop()
                elif state == 'inactive':
                    changed[-1]['info']['state'] = 'paused'
                elif state == 'recorder-missing':
                    changed.pop(1)
                else:
                    changed[0]['info']['props']['object.serial'] = 121
                with self.assertRaises((ValueError, RuntimeError)):
                    audio.verify_capture(changed, target, 'recorder')

    def test_capture_uses_serial_and_forbids_policy_fallback(self):
        command = audio.recorder_command(audio.resolve_target(self.graph(), 'source'), 'owned')
        self.assertEqual(command[command.index('--target') + 1], '120')
        props = json.loads(command[command.index('--properties') + 1])
        self.assertEqual(props['node.name'], 'owned')
        for prop in ('node.dont-fallback', 'node.dont-move', 'node.dont-reconnect'):
            self.assertIs(props[prop], True)

    def test_pcm_is_drained_while_identity_query_is_pending(self):
        drained = threading.Event()
        original_feed = audio.PCMWindow.feed

        def feed(pcm, data, timestamp):
            original_feed(pcm, data, timestamp)
            if pcm.frames >= 48000:
                drained.set()

        def inspect(env, target, name):
            self.assertTrue(drained.wait(2), 'Graph inspection blocked PCM consumption')
            return dict(target=target, recorderId=20, links=2)

        child = subprocess.Popen([sys.executable, '-c', '''
import os, signal, sys, time
signal.signal(signal.SIGINT, lambda *_: sys.exit(0))
while True:
    os.write(1, bytes([1]) * 1920)
    time.sleep(.001)
'''], stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0)
        output = io.StringIO()
        try:
            with mock.patch.object(sys, 'argv', ['capture', '--env-pid', str(os.getpid()),
                    '--target', 'source', '--seconds', '3']), \
                    mock.patch.object(audio, 'graph_snapshot', return_value=self.graph()), \
                    mock.patch.object(audio, 'inspect_capture', side_effect=inspect) as query, \
                    mock.patch.object(audio.PCMWindow, 'feed', feed), \
                    mock.patch.object(audio.subprocess, 'Popen', return_value=child), \
                    redirect_stdout(output):
                audio.main()
            self.assertEqual(query.call_count, 2)
            report = json.loads(output.getvalue().splitlines()[-1])
            self.assertGreaterEqual(report['frames'], 3 * 48000)
            self.assertEqual(report['capture']['target']['serial'], 120)
            self.assertEqual(report['recorderExit'], 0)
        finally:
            if child.poll() is None:
                child.kill()
                child.wait()

    def test_phase_boundaries_and_late_delivery(self):
        phases = audio.PhaseTimeline([('before', 2), ('motion', 2), ('after', 2)], 48000)
        self.assertEqual(phases.advance(480, 10)[0]['frame'], 0)
        self.assertEqual(phases.advance(95999, 11), [])
        events = phases.advance(200000, 12)
        self.assertEqual([e['name'] for e in events], ['motion', 'after'])
        self.assertEqual([e['frame'] for e in events], [96000, 192000])
        self.assertTrue(all(e['observedFrame'] == 200000 and e['observed'] == 12 for e in events))
        self.assertEqual(phases.advance(288000, 13), [])
        self.assertEqual(len(phases.markers), 3)

    def test_fragmented_frames_and_warmup(self):
        pcm = audio.PCMWindow()
        size = pcm.block_bytes
        pcm.feed(bytes(200 * size), 10)
        self.assertEqual(pcm.spans, [])
        pcm.feed(bytes(3 * size - 1), 11)
        pcm.feed(b'\0' + b'\1' * size, 12)
        self.assertEqual(pcm.frames, 204 * pcm.block_frames)
        self.assertEqual(pcm.spans, [dict(startFrame=96000, frames=1440,
                                        firstObserved=11, lastObserved=12)])
        self.assertEqual(pcm.pending, b'')

    def test_quiet_is_not_zero_and_terminal_span(self):
        pcm = audio.PCMWindow()
        size = pcm.block_bytes
        pcm.feed(b'\1' * (200 * size), 1)
        pcm.feed(b'\1' + bytes(size - 1), 2)
        pcm.feed(bytes(size + 7), 3)
        pcm.finish_span(4)
        self.assertEqual(pcm.spans[0]['startFrame'], 201 * pcm.block_frames)
        self.assertEqual(pcm.spans[0]['frames'], pcm.block_frames)
        self.assertEqual(len(pcm.pending), 7)
        pcm.finish_span(5)
        self.assertEqual(len(pcm.spans), 1)


if __name__ == '__main__':
    unittest.main()
