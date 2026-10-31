#!/usr/bin/env python3
"""Mounted proc policies through real shell, with unmodified guest syscalls."""
import argparse
import ctypes
from contextlib import ExitStack
import errno
import fcntl
import os
from pathlib import Path
import socket
import stat
import struct
import sys
import tempfile
import traceback


def kernel_arguments(root, expected=True):
    if not expected:
        try:
            os.stat('cmdline', dir_fd=root)
        except FileNotFoundError:
            return
        raise AssertionError('subset=pid exposed kernel command line')
    with ExitStack() as cleanup:
        fd = os.open('cmdline', os.O_RDONLY, dir_fd=root)
        cleanup.callback(os.close, fd)
        info = os.fstat(fd)
        assert stat.S_ISREG(info.st_mode) and stat.S_IMODE(info.st_mode) == 0o444
        assert os.read(fd, 4096) == b'\n' and os.read(fd, 1) == b''
        assert os.pread(fd, 4096, 0) == b'\n'
        assert os.lseek(fd, 0, os.SEEK_SET) == 0 and os.read(fd, 4096) == b'\n'
        pin = os.open('cmdline', os.O_PATH | os.O_NOFOLLOW, dir_fd=root)
        cleanup.callback(os.close, pin)
        assert os.fstat(pin).st_ino == info.st_ino
        assert os.stat('cmdline', dir_fd=root).st_ino == info.st_ino
        directory = os.open('.', os.O_RDONLY | os.O_DIRECTORY, dir_fd=root)
        try:
            with os.scandir(directory) as entries:
                nodes = {e.name: e.inode() for e in entries if e.name in ('cmdline', 'devices')}
            assert nodes['cmdline'] == info.st_ino
            assert nodes['devices'] == os.stat('devices', dir_fd=root).st_ino
        finally:
            os.close(directory)
        try:
            os.open('cmdline/invalid', os.O_RDONLY, dir_fd=root)
        except OSError as error:
            assert error.errno == errno.ENOTDIR, error
        else:
            raise AssertionError('kernel command line became a directory')
        try:
            writable = os.open('cmdline', os.O_WRONLY | os.O_TRUNC, dir_fd=root)
        except OSError as error:
            assert error.errno == errno.EACCES, error
        else:
            os.close(writable)
            raise AssertionError('kernel command line is writable')


def pressure(root, expected):
    directory = os.open('.', os.O_RDONLY | os.O_DIRECTORY, dir_fd=root)
    try:
        assert ('pressure' in os.listdir(directory)) == expected
    finally:
        os.close(directory)
    for path in ('pressure', './pressure', 'pressure/cpu', 'pressure/memory', 'pressure/io'):
        for flags in (os.O_PATH | os.O_NOFOLLOW, os.O_RDONLY):
            try:
                fd = os.open(path, flags, dir_fd=root)
            except OSError as error:
                assert not expected and error.errno == errno.ENOENT, (path, flags, error)
            else:
                try:
                    assert expected, path
                    if flags == os.O_RDONLY and path.startswith('pressure/'):
                        assert b'some avg10=' in os.read(fd, 4096), path
                finally:
                    os.close(fd)
        try:
            os.stat(path, dir_fd=root, follow_symlinks=False)
        except OSError as error:
            assert not expected and error.errno == errno.ENOENT, (path, error)
        else:
            assert expected, path


def pinned_links(root):
    with ExitStack() as cleanup:
        for name in ('self', 'thread-self', 'mounts', 'net', 'device-tree'):
            try:
                info = os.stat(name, dir_fd=root, follow_symlinks=False)
            except FileNotFoundError:
                assert name == 'device-tree'
                continue
            assert stat.S_ISLNK(info.st_mode), name
            link = os.open(name, os.O_PATH | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=root)
            try:
                pinned = os.fstat(link)
                assert (pinned.st_mode, pinned.st_ino) == (info.st_mode, info.st_ino), (name, pinned, info)
                assert os.readlink('', dir_fd=link) == os.readlink(name, dir_fd=root), name
            finally:
                os.close(link)
        target = os.open('/etc/os-release', os.O_RDONLY)
        cleanup.callback(os.close, target)
        directory = os.open('self/fd', os.O_PATH | os.O_DIRECTORY, dir_fd=root)
        cleanup.callback(os.close, directory)
        expected = os.path.realpath('/etc/os-release')
        assert os.readlink(str(target), dir_fd=directory) == expected
        link = os.open(str(target), os.O_PATH | os.O_NOFOLLOW, dir_fd=directory)
        cleanup.callback(os.close, link)
        assert stat.S_ISLNK(os.fstat(link).st_mode)
        assert os.readlink('', dir_fd=link) == expected
        alias = os.dup(link)
        cleanup.callback(os.close, alias)
        assert os.readlink('', dir_fd=alias) == expected


