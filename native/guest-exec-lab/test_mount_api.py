#!/usr/bin/env python3
"""Descriptor mount API and stock util-linux consumers, through actual shell."""
import argparse
import array
import ctypes
import errno
import fcntl
import os
import select
from pathlib import Path
import socket
import struct
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor


def guest_root():
    libc = ctypes.CDLL(None, use_errno=True)
    libc.syscall.restype = ctypes.c_long

    def call(number, *args, error=0):
        values = [ctypes.c_char_p(value.encode()) if isinstance(value, str)
                  else ctypes.c_long(value) for value in args]
        result = libc.syscall(ctypes.c_long(number), *values)
        assert (ctypes.get_errno() if result < 0 else 0) == error, (number, args, result, ctypes.get_errno())
        return result

    context = call(433, -100, '/', 1)
    call(431, context, 0, 'rw', 0, 0)
    call(431, context, 7, 0, 0, 0)
    assert not os.statvfs('/').f_flag & os.ST_RDONLY
    call(431, context, 0, 'mode', 0, 0, error=errno.EINVAL)
    trace = subprocess.run(['strace', '-e', 'trace=fspick,fsconfig,mount_setattr',
                            'mount', '-o', 'remount,rw', '/'], capture_output=True, text=True)
    print(trace.stderr, end='', flush=True)
    assert trace.returncode == 0, trace
    assert 'fspick(' in trace.stderr and 'FSCONFIG_CMD_RECONFIGURE' in trace.stderr, trace.stderr
    import mmap
    with tempfile.TemporaryDirectory(prefix='/md-readonly-') as directory:
        path = Path(directory) / 'data'
        writable = os.open(path, os.O_CREAT | os.O_RDWR, 0o600)
        os.ftruncate(writable, mmap.PAGESIZE)
        mapping = mmap.mmap(writable, mmap.PAGESIZE, flags=mmap.MAP_SHARED)
        try:
            call(431, context, 0, 'ro', 0, 0)
            call(431, context, 7, 0, 0, 0)
            assert os.statvfs('/').f_flag & os.ST_RDONLY
            for action in (lambda: os.open(path, os.O_WRONLY),
                           lambda: path.unlink(), lambda: (path.parent / 'blocked').mkdir()):
                try:
                    action()
                    raise AssertionError('readonly superblock granted new write authority')
                except OSError as error:
                    assert error.errno == errno.EROFS, error
            assert os.pwrite(writable, b'fd', 0) == 2
            mapping[2:6] = b'mmap'
            assert path.read_bytes()[:6] == b'fdmmap'
        finally:
            call(431, context, 0, 'rw', 0, 0)
            call(431, context, 7, 0, 0, 0)
            mapping.close()
            os.close(writable)
        path.write_bytes(b'write-restored')
    # Superblock confirmation must not clear a readonly mount placement.
    call(40, 0, '/', 0, 4096 | 32 | 1, 0)
    call(431, context, 0, 'rw', 0, 0)
    call(431, context, 7, 0, 0, 0)
    assert os.statvfs('/').f_flag & os.ST_RDONLY
    call(40, 0, '/', 0, 4096 | 32, 0)
    assert not os.statvfs('/').f_flag & os.ST_RDONLY
    child = os.fork()
    if child == 0:
        try:
            call(97, 0x10000000 | 0x20000)
            Path('/proc/self/uid_map').write_text('0 0 1\n')
            Path('/proc/self/setgroups').write_text('deny\n')
            Path('/proc/self/gid_map').write_text('0 0 1\n')
            selected = call(433, -100, '/', 1)
            for descriptor in (context, selected):
                call(431, descriptor, 0, 'rw', 0, 0)
                call(431, descriptor, 7, 0, 0, 0, error=errno.EPERM)
            os._exit(0)
        except BaseException:
            import traceback
            traceback.print_exc()
            os._exit(1)
    # EVENT_WAIT: child proves inherited/fresh context admission; suite bounds hangs.
    assert os.waitpid(child, 0)[1] == 0
    os.close(context)
    print('PASS fspick persistent root: stock remount, mount policy and namespace authority', flush=True)


