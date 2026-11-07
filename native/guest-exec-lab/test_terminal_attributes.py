#!/usr/bin/env python3
"""Extended terminal ABI and Android-policy fallback through the shell runtime."""
import argparse
import ctypes
import errno
import fcntl
import os
from pathlib import Path
import pty
import struct
import sys

def guest():
    master, slave = pty.openpty()
    get, setters = 0x802c542a, (0x402c542b, 0x402c542c, 0x402c542d)
    original = bytearray(44)
    try:
        fcntl.ioctl(slave, get, original)
        legacy = bytearray(36)
        fcntl.ioctl(slave, 0x5401, legacy)
        assert original[:36] == legacy
        changed = bytearray(original)
        flags = struct.unpack_from('I', changed, 8)[0]
        struct.pack_into('I', changed, 8, (flags & ~(0x100f | 0x100f0000)) | 0x1002)
        for command in setters:
            fcntl.ioctl(slave, command, changed)
            actual = bytearray(44)
            fcntl.ioctl(slave, get, actual)
            assert struct.unpack_from('II', actual, 36) == (115200, 115200)
            assert actual[17:36] == original[17:36]
        libc = ctypes.CDLL(None, use_errno=True)
        for fd, pointer, expected in ((slave, 1, errno.EFAULT), (-1, 1, errno.EBADF)):
            assert libc.ioctl(fd, get, ctypes.c_void_p(pointer)) == -1
            assert ctypes.get_errno() == expected
        with open('/dev/null', 'rb') as ordinary:
            try:
                fcntl.ioctl(ordinary.fileno(), get, bytearray(44))
                raise AssertionError('non-terminal accepted TCGETS2')
            except OSError as error:
                assert error.errno == errno.ENOTTY, error
        # An arbitrary BOTHER rate is supported by the native extended ABI,
        # or explicitly rejected when only Android's legacy ABI is available.
        struct.pack_into('I', changed, 8, (flags & ~(0x100f | 0x100f0000)) | 0x1000)
        struct.pack_into('II', changed, 36, 123457, 123457)
        try:
            fcntl.ioctl(slave, setters[0], changed)
            actual = bytearray(44)
            fcntl.ioctl(slave, get, actual)
            assert struct.unpack_from('I', actual, 40)[0] == 123457
        except OSError as error:
            assert error.errno == errno.EOPNOTSUPP, error
        print('PASS termios2: roundtrip, standard rates, control bytes, invalid inputs and explicit BOTHER support')
    finally:
        fcntl.ioctl(slave, setters[0], original)
        os.close(slave)
        os.close(master)


def main():
    from test_oci_services import Suite

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build', type=Path, required=True)
    parser.add_argument('--store', required=True)
    args = parser.parse_args()
    suite = Suite(Path('build/terminal-attributes-results'), args.build)
    target = '/tmp/md-terminal-' + suite.report['id']
    prefix = [suite.runner, '--store', args.store, '--user', '0:0']
    created = False
    try:
        suite.command(prefix + ['--', '/bin/mkdir', target])
        created = True
        suite.upload(Path(__file__), suite.base + '/test.py')
        result = suite.command(prefix + ['--bind', suite.base, target, '--deadline-seconds', '30',
                                        '--', '/usr/bin/python3', target + '/test.py', '--guest'])
        assert 'PASS termios2:' in result
        suite.report['passed'] = True
    finally:
        try:
            if created:
                suite.command(prefix + ['--', '/bin/rmdir', target])
        finally:
            suite.finish()


if __name__ == '__main__':
    if '--guest' in sys.argv:
        guest()
    else:
        main()