def scoped_directories(root):
    libc = ctypes.CDLL(None, use_errno=True)
    libc.syscall.restype = ctypes.c_long
    for flags in (os.O_PATH | os.O_DIRECTORY | os.O_CLOEXEC,
                  os.O_PATH | os.O_NOFOLLOW | os.O_CLOEXEC):
        for resolve in (0xb, 0x13):
            how = struct.pack('=QQQ', flags, 0, resolve)
            for path in ('thread-self/fd/', 'self/fd/', 'thread-self/fdinfo/', 'self/ns/'):
                fd = libc.syscall(437, root, ctypes.c_char_p(path.encode()),
                                  ctypes.c_char_p(how), len(how))
                assert fd >= 0, ('scoped proc directory', path, resolve, ctypes.get_errno())
                os.close(fd)
    for path, resolve, expected in (('thread-self/fd/0', 0xb, errno.ELOOP),
                                    ('thread-self/root/', 0xb, errno.ELOOP),
                                    ('../', 0xb, errno.EXDEV),
                                    ('thread-self/fd/', 0xf, errno.ELOOP)):
        how = struct.pack('=QQQ', os.O_PATH | os.O_CLOEXEC, 0, resolve)
        fd = libc.syscall(437, root, ctypes.c_char_p(path.encode()),
                          ctypes.c_char_p(how), len(how))
        assert fd == -1 and ctypes.get_errno() == expected, (path, resolve, fd, ctypes.get_errno())


