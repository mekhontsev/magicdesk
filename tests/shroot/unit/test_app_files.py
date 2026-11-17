import _bootstrap
import hashlib
from pathlib import Path
import re
import secrets
import socket
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

with patch.object(sys, 'path', [str(Path(__file__).resolve().parents[1]), *sys.path]):
    from app_files import AppFiles


class AppFileTransportTests(unittest.TestCase):
    def transfer(self, payload, upload, corrupt=False):
        owner = AppFiles.__new__(AppFiles)
        observed = bytearray()

        def command(value):
            if value.startswith('sha256sum '):
                return dict(exitCode=0, output=hashlib.sha256(payload).hexdigest() + ' file')
            token = re.search(r'printf %s ([0-9a-f]{64})', value)[1]
            port = int(re.search(r'127.0.0.1 (\d+)', value)[1])
            with socket.create_connection(('127.0.0.1', port), timeout=5) as connection:
                connection.sendall(token.encode())
                if upload:
                    while chunk := connection.recv(8192):
                        observed.extend(chunk)
                else:
                    connection.sendall(payload + (b'corrupt' if corrupt else b''))
                    connection.shutdown(socket.SHUT_WR)
            return dict(exitCode=0, output='')

        owner.command = command
        with tempfile.TemporaryDirectory() as directory:
            local = Path(directory) / 'bytes'
            if upload:
                local.write_bytes(payload)
            result = owner.transfer('/private/source with spaces', local, upload=upload)
            self.assertEqual(payload, bytes(observed) if upload else local.read_bytes())
            self.assertEqual(hashlib.sha256(payload).hexdigest(), result['sha256'])

    def test_bidirectional_empty_and_binary_streams(self):
        for payload in (b'', secrets.token_bytes(2 * 1024 * 1024 + 19)):
            for upload in (False, True):
                with self.subTest(size=len(payload), upload=upload):
                    self.transfer(payload, upload)

    def test_download_rejects_changed_bytes(self):
        with self.assertRaisesRegex(IOError, 'checksum mismatch'):
            self.transfer(b'original', False, corrupt=True)

    def test_command_failure_retires_waiting_transfer(self):
        owner = AppFiles.__new__(AppFiles)
        owner.command = lambda _: (_ for _ in ()).throw(RuntimeError('command failed'))
        before = time.monotonic()
        with self.assertRaisesRegex(RuntimeError, 'command failed'):
            owner.transfer('/unused', '/unused')
        self.assertLess(time.monotonic() - before, 5)
        self.assertFalse(any(thread.name == 'fixture-files' for thread in threading.enumerate()))


if __name__ == '__main__':
    unittest.main()
