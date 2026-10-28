#!/usr/bin/env python3
"""Foreign proc-root lookup retains the selected namespace, not Android's root."""
import ctypes
import errno
import os
import shutil
import signal
import select
import socket
import stat
import struct
import tempfile

libc = ctypes.CDLL(None, use_errno=True)


def native(call, *args):
    result = call(*args)
    if result < 0:
        raise OSError(ctypes.get_errno(), os.strerror(ctypes.get_errno()))
    return result


def rejects(code, call, *args, **kwargs):
    try:
        result = call(*args, **kwargs)
    except OSError as error:
        assert error.errno == code, error
    else:
        raise AssertionError(('expected errno', code, 'got', result))


def readat(fd, path):
    child = os.open(path, os.O_RDONLY, dir_fd=fd)
    try:
        return os.read(child, 100)
    finally:
        os.close(child)


def status_identity(process, uid, gid):
    with open(f'/proc/{process}/status') as stream:
        fields = dict(line.rstrip().split(':', 1) for line in stream)
    assert list(map(int, fields['Uid'].split())) == [uid] * 4, fields['Uid']
    assert list(map(int, fields['Gid'].split())) == [gid] * 4, fields['Gid']
    assert int(fields['TracerPid']) == 0, fields['TracerPid']


def fdinfo(fd, process='self'):
    with open(f'/proc/{process}/fdinfo/{fd}') as stream:
        return dict(line.rstrip().split(':', 1) for line in stream)


def descriptor_info():
    def filesystem(fd):
        result = (ctypes.c_long * 15)()
        native(libc.fstatfs, fd, ctypes.byref(result))
        assert result[0] in (0x09041934, 0x50494446), list(result)
        assert result[1] == os.sysconf('SC_PAGE_SIZE') and result[8] == 255, list(result)
        by_path = (ctypes.c_long * 15)()
        native(libc.statfs, f'/proc/self/fd/{fd}'.encode(), ctypes.byref(by_path))
        assert bytes(result) == bytes(by_path), (list(result), list(by_path))
        # musl clears the buffer before entering the kernel; test the syscall's
        # fault contract without that userspace write (AArch64 SYS_fstatfs=44).
        rejects(errno.EFAULT, native, libc.syscall, 44, fd, ctypes.c_void_p(1))
        duplicate = os.dup(fd)
        try:
            native(libc.fstatfs, duplicate, ctypes.byref(by_path))
            assert bytes(result) == bytes(by_path)
        finally:
            os.close(duplicate)

    pidfd = os.pidfd_open(os.getpid())
    try:
        filesystem(pidfd)
        fields = fdinfo(pidfd)
        assert int(fields['Pid']) == os.getpid(), (fields, os.getpid())
        if os.getpid() == 1 or 'NSpid' in fields:
            assert int(fields['NSpid'].split()[0]) == os.getpid(), fields
        os.set_inheritable(pidfd, True)
        assert not (int(fdinfo(pidfd)['flags'], 8) & os.O_CLOEXEC)
        os.set_inheritable(pidfd, False)
        assert int(fdinfo(pidfd)['flags'], 8) & os.O_CLOEXEC
        with open(f'/proc/self/fdinfo/{pidfd}', 'rb', buffering=0) as stream:
            first = stream.read(4)
            duplicate = os.dup(stream.fileno())
            try:
                rest = os.read(duplicate, 4096)
                assert first + rest == os.pread(stream.fileno(), 4096, 0)
                os.close(pidfd)
                pidfd = -1
                stream.seek(0)
                rejects(errno.ENOENT, stream.read, 4096)
            finally:
                os.close(duplicate)
    finally:
        if pidfd >= 0:
            os.close(pidfd)
    with tempfile.TemporaryFile() as stream:
        stream.write(b'ordinary descriptor')
        stream.flush()
        assert int(fdinfo(stream.fileno())['pos']) == stream.tell()
        stream.seek(3)
        assert int(fdinfo(stream.fileno())['pos']) == 3
    a, b = socket.socketpair()
    try:
        try:
            pidfd = a.getsockopt(socket.SOL_SOCKET, 77)  # SO_PEERPIDFD, optional since 6.5
        except OSError as error:
            assert error.errno in (errno.ENOPROTOOPT, errno.EINVAL, errno.ENOSYS), error
        else:
            try:
                assert int(fdinfo(pidfd)['Pid']) == os.getpid()
                filesystem(pidfd)
            finally:
                os.close(pidfd)
    finally:
        a.close()
        b.close()


