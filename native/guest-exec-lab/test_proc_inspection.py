"""Guest-root proc inspection, shared by shell-UID and app-UID launchers."""
import ctypes
import errno
import json
import os
import signal
import socket
import stat
import sys
import tempfile


signal.alarm(30)
libc = ctypes.CDLL(None, use_errno=True)
libc.syscall.restype = ctypes.c_long
busy = '--busy' in sys.argv
fifo = '--fifo' in sys.argv
assert not (busy and fifo)
for dumpable in (1, 0):
    ready_r, ready_w = os.pipe()
    release_r, release_w = os.pipe()
    child = os.fork()
    if not child:
        os.close(ready_r)
        os.close(release_w)
        os.dup2(release_r, 0)
        with tempfile.TemporaryDirectory(prefix='proc-inspection-') as directory:
            os.mkfifo(directory + '/fifo', 0o600)
            left, right = socket.socketpair()
            descriptors = [0, os.open('/dev/null', os.O_RDWR),
                           os.open(directory + '/file', os.O_CREAT | os.O_RDWR, 0o600),
                           os.open(directory + '/fifo', os.O_RDWR), left.fileno()]
            expected = {str(fd): os.readlink(f'/proc/self/fd/{fd}') for fd in descriptors}
            assert expected['0'].startswith('pipe:['), expected
            with open('/proc/self/stat') as stream:
                expected['start_time'] = stream.read().rsplit(')', 1)[1].split()[19]
            with open('/proc/self/cgroup') as stream:
                expected['cgroup'] = stream.read()
            assert libc.prctl(4, dumpable, 0, 0, 0) == 0
            observed = set(os.listdir('/proc/self/fd'))
            assert all(str(fd) in observed for fd in descriptors), observed
            if busy:
                os.set_blocking(release_r, False)
            if fifo:
                os.mkfifo(directory + '/release', 0o600)
                expected['release_fifo'] = directory + '/release'
            os.write(ready_w, json.dumps(expected).encode())
            expected.pop('start_time')
            expected.pop('cgroup')
            if fifo:
                with open(expected.pop('release_fifo'), 'rb', buffering=0) as stream:
                    assert stream.read(1) == b'F'
            while True:
                try:
                    assert os.read(release_r, 1) == b'Q'
                    break
                except BlockingIOError:
                    os.stat(directory + '/file')
                    fd = os.open(directory + '/file', os.O_RDONLY)
                    os.close(fd)
                    with open('/proc/self/status') as status_file:
                        assert 'Pid:' in status_file.read()
            assert libc.prctl(3, 0, 0, 0, 0) == dumpable
            for fd, path in expected.items():
                assert os.readlink(f'/proc/self/fd/{fd}') == path
            for fd in descriptors[1:4]:
                os.close(fd)
            left.close()
            right.close()
        os._exit(0)
    os.close(ready_w)
    os.close(release_r)
    release_fifo = None
    try:
        expected = json.loads(os.read(ready_r, 4096))
        release_fifo = expected.pop('release_fifo', None)
        start_time = expected.pop('start_time')
        cgroup = expected.pop('cgroup')
        base = f'/proc/{child}'
        print(f'List process dumpable={dumpable}', flush=True)
        observed = set(os.listdir(base))
        assert {'status', 'stat', 'cgroup', 'uid_map'} <= observed, observed
        print(f'Open descriptor directory dumpable={dumpable}', flush=True)
        directory = os.open(base + '/fd', os.O_RDONLY | os.O_DIRECTORY)
        try:
            print(f'List descriptor directory dumpable={dumpable}', flush=True)
            observed = set(os.listdir(directory))
            assert all(fd in observed for fd in expected), observed
        finally:
            os.close(directory)
        for suffix in ('cgroup', 'uid_map', 'gid_map', 'setgroups'):
            with open(base + '/' + suffix) as stream:
                value = stream.read()
            assert value, (dumpable, suffix)
            if suffix == 'cgroup':
                assert value == cgroup, (value, cgroup)
        pinned = os.open(base + '/stat', os.O_PATH | os.O_CLOEXEC)
        try:
            with open(f'/proc/self/fd/{pinned}') as stream:
                data = stream.read()
            assert data and data.rsplit(')', 1)[1].split()[19] == start_time, (
                'reopened proc stat', dumpable, data)
        finally:
            os.close(pinned)
        proc = os.open('/proc', os.O_PATH | os.O_DIRECTORY)
        try:
            how = (ctypes.c_uint64 * 3)(os.O_RDONLY | os.O_CLOEXEC, 0, 0xb)
            descriptor = libc.syscall(ctypes.c_long(437), ctypes.c_int(proc),
                ctypes.c_char_p(f'{child}/stat'.encode()), ctypes.byref(how),
                ctypes.c_size_t(ctypes.sizeof(how)))
            assert descriptor >= 0, ('cold scoped stat', dumpable, ctypes.get_errno())
            with os.fdopen(descriptor) as stream:
                assert stream.read().rsplit(')', 1)[1].split()[19] == start_time
        finally:
            os.close(proc)
        for suffix, predicate in (('', stat.S_ISDIR), ('/fd', stat.S_ISDIR),
                                  ('/fd/0', stat.S_ISLNK), ('/status', stat.S_ISREG)):
            print(f'Inspect dumpable={dumpable} path={base + suffix}', flush=True)
            assert predicate(os.lstat(base + suffix).st_mode)
        for iteration in range(16 if busy or fifo else 1):
            for fd, path in expected.items():
                if not iteration:
                    print(f'Readlink dumpable={dumpable} fd={fd} expected={path}', flush=True)
                actual = os.readlink(base + '/fd/' + fd)
                assert actual == path, (actual, path)
        with open(base + '/status') as status_file:
            status_text = status_file.read()
        assert f'Pid:\t{child}\n' in status_text, status_text
        assert 'Uid:\t0\t0\t0\t0\n' in status_text, status_text
        with open(base + '/stat') as stream:
            fields = stream.read().rsplit(')', 1)[1].split()
        assert fields[19] == start_time and fields[0] not in ('Z', 'X'), fields
        for flags in (os.O_PATH, os.O_RDONLY):
            print(f'Inspect relative dumpable={dumpable} flags={flags}', flush=True)
            directory = os.open(base, flags | os.O_DIRECTORY)
            try:
                assert stat.S_ISDIR(os.fstat(directory).st_mode)
                same = os.open('.', flags | os.O_DIRECTORY, dir_fd=directory)
                try:
                    assert os.fstat(same).st_ino == os.fstat(directory).st_ino
                    if not flags & os.O_PATH:
                        assert {'status', 'stat', 'cgroup', 'uid_map'} <= set(os.listdir(same))
                finally:
                    os.close(same)
                with os.fdopen(os.open('stat', os.O_RDONLY, dir_fd=directory)) as stream:
                    fields = stream.read().rsplit(')', 1)[1].split()
                assert fields[19] == start_time and fields[0] not in ('Z', 'X'), fields
            finally:
                os.close(directory)
        print(f'PASS protected proc dumpable={dumpable} busy={busy} fifo={fifo}', flush=True)
    finally:
        if fifo and release_fifo:
            with open(release_fifo, 'wb', buffering=0) as stream:
                assert stream.write(b'F') == 1
        try:
            os.write(release_w, b'Q')
        except BrokenPipeError:
            pass
        os.close(release_w)
        os.close(ready_r)
        pid, status = os.waitpid(child, 0)
        assert pid == child and status == 0, status

child = os.fork()
if not child:
    os.setgroups([])
    os.setgid(65534)
    os.setuid(65534)
    for flags in (os.O_RDONLY, os.O_PATH | os.O_NOATIME):
        descriptor = os.open('/proc/version', flags)
        os.close(descriptor)
    try:
        descriptor = os.open('/proc/version', os.O_RDONLY | os.O_NOATIME)
    except OSError as error:
        assert error.errno == errno.EPERM, error
    else:
        os.close(descriptor)
        raise AssertionError('non-owner O_NOATIME admitted on /proc/version')
    os._exit(0)
pid, status = os.waitpid(child, 0)
assert pid == child and status == 0, status
print('PASS proc/version guest ownership and O_NOATIME', flush=True)
