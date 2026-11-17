"""Native application adapter: delegation, argv boundaries and FD lifetime."""
import json
import os
from pathlib import Path
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import unittest


class ShrootAdapter(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix='command-shroot-')
        cls.root = Path(cls.tmp.name)
        cls.bridge = cls.root / 'bridge'
        source = Path(__file__).resolve().parents[2] / 'native/command-client'
        subprocess.run([os.environ.get('CC', 'cc'), '-std=c17', '-Wall', '-Wextra', '-Werror',
                        *(str(source / name) for name in ('bridge.c', 'shroot.c', 'wire.c')),
                        '-o', str(cls.bridge)], check=True, timeout=30)
        cls.capture = cls.root / 'capture'
        cls.capture.write_text('#!' + sys.executable + '\n'
            'import json, os, sys\n'
            'args = sys.argv[1:]\n'
            'fd = int(args[args.index("--owner-fd") + 1]) if "--owner-fd" in args else None\n'
            'print(json.dumps(dict(args=args, owner=bool(fd is not None and os.fstat(fd)), '
            'endpoint=os.environ.get("MAGICDESK_COMMAND_ENDPOINT"))))\n')
        cls.capture.chmod(0o700)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def run_adapter(self, args, endpoint=None, binary=None):
        env = dict(os.environ, MAGICDESK_COMMAND_BUILD='fixture',
                   MAGICDESK_X11_AUTHORITY='/run/fixture/authority')
        env.pop('MAGICDESK_COMMAND_ENDPOINT', None)
        if endpoint:
            env['MAGICDESK_COMMAND_ENDPOINT'] = endpoint
        return subprocess.run([str(self.bridge), '--shroot', str(self.root),
                               str(binary or self.capture), *args], env=env,
                              capture_output=True, text=True, timeout=15)

    def delegated(self, args, binary=None):
        errors = []
        with socket.socket() as server:
            server.bind(('127.0.0.1', 0)); server.listen(); server.settimeout(10)
            def serve():
                try:
                    with server.accept()[0] as client:
                        client.settimeout(10)
                        def read(size):
                            value = b''
                            while len(value) < size:
                                part = client.recv(size - len(value))
                                if not part:
                                    raise AssertionError('short handshake')
                                value += part
                            return value
                        def number():
                            return struct.unpack('!I', read(4))[0]
                        self.assertEqual(number(), 0x8d444301)
                        self.assertEqual(number(), 1)
                        self.assertEqual(read(number()), b'M' * 43)
                        self.assertEqual(read(number()), b'fixture')
                        client.sendall(struct.pack('!II', 0, 43) + b'D' * 43)
                        self.assertEqual(client.recv(1), b'', 'lease outlived launch')
                except BaseException as error:
                    errors.append(error)
            thread = threading.Thread(target=serve)
            thread.start()
            try:
                result = self.run_adapter(args, f'{server.getsockname()[1]}:' + 'M' * 43, binary)
            finally:
                thread.join(12)
            self.assertFalse(thread.is_alive())
            if errors:
                raise errors[0]
            return result

    def test_delegated_image_and_raw_launch(self):
        for prefix in (['exec', '/store'], ['login', '/store'], ['run', '/store'], ['--store', '/store']):
            with self.subTest(prefix=prefix):
                result = self.delegated(prefix + ['--env', 'PATH=/custom', '--magicdesk', '--', '/bin/sh'])
                self.assertEqual(result.returncode, 0, result.stderr)
                value = json.loads(result.stdout)
                args = value['args']
                self.assertTrue(value['owner'])
                self.assertNotIn('--magicdesk', args)
                self.assertEqual(args[:len(prefix)], prefix)
                self.assertEqual(args[-2:], ['--', '/bin/sh'])
                self.assertIn('PATH=/custom', args)
                self.assertIn('MAGICDESK_COMMAND_ENDPOINT=' + value['endpoint'], args)
                self.assertTrue(value['endpoint'].endswith('D' * 43))
                self.assertEqual(args[args.index('--path-prefix') + 1], '/run/magicdesk')
                self.assertEqual(args[args.index('--bind-ro-create') + 1:][:2], [str(self.root), '/run/magicdesk'])

    def test_guest_argument_is_not_an_integration_option(self):
        result = self.run_adapter(['--store', '/store', '--', '/bin/echo', '--magicdesk'])
        self.assertEqual(result.returncode, 0, result.stderr)
        value = json.loads(result.stdout)
        self.assertFalse(value['owner'])
        self.assertIsNone(value['endpoint'])
        self.assertEqual(value['args'][-3:], ['--', '/bin/echo', '--magicdesk'])
        self.assertIn('MAGICDESK_X11_AUTHORITY=/run/fixture/authority', value['args'])

    def test_failures_do_not_retain_lease(self):
        result = self.delegated(['--store', '/store', '--magicdesk', '--', '/bin/sh'], self.root / 'absent')
        self.assertEqual(result.returncode, 125, result.stderr)
        for args in (['--magicdesk', '--magicdesk'], ['--env'],
                     ['--env', 'MAGICDESK_COMMAND_ENDPOINT=forged']):
            self.assertEqual(self.run_adapter(args).returncode, 2)


if __name__ == '__main__':
    unittest.main()