def signal_identity():
    ready, notify = os.pipe()
    child = os.fork()
    if not child:
        os.close(ready)
        os.setgid(1234)
        os.setuid(1234)
        os.write(notify, b'R')
        signal.pause()
        os._exit(1)
    os.close(notify)
    try:
        # EVENT_WAIT: child publishes its new credentials; harness owns timeout.
        assert os.read(ready, 1) == b'R'
        denied = os.fork()
        if not denied:
            try:
                os.setgid(2345)
                os.setuid(2345)
                rejects(errno.EPERM, os.kill, child, 0)
            except BaseException:
                os._exit(1)
            os._exit(0)
        assert os.waitpid(denied, 0)[1] == 0
        os.kill(child, signal.SIGTERM)
        assert os.waitpid(child, 0)[1] == signal.SIGTERM
    finally:
        os.close(ready)


def foreign_descriptors():
    with tempfile.TemporaryDirectory(prefix='proc-fd-') as directory:
        path = directory + '/removed'
        stream = open(path, 'w+b')
        stream.write(b'retained file')
        stream.flush()
        os.unlink(path)
        sock = socket.socket()
        sock.bind(('127.0.0.1', 0))
        sock.listen()
        pipe, writer = os.pipe()
        namespace = os.open('/proc/self/ns/pid', os.O_RDONLY)
        release, done = os.pipe()
        acknowledged, notify = os.pipe()
        pid = os.fork()
        if not pid:
            os.close(done)
            os.close(acknowledged)
            while True:
                # EVENT_WAIT: parent controls each FD transition; harness bounds failure.
                command = os.read(release, 1)
                if command == b'Q':
                    os._exit(0)
                if command == b'C':
                    os.close(stream.fileno())
                elif command == b'R':
                    os.dup2(sock.fileno(), stream.fileno())
                else:
                    os._exit(1)
                os.write(notify, command)
        os.close(release)
        os.close(notify)
        try:
            def inspect():
                for fd in (stream.fileno(), sock.fileno(), pipe, namespace):
                    name = f'/proc/{pid}/fd/{fd}'
                    expected, actual = os.fstat(fd), os.stat(name)
                    assert (actual.st_dev, actual.st_ino, actual.st_mode, actual.st_size) == (
                        expected.st_dev, expected.st_ino, expected.st_mode, expected.st_size), (name, expected, actual)
                    assert stat.S_ISLNK(os.lstat(name).st_mode)
                    assert os.readlink(name) == os.readlink(f'/proc/self/fd/{fd}')
                    value = ctypes.create_string_buffer(256)
                    native(libc.statx, -100, os.fsencode(name), 0, 0x7ff, value)
                    assert struct.unpack_from('=Q', value.raw, 32)[0] == expected.st_ino
                    assert struct.unpack_from('=H', value.raw, 28)[0] == expected.st_mode
                    native(libc.statx, -100, os.fsencode(name), 0x100, 0x7ff, value)
                    assert stat.S_ISLNK(struct.unpack_from('=H', value.raw, 28)[0])
                assert os.readlink(f'/proc/{pid}/cwd') == os.getcwd()
                assert os.stat(f'/proc/{pid}/cwd').st_ino == os.stat('.').st_ino
                assert os.stat(f'/proc/{pid}/exe').st_ino == os.stat('/proc/self/exe').st_ino
                directory_fd = os.open(f'/proc/{pid}/fd', os.O_RDONLY | os.O_DIRECTORY)
                try:
                    assert os.stat(str(sock.fileno()), dir_fd=directory_fd).st_ino == os.fstat(sock.fileno()).st_ino
                finally:
                    os.close(directory_fd)

            inspect()
            reader = os.fork()
            if not reader:
                try:
                    native(libc.prctl, 4, 0, 0, 0, 0)
                    inspect()
                except BaseException:
                    import traceback
                    traceback.print_exc()
                    os._exit(1)
                os._exit(0)
            assert os.waitpid(reader, 0)[1] == 0
            denied = os.fork()
            if not denied:
                try:
                    os.setgid(1234)
                    os.setuid(1234)
                    for name in (f'/proc/{pid}/fd/{sock.fileno()}', f'/proc/{pid}/cwd', f'/proc/{pid}/exe'):
                        rejects(errno.EACCES, os.stat, name)
                        rejects(errno.EACCES, os.readlink, name)
                except BaseException:
                    import traceback
                    traceback.print_exc()
                    os._exit(1)
                os._exit(0)
            assert os.waitpid(denied, 0)[1] == 0
            reused = f'/proc/{pid}/fd/{stream.fileno()}'
            os.write(done, b'C')
            assert os.read(acknowledged, 1) == b'C'
            rejects(errno.ENOENT, os.stat, reused)
            os.write(done, b'R')
            assert os.read(acknowledged, 1) == b'R'
            assert os.stat(reused).st_ino == os.fstat(sock.fileno()).st_ino
            assert stat.S_ISSOCK(os.stat(reused).st_mode)
            os.write(done, b'Q')
            assert os.waitpid(pid, 0)[1] == 0
            old_pid, pid = pid, None
            rejects(errno.ENOENT, os.stat, f'/proc/{old_pid}/fd/{sock.fileno()}')
        finally:
            if pid is not None:
                os.kill(pid, 9)
                os.waitpid(pid, 0)
            stream.close()
            sock.close()
            for fd in (pipe, writer, namespace, done, acknowledged):
                os.close(fd)
    print('PASS foreign proc metadata: sockets, pipes, deleted files, namespaces, cwd, exe, protected reader and denied identity', flush=True)


