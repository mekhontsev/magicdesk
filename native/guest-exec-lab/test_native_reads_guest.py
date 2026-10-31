#!/usr/bin/env python3
"""Read-only namespace operations, including protected task-affine fallbacks."""
import ctypes
import errno
import os
from pathlib import Path
import stat
import socket
import sys
import tempfile


libc = ctypes.CDLL(None, use_errno=True)
libc.readlinkat.argtypes = (ctypes.c_int, ctypes.c_char_p, ctypes.c_void_p, ctypes.c_size_t)
libc.readlinkat.restype = ctypes.c_ssize_t
libc.mount.argtypes = (ctypes.c_char_p, ctypes.c_char_p, ctypes.c_char_p, ctypes.c_ulong, ctypes.c_void_p)
libc.umount2.argtypes = (ctypes.c_char_p, ctypes.c_int)


def check_errno(expected, directory, name, buffer, count):
    ctypes.set_errno(0)
    assert libc.readlinkat(directory, name, buffer, count) == -1
    assert ctypes.get_errno() == expected, (name, ctypes.get_errno(), expected)


def ptrace_capability(enabled):
    header = (ctypes.c_uint32 * 2)(0x20080522, 0)
    data = (ctypes.c_uint32 * 6)()
    assert libc.capget(header, data) == 0, ctypes.get_errno()
    previous = bool(data[0] & (1 << 19))
    data[0] = data[0] | (1 << 19) if enabled else data[0] & ~(1 << 19)
    assert libc.capset(header, data) == 0, ctypes.get_errno()
    return previous


def managed_process_reads(root, protected, attached):
    for restricted in ('none', 'dumpable', 'identity'):
        ready_r, ready_w = os.pipe()
        done_r, done_w = os.pipe()
        file = os.open(root / 'target', os.O_RDONLY)
        alias = os.open(Path(attached) / 'target', os.O_RDONLY)
        namespace = os.open('/proc/self/ns/mnt', os.O_RDONLY)
        left, right = socket.socketpair()
        child = os.fork()
        if child == 0:
            try:
                os.close(ready_r)
                os.close(done_w)
                assert libc.prctl(4, 0 if restricted == 'dumpable' else 1, 0, 0, 0) == 0
                if restricted == 'identity':
                    os.setuid(1001)
                os.write(ready_w, b'r')
                # EVENT_WAIT: observer completes exact FD queries; launch deadline bounds failure.
                assert os.read(done_r, 1) == b'd'
                os._exit(0)
            except BaseException:
                os._exit(99)
        os.close(ready_w)
        os.close(done_r)
        authority = None
        try:
            # EVENT_WAIT: child publishes its protection/identity before observation.
            assert os.read(ready_r, 1) == b'r'
            # This fixture starts as guest root. Test credential rejection
            # without CAP_SYS_PTRACE, which explicitly authorizes foreign UIDs.
            if restricted == 'identity':
                authority = ptrace_capability(False)
            for fd in (file, alias, namespace, left.fileno()):
                path = f'/proc/{child}/fd/{fd}'
                # Native descriptions retain kernel dumpability checks. An
                # already admitted namespace handle uses its logical provider.
                if restricted == 'identity' or (restricted == 'dumpable' and fd != namespace):
                    try:
                        os.stat(path)
                    except OSError as error:
                        assert error.errno in (errno.EACCES, errno.EPERM), error
                    else:
                        raise AssertionError(('protected target admitted', restricted, path))
                    continue
                expected = os.fstat(fd)
                actual = os.stat(path)
                assert (actual.st_dev, actual.st_ino, actual.st_mode) == (expected.st_dev, expected.st_ino, expected.st_mode)
                link = os.readlink(path)
                expected_link = os.readlink(f'/proc/self/fd/{fd}')
                assert link == expected_link, (path, link, expected_link)
                if restricted == 'none':
                    assert stat.S_ISLNK(os.lstat(path).st_mode)
            print(f'PASS managed proc reads: observer_protected={protected} target={restricted}', flush=True)
        finally:
            if authority is not None:
                ptrace_capability(authority)
            os.write(done_w, b'd')
            pid, status = os.waitpid(child, 0)
            assert pid == child and status == 0, (pid, status)
            for fd in (ready_r, done_w, file, alias, namespace):
                os.close(fd)
            left.close()
            right.close()


def main():
    external_pid, attached, alias = sys.argv[1:]
    with tempfile.TemporaryDirectory(prefix='md-native-reads-') as temporary:
        root = Path(temporary)
        (root / 'target').write_text('namespace data')
        (root / 'link').symlink_to('target')
        for protected in (False, True):
            assert libc.prctl(4, 0 if protected else 1, 0, 0, 0) == 0
            try:
                for base in (root, Path(attached)):
                    assert os.readlink(base / 'link') == 'target'
                    directory = os.open(base, os.O_RDONLY | os.O_DIRECTORY)
                    try:
                        assert os.readlink('link', dir_fd=directory) == 'target'
                        assert os.stat('target', dir_fd=directory).st_size > 0
                        assert stat.S_ISLNK(os.stat('link', dir_fd=directory, follow_symlinks=False).st_mode)
                        assert {'target', 'link'} <= set(os.listdir(directory))
                        buffer = ctypes.create_string_buffer(64)
                        assert libc.readlinkat(directory, b'link', buffer, 3) == 3
                        assert buffer.raw[:3] == b'tar'
                        check_errno(errno.EINVAL, directory, b'link', buffer, 0)
                        check_errno(errno.EFAULT, directory, b'link', 1, 64)
                        check_errno(errno.ENOENT, directory, b'absent', buffer, 64)
                        check_errno(errno.EINVAL, directory, b'target', buffer, 64)
                    finally:
                        os.close(directory)
                fd = os.open(root / 'target', os.O_RDONLY)
                try:
                    assert os.readlink(f'/proc/self/fd/{fd}') == str(root / 'target')
                    assert os.readlink(f'/proc/thread-self/fd/{fd}') == str(root / 'target')
                    assert os.stat(f'/proc/self/fd/{fd}').st_ino == os.fstat(fd).st_ino
                finally:
                    os.close(fd)
                proc = f'/proc/{external_pid}/fd/3'
                assert os.readlink(proc) == str(Path(attached) / 'target'), (proc, os.readlink(proc))
                assert stat.S_ISLNK(os.lstat(proc).st_mode)
                assert os.stat(proc).st_ino == os.stat(Path(attached) / 'target').st_ino
                assert Path('/proc/uptime').read_text().split()
                assert stat.S_ISCHR(os.stat('/dev/null').st_mode)
                assert 'null' in os.listdir('/dev')
                assert 'self' in os.listdir('/proc')
                print(f'PASS native reads: protected={protected} inode/bind/proc/dev links, metadata, directory, errors', flush=True)
            finally:
                assert libc.prctl(4, 1, 0, 0, 0) == 0
        assert libc.mount(os.fsencode(attached), os.fsencode(alias), None, 4096, None) == 0, ctypes.get_errno()
        try:
            for protected in (False, True):
                assert libc.prctl(4, 0 if protected else 1, 0, 0, 0) == 0
                try:
                    managed_process_reads(root, protected, alias)
                finally:
                    assert libc.prctl(4, 1, 0, 0, 0) == 0
        finally:
            assert libc.umount2(os.fsencode(alias), 2) == 0, ctypes.get_errno()


if __name__ == '__main__':
    main()