def guest():
    libc = ctypes.CDLL(None, use_errno=True)
    libc.syscall.restype = ctypes.c_long

    def call(nr, *args, error=None):
        converted = [ctypes.c_char_p(arg.encode()) if isinstance(arg, str)
                     else ctypes.c_long(arg) for arg in args]
        result = libc.syscall(ctypes.c_long(nr), *converted)
        code = ctypes.get_errno() if result < 0 else 0
        assert code == (error or 0), (nr, args, result, code, error)
        return result

    def config(fd, key, value=None):
        return call(431, fd, 0 if value is None else 1, key, 0 if value is None else value, 0)

    # Exec closes the construction FD, but the running image must keep its
    # detached mount alive across later mount collection, including in a peer.
    image_check = '''
import ctypes, os, sys
libc = ctypes.CDLL(None, use_errno=True)
libc.syscall.restype = ctypes.c_long
for _ in range(8):
    fd = libc.syscall(ctypes.c_long(428), ctypes.c_long(-100),
                      ctypes.c_char_p(b'/usr/bin/true'), ctypes.c_long(1 | os.O_CLOEXEC))
    assert fd >= 0, ctypes.get_errno()
    os.close(fd)
with open('/proc/self/exe', 'rb') as image:
    assert image.read(4) == b'\\x7fELF'
if sys.argv[-1] == '0':
    os.execve('/proc/self/exe', [sys.executable, '-c', sys.argv[1], sys.argv[1], '1'], os.environ)
'''
    children = []
    for _ in range(4):
        child = os.fork()
        if not child:
            try:
                image = call(428, -100, '/proc/self/exe', 1 | os.O_CLOEXEC)
                os.execve(image, [sys.executable, '-c', image_check, image_check, '0'], os.environ)
            except BaseException:
                import traceback
                traceback.print_exc()
                os._exit(1)
        children.append(child)
    # EVENT_WAIT: every re-executed child must finish; suite deadline bounds a hang.
    statuses = [os.waitpid(child, 0)[1] for child in children]
    assert statuses == [0] * len(children), statuses
    print('PASS detached executable mount survives CLOEXEC and concurrent collection', flush=True)

    def create(kind='tmpfs', **options):
        fd = call(430, kind, 1)
        for key, value in options.items():
            config(fd, key, value)
        call(431, fd, 6, 0, 0, 0)
        return fd

    def attributes(path='', fd=-100, flags=0, set_bits=0, clear_bits=0, propagation=0,
                   userns=0, tail=None, error=None):
        words = [set_bits, clear_bits, propagation, userns]
        if tail is not None:
            words.append(tail)
        value = (ctypes.c_uint64 * len(words))(*words)
        return call(442, fd, str(path), flags, ctypes.addressof(value), ctypes.sizeof(value), error=error)

    def write_denied(path):
        try:
            Path(path).write_text('not allowed')
            raise AssertionError('readonly mount accepted a write')
        except OSError as error:
            assert error.errno == errno.EROFS, error

    def mount_root(path='', fd=-100, expected=True):
        value = ctypes.create_string_buffer(256)
        call(291, fd, str(path), 0x1000 if not path else 0, 0x17ff, ctypes.addressof(value))
        attributes = struct.unpack_from('Q', value, 8)[0]
        mask = struct.unpack_from('Q', value, 56)[0]
        assert mask & 0x2000, 'missing STATX_ATTR_MOUNT_ROOT support'
        assert bool(attributes & 0x2000) == expected, (path, fd, attributes, expected)

    def mount_identity(path='', fd=-100):
        value = ctypes.create_string_buffer(256)
        call(291, fd, str(path), 0x1000 if not path else 0, 0x17ff, ctypes.addressof(value))
        assert struct.unpack_from('I', value)[0] & 0x1000, 'missing STATX_MNT_ID'
        return struct.unpack_from('Q', value, 144)[0]

    def filesystem_ioctls(root):
        commands = (0x80086601, 0x40086602, 0x80087601, 0x40087602,
                    0x801c581f, 0x401c5820)
        value = ctypes.create_string_buffer(32)

        def check(fd, error=errno.ENOTTY):
            for command in commands:
                call(29, fd, command, ctypes.addressof(value), error=error)

        path = root / 'ioctl-file'
        path.touch()
        regular = os.open(path, os.O_RDWR)
        directory = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
        check(regular)
        check(directory)
        duplicate = os.dup(regular)
        check(duplicate)
        os.close(duplicate)
        invalid = os.open(path, os.O_PATH)
        check(invalid, errno.EBADF)
        os.close(invalid)
        check(invalid, errno.EBADF)
        left, right = socket.socketpair()
        left.sendmsg([b'x'], [(socket.SOL_SOCKET, socket.SCM_RIGHTS, array.array('i', [regular]))])
        _, ancillary, _, _ = right.recvmsg(1, socket.CMSG_SPACE(4))
        received = array.array('i')
        received.frombytes(ancillary[0][2])
        check(received[0])
        os.close(received[0])
        left.close()
        right.close()
        alias = root / 'ioctl-alias'
        alias.touch()
        call(40, str(path), str(alias), 0, 4096, 0)
        with open(alias, 'rb') as stream:
            check(stream.fileno())
        call(39, str(alias), 2)
        alias.unlink()
        context = create()
        detached = call(432, context, 1, 0)
        temporary = os.open('.', os.O_RDONLY | os.O_DIRECTORY, dir_fd=detached)
        check(temporary)
        os.close(temporary)
        os.close(detached)
        os.close(context)
        child = os.fork()
        if child == 0:
            try:
                class Filter(ctypes.Structure):
                    _fields_ = [('code', ctypes.c_ushort), ('jt', ctypes.c_ubyte),
                                ('jf', ctypes.c_ubyte), ('k', ctypes.c_uint)]

                class Program(ctypes.Structure):
                    _fields_ = [('length', ctypes.c_ushort), ('filter', ctypes.POINTER(Filter))]

                instruction = Filter(6, 0, 0, 0x7fff0000)  # SECCOMP_RET_ALLOW
                program = Program(1, ctypes.pointer(instruction))
                call(167, 38, 1, 0, 0, 0)  # PR_SET_NO_NEW_PRIVS
                call(277, 1, 0, ctypes.addressof(program))
                check(regular)
                check(directory)
                os._exit(0)
            except BaseException:
                import traceback
                traceback.print_exc()
                os._exit(1)
        # EVENT_WAIT: inherited/sandboxed FD tests; outer deadline fails a hang.
        assert os.waitpid(child, 0)[1] == 0
        os.close(directory)
        os.close(regular)
        path.unlink()
        # A native attachment keeps its readonly policy; unrelated native
        # ioctls still execute against the original open description.
        with open(__file__, 'rb') as stream:
            call(29, stream.fileno(), commands[1], ctypes.addressof(value), error=errno.EROFS)
        read, write = os.pipe()
        os.write(write, b'abc')
        call(29, read, 0x541b, ctypes.addressof(value))  # FIONREAD
        assert struct.unpack_from('i', value)[0] == 3
        os.close(read)
        os.close(write)
        print('PASS filesystem ioctl provider ownership, transfer, sandbox and native IO', flush=True)

    def nosymfollow(root):
        nosym = 0x200000  # MOUNT_ATTR_NOSYMFOLLOW; statfs uses ST_NOSYMFOLLOW (0x2000).

        def denied(action):
            try:
                result = action()
                if isinstance(result, int):
                    os.close(result)
                raise AssertionError('nosymfollow accepted a symlink traversal')
            except OSError as error:
                assert error.errno == errno.ELOOP, error

        context = create()
        detached = call(432, context, 1, nosym)
        os.close(context)
        data = os.open('value', os.O_CREAT | os.O_RDWR, 0o600, dir_fd=detached)
        os.write(data, b'value')
        os.close(data)
        os.symlink('value', 'link', dir_fd=detached)
        denied(lambda: os.open('link', os.O_RDONLY, dir_fd=detached))
        assert os.readlink('link', dir_fd=detached) == 'value'
        link = os.open('link', os.O_PATH | os.O_NOFOLLOW, dir_fd=detached)
        assert os.fstat(link).st_mode & 0o170000 == 0o120000
        os.close(link)
        source, alias = root / 'nosym', root / 'sym'
        source.mkdir()
        alias.mkdir()
        call(429, detached, '', -100, str(source), 4)
        call(40, str(source), str(alias), 0, 4096, 0)
        attributes(alias, clear_bits=nosym)
        assert (alias / 'link').read_text() == 'value'
        denied(lambda: (source / 'link').read_text())
        denied(lambda: (source / 'link').stat())
        assert (source / 'link').lstat().st_mode & 0o170000 == 0o120000
        (source / 'dir').mkdir()
        (source / 'dirlink').symlink_to('dir')
        denied(lambda: os.open(source / 'dirlink' / 'new', os.O_CREAT | os.O_WRONLY, 0o600))
        denied(lambda: os.stat(str(source / 'dirlink') + '/'))
        assert os.statvfs(source).f_flag & 0x2000
        assert not os.statvfs(alias).f_flag & 0x2000
        entries = Path('/proc/self/mountinfo').read_text().splitlines()
        entry = next(line for line in entries if line.split()[4] == str(source))
        assert 'nosymfollow' in entry.split()[5].split(','), entry
        child = os.fork()
        if child == 0:
            try:
                call(97, 0x10000000 | 0x20000)
                Path('/proc/self/uid_map').write_text('0 0 1\n')
                Path('/proc/self/setgroups').write_text('deny\n')
                Path('/proc/self/gid_map').write_text('0 0 1\n')
                denied(lambda: (source / 'link').read_text())
                # Linux does not lock NOSYMFOLLOW across user namespaces.
                attributes(source, clear_bits=nosym)
                assert (source / 'link').read_text() == 'value'
                os._exit(0)
            except BaseException:
                import traceback
                traceback.print_exc()
                os._exit(1)
        # EVENT_WAIT: inherited mount-policy check; outer deadline fails a hang.
        assert os.waitpid(child, 0)[1] == 0
        denied(lambda: (source / 'link').read_text())
        call(40, 0, str(source), 0, 4096 | 32, 0)  # Legacy bind remount clears NOSYMFOLLOW.
        reopened = os.open('link', os.O_RDONLY, dir_fd=detached)
        os.close(reopened)
        call(40, 0, str(source), 0, 4096 | 32 | 256, 0)
        denied(lambda: os.open('link', os.O_RDONLY, dir_fd=detached))
        call(39, str(source), 2)
        denied(lambda: os.open('link', os.O_RDONLY, dir_fd=detached))
        os.close(detached)
        call(39, str(alias), 2)
        source.rmdir()
        alias.rmdir()

        # Native attachments obey the same mount-local policy.
        native = Path(__file__).with_name('linked.py')
        assert native.read_bytes() == Path(__file__).read_bytes()
        attributes(native.parent, set_bits=nosym)
        denied(native.read_bytes)
        assert native.lstat().st_mode & 0o170000 == 0o120000
        attributes(native.parent, clear_bits=nosym)

        # Typed proc delegation must not skip mount or openat2 link policy.
        proc = root / 'nosym-proc'
        proc.mkdir()
        call(40, 'none', str(proc), 'proc', 256, 0)
        denied(lambda: os.stat(proc / 'self'))
        denied(lambda: os.stat(proc / 'thread-self' / 'status'))
        assert os.readlink(proc / 'self') == str(os.getpid())
        task = proc / str(os.getpid())
        assert (task / 'status').read_text()
        with open(__file__, 'rb') as stream:
            for path in (task / 'exe', task / 'root', task / 'cwd', task / 'ns' / 'mnt',
                         task / 'fd' / str(stream.fileno())):
                denied(lambda: os.open(path, os.O_RDONLY))
                assert path.lstat().st_mode & 0o170000 == 0o120000, path
                assert os.readlink(path), path
        call(39, str(proc), 2)
        proc.rmdir()
        print('PASS nosymfollow: detached/native/proc, alias, remount, retained FD and namespace locks', flush=True)

    def root_operands(root):
        nested = root / 'remount-child'
        nested.mkdir()
        call(40, 'none', str(nested), 'tmpfs', 0, 0)
        descriptor = os.open('/', os.O_PATH | os.O_DIRECTORY)
        reference = '/proc/self/fd/' + str(descriptor)
        for path in ('/', reference):
            call(40, 0, path, 0, (1 << 19) | 16384, 0)  # MS_SLAVE | MS_REC
            call(40, 0, path, 0, 4096 | 32 | 2 | 16384, 0)  # REC is ignored on legacy remount.
            assert os.statvfs('/').f_flag & os.ST_NOSUID
            assert not os.statvfs(nested).f_flag & os.ST_NOSUID
            call(40, 0, path, 0, 4096 | 32, 0)
        ordinary = os.open(root, os.O_PATH | os.O_DIRECTORY)
        call(40, 0, '/proc/self/fd/' + str(ordinary), 0, 1 << 19, 0, error=errno.EINVAL)
        os.close(ordinary)
        os.close(descriptor)
        call(39, str(nested), 0)
        nested.rmdir()
        child = os.fork()
        if child == 0:
            try:
                os.chroot(root)
                os.chdir('/')
                # A process root is not necessarily the root of its mount.
                call(40, 0, '/', 0, 1 << 19, 0, error=errno.EINVAL)
                call(40, 0, '/', 0, 4096 | 32 | 2, 0, error=errno.EINVAL)
                os._exit(0)
            except BaseException:
                import traceback
                traceback.print_exc()
                os._exit(1)
        # EVENT_WAIT: isolated chroot checks; outer deadline fails a hang.
        assert os.waitpid(child, 0)[1] == 0
        print('PASS mount root identity: pathname, proc FD, remount and chroot rejection', flush=True)

    def removed_mountpoint(root, first):
        target = root / 'retired'
        target.mkdir()
        call(40, 'tmpfs', str(target), 'tmpfs', 0, 0)
        (target / 'retained').write_text('original')
        (target / 'nested').mkdir()
        call(40, 'tmpfs', str(target / 'nested'), 'tmpfs', 0, 0)
        (target / 'nested' / 'retained').write_text('child')
        left, right = socket.socketpair()
        child = os.fork()
        if child == 0:
            left.close()
            try:
                call(97, 0x00020000)
                retained = os.open(target, os.O_PATH | os.O_DIRECTORY)
                nested = os.open(target / 'nested', os.O_PATH | os.O_DIRECTORY)
                right.sendall(b'R')
                # EVENT_WAIT: parent removes the covered directory; launch deadline bounds failure.
                assert right.recv(1) == b'D'
                if first == 'clone':
                    clone = call(428, -100, str(root), 1 | os.O_CLOEXEC | 0x8000)
                    os.close(clone)
                elif first == 'copy':
                    call(97, 0x00020000)
                mounts = Path('/proc/self/mountinfo').read_text()
                assert not any(line.split()[4] == str(target)
                               or line.split()[4].startswith(str(target) + '/')
                               for line in mounts.splitlines())
                fd = os.open('retained', os.O_RDONLY, dir_fd=retained)
                assert os.read(fd, 16) == b'original'
                os.close(fd)
                fd = os.open('retained', os.O_RDONLY, dir_fd=nested)
                assert os.read(fd, 16) == b'child'
                os.close(fd)
                clone = call(428, -100, str(root), 1 | os.O_CLOEXEC | 0x8000)
                os.close(clone)
                assert not (target / 'retained').exists(), 'recreated name resurrected a retired mount'
                os.close(retained)
                os.close(nested)
                os._exit(0)
            except BaseException:
                import traceback
                traceback.print_exc()
                os._exit(1)
        right.close()
        try:
            # EVENT_WAIT: child owns a private mount view and retained root.
            assert left.recv(1) == b'R'
            call(39, str(target), 2)
            target.rmdir()
            target.mkdir()
            left.sendall(b'D')
            assert os.waitpid(child, 0)[1] == 0
        finally:
            left.close()
            target.rmdir()
        print('PASS removed mountpoint: ' + first + ', subtree and retained origins', flush=True)

    # The launch enters a child user/mount namespace but retains its parent's
    # cgroup namespace. fsopen must not acquire the authority of a later unshare.
    old_context = call(430, 'cgroup2', 1)
    call(97, 0x02000000)  # AArch64 unshare(CLONE_NEWCGROUP).
    call(431, old_context, 6, 0, 0, 0, error=errno.EPERM)
    os.close(old_context)
    print('PASS cgroup fscontext retains namespace authority', flush=True)

    with tempfile.TemporaryDirectory(prefix='md-fsopen-') as directory:
        root = Path(directory)
        root_operands(root)
        for first in ('table', 'clone', 'copy'):
            removed_mountpoint(root, first)
        filesystem_ioctls(root)
        nosymfollow(root)
        mount_root('/')
        mount_root(root, expected=False)
        watched = os.open('/proc/self/mountinfo', os.O_RDONLY | os.O_CLOEXEC)
        event = select.epoll()
        event.register(watched, select.EPOLLPRI)
        os.read(watched, 1 << 20)
        assert not event.poll(0)
        context = create()
        detached = call(432, context, 1, 0)
        os.close(context)
        assert not event.poll(0), 'detached mounts must not publish a placement'
        call(429, detached, '', -100, str(root), 4)
        mount_root(root)
        os.close(detached)
        assert event.poll(0), 'missing mount namespace notification'
        os.lseek(watched, 0, os.SEEK_SET)
        assert (' ' + str(root) + ' ') in os.read(watched, 1 << 20).decode()
        assert not event.poll(0), 'reading did not acknowledge mount change'
        call(39, str(root), 2)
        assert event.poll(0), 'missing unmount notification'
        os.lseek(watched, 0, os.SEEK_SET)
        assert (' ' + str(root) + ' ') not in os.read(watched, 1 << 20).decode()
        assert not event.poll(0)
        event.close()
        os.close(watched)
        print('PASS live mount table, epoll notification, reread and unmount', flush=True)

        for table in ('/proc/mounts', '/proc/self/mountinfo'):
            paths = [root / ('ordered-' + str(i)) for i in range(3)]
            for path in paths:
                path.mkdir()
            for path in paths[:2]:
                call(40, 'tmpfs', str(path), 'tmpfs', 0, 0)
            call(39, str(paths[0]), 2)
            before = Path(table).read_text().splitlines()
            with open(table) as opened:
                call(40, 'tmpfs', str(paths[2]), 'tmpfs', 0, 0)
                after = opened.read().splitlines()
            assert after[:len(before)] == before, (table, before, after)
            assert len(after) == len(before) + 1 and (' ' + str(paths[2]) + ' ') in after[-1]
            for path in paths[1:]:
                call(39, str(path), 2)
            for path in paths:
                path.rmdir()
        print('PASS new mounts append after existing entries despite retired slot reuse', flush=True)

        mounted = root / 'mounted'
        mounted.mkdir()
        alias = root / 'alias'
        alias.symlink_to(mounted)
        call(40, 'tmpfs', str(mounted), 'tmpfs', 0, 0)
        mount_root(mounted)
        (mounted / 'kept').touch()
        call(39, str(alias), 8, error=errno.EINVAL)  # UMOUNT_NOFOLLOW
        assert (mounted / 'kept').exists()
        call(39, str(mounted), 8)
        assert not (mounted / 'kept').exists()
        call(40, 'tmpfs', str(mounted), 'tmpfs', 0, 0)
        call(39, str(alias), 0)
        alias.unlink()
        mounted.rmdir()
        print('PASS unmount nofollow preserves the symlink target', flush=True)

        mounted.mkdir()
        before = os.open(mounted, os.O_PATH | os.O_DIRECTORY)
        before_id = os.stat(mounted).st_ino
        call(40, 'tmpfs', str(mounted), 'tmpfs', 0, 0)
        mount_root(mounted)
        mount_root(fd=before, expected=False)
        call(40, 0, f'/proc/self/fd/{before}', 0, 1 | 32 | 4096, 0)
        assert os.fstat(before).st_ino == before_id
        write_denied(mounted / 'blocked')
        call(39, f'/proc/self/fd/{before}', 2)
        (mounted / 'writable').touch()
        (mounted / 'writable').unlink()
        os.close(before)
        mounted.rmdir()
        print('PASS legacy mount target follows overmount without retargeting old FD', flush=True)

        source = '/proc/sys/kernel/domainname'
        parent = os.open('/proc/sys/kernel', os.O_PATH)
        sysctl = os.open(source, os.O_PATH)

        def fdinfo(fd):
            return dict(line.split(':', 1) for line in Path(f'/proc/self/fdinfo/{fd}').read_text().splitlines())

        assert fdinfo(parent)['mnt_id'] == fdinfo(sysctl)['mnt_id']
        assert int(fdinfo(sysctl)['ino']) == os.fstat(sysctl).st_ino
        os.close(parent)
        os.close(sysctl)
        bound = root / 'sysctl'
        bound.touch()
        call(40, source, str(bound), 0, 4096, 0)
        assert bound.read_text() == Path(source).read_text()
        assert os.stat(bound).st_ino == os.stat(source).st_ino
        sysctl = os.open(bound, os.O_PATH)
        assert int(fdinfo(sysctl)['ino']) == os.fstat(sysctl).st_ino
        assert mount_identity(bound) == mount_identity(fd=sysctl) == int(fdinfo(sysctl)['mnt_id'])
        mount_root(fd=sysctl)
        os.close(sysctl)
        attributes(bound, set_bits=1)
        write_denied(bound)
        call(39, str(bound), 2)
        bound.unlink()
        print('PASS typed sysctl bind mount and readonly policy', flush=True)

        proc = root / 'proc'
        proc.mkdir()
        call(97, 0x04000000)  # The test owns a private UTS namespace.
        call(40, 'proc', str(proc), 'proc', 0, 0)
        domain = proc / 'sys/kernel/domainname'
        sysctl = call(428, -100, str(domain), os.O_CLOEXEC | 0x100)
        assert mount_identity(domain) == mount_identity(fd=sysctl)
        mount_root(fd=sysctl, expected=False)
        duplicate = os.dup(sysctl)
        assert mount_identity(fd=duplicate) == mount_identity(fd=sysctl)
        os.close(duplicate)
        left, right = socket.socketpair()
        left.sendmsg([b'x'], [(socket.SOL_SOCKET, socket.SCM_RIGHTS, array.array('i', [sysctl]))])
        _, ancillary, _, _ = right.recvmsg(1, socket.CMSG_SPACE(4))
        received = array.array('i')
        received.frombytes(ancillary[0][2])
        assert mount_identity(fd=received[0]) == mount_identity(fd=sysctl)
        mount_root(fd=received[0], expected=False)
        os.close(received[0])
        left.close()
        right.close()
        os.close(sysctl)
        previous = domain.read_text()
        domain.write_text('md-proc-view\n')
        assert domain.read_text() == 'md-proc-view\n'
        assert Path(source).read_text() == 'md-proc-view\n'
        domain.write_text(previous)
        attributes(proc, set_bits=1)
        write_denied(domain)
        call(39, str(proc), 2)
        proc.rmdir()
        print('PASS mounted proc sysctl write and readonly policy', flush=True)

        call(430, 'unknown', 0, error=errno.ENODEV)
        call(430, 'tmpfs', 2, error=errno.EINVAL)
        call(431, 123456, 6, 0, 0, 0, error=errno.EBADF)
        with open('/dev/null', 'rb') as ordinary:
            call(431, ordinary.fileno(), 6, 0, 0, 0, error=errno.EINVAL)
        fd = call(430, 'tmpfs', 0)
        assert not fcntl.fcntl(fd, fcntl.F_GETFD) & fcntl.FD_CLOEXEC
        for action, expected in [(lambda: os.read(fd, 10), errno.ENODATA),
                                 (lambda: os.lseek(fd, 0, os.SEEK_SET), errno.ESPIPE),
                                 (lambda: os.pread(fd, 10, 0), errno.ESPIPE)]:
            try:
                action()
                raise AssertionError('fscontext IO unexpectedly succeeded')
            except OSError as error:
                assert error.errno == expected, error
        call(432, fd, 1, 0, error=errno.EINVAL)
        call(431, fd, 0, 'unsupported', 0, 0, error=errno.EINVAL)
        call(431, fd, 0, 'noswap', 0, 0, error=errno.EINVAL)
        call(431, fd, 0, 'size', 0, 0, error=errno.EINVAL)
        config(fd, 'mode', '0700')
        config(fd, 'size', '1')
        config(fd, 'nr_blocks', '1')
        config(fd, 'nr_inodes', '1')
        duplicate = os.dup(fd)
        os.close(fd)
        call(431, duplicate, 6, 0, 0, 0)
        call(431, duplicate, 6, 0, 0, 0, error=errno.EBUSY)
        call(432, duplicate, 1, 0x30, error=errno.EINVAL)
        mount = call(432, duplicate, 1, 0x20)  # MOUNT_ATTR_STRICTATIME is a backing hint.
        assert fcntl.fcntl(mount, fcntl.F_GETFD) & fcntl.FD_CLOEXEC
        assert fcntl.fcntl(mount, fcntl.F_GETFL) & os.O_PATH
        os.close(duplicate)
        assert os.fstat(mount).st_mode & 0o777 == 0o700
        nested = os.open('before-attach', os.O_CREAT | os.O_RDWR, 0o600, dir_fd=mount)
        os.write(nested, b'kept')
        os.ftruncate(nested, 16384)
        assert os.pwrite(nested, b'beyond', 8192) == 6
        os.ftruncate(nested, 4)
        os.close(nested)
        assert not (root / 'before-attach').exists()
        call(429, mount, '', -100, str(root), 4)
        assert (root / 'before-attach').read_bytes() == b'kept'
        (root / 'moved').mkdir()
        call(429, mount, '', -100, str(root / 'moved'), 4, error=errno.EINVAL)
        call(39, str(root), 2)
        assert not (root / 'before-attach').exists()
        call(429, mount, '', -100, str(root), 4, error=errno.EINVAL)
        os.close(mount)
        print('PASS fscontext state, dup, detached IO, attach and unmount', flush=True)

        # Superblock reconfiguration affects every alias, not just the mount
        # returned by fsmount. Native leases include unlinked writers and mmap.
        import mmap
        context = create(mode='0755')
        mount = call(432, context, 1, 0)
        data = os.open('data', os.O_CREAT | os.O_RDWR, 0o600, dir_fd=mount)
        os.ftruncate(data, mmap.PAGESIZE)
        call(431, context, 0, 'ro', 0, 0)
        call(431, context, 7, 0, 0, 0, error=errno.EBUSY)
        mapping = mmap.mmap(data, mmap.PAGESIZE, flags=mmap.MAP_SHARED)
        os.close(data)
        call(431, context, 7, 0, 0, 0, error=errno.EBUSY)
        mapping.close()
        call(429, mount, '', -100, str(root), 4)
        alias = root / 'alias'
        alias.mkdir()
        call(40, str(root), str(alias), 0, 4096, 0)
        data = os.open(root / 'unlinked', os.O_CREAT | os.O_RDWR, 0o600)
        os.unlink(root / 'unlinked')
        call(431, context, 7, 0, 0, 0, error=errno.EBUSY)
        os.close(data)
        replaced = os.open(root / 'replaced', os.O_CREAT | os.O_RDWR, 0o600)
        (root / 'replacement').write_bytes(b'new')
        os.replace(root / 'replacement', root / 'replaced')
        assert os.fstat(replaced).st_nlink == 0
        call(431, context, 7, 0, 0, 0, error=errno.EBUSY)
        os.close(replaced)
        call(431, context, 7, 0, 0, 0)
        for path in (root, alias):
            write_denied(path / 'blocked')
            write_denied(path / 'data')
            assert os.statvfs(path).f_flag & os.ST_RDONLY
        assert os.fstatvfs(mount).f_flag & os.ST_RDONLY
        fields = next(line.split(' - ') for line in Path('/proc/self/mountinfo').read_text().splitlines()
                      if line.split()[4] == str(alias))
        assert fields[1].split()[2] == 'ro', fields
        child = os.fork()
        if child == 0:
            call(97, 0x20000)  # CLONE_NEWNS retains superblock policy.
            write_denied(alias / 'child-blocked')
            os._exit(0)
        # EVENT_WAIT: inherited namespace completes; outer deadline fails a hang.
        assert os.waitpid(child, 0)[1] == 0
        attributes(alias, set_bits=1)
        call(431, context, 0, 'rw', 0, 0)
        call(431, context, 7, 0, 0, 0)
        assert not os.statvfs(root).f_flag & os.ST_RDONLY
        (root / 'data').write_bytes(b'writable')
        write_denied(alias / 'data')
        call(39, str(alias), 2)
        alias.rmdir()
        call(39, str(root), 2)
        os.close(mount)
        os.close(context)
        print('PASS fsconfig reconfigure: writers, unlinked files, mmap, aliases, namespace and per-mount policy', flush=True)

        # A fresh context selects the mounted filesystem, not the fsopen FD.
        call(40, 'tmpfs', str(root), 'tmpfs', 0, 0)
        (root / 'subdir').mkdir()
        (root / 'value').write_bytes(b'kept')
        link = root / 'root-link'
        link.symlink_to(root)
        call(433, -100, str(root), 16, error=errno.EINVAL)
        call(433, -100, str(root / 'subdir'), 0, error=errno.EINVAL)
        call(433, -100, str(link), 2, error=errno.EINVAL)
        picked = call(433, -100, str(link), 1)
        assert fcntl.fcntl(picked, fcntl.F_GETFD) & fcntl.FD_CLOEXEC
        call(431, picked, 6, 0, 0, 0, error=errno.EBUSY)
        root_fd = os.open(root, os.O_PATH)
        call(433, root_fd, '', 0, error=errno.ENOENT)
        retained = call(433, root_fd, '', 8 | 4)
        assert not fcntl.fcntl(retained, fcntl.F_GETFD) & fcntl.FD_CLOEXEC
        config(picked, 'ro')
        call(431, picked, 7, 0, 0, 0)
        write_denied(root / 'value')
        assert os.fstatvfs(root_fd).f_flag & os.ST_RDONLY
        config(retained, 'rw')
        call(431, retained, 7, 0, 0, 0)
        (root / 'value').write_bytes(b'new')
        call(39, str(root), 2)
        config(retained, 'ro')
        call(431, retained, 7, 0, 0, 0)
        assert os.fstatvfs(root_fd).f_flag & os.ST_RDONLY
        os.close(root_fd)
        os.close(picked)
        os.close(retained)
        print('PASS fspick: mount root, symlinks, EMPTY_PATH, context lifetime and superblock policy', flush=True)

        # Provider policy must cover aliases and an already-open control FD.
        call(40, 'proc', str(root), 'proc', 0, 0)
        picked = call(433, -100, str(root), 1)
        domain = root / 'sys/kernel/domainname'
        previous = domain.read_bytes()
        control = os.open(domain, os.O_WRONLY)
        config(picked, 'ro')
        call(431, picked, 7, 0, 0, 0)
        write_denied(domain)
        try:
            os.write(control, previous)
            raise AssertionError('retained control ignored readonly superblock')
        except OSError as error:
            assert error.errno == errno.EROFS, error
        assert os.statvfs(root).f_flag & os.ST_RDONLY
        child = os.fork()
        if child == 0:
            call(97, 0x20000)
            write_denied(domain)
            call(97, 0x10000000 | 0x20000)
            Path('/proc/self/uid_map').write_text('0 0 1\n')
            Path('/proc/self/setgroups').write_text('deny\n')
            Path('/proc/self/gid_map').write_text('0 0 1\n')
            restricted = call(433, -100, str(root), 1)
            config(restricted, 'rw')
            call(431, restricted, 7, 0, 0, 0, error=errno.EPERM)
            config(picked, 'rw')
            call(431, picked, 7, 0, 0, 0, error=errno.EPERM)
            os.close(restricted)
            os._exit(0)
        assert os.waitpid(child, 0)[1] == 0
        config(picked, 'rw')
        call(431, picked, 7, 0, 0, 0)
        assert os.write(control, previous) == len(previous)
        os.close(control)
        os.close(picked)
        call(39, str(root), 2)
        print('PASS fspick provider: live controls, readonly and namespace copies', flush=True)

        # Real SCM_RIGHTS transfers retain authority, not the carrier inode.
        left, right = socket.socketpair()
        context = create(mode='0751')
        left.sendmsg([b'x'], [(socket.SOL_SOCKET, socket.SCM_RIGHTS, array.array('i', [context]))])
        os.close(context)
        _, ancillary, _, _ = right.recvmsg(1, socket.CMSG_SPACE(4))
        received = array.array('i')
        received.frombytes(ancillary[0][2])
        mount = call(432, received[0], 0, 1)
        os.close(received[0])
        assert not fcntl.fcntl(mount, fcntl.F_GETFD) & fcntl.FD_CLOEXEC
        call(429, mount, '', -100, str(root), 4)
        os.close(mount)
        try:
            (root / 'readonly').write_text('not allowed')
            raise AssertionError('readonly mount accepted a write')
        except OSError as error:
            assert error.errno == errno.EROFS, error
        call(39, str(root), 2)
        left.close()
        right.close()
        print('PASS SCM_RIGHTS filesystem context and readonly policy', flush=True)

        context = create(mode='0750')
        child = os.fork()
        if child == 0:
            mount = call(432, context, 1, 0)
            call(429, mount, '', -100, str(root), 4)
            os.close(mount)
            os._exit(0)
        # EVENT_WAIT: child completion; outer supervised deadline fails a hang.
        assert os.waitpid(child, 0)[1] == 0
        os.close(context)
        assert root.stat().st_mode & 0o777 == 0o750
        call(39, str(root), 2)
        print('PASS inherited fscontext and shared mount publication', flush=True)

        # Zero attributes skip path lookup, but malformed structures never do.
        zero = (ctypes.c_uint64 * 4)()
        call(442, -1, 0, 0, ctypes.addressof(zero), 32)
        call(442, -100, str(root), 0, 0, 31, error=errno.EINVAL)
        call(442, -100, str(root), 0, 0, os.sysconf('SC_PAGE_SIZE') + 1, error=errno.E2BIG)
        call(442, -100, str(root), 0, 1, 32, error=errno.EFAULT)
        call(442, -100, str(root), 1, ctypes.addressof(zero), 32, error=errno.EINVAL)
        attributes(root, tail=1, error=errno.E2BIG)
        attributes(root, set_bits=1 << 63, error=errno.EINVAL)
        attributes(root, set_bits=0x20, error=errno.EINVAL)
        attributes(root, clear_bits=0x10, error=errno.EINVAL)
        attributes(root, set_bits=0x30, clear_bits=0x70, error=errno.EINVAL)
        attributes(root, set_bits=0x100000, error=errno.EOPNOTSUPP)  # IDMAP
        attributes(root, set_bits=0x200000, error=errno.EINVAL)  # Not a mount root.
        attributes(root, propagation=(1 << 18) | (1 << 19), error=errno.EINVAL)
        attributes(root, propagation=1 << 20, error=errno.EINVAL)  # Not a mount root.
        context = create()
        mount = call(432, context, 1, 0)
        os.close(context)
        attributes(fd=mount, set_bits=1, error=errno.ENOENT)
        attributes(fd=mount, flags=0x1000, set_bits=1, tail=0)
        attributes(fd=mount, flags=0x1000, propagation=1 << 20)  # SHARED
        attributes(fd=mount, flags=0x1000, propagation=1 << 18)  # PRIVATE
        try:
            os.open('blocked', os.O_CREAT | os.O_WRONLY, 0o600, dir_fd=mount)
            raise AssertionError('detached readonly mount accepted a write')
        except OSError as error:
            assert error.errno == errno.EROFS, error
        attributes(fd=mount, flags=0x1000, clear_bits=1)
        call(429, mount, '', -100, str(root), 4)
        (root / 'child').mkdir()
        (root / 'ordinary').mkdir()
        attributes(root / 'ordinary', set_bits=1, error=errno.EINVAL)
        context = create()
        child_mount = call(432, context, 1, 0)
        os.close(context)
        call(429, child_mount, '', -100, str(root / 'child'), 4)
        os.close(child_mount)
        (root / 'child' / 'value').write_text('child')
        (root / 'value').write_text('root')
        alias = root / 'link'
        alias.symlink_to(root / 'child', target_is_directory=True)
        attributes(alias, flags=0x100, set_bits=1, error=errno.EINVAL)  # NOFOLLOW
        attributes(alias, set_bits=1)
        write_denied(root / 'child' / 'blocked')
        attributes(root, set_bits=2)  # NOSUID on parent only.
        assert os.statvfs(root).f_flag & os.ST_NOSUID
        assert not os.statvfs(root / 'child').f_flag & os.ST_NOSUID
        attributes(root, clear_bits=2)

        child = os.fork()
        if child == 0:
            try:
                assert libc.prctl(4, 0, 0, 0, 0) == 0  # PR_SET_DUMPABLE
                attributes(root, set_bits=8)
                assert os.statvfs(root).f_flag & os.ST_NOEXEC
                attributes(root, clear_bits=8)
                os.setresuid(1, 1, 1)
                call(442, -1, 0, 0, ctypes.addressof(zero), 32, error=errno.EPERM)
                os._exit(0)
            except BaseException:
                import traceback
                traceback.print_exc()
                os._exit(1)
        # EVENT_WAIT: protected caller and authority check; outer deadline fails a hang.
        assert os.waitpid(child, 0)[1] == 0

        child = os.fork()
        if child == 0:
            try:
                call(97, 0x10000000 | 0x20000)  # NEWUSER | NEWNS, locks inherited policy.
                Path('/proc/self/uid_map').write_text('0 0 1\n')
                Path('/proc/self/setgroups').write_text('deny\n')
                Path('/proc/self/gid_map').write_text('0 0 1\n')
                attributes(root, flags=0x8000, set_bits=2, clear_bits=1, error=errno.EPERM)
                assert not os.statvfs(root).f_flag & os.ST_NOSUID
                assert not os.statvfs(root / 'child').f_flag & os.ST_NOSUID
                assert os.statvfs(root / 'child').f_flag & os.ST_RDONLY
                (root / 'parent-still-writable').write_text('kept')
                os._exit(0)
            except BaseException:
                import traceback
                traceback.print_exc()
                os._exit(1)
        # EVENT_WAIT: namespace child completion; outer deadline fails a hang.
        assert os.waitpid(child, 0)[1] == 0
        attributes(root, flags=0x8000, set_bits=1 | 2 | 4 | 8)
        write_denied(root / 'blocked')
        write_denied(root / 'child' / 'blocked')
        for path in (root, root / 'child'):
            assert os.statvfs(path).f_flag & 15 == 15
        attributes(root, flags=0x8000, set_bits=0x20, clear_bits=15 | 0x70, propagation=1 << 18)
        (root / 'value').write_text('writable')
        (root / 'child' / 'value').write_text('writable')
        # Retained descriptors observe the same policy after attachment.
        attributes(fd=mount, flags=0x1000, set_bits=1, clear_bits=1)
        write_denied(root / 'blocked')
        attributes(fd=mount, flags=0x1000, clear_bits=1)
        call(39, str(root), 2)
        attributes(fd=mount, flags=0x1000, set_bits=1, error=errno.EINVAL)
        os.close(mount)
        print('PASS mount_setattr: ABI, detached FD, recursive policy, locked rollback and retained origins', flush=True)

        source, destination = root / 'source', root / 'destination'
        source.mkdir()
        destination.mkdir()
        (source / 'value').write_text('source')
        call(428, -100, str(source), 2, error=errno.EINVAL)
        call(428, -100, str(source), 0x8000, error=errno.EINVAL)
        opened = call(428, -100, str(source), os.O_CLOEXEC)
        assert fcntl.fcntl(opened, fcntl.F_GETFD) & fcntl.FD_CLOEXEC
        duplicate = call(428, opened, '', 0x1000)
        assert not fcntl.fcntl(duplicate, fcntl.F_GETFD) & fcntl.FD_CLOEXEC
        assert os.fstat(duplicate).st_ino == source.stat().st_ino
        os.close(duplicate)
        clone = call(428, opened, '', 1 | os.O_CLOEXEC | 0x1000)
        os.close(opened)
        attributes(fd=clone, flags=0x1000, set_bits=1)
        call(429, clone, '', -100, str(destination), 4)
        os.close(clone)
        assert (destination / 'value').read_text() == 'source'
        write_denied(destination / 'value')
        (source / 'value').write_text('shared')
        assert (destination / 'value').read_text() == 'shared'
        call(39, str(destination), 2)

        (source / 'child').mkdir()
        context = create()
        child_mount = call(432, context, 1, 0)
        os.close(context)
        call(429, child_mount, '', -100, str(source / 'child'), 4)
        os.close(child_mount)
        (source / 'child' / 'nested').write_text('nested')
        clone = call(428, -100, str(source), 1 | os.O_CLOEXEC | 0x8000)
        call(429, clone, '', -100, str(destination), 4)
        os.close(clone)
        assert (destination / 'child' / 'nested').read_text() == 'nested'
        call(39, str(source / 'child'), 2)
        assert (destination / 'child' / 'nested').read_text() == 'nested'
        call(39, str(destination), 2)
        with open(root / 'device', 'w'):
            pass
        clone = call(428, -100, '/dev/null', 1 | os.O_CLOEXEC)
        call(429, clone, '', -100, str(root / 'device'), 4)
        os.close(clone)
        with open(root / 'device', 'rb') as device:
            assert device.read(1) == b''
        call(39, str(root / 'device'), 2)
        before = len(os.listdir('/proc/self/fd'))
        for _ in range(96):
            os.close(call(428, -100, str(source), 1 | os.O_CLOEXEC))
        assert len(os.listdir('/proc/self/fd')) == before
        print('PASS open_tree: retained roots, recursive clones, native files, policy and cleanup', flush=True)

        # openat2 shares the versioned-structure reader, not mount semantics.
        how = (ctypes.c_uint64 * 4)(os.O_PATH, 0, 0, 0)
        opened = call(437, -100, str(root), ctypes.addressof(how), ctypes.sizeof(how))
        os.close(opened)
        how[3] = 1
        call(437, -100, str(root), ctypes.addressof(how), ctypes.sizeof(how), error=errno.E2BIG)
        call(437, -100, str(root), ctypes.addressof(how), 23, error=errno.EINVAL)
        call(437, -100, str(root), 1, 24, error=errno.EFAULT)
        print('PASS shared structure reader: openat2 zero extension and invalid buffers', flush=True)

        mount_trace = ['strace', '-e', 'trace=fsopen,fspick,fsconfig,fsmount,move_mount,mount_setattr,open_tree,mount']
        for kind, options in [('tmpfs', 'mode=0755,size=65536k,strictatime'), ('ramfs', 'mode=0700'), ('cgroup2', None), ('cgroup', 'devices'),
                              ('proc', None), ('sysfs', None), ('devpts', 'newinstance,ptmxmode=0666,mode=0620,max=1028')]:
            if kind == 'sysfs':
                # The inherited network namespace belongs to the parent user
                # namespace. A new user namespace cannot mount its sysfs.
                call(430, 'sysfs', 1, error=errno.EPERM)
                call(97, 0x40000000)  # CLONE_NEWNET owned by this user namespace.
            command = ['strace', '-e', 'trace=fsopen,fsconfig,fsmount,move_mount,mount_setattr',
                       'mount', '-t', kind]
            if options:
                command += ['-o', options]
            result = subprocess.run(command + ['none', str(root)], capture_output=True, text=True)
            print(result.stderr, end='', flush=True)
            assert result.returncode == 0, result
            assert 'fsopen(' in result.stderr and 'move_mount(' in result.stderr, result.stderr
            opened = os.open(root, os.O_PATH | os.O_DIRECTORY)
            try:
                link = '/proc/self/fd/' + str(opened)
                assert os.readlink(link) == str(root), (kind, os.readlink(link))
                with ThreadPoolExecutor(max_workers=1) as workers:
                    linked = workers.submit(os.readlink, link).result(timeout=10)
                assert linked == str(root), (kind, 'threaded proc fd', linked)
            finally:
                os.close(opened)
            if kind == 'cgroup':
                entries = Path('/proc/self/mountinfo').read_text().splitlines()
                entry = next(line for line in entries if line.split()[4] == str(root))
                assert 'devices' in entry.split(' - ')[1].split()[2].split(','), entry
            if kind in ('tmpfs', 'ramfs', 'proc', 'sysfs'):
                for mode in ('ro', 'rw'):
                    result = subprocess.run(mount_trace + ['mount', '-o', 'remount,' + mode, str(root)],
                                            capture_output=True, text=True)
                    print(result.stderr, end='', flush=True)
                    assert result.returncode == 0, result
                    assert 'fspick(' in result.stderr and 'FSCONFIG_CMD_RECONFIGURE' in result.stderr, result.stderr
                    assert bool(os.statvfs(root).f_flag & os.ST_RDONLY) == (mode == 'ro'), (kind, mode)
                    if kind == 'proc':
                        control_path = root / 'sys/kernel/domainname'
                    elif kind == 'sysfs':
                        control_path = root / 'class/net/lo/mtu'
                    else:
                        control_path = root
                    assert bool(os.statvfs(control_path).f_flag & os.ST_RDONLY) == (mode == 'ro'), (kind, mode, control_path)
            call(39, str(root), 2)
        print('PASS stock util-linux mount using new API', flush=True)

        for part in ('lower', 'upper', 'work', 'merged'):
            (root / part).mkdir()
        (root / 'lower' / 'value').write_text('lower')
        context = create('overlay', lowerdir=str(root / 'lower'), upperdir=str(root / 'upper'),
                         workdir=str(root / 'work'))
        mount = call(432, context, 1, 0)
        os.close(context)
        call(429, mount, '', -100, str(root / 'merged'), 4)
        os.close(mount)
        assert (root / 'merged' / 'value').read_text() == 'lower'
        (root / 'merged' / 'value').write_text('upper')
        assert (root / 'lower' / 'value').read_text() == 'lower'
        assert (root / 'upper' / 'value').read_text() == 'upper'
        call(39, str(root / 'merged'), 2)
        print('PASS overlay through the common filesystem provider', flush=True)

        before = len(os.listdir('/proc/self/fd'))
        for _ in range(96):
            context = create(mode='0700')
            mount = call(432, context, 1, 0)
            os.close(context)
            os.close(mount)
        assert len(os.listdir('/proc/self/fd')) == before
        print('PASS abandoned detached mounts: slot reuse and descriptor release', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('store')
    parser.add_argument('--base', action='store_true')
    args = parser.parse_args()
    from test_oci_services import Suite
    suite = Suite(args.build, args.build)
    target = '/tmp/md-mount-api-' + suite.report['id']
    prefix = [suite.runner, '--store', args.store, '--user', '0:0', '--network', 'routed']
    if args.base:
        prefix += ['--env', 'SHROOT_MACHINE_ENTRY=0']
    suite.report['entry'] = 'base' if args.base else 'default'
    try:
        suite.transport.upload(suite.client, Path(__file__), suite.base + '/test.py')
        suite.command(['/system/bin/ln', '-s', 'test.py', suite.base + '/linked.py'])
        suite.command(prefix + ['--', '/bin/mkdir', target])
        suite.command(prefix + ['--bind-ro', suite.base, target, '--deadline-seconds', '30', '--',
                              '/usr/bin/unshare', '--mount', '/usr/bin/python3',
                              target + '/test.py', '--guest-root'], timeout=45)
        command = prefix + ['--bind-ro', suite.base, target, '--deadline-seconds', '100', '--',
                            '/usr/bin/unshare', '--user', '--map-users=0:0:65536', '--map-groups=0:0:65536',
                            '--mount', '--pid', '--fork', '/usr/bin/python3', target + '/test.py', '--guest']
        suite.command(command, timeout=120)
        suite.report['passed'] = True
    finally:
        try:
            suite.command(prefix + ['--', '/bin/rmdir', target])
        finally:
            suite.finish()


if __name__ == '__main__':
    if sys.argv[1:] == ['--guest']:
        guest()
    elif sys.argv[1:] == ['--guest-root']:
        guest_root()
    else:
        main()
