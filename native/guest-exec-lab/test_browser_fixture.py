#!/usr/bin/env python3
"""Failed GUI-fixture admission must leave prior browser evidence intact."""
import fcntl
from pathlib import Path
import runpy
import socket
import subprocess
import sys
import tempfile
import unittest


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
