#!/usr/bin/env python3
"""Proc bind/remount policy without reading or writing host control files."""
import argparse
import ctypes
import errno
import os
from pathlib import Path
import stat
import sys
import tempfile


def guest(native_pid, native_cgroup):
    libc = ctypes.CDLL(None, use_errno=True)
    libc.syscall.restype = ctypes.c_long
    libc.mount.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_char_p,
                           ctypes.c_ulong, ctypes.c_void_p]

    def call(nr, *args, error=None):
        values = [ctypes.c_char_p(arg.encode()) if isinstance(arg, str)
                  else ctypes.c_long(arg) for arg in args]
        result = libc.syscall(ctypes.c_long(nr), *values)
        assert (ctypes.get_errno() if result < 0 else 0) == (error or 0), (
            nr, args, result, ctypes.get_errno(), error)
        return result

    def external_task(root):
        for name in ('status', 'stat', 'limits', 'cgroup'):
            path = str(native_pid) + '/' + name
            expected = os.stat(path, dir_fd=root)
            for scoped in (False, True):
                if scoped:
                    how = (ctypes.c_uint64 * 3)(os.O_RDONLY | os.O_CLOEXEC, 0, 0xb)
                    fd = call(437, root, path, ctypes.addressof(how), ctypes.sizeof(how))
                else:
                    fd = os.open(path, os.O_RDONLY | os.O_CLOEXEC, dir_fd=root)
                try:
                    info = os.fstat(fd)
                    assert (info.st_dev, info.st_ino) == (expected.st_dev, expected.st_ino)
                    data = os.read(fd, 65536)
                    assert data
                    if name == 'cgroup':
                        assert data.decode().strip() == native_cgroup.strip(), data
                finally:
                    os.close(fd)

    initial = os.open('/proc', os.O_PATH | os.O_DIRECTORY)
    try:
        external_task(initial)
    finally:
        os.close(initial)

    def scoped_controls(root):
        external_task(root)
        def opened(path, resolve=0xb, error=None):
            how = (ctypes.c_uint64 * 3)(os.O_RDONLY | os.O_CLOEXEC, 0, resolve)
            fd = call(437, root, path, ctypes.addressof(how), ctypes.sizeof(how), error=error)
            if fd >= 0:
                os.read(fd, 4096)
                os.close(fd)

        for name in ('setgroups', 'uid_map', 'gid_map', 'status', 'stat',
                     'limits', 'cgroup', 'mounts', 'mountinfo', 'cmdline', 'auxv'):
            opened('self/' + name)
            opened('self/' + name, resolve=0x13)  # IN_ROOT | NO_XDEV | NO_MAGICLINKS
            opened('self/' + name, resolve=0xf, error=errno.ELOOP)
            opened('self/' + name + '/..', error=errno.ENOTDIR)
        opened('../self/setgroups', error=errno.EXDEV)
        for name in ('exe', 'root', 'cwd', 'ns/mnt', 'fd/0'):
            opened('self/' + name, error=errno.ELOOP)

    context = call(430, 'proc', 1)
    call(431, context, 6, 0, 0, 0)
    detached = call(432, context, 1, 0)
    os.close(context)
    try:
        scoped_controls(detached)
    finally:
        os.close(detached)
    print('PASS detached proc scoped controls and traversal restrictions', flush=True)

    def mount(source, target, kind, flags):
        args = [os.fsencode(value) if value is not None else None
                for value in (source, target, kind)]
        if libc.mount(*args, flags, None):
            raise OSError(ctypes.get_errno(), repr((source, target, kind, flags)))

    with tempfile.TemporaryDirectory(prefix='md-proc-policy-') as directory:
        mount('proc', directory, 'proc', 2 | 4 | 8)
        try:
            root = os.open(directory, os.O_PATH | os.O_DIRECTORY)
            try:
                scoped_controls(root)
            finally:
                os.close(root)
            entries = os.listdir(directory)
            for name in ('sysrq-trigger', 'kcore', 'kmsg', 'keys', 'asound', 'kallsyms'):
                assert name in entries, name
                path = directory + '/' + name
                info = os.stat(path)
                assert stat.S_IMODE(info.st_mode) == 0, info
                assert (stat.S_ISDIR if name == 'asound' else stat.S_ISREG)(info.st_mode)
                pinned = os.open(path, os.O_PATH | os.O_CLOEXEC)
                try:
                    assert os.fstat(pinned).st_ino == info.st_ino
                finally:
                    os.close(pinned)
                for flags in (os.O_RDONLY, os.O_WRONLY) if name != 'asound' else (os.O_RDONLY,):
                    try:
                        fd = os.open(path, flags)
                    except OSError as error:
                        assert error.errno == errno.EACCES, error
                    else:
                        os.close(fd)
                        raise AssertionError('host kernel control was exposed: ' + name)
            print('PASS mounted proc retains inaccessible kernel-control mount targets', flush=True)
            for name in ('irq', 'bus', 'fs', 'version'):
                target = directory + '/' + name
                print('LOOKUP', target, flush=True)
                before = os.stat(target)
                fd = os.open(target, os.O_PATH | os.O_CLOEXEC)
                try:
                    assert os.fstat(fd).st_ino == before.st_ino
                finally:
                    os.close(fd)
                print('O_PATH', name, 'ok', flush=True)
                mount(target, target, None, 4096 | 16384)
                print('BIND', name, 'ok', flush=True)
                mount(None, target, None, 4096 | 32 | 1 | 2 | 4 | 8)
                print('REMOUNT', name, 'ok', flush=True)
                assert os.statvfs(target).f_flag & os.ST_RDONLY
            target = directory + '/version'
            try:
                fd = os.open(target, os.O_WRONLY)
            except OSError as error:
                assert error.errno == errno.EROFS, error
            else:
                os.close(fd)
                raise AssertionError('read-only proc mount admitted write open')
            print('PASS proc bind/remount, readonly metadata and denied data open', flush=True)
        finally:
            assert libc.umount2(os.fsencode(directory), 2) == 0, ctypes.get_errno()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('store')
    from test_oci_services import Suite, add_machine_entry_argument
    add_machine_entry_argument(parser)
    args = parser.parse_args()
    suite = Suite(args.build, args.build, machine_entry=args.machine_entry)
    target = '/tmp/md-proc-policy-' + suite.report['id']
    prefix = suite.guest(args.store, '--user', '0:0')
    try:
        suite.transport.upload(suite.client, Path(__file__), suite.base + '/test.py')
        suite.command(prefix + ['--', '/bin/mkdir', target])
        # The exec wrapper remains outside the supervised guest tree. Compare
        # its native proc text without inventing a guest identity for it.
        suite.command(['/system/bin/sh', '-c', 'exec "$@" --guest "$$" "$(cat /proc/$$/cgroup)"',
                       'proc-fixture', *prefix, '--bind-ro', suite.base, target, '--deadline-seconds', '60', '--',
                       '/usr/bin/unshare', '--mount', '/usr/bin/python3', target + '/test.py'],
                      timeout=80)
        suite.report['passed'] = True
    finally:
        try:
            suite.command(prefix + ['--', '/bin/rmdir', target])
        finally:
            suite.finish()


if __name__ == '__main__':
    guest(int(sys.argv[2]), sys.argv[3]) if sys.argv[1:2] == ['--guest'] else main()