def main():
    status_identity('self', 0, 0)
    if os.getpid() == 1:
        signal_identity()
    descriptor_info()
    foreign_descriptors()
    protected_info = os.fork()
    if not protected_info:
        try:
            native(libc.prctl, 4, 0, 0, 0, 0)
            descriptor_info()
        except BaseException:
            import traceback
            traceback.print_exc()
            os._exit(1)
        os._exit(0)
    assert os.waitpid(protected_info, 0)[1] == 0
    root = tempfile.mkdtemp(prefix='proc-root-')
    os.mkdir(root + '/mounted')
    with open(root + '/identity', 'wb') as stream:
        stream.write(b'guest root')
    ready, notify = os.pipe()
    release, done = os.pipe()
    pid = os.fork()
    if pid == 0:
        try:
            os.close(ready)
            os.close(done)
            proc_fds = os.open('/proc/self/fd', os.O_PATH | os.O_DIRECTORY)
            previous_root = os.open('/', os.O_PATH | os.O_DIRECTORY)
            native(libc.unshare, 0x00020000)  # CLONE_NEWNS
            assert os.readlink(str(previous_root), dir_fd=proc_fds) == '/'
            current_root = os.open('/', os.O_PATH | os.O_DIRECTORY)
            native(libc.mount, b'tmpfs', os.fsencode(root + '/mounted'), b'tmpfs', 0, b'mode=755')
            with open(root + '/mounted/private', 'wb') as stream:
                stream.write(b'private mount')
            os.chroot(root)
            os.chdir('/')
            for retained_root in (previous_root, current_root):
                assert os.readlink(str(retained_root), dir_fd=proc_fds) == '/'
                os.close(retained_root)
            os.close(proc_fds)
            os.chdir('/mounted')
            os.write(notify, b'R')
            # EVENT_WAIT: parent owns inspection; the harness deadline cancels failure.
            assert os.read(release, 1) == b'M'
            os.mkdir('/extra')
            native(libc.mount, b'tmpfs', b'/extra', b'tmpfs', 0, b'mode=755')
            os.write(notify, b'M')
            assert os.read(release, 1) == b'Q'
        except BaseException:
            import traceback
            traceback.print_exc()
            os._exit(1)
        os._exit(0)
    os.close(notify)
    os.close(release)
    fd = duplicate = table = -1
    try:
        assert os.read(ready, 1) == b'R'
        status_identity(pid, 0, 0)
        path = f'/proc/{pid}/root'
        assert stat.S_ISDIR(os.stat(path).st_mode)
        assert stat.S_ISLNK(os.lstat(path).st_mode)
        rejects(errno.ELOOP, os.open, path, os.O_RDONLY | os.O_NOFOLLOW)
        fd = os.open(path, os.O_PATH | os.O_DIRECTORY | os.O_CLOEXEC)
        duplicate = os.dup(fd)
        directory = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
        try:
            assert sorted(os.listdir(directory)) == ['identity', 'mounted']
        finally:
            os.close(directory)
        rejects(errno.ENOENT, readat, fd, '.flatpak-info')
        assert readat(fd, 'identity') == b'guest root'
        assert readat(fd, 'mounted/private') == b'private mount'
        assert open(path + '/mounted/private', 'rb').read() == b'private mount'
        assert not os.path.exists(root + '/mounted/private')
        assert os.readlink(path) == root, (os.readlink(path), root)
        cwd_path = f'/proc/{pid}/cwd'
        assert os.readlink(cwd_path) == root + '/mounted', os.readlink(cwd_path)
        assert open(cwd_path + '/private', 'rb').read() == b'private mount'
        cwd_fd = os.open(cwd_path, os.O_PATH | os.O_DIRECTORY)
        try:
            assert readat(cwd_fd, 'private') == b'private mount'
        finally:
            os.close(cwd_fd)
        created = os.open('created', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600, dir_fd=fd)
        os.close(created)
        assert os.path.isfile(root + '/created')
        # A process with different logical credentials must not borrow this root.
        denied = os.fork()
        if denied == 0:
            try:
                os.setgid(1234)
                os.setuid(1234)
                status_identity('self', 1234, 1234)
                status_identity(pid, 0, 0)
                rejects(errno.EACCES, os.open, path, os.O_PATH | os.O_DIRECTORY)
            except BaseException:
                os._exit(1)
            os._exit(0)
        assert os.waitpid(denied, 0)[1] == 0
        capable = os.fork()
        if capable == 0:
            try:
                native(libc.prctl, 8, 1, 0, 0, 0)  # PR_SET_KEEPCAPS
                os.setgid(1234)
                os.setuid(1234)
                header = ctypes.create_string_buffer(struct.pack('Ii', 0x20080522, 0))
                mask = (1 << 19) | (1 << 1)  # SYS_PTRACE and DAC_OVERRIDE
                data = ctypes.create_string_buffer(struct.pack('IIIIII', mask, mask, 0, 0, 0, 0))
                native(libc.capset, header, data)
                assert stat.S_ISREG(os.stat(path + '/identity').st_mode)
                capable_fd = os.open(path, os.O_PATH | os.O_DIRECTORY)
                assert readat(capable_fd, 'mounted/private') == b'private mount'
                os.close(capable_fd)
                data = ctypes.create_string_buffer(struct.pack('IIIIII', 2, 2, 0, 0, 0, 0))
                native(libc.capset, header, data)
                rejects(errno.EACCES, os.open, path, os.O_PATH | os.O_DIRECTORY)
            except BaseException:
                import traceback
                traceback.print_exc()
                os._exit(1)
            os._exit(0)
        assert os.waitpid(capable, 0)[1] == 0
        print('PASS foreign proc root: non-root capabilities authorize access; dropping SYS_PTRACE revokes it', flush=True)
        protected = os.fork()
        if protected == 0:
            try:
                native(libc.prctl, 4, 0, 0, 0, 0)  # PR_SET_DUMPABLE
                protected_fd = os.open(path, os.O_PATH | os.O_DIRECTORY)
                assert readat(protected_fd, 'mounted/private') == b'private mount'
                os.close(protected_fd)
            except BaseException:
                import traceback
                traceback.print_exc()
                os._exit(1)
            os._exit(0)
        assert os.waitpid(protected, 0)[1] == 0
        table_path = f'/proc/{pid}/mountinfo'
        table = os.open(table_path, os.O_RDONLY | os.O_CLOEXEC)
        initial = os.read(table, 65536)
        assert b' /mounted ' in initial and os.fsencode(root) not in initial, initial
        assert b' /extra ' not in initial
        assert b' /mounted ' not in open('/proc/self/mountinfo', 'rb').read()
        mounts = open(f'/proc/{pid}/mounts', 'rb').read()
        assert b' /mounted ' in mounts and os.fsencode(root) not in mounts, mounts
        path_fd = os.open(table_path, os.O_PATH | os.O_CLOEXEC)
        assert stat.S_ISREG(os.fstat(path_fd).st_mode)
        rejects(errno.EBADF, os.read, path_fd, 1)
        os.close(path_fd)
        how = ctypes.create_string_buffer(struct.pack('QQQ', os.O_CLOEXEC, 0, 4))
        opened = native(libc.syscall, 437, -100, os.fsencode(table_path), how, 24)
        assert os.read(opened, 65536) == initial
        os.close(opened)
        rejects(errno.EACCES, os.open, table_path, os.O_WRONLY)
        rejects(errno.ENOTDIR, os.open, table_path, os.O_DIRECTORY)
        rejects(errno.ENOTDIR, os.open, table_path + '/suffix', os.O_RDONLY)
        poller = select.poll()
        poller.register(table, select.POLLPRI)
        os.write(done, b'M')
        assert os.read(ready, 1) == b'M'
        # EVENT_WAIT: mount publication must wake the foreign table descriptor.
        assert any(events & select.POLLPRI for _, events in poller.poll(2000))
        os.lseek(table, 0, os.SEEK_SET)
        updated = os.read(table, 65536)
        assert b' /extra ' in updated, updated
        os.write(done, b'Q')
        assert os.waitpid(pid, 0)[1] == 0
        pid = None
        rejects(errno.ENOENT, os.open, path, os.O_RDONLY | os.O_DIRECTORY)
        assert readat(duplicate, 'mounted/private') == b'private mount'
        os.lseek(table, 0, os.SEEK_SET)
        assert os.read(table, 65536) == updated
        print('PASS foreign mount tables: target namespace/root, openat2, poll changes and retained lifetime', flush=True)
        print('PASS foreign proc root: namespace, chroot, suffix, retained FD, protected reader, exit and denied identity', flush=True)
    finally:
        if pid is not None:
            os.kill(pid, 9)
            os.waitpid(pid, 0)
        for number in (fd, duplicate, table, ready, done):
            if number >= 0:
                os.close(number)
        shutil.rmtree(root)


if __name__ == '__main__':
    main()