def detached_proc():
    libc = ctypes.CDLL(None, use_errno=True)
    libc.syscall.restype = ctypes.c_long

    def call(nr, *args, error=0):
        values = [ctypes.c_char_p(a.encode()) if isinstance(a, str) else ctypes.c_long(a) for a in args]
        value = libc.syscall(ctypes.c_long(nr), *values)
        actual = ctypes.get_errno() if value < 0 else 0
        assert actual == error, (nr, args, value, actual, error)
        return value

    class OpenHow(ctypes.Structure):
        _fields_ = [('flags', ctypes.c_uint64), ('mode', ctypes.c_uint64),
                    ('resolve', ctypes.c_uint64)]

    for flags in (1 | os.O_CLOEXEC, 1 | os.O_CLOEXEC | 0x8000):
        clone = call(428, -100, '/proc', flags)
        try:
            scoped_directories(clone)
        finally:
            os.close(clone)
    with ExitStack() as cleanup:
        context = call(430, 'proc', 1)
        cleanup.callback(os.close, context)
        call(431, context, 6, 0, 0, 0)
        root = call(432, context, 1, 0)
        cleanup.callback(os.close, root)
        scoped_directories(root)
        kernel_arguments(root)
        pressure(root, os.environ.get('MD_TEST_PSI') == '1')
        pinned_links(root)
        assert os.readlink('/proc/self/fd/' + str(root)) == '/'
        directory = os.open('thread-self/fd/', os.O_PATH | os.O_DIRECTORY, dir_fd=root)
        cleanup.callback(os.close, directory)
        link = os.open(str(directory), os.O_PATH | os.O_NOFOLLOW, dir_fd=directory)
        cleanup.callback(os.close, link)
        described = os.readlink('', dir_fd=link)
        assert described == '/' + str(os.getpid()) + '/fd', described
        assert os.readlink('/proc/self/fd/' + str(directory)) == described
        def mount_id(fd, path=''):
            value = ctypes.create_string_buffer(256)
            call(291, fd, path, 0x100 if path else 0x1000, 0x17ff, ctypes.addressof(value))
            assert struct.unpack_from('I', value)[0] & 0x1000
            return struct.unpack_from('Q', value, 144)[0]
        identity = mount_id(root)
        namespaces = {'user', 'mnt', 'uts', 'ipc', 'pid', 'pid_for_children', 'net', 'cgroup'}
        directory = os.open('self/ns', os.O_RDONLY | os.O_DIRECTORY, dir_fd=root)
        cleanup.callback(os.close, directory)
        assert set(os.listdir(directory)) == namespaces
        for name in namespaces:
            path = 'self/ns/' + name
            info = os.stat(path, dir_fd=root, follow_symlinks=False)
            assert stat.S_ISLNK(info.st_mode), (name, info)
            assert mount_id(root, path) == identity, name
            label = os.readlink(path, dir_fd=root)
            assert label.startswith(('pid' if name == 'pid_for_children' else name) + ':['), label
            how = OpenHow(os.O_PATH | os.O_NOFOLLOW | os.O_CLOEXEC, 0, 0xb)
            pin = call(437, root, path, ctypes.addressof(how), ctypes.sizeof(how))
            try:
                assert stat.S_ISLNK(os.fstat(pin).st_mode), name
                assert os.readlink('/proc/self/fd/' + str(pin)) == label
            finally:
                os.close(pin)
        info_how = OpenHow(os.O_CLOEXEC | os.O_NOFOLLOW | os.O_NOCTTY, 0, 0xb)
        for name in ('setgroups', 'uid_map', 'gid_map', 'status', 'stat', 'limits', 'cgroup'):
            fd = call(437, root, 'self/' + name, ctypes.addressof(info_how), ctypes.sizeof(info_how))
            try:
                info = call(437, root, './thread-self/fdinfo/' + str(fd),
                            ctypes.addressof(info_how), ctypes.sizeof(info_how))
                try:
                    values = dict(line.split(':', 1) for line in os.read(info, 4096).decode().splitlines())
                    assert int(values['mnt_id']) == identity, (name, values)
                finally:
                    os.close(info)
                assert mount_id(fd) == identity, name
                assert os.readlink('/proc/self/fd/' + str(fd)) == '/' + str(os.getpid()) + '/' + name
                alias = os.dup(fd)
                try:
                    assert mount_id(alias) == identity, name
                    assert os.fstat(alias).st_ino == os.fstat(fd).st_ino, name
                    try:
                        os.fchdir(alias)
                    except OSError as error:
                        assert error.errno == errno.ENOTDIR, error
                    else:
                        raise AssertionError('virtual proc leaf became a directory')
                finally:
                    os.close(alias)
            finally:
                os.close(fd)
        for path in ('./thread-self/fdinfo/', 'self/fdinfo/'):
            info = call(437, root, path + str(root), ctypes.addressof(info_how), ctypes.sizeof(info_how))
            try:
                values = dict(line.split(':', 1) for line in os.read(info, 4096).decode().splitlines())
                assert int(values['mnt_id']) == identity, values
            finally:
                os.close(info)
        # NO_XDEV | NO_MAGICLINKS | BENEATH: directories are not magic links.
        how = OpenHow(os.O_PATH | os.O_DIRECTORY | os.O_CLOEXEC, 0, 0xb)
        for path in ['thread-self/fd/', 'self/fd', 'thread-self/fdinfo/', 'self/ns/']:
            fd = call(437, root, path, ctypes.addressof(how), ctypes.sizeof(how))
            assert mount_id(fd) == identity, path
            if '/fd' in path and '/fdinfo' not in path:
                assert mount_id(fd, str(root)) == identity, 'magic link lost its mount'
                link = os.open(str(root), os.O_PATH | os.O_NOFOLLOW, dir_fd=fd)
                assert mount_id(link) == identity
                os.close(link)
                dumpable = libc.prctl(3, 0, 0, 0, 0)
                assert dumpable >= 0
                try:
                    assert libc.prctl(4, 0, 0, 0, 0) == 0
                    assert mount_id(fd, str(root)) == identity, 'protected proc link lost its mount'
                    assert os.stat(str(root), dir_fd=fd, follow_symlinks=False).st_mode & 0o170000 == 0o120000
                    link = os.open(str(root), os.O_PATH | os.O_NOFOLLOW, dir_fd=fd)
                    assert mount_id(link) == identity, 'protected O_PATH link lost its mount'
                    os.close(link)
                    leaf_how = OpenHow(os.O_PATH | os.O_NOFOLLOW | os.O_CLOEXEC, 0, 0xb)
                    link = call(437, fd, str(root), ctypes.addressof(leaf_how), ctypes.sizeof(leaf_how))
                    assert mount_id(link) == identity, 'protected openat2 link lost its mount'
                    os.close(link)
                    reopened = os.open('/proc/self/fd/' + str(fd), os.O_RDONLY | os.O_DIRECTORY)
                    assert mount_id(reopened) == identity, 'protected reopen lost its mount'
                    assert str(root) in os.listdir(reopened)
                    os.close(reopened)
                    assert libc.prctl(3, 0, 0, 0, 0) == 0, 'metadata changed dumpability'
                finally:
                    assert libc.prctl(4, dumpable, 0, 0, 0) == 0
            os.close(fd)
        for path in ['thread-self/fd/0', 'thread-self/root/', 'thread-self/cwd/',
                     'thread-self/ns/mnt']:
            call(437, root, path, ctypes.addressof(how), ctypes.sizeof(how), error=errno.ELOOP)
        call(437, root, '../', ctypes.addressof(how), ctypes.sizeof(how), error=errno.EXDEV)
        how.resolve |= 4  # NO_SYMLINKS rejects even the ordinary self symlink.
        call(437, root, 'thread-self/fd/', ctypes.addressof(how), ctypes.sizeof(how), error=errno.ELOOP)
    print('PASS detached proc: scoped directories and rejected magic-link/parent escapes', flush=True)


