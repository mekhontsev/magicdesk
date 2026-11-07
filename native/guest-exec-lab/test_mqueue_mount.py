#!/usr/bin/env python3
"""Limited mqueue mount capability, IPC ownership and explicit absent queue IO."""
import argparse
from contextlib import ExitStack
import ctypes
import errno
import os
from pathlib import Path
import subprocess
import sys
import tempfile


def guest():
    libc = ctypes.CDLL(None, use_errno=True)
    libc.syscall.restype = ctypes.c_long

    def call(nr, *args, error=0):
        converted = [ctypes.c_char_p(str(arg).encode()) if isinstance(arg, (str, Path))
                     else ctypes.c_long(arg) for arg in args]
        result = libc.syscall(ctypes.c_long(nr), *converted)
        code = ctypes.get_errno() if result < 0 else 0
        assert code == error, (nr, args, result, code, error)
        return result

    def denied(action, expected):
        try:
            action()
            raise AssertionError('unexpected success')
        except OSError as error:
            assert error.errno == expected, error

    def identity(path):
        st = os.fstat(path) if isinstance(path, int) else os.stat(path)
        return st.st_dev, st.st_ino

    def fs_type(path):
        value = (ctypes.c_long * 32)()
        call(43, path, ctypes.addressof(value))
        return value[0]

    def attach(context, path):
        call(431, context, 6, 0, 0, 0)
        fd = call(432, context, 1, 0)
        os.close(context)
        call(429, fd, '', -100, path, 4)
        os.close(fd)

    with tempfile.TemporaryDirectory(prefix='md-mqueue-') as directory, ExitStack() as cleanup:
        root = Path(directory)
        a, b, c, alias = [root / name for name in ('a', 'b', 'c', 'alias')]
        for path in (a, b, c, alias):
            path.mkdir()
            cleanup.callback(libc.syscall, ctypes.c_long(39), ctypes.c_char_p(str(path).encode()), ctypes.c_long(2))
        call(97, 0x08000000)  # A new IPC owner within the existing logical user authority.
        first = os.open('/proc/self/ns/ipc', os.O_RDONLY)
        call(40, 'mqueue', a, 'mqueue', 14, 0)
        call(40, 'mqueue', b, 'mqueue', 14, 0)
        assert identity(a) == identity(b)
        assert a.stat().st_mode & 0o7777 == 0o1777
        assert a.stat().st_uid == 0 and a.stat().st_size == 40
        assert fs_type(a) == 0x19800202 and os.listdir(a) == []
        line = next(line for line in Path('/proc/self/mountinfo').read_text().splitlines()
                    if line.split()[4] == str(a))
        assert ' - mqueue ' in line, line
        os.chmod(a, 0o1700)
        assert b.stat().st_mode & 0o7777 == 0o1700
        os.chown(b, 123, 456)
        assert a.stat().st_uid == 123 and a.stat().st_gid == 456
        os.chown(b, 0, 0)
        print('PASS mqueue metadata, mountinfo, statfs and shared IPC filesystem identity', flush=True)

        denied(lambda: (a / 'not-a-queue').write_bytes(b'no'), errno.EOPNOTSUPP)
        denied(lambda: (a / 'directory').mkdir(), errno.EOPNOTSUPP)
        denied(lambda: os.symlink('/tmp', a / 'link'), errno.EOPNOTSUPP)
        denied(lambda: os.setxattr(a, 'user.test', b'no'), errno.EOPNOTSUPP)
        assert os.listdir(a) == []
        for nr in range(180, 186):
            call(nr, 0, 0, 0, 0, 0, error=errno.ENOSYS)
        call(40, a, alias, 0, 4096, 0)
        call(40, 0, alias, 0, 4096 | 32 | 1, 0)
        denied(lambda: os.chmod(alias, 0o777), errno.EROFS)
        denied(lambda: (alias / 'file').touch(), errno.EROFS)
        assert a.stat().st_mode & 0o7777 == 0o1700
        call(39, alias, 2)
        print('PASS unsupported queue creation/IO and per-mount readonly policy', flush=True)

        retained = os.open(a, os.O_RDONLY | os.O_DIRECTORY)
        context = call(430, 'mqueue', 1)
        call(97, 0x08000000)
        second = os.open('/proc/self/ns/ipc', os.O_RDONLY)
        attach(context, c)
        assert identity(c) == identity(a)
        call(39, c, 2)
        call(40, 'mqueue', c, 'mqueue', 14, 0)
        assert identity(c) != identity(a)
        assert c.stat().st_mode & 0o7777 == 0o1777
        call(268, first, 0x08000000)
        call(40, b, alias, 0, 4096 | 16384, 0)
        assert identity(alias) == identity(a)
        call(39, a, 2)
        call(39, b, 2)
        assert identity(alias) == identity(retained)
        call(39, alias, 2)
        os.close(first)
        call(268, second, 0x08000000)
        os.close(second)
        assert os.listdir(retained) == []
        assert os.fstat(retained).st_mode & 0o7777 == 0o1700
        reopened = os.open('/proc/self/fd/' + str(retained), os.O_PATH)
        assert identity(reopened) == identity(retained)
        os.close(reopened)
        os.close(retained)
        print('PASS fsopen IPC capture, setns, recursive bind and detached directory lifetime', flush=True)

        child = os.fork()
        if child == 0:
            try:
                call(97, 0x20000)
                assert c.stat().st_mode & 0o7777 == 0o1777
                os.chmod(c, 0o1700)
                call(97, 0x10000000 | 0x20000)
                Path('/proc/self/uid_map').write_text('0 0 1\n')
                Path('/proc/self/setgroups').write_text('deny\n')
                Path('/proc/self/gid_map').write_text('0 0 1\n')
                call(430, 'mqueue', 1, error=errno.EPERM)
                call(40, 'mqueue', a, 'mqueue', 14, 0, error=errno.EPERM)
                call(97, 0x08000000)
                call(40, 'mqueue', a, 'mqueue', 14, 0)
                assert a.stat().st_mode & 0o7777 == 0o1777
                call(39, a, 2)
                os._exit(0)
            except BaseException:
                import traceback
                traceback.print_exc()
                os._exit(1)
        # EVENT_WAIT: namespace child exit; the supervised deadline fails a hang.
        assert os.waitpid(child, 0)[1] == 0
        assert c.stat().st_mode & 0o7777 == 0o1700
        call(39, c, 2)
        print('PASS copied mount view, shared metadata and IPC-owner user authority', flush=True)

        result = subprocess.run(['strace', '-e', 'trace=fsopen,fsconfig,fsmount,move_mount',
                                 'mount', '-t', 'mqueue', 'mqueue', str(a)], capture_output=True, text=True)
        print(result.stderr, end='', flush=True)
        assert result.returncode == 0 and 'move_mount(' in result.stderr, result
        call(39, a, 2)
        before = len(os.listdir('/proc/self/fd'))
        for _ in range(40):
            call(97, 0x08000000)
            context = call(430, 'mqueue', 1)
            call(431, context, 6, 0, 0, 0)
            mount = call(432, context, 1, 0)
            os.close(context)
            os.close(mount)
        assert len(os.listdir('/proc/self/fd')) == before
        print('PASS stock util-linux mount and abandoned detached IPC mounts', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('store')
    args = parser.parse_args()
    from test_oci_services import Suite
    suite = Suite(args.build, args.build)
    target = '/tmp/md-mqueue-' + suite.report['id']
    prefix = [suite.runner, '--store', args.store, '--user', '0:0']
    try:
        suite.upload(Path(__file__), suite.base + '/test.py')
        suite.command(prefix + ['--', '/bin/mkdir', target])
        suite.command(prefix + ['--bind-ro', suite.base, target, '--deadline-seconds', '100', '--',
                                '/usr/bin/unshare', '--mount', '/usr/bin/python3', target + '/test.py', '--guest'],
                      timeout=120)
        suite.report['passed'] = True
    finally:
        try:
            suite.command(prefix + ['--', '/bin/rmdir', target])
        finally:
            suite.finish()


if __name__ == '__main__':
    if sys.argv[1:] == ['--guest']:
        guest()
    else:
        main()