def guest():
    root = os.open('/proc', os.O_PATH | os.O_DIRECTORY)
    try:
        scoped_directories(root)
        kernel_arguments(root)
        pinned_links(root)
        libc = ctypes.CDLL(None, use_errno=True)
        how = struct.pack('=QQQ', os.O_CLOEXEC | os.O_NOFOLLOW | os.O_NOCTTY, 0, 0xb)
        path = os.fsencode('self/fdinfo/' + str(root))
        fd = libc.syscall(437, root, ctypes.c_char_p(path), ctypes.c_char_p(how), len(how))
        assert fd >= 0, ('scoped implicit proc fdinfo', ctypes.get_errno())
        try:
            assert b'mnt_id:' in os.read(fd, 4096)
        finally:
            os.close(fd)
    finally:
        os.close(root)
    detached_proc()
    libc = ctypes.CDLL(None, use_errno=True)
    libc.syscall.restype = ctypes.c_long

    def call(nr, *args, error=0):
        values = [ctypes.c_char_p(a.encode()) if isinstance(a, str) else ctypes.c_long(a) for a in args]
        value = libc.syscall(ctypes.c_long(nr), *values)
        actual = ctypes.get_errno() if value < 0 else 0
        assert actual == error, (nr, args, value, actual, error)
        return value

    def denied(fn, error):
        try:
            fn()
        except OSError as failure:
            assert failure.errno == error, failure
        else:
            raise AssertionError(('accepted forbidden operation', error))

    def child(fn):
        pid = os.fork()
        if not pid:
            try:
                fn()
                os._exit(0)
            except BaseException:
                traceback.print_exc()
                os._exit(1)
        assert os.waitpid(pid, 0)[1] == 0

    def user():
        os.setgroups([])
        os.setgid(1000)
        os.setuid(1000)

    def user_arguments():
        user()
        fd = os.open('/proc', os.O_PATH | os.O_DIRECTORY)
        try:
            scoped_directories(fd)
            kernel_arguments(fd)
        finally:
            os.close(fd)

    child(user_arguments)

    def devices(root):
        path = Path(root) / 'devices'
        text = path.read_bytes()
        assert text.startswith(b'Character devices:\n') and b'\nBlock devices:\n' in text
        drivers = {line.split()[1]: int(line.split()[0]) for line in text.splitlines()
                   if len(line.split()) == 2 and line.split()[0].isdigit()}
        assert drivers[b'pts'] == 136 and drivers[b'mem'] == 1
        denied(lambda: os.open(path, os.O_RDWR), errno.EACCES)
        denied(lambda: (path / 'invalid').read_bytes(), errno.ENOTDIR)
        with ExitStack() as cleanup:
            fd = os.open(path, os.O_RDONLY)
            cleanup.callback(os.close, fd)
            alias = os.dup(fd)
            cleanup.callback(os.close, alias)
            assert os.read(fd, 9) + os.read(alias, 4096) == text
            assert os.pread(fd, 4096, 0) == text and os.read(fd, 1) == b''
            assert os.lseek(alias, 0, os.SEEK_SET) == 0
            assert os.read(fd, 4096) == text
            info = os.fstat(fd)
            assert stat.S_ISREG(info.st_mode) and stat.S_IMODE(info.st_mode) == 0o444
            named = path.stat()
            assert (info.st_dev, info.st_ino) == (named.st_dev, named.st_ino)
            pin = os.open(path, os.O_PATH)
            cleanup.callback(os.close, pin)
            assert os.fstat(pin).st_ino == info.st_ino

    devices('/proc')

    def noatime():
        for path in ('/proc/self', '/proc/self/stat', '/proc/self/status',
                     '/proc/self/uid_map', '/proc/self/cgroup'):
            fd = os.open(path, os.O_RDONLY | os.O_NOATIME)
            try:
                assert fcntl.fcntl(fd, fcntl.F_GETFL) & os.O_NOATIME, path
                reopened = os.open('/proc/self/fd/' + str(fd), os.O_RDONLY | os.O_NOATIME)
                os.close(reopened)
                fcntl.fcntl(fd, fcntl.F_SETFL, 0)
                assert not fcntl.fcntl(fd, fcntl.F_GETFL) & os.O_NOATIME
                fcntl.fcntl(fd, fcntl.F_SETFL, os.O_NOATIME)
                assert fcntl.fcntl(fd, fcntl.F_GETFL) & os.O_NOATIME
                if path == '/proc/self':
                    relative = os.open('stat', os.O_RDONLY | os.O_NOATIME, dir_fd=fd)
                    assert os.read(relative, 4096)
                    os.close(relative)
                else:
                    assert os.read(fd, 4096)
            finally:
                os.close(fd)

    noatime()

    call(97, 0x20000)  # Own mount namespace; no host proc changes.
    with tempfile.TemporaryDirectory(prefix='md-proc-policy-') as tmp, ExitStack() as cleanup:
        root = Path(tmp)
        root.chmod(0o755)
        mounts = {}
        for key, options in [('off', 'hidepid=off'), ('one', 'hidepid=1'),
                             ('two', 'hidepid=invisible'), ('four', 'hidepid=ptraceable'),
                             ('group', 'hidepid=2,gid=1000'), ('strict', 'hidepid=4,gid=1000'),
                             ('subset', 'subset=pid')]:
            path = root / key
            path.mkdir()
            cleanup.callback(path.rmdir)
            call(40, 'none', str(path), 'proc', 0, options)
            cleanup.callback(call, 39, str(path), 2)
            mounts[key] = path
            fd = os.open(path, os.O_PATH | os.O_DIRECTORY)
            try:
                kernel_arguments(fd, key != 'subset')
                pressure(fd, key != 'subset' and os.environ.get('MD_TEST_PSI') == '1')
            finally:
                os.close(fd)
            if key == 'subset':
                denied(lambda: (path / 'devices').read_bytes(), errno.ENOENT)
            else:
                devices(path)
        # File covers must belong to this proc instance, including native
        # sysctl leaves that are not implemented by Shroot's control provider.
        source = root / 'replacement'
        source.write_bytes(b'private-proc-cover\n')
        cleanup.callback(source.unlink)
        for suffix in ('sys/kernel/random/boot_id', 'sys/kernel/hostname', 'cmdline'):
            target = mounts['off'] / suffix
            original = target.read_bytes()
            other = (mounts['two'] / suffix).read_bytes()
            for modern in (False, True):
                if modern:
                    tree = call(428, -100, str(source), 1 | os.O_CLOEXEC)
                    try:
                        call(429, tree, '', -100, str(target), 4)
                    finally:
                        os.close(tree)
                else:
                    call(40, str(source), str(target), 0, 4096, 0)
                try:
                    assert target.read_bytes() == source.read_bytes(), (suffix, modern)
                    assert (mounts['two'] / suffix).read_bytes() == other
                    assert os.stat(target).st_ino == source.stat().st_ino
                finally:
                    call(39, str(target), 2)
                assert target.read_bytes() == original
        fifo_dir = root / 'fifo-dir'
        fifo_dir.mkdir()
        cleanup.callback(fifo_dir.rmdir)
        call(40, 'tmpfs', str(fifo_dir), 'tmpfs', 0, 'mode=0700')
        cleanup.callback(call, 39, str(fifo_dir), 2)
        fifo = fifo_dir / 'log-fifo'
        os.mkfifo(fifo)
        call(40, str(fifo_dir), str(fifo_dir), 0, 4096, 0)
        cleanup.callback(call, 39, str(fifo_dir), 2)
        call(40, 'none', str(fifo_dir), 0, 4096 | 32 | 1, 0)
        for name in ('kmsg', 'sysrq-trigger', 'kcore', 'keys', 'kallsyms'):
            target = mounts['off'] / name
            before = target.stat()
            assert stat.S_ISREG(before.st_mode) and stat.S_IMODE(before.st_mode) == 0
            assert name in os.listdir(mounts['off'])
            denied(target.read_bytes, errno.EACCES)
            denied(lambda: os.chmod(target, 0o600), errno.EROFS)
            assert not os.access(target, os.R_OK) and not os.access(target, os.W_OK)
            pinned = os.open(target, os.O_PATH | os.O_NOFOLLOW)
            tree = call(428, -100, str(fifo), 1 | os.O_CLOEXEC)
            try:
                denied(lambda: os.open('/proc/self/fd/' + str(pinned), os.O_RDONLY), errno.EACCES)
                call(429, tree, '', pinned, '', 4 | 0x40)
                assert stat.S_ISFIFO(target.stat().st_mode)
                assert name in os.listdir(mounts['off'])
                denied((mounts['two'] / name).read_bytes, errno.EACCES)
                assert stat.S_ISREG(os.fstat(pinned).st_mode)
                fd = os.open(target, os.O_RDWR | os.O_NONBLOCK)
                try:
                    assert os.access(target, os.W_OK)
                    assert os.write(fd, b'guest-log') == 9
                    assert os.read(fd, 9) == b'guest-log'
                finally:
                    os.close(fd)
            finally:
                os.close(tree)
                os.close(pinned)
                call(39, str(target), 2)
            assert target.stat().st_ino == before.st_ino
            denied(target.read_bytes, errno.EACCES)
        asound = mounts['off'] / 'asound'
        assert stat.S_ISDIR(asound.stat().st_mode) and stat.S_IMODE(asound.stat().st_mode) == 0
        denied(lambda: os.listdir(asound), errno.EACCES)
        denied(lambda: (asound / 'cards').read_bytes(), errno.EACCES)
        call(40, str(fifo), str(mounts['subset'] / 'kmsg'), 0, 4096, 0, error=errno.ENOENT)
        invalid = root / 'invalid'
        invalid.mkdir()
        cleanup.callback(invalid.rmdir)
        for options in ['hidepid=3', 'hidepid=-1', 'gid=-1', 'gid=4294967295', 'subset=bad', 'unknown=1']:
            call(40, 'none', str(invalid), 'proc', 0, options, error=errno.EINVAL)
        context = call(430, 'proc', 1)
        for key in ('adefinitelynotexistingmountoption', 'ro', 'hidepid', 'source'):
            call(431, context, 5, key, 0, context, error=errno.EINVAL)
        call(431, context, 5, 'unknown', 0, -1, error=errno.EBADF)
        missing_fd = os.dup(context)
        os.close(missing_fd)
        call(431, context, 5, 'unknown', 0, missing_fd, error=errno.EBADF)
        call(431, context, 5, 'unknown', 'invalid', context, error=errno.EINVAL)
        call(431, context, 1, 'hidepid', '2', 0)
        call(431, context, 1, 'subset', 'pid', 0)
        call(431, context, 6, 0, 0, 0)
        detached = call(432, context, 1, 0)
        os.close(context)
        call(429, detached, '', -100, str(invalid), 4)
        cleanup.callback(call, 39, str(invalid), 2)
        os.close(detached)
        mounts['api'] = invalid
        parent = str(os.getpid())
        held = os.open(mounts['two'] / parent, os.O_RDONLY | os.O_DIRECTORY)
        inherited = os.open(mounts['off'] / parent / 'stat', os.O_RDONLY)

        def restricted():
            user()
            noatime()
            devices('/proc')
            devices(mounts['off'])
            for suffix in ('', '/stat', '/status', '/uid_map', '/cgroup'):
                path = str(mounts['off'] / parent) + suffix
                denied(lambda: os.open(path, os.O_RDONLY | os.O_NOATIME), errno.EPERM)
            denied(lambda: fcntl.fcntl(inherited, fcntl.F_SETFL, os.O_NOATIME), errno.EPERM)
            assert not fcntl.fcntl(inherited, fcntl.F_GETFL) & os.O_NOATIME
            for key in ['off', 'one', 'two', 'four', 'group', 'strict', 'api']:
                path = mounts[key]
                names = os.listdir(path)
                assert str(os.getpid()) in names, (key, names)
                assert (path / 'self/status').read_text()
                hidden = key in ['two', 'four', 'strict', 'api']
                assert (parent in names) != hidden, (key, names, parent)
                target = path / parent
                if hidden:
                    denied(lambda: os.stat(target), errno.ENOENT)
                    denied(lambda: (target / 'status').read_text(), errno.ENOENT)
                elif key == 'one':
                    os.stat(target)
                    denied(lambda: (target / 'status').read_text(), errno.EPERM)
                else:
                    assert (target / 'status').read_text()
            denied(lambda: os.listdir(held), errno.ENOENT)
            denied(lambda: os.open('status', os.O_RDONLY, dir_fd=held), errno.ENOENT)

        child(restricted)
        os.close(held)
        os.close(inherited)
        def group_access():
            os.setgroups([1000])
            os.setgid(1001)
            os.setuid(1001)
            assert (mounts['group'] / parent / 'status').read_text()
            denied(lambda: os.stat(mounts['strict'] / parent), errno.ENOENT)
        child(group_access)
        def default_group():
            os.setgroups([])
            os.setuid(1001)
            assert (mounts['two'] / parent / 'status').read_text()
            denied(lambda: os.stat(mounts['four'] / parent), errno.ENOENT)
        child(default_group)
        call(40, 0, str(mounts['two']), 0, 32, 'hidepid=off', error=errno.EOPNOTSUPP)
        subset = mounts['subset']
        assert set(os.listdir(subset)) <= {'self', 'thread-self'} | {x for x in os.listdir(subset) if x.isdigit()}
        for name in ['sys', 'meminfo', 'uptime', 'net', 'cgroups']:
            denied(lambda: os.stat(subset / name), errno.ENOENT)
        assert (subset / 'self/status').read_text()
        table = Path('/proc/self/mountinfo').read_text()
        assert 'hidepid=2' in table and 'subset=pid' in table and 'gid=1000' in table

        left, right = socket.socketpair()
        target = os.fork()
        if not target:
            left.close()
            user()
            right.send(b'R')
            # EVENT_WAIT: the parent's commands delimit credential and
            # dumpability observations; socket timeout fails this fixture.
            right.settimeout(15)
            assert right.recv(1) == b'D'
            call(167, 4, 0)  # PR_SET_DUMPABLE
            right.send(b'D')
            assert right.recv(1) == b'Q'
            os._exit(0)
        right.close()
        left.settimeout(15)
        assert left.recv(1) == b'R'

        def peer(hidden=False):
            user()
            path = mounts['four'] / str(target)
            if hidden:
                denied(lambda: os.stat(path), errno.ENOENT)
            else:
                assert (path / 'status').read_text()

        child(peer)
        def filesystem_identity():
            os.setgroups([])
            os.setgid(1000)
            os.setresuid(1000, 1000, 1001)
            assert call(151, 1001) == 1000
            denied(lambda: os.stat(mounts['four'] / str(target)), errno.ENOENT)
        child(filesystem_identity)
        left.send(b'D')
        assert left.recv(1) == b'D'
        child(lambda: peer(True))
        left.send(b'Q')
        # EVENT_WAIT: retain the child's zombie for peer proc observation;
        # the outer launch deadline bounds missing exit notification.
        os.waitid(os.P_PID, target, os.WEXITED | os.WNOWAIT)
        child(peer)
        assert os.waitpid(target, 0)[1] == 0
        left.close()

        alias = root / 'alias'
        alias.mkdir()
        cleanup.callback(alias.rmdir)
        call(40, str(mounts['two']), str(alias), 0, 4096, 0)
        cleanup.callback(call, 39, str(alias), 2)
        def copied():
            call(97, 0x20000)
            fd = os.open(alias, os.O_PATH | os.O_DIRECTORY)
            try:
                kernel_arguments(fd)
                pressure(fd, os.environ.get('MD_TEST_PSI') == '1')
            finally:
                os.close(fd)
            user()
            denied(lambda: os.stat(alias / parent), errno.ENOENT)
        child(copied)
    print('PASS proc: kernel arguments, O_NOATIME ownership/reopen/fcntl, hidepid modes/gid, retained FD, credentials/dumpability, subset, fsconfig, bind and namespace copy', flush=True)


def namespace_guest():
    # Guest root owns the parent user namespace. Publish its maps directly;
    # unshare's newuidmap policy depends on unrelated distribution subuid files.
    parent, child = socket.socketpair()
    pid = os.fork()
    if not pid:
        parent.close()
        libc = ctypes.CDLL(None, use_errno=True)
        assert libc.unshare(0x10000000 | 0x20000 | 0x20000000) == 0, ctypes.get_errno()
        child.sendall(b'R')
        # EVENT_WAIT: parent publishes both maps; launch deadline bounds loss.
        assert child.recv(1) == b'G'
        child.close()
        init = os.fork()
        if not init:
            guest()
            sys.exit(0)
        assert os.waitpid(init, 0)[1] == 0
        sys.exit(0)
    child.close()
    try:
        assert parent.recv(1) == b'R'
        Path('/proc/%d/uid_map' % pid).write_text('0 0 65536\n')
        Path('/proc/%d/gid_map' % pid).write_text('0 0 65536\n')
        parent.sendall(b'G')
    finally:
        parent.close()
        assert os.waitpid(pid, 0)[1] == 0


def main():
    from test_oci_services import Suite, add_machine_entry_argument
    parser = argparse.ArgumentParser()
    parser.add_argument('build', type=Path)
    parser.add_argument('store')
    add_machine_entry_argument(parser)
    args = parser.parse_args()
    suite = Suite(args.build, args.build, machine_entry=args.machine_entry)
    target = '/tmp/md-proc-policy-' + suite.report['id']
    prefix = [suite.runner, '--store', args.store, '--user', '0:0', *suite.entry_options]
    try:
        # Observe PSI under the same real executor, outside the guest. An
        # existing directory alone is not evidence that its samples are usable.
        host = suite.command(['/system/bin/sh', '-c',
                              'available=1; for name in cpu memory io; do '
                              'head -c 1 /proc/pressure/$name >/dev/null 2>&1 || available=0; '
                              'done; echo PSI=$available'])
        psi = '1' if 'PSI=1' in host else '0'
        suite.transport.upload(suite.client, Path(__file__), suite.base + '/test.py')
        suite.command(prefix + ['--', '/bin/mkdir', target])
        suite.command(prefix + ['--bind-ro', suite.base, target, '--deadline-seconds', '90', '--',
                               '/usr/bin/env', 'MD_TEST_PSI=' + psi,
                               '/usr/bin/python3', target + '/test.py', '--guest-namespace'], timeout=110)
        suite.report['passed'] = True
    finally:
        try:
            suite.command(prefix + ['--', '/bin/rmdir', target])
        finally:
            suite.finish()


if __name__ == '__main__':
    if sys.argv[1:] == ['--guest']:
        guest()
    elif sys.argv[1:] == ['--guest-namespace']:
        namespace_guest()
    elif sys.argv[1:] == ['--guest-detached']:
        detached_proc()
    else:
        main()
