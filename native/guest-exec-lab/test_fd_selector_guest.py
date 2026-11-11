#!/usr/bin/env python3
"""Descriptor dispatch follows the current object, not stacked filter order."""
import ctypes
import errno
import fcntl
import os
import select
import socket
import struct
import sys
import tempfile
import threading
import traceback

libc = ctypes.CDLL(None, use_errno=True)


def checked(result):
    if result < 0:
        raise OSError(ctypes.get_errno(), os.strerror(ctypes.get_errno()))
    return result


def proc(slot=None):
    fd = os.open('/proc/self/stat', os.O_RDONLY | os.O_CLOEXEC)
    try:
        assert slot is None or fd == slot, (fd, slot)
        expected = str(os.getpid()).encode() + b' ('
        data = os.read(fd, 4096)
        assert data.startswith(expected), (fd, data)
        assert os.lseek(fd, 0, os.SEEK_SET) == 0
        buffers = [bytearray(8), bytearray(4096)]
        length = os.readv(fd, buffers)
        assert b''.join(buffers)[:length].startswith(expected)
        assert os.pread(fd, 4096, 0).startswith(expected)
        try:
            fcntl.ioctl(fd, 0x5401, bytearray(64))  # TCGETS requires a terminal.
        except OSError as error:
            assert error.errno == errno.ENOTTY, error
        else:
            raise AssertionError('proc carrier exposed through ioctl')
    finally:
        os.close(fd)
    return fd


def watch(slot=None):
    fd = checked(libc.inotify_init1(os.O_NONBLOCK | os.O_CLOEXEC))
    try:
        assert slot is None or fd == slot, (fd, slot)
        with tempfile.TemporaryDirectory() as directory:
            wd = checked(libc.inotify_add_watch(fd, os.fsencode(directory), 0x100))
            created = os.open(directory + '/created', os.O_WRONLY | os.O_CREAT, 0o600)
            os.close(created)
            # EVENT_WAIT: kernel/provider event publication, bounded by poll.
            import select
            poll = select.poll()
            poll.register(fd, select.POLLIN)
            assert poll.poll(2000), 'inotify creation event missing'
            available = bytearray(4)
            fcntl.ioctl(fd, 0x541B, available)  # FIONREAD on the actual watch queue.
            assert struct.unpack('i', available)[0] >= 16
            data = os.read(fd, 4096)
            found, mask, _, _ = struct.unpack_from('iIII', data)
            assert found == wd and mask & 0x100, data
            assert b'created\0' in data, data
    finally:
        os.close(fd)
    return fd


def ordinary(slot):
    read, write = os.pipe()
    try:
        assert read == slot, (read, slot)
        assert os.write(write, b'native') == 6
        assert os.read(read, 6) == b'native'
    finally:
        os.close(read)
        os.close(write)


def child(call):
    pid = os.fork()
    if pid == 0:
        try:
            call()
        except BaseException:
            traceback.print_exc()
            os._exit(1)
        os._exit(0)
    assert os.waitpid(pid, 0)[1] == 0


def ordered(first, second):
    slot = first()
    second(slot)
    for _ in range(3):
        proc(slot)
        watch(slot)
        ordinary(slot)
    child(lambda: proc(slot))
    child(lambda: os.execv(sys.executable, [sys.executable, __file__, '--inherited', str(slot)]))
    print('PASS descriptor selector order:', first.__name__, second.__name__, flush=True)


def rejected_write(fd, expected):
    try:
        os.write(fd, b'x')  # Invalid provider request, not a byte for its carrier.
    except OSError as error:
        assert error.errno == expected, error
    else:
        raise AssertionError(f'typed descriptor {fd} lost write dispatch')


def netlink(fd):
    rejected_write(fd, errno.EINVAL)
    for datagram in (False, True):
        netlink_exchange(fd, datagram)


def netlink_exchange(fd, datagram):
    request = struct.pack('=IHHII', 32, 18, 0x301, 42, 0) + bytes(16)
    # Addressless send/receive are native for ordinary sockets, but retain
    # the same provider ownership as read/write for a virtual control socket.
    if datagram:
        data = ctypes.create_string_buffer(request)
        assert checked(libc.syscall(206, fd, data, len(request), socket.MSG_DONTWAIT, 0, 0)) == len(request)
    else:
        assert os.write(fd, request) == len(request)
    poll = select.poll()
    poll.register(fd, select.POLLIN)
    while True:
        # EVENT_WAIT: netlink dump completion; missing response is failure.
        assert poll.poll(2000), 'typed netlink response missing'
        if datagram:
            data = ctypes.create_string_buffer(65536)
            length = checked(libc.syscall(207, fd, data, len(data), socket.MSG_DONTWAIT, 0, 0))
            packet = data.raw[:length]
        else:
            packet = os.read(fd, 65536)
        offset = 0
        while offset < len(packet):
            size, kind, _, seq, _ = struct.unpack_from('=IHHII', packet, offset)
            assert 16 <= size <= len(packet) - offset and seq == 42, packet
            if kind == 3:  # NLMSG_DONE
                return
            assert kind == 16, packet  # RTM_NEWLINK
            offset += (size + 3) & ~3


def duplicates(source, check, label):
    check(source.fileno())
    for operation in ('dup3', 'dup', 'fcntl', 'fcntl-cloexec', 'fcntl-wide', 'fcntl-cloexec-wide'):
        def exercise():
            cloexec = operation == 'dup3' or 'cloexec' in operation
            if operation == 'dup3':
                alias = checked(libc.dup3(source.fileno(), 190, os.O_CLOEXEC))
            elif operation == 'dup':
                alias = checked(libc.dup(source.fileno()))
            else:
                command = fcntl.F_DUPFD_CLOEXEC if cloexec else fcntl.F_DUPFD
                if operation.endswith('-wide'):
                    alias = checked(libc.syscall(25, source.fileno(), ctypes.c_ulong((1 << 32) | command), 193))
                else:
                    alias = fcntl.fcntl(source.fileno(), command, 191 if not cloexec else 192)
            try:
                assert bool(fcntl.fcntl(alias, fcntl.F_GETFD) & fcntl.FD_CLOEXEC) == cloexec
                source.close()
                check(alias)
                child(lambda: check(alias))
            finally:
                os.close(alias)
        # Each case starts without selector classes admitted by a preceding dup.
        child(exercise)
        print('PASS typed descriptor duplicate:', label, operation, flush=True)


def typed_duplicates():
    checked(libc.unshare(0x40000000))  # Private logical network namespace.
    with socket.socket(socket.AF_NETLINK, socket.SOCK_RAW | socket.SOCK_NONBLOCK, 0) as source:
        duplicates(source, netlink, 'route')


def selector_budget():
    source = os.open('/proc/self/stat', os.O_RDONLY | os.O_CLOEXEC)
    try:
        expected = str(os.getpid()).encode() + b' ('
        for slot in range(512, 1024):
            alias = checked(libc.dup3(source, slot, os.O_CLOEXEC))
            try:
                assert os.pread(alias, 4096, 0).startswith(expected)
            finally:
                os.close(alias)
        watch()
        child(lambda: proc())
    finally:
        os.close(source)
    print('PASS selector instruction budget: all FD buckets, watch and fork', flush=True)


def duplicate_errors():
    class Filter(ctypes.Structure):
        _fields_ = [('code', ctypes.c_ushort), ('jt', ctypes.c_ubyte),
                   ('jf', ctypes.c_ubyte), ('k', ctypes.c_uint)]

    class Program(ctypes.Structure):
        _fields_ = [('length', ctypes.c_ushort), ('filters', ctypes.POINTER(Filter))]

    watched = checked(libc.inotify_init1(os.O_NONBLOCK | os.O_CLOEXEC))
    target = checked(libc.open(b'/dev/null', os.O_RDONLY | os.O_CLOEXEC))
    checked(libc.dup3(target, 190, os.O_CLOEXEC))
    identity = os.fstat(190)
    for nr, first, second in ((24, 190, os.O_NONBLOCK), (25, fcntl.F_DUPFD_CLOEXEC, -1)):
        ctypes.set_errno(0)
        outcome = libc.syscall(nr, watched, first, second)
        error = ctypes.get_errno()
        assert outcome == -1 and error == errno.EINVAL, (nr, outcome, error)
        current = os.fstat(190)
        assert (current.st_dev, current.st_ino) == (identity.st_dev, identity.st_ino)
    # Sandbox preparation already admits PROC/WATCH dispatch. Later duplicates
    # must reuse it without relaxing this real denial of further seccomp calls.
    code = (Filter * 4)(Filter(0x20, 0, 0, 0), Filter(0x15, 0, 1, 277),
                        Filter(0x06, 0, 0, 0x50000 | errno.EPERM), Filter(0x06, 0, 0, 0x7FFF0000))
    program = Program(len(code), code)
    checked(libc.prctl(38, 1, 0, 0, 0))
    checked(libc.syscall(277, 1, 0, ctypes.byref(program)))
    assert libc.syscall(277, 1, 0, ctypes.byref(program)) == -1 and ctypes.get_errno() == errno.EPERM
    for nr, first, second in ((24, 190, os.O_CLOEXEC), (25, fcntl.F_DUPFD_CLOEXEC, 191)):
        alias = checked(libc.syscall(nr, watched, first, second))
        assert alias == (190 if nr == 24 else 191)
        assert fcntl.fcntl(alias, fcntl.F_GETFD) & fcntl.FD_CLOEXEC
        available = bytearray(4)
        fcntl.ioctl(alias, 0x541B, available)
        assert struct.unpack('i', available)[0] == 0
    for fd in (watched, target, 190, 191):
        os.close(fd)
    print('PASS duplicate errors: unchanged target, released table, prepared selectors retain seccomp denial', flush=True)


def duplicate_race():
    # Threads share the actual kernel FD table. The duplicated description can
    # be either source, but must never expose a typed file's empty carrier.
    typed = os.open('/proc/self/stat', os.O_RDONLY | os.O_CLOEXEC)
    with tempfile.TemporaryFile() as native:
        native.write(b'native-source')
        native.flush()
        checked(libc.dup3(native.fileno(), 180, os.O_CLOEXEC))
        gate = threading.Barrier(2)
        failures = []

        def replace():
            try:
                gate.wait()
                for iteration in range(160):
                    source = typed if iteration % 2 else native.fileno()
                    checked(libc.dup3(source, 180, os.O_CLOEXEC))
            except BaseException as error:
                failures.append(error)

        worker = threading.Thread(target=replace)
        worker.start()
        gate.wait()
        try:
            for iteration in range(160):
                alias = checked(libc.dup3(180, 192 + iteration, os.O_CLOEXEC))
                try:
                    data = os.pread(alias, 4096, 0)
                    assert data == b'native-source' or data.startswith(str(os.getpid()).encode() + b' ('), data
                finally:
                    os.close(alias)
        finally:
            worker.join(10)
            assert not worker.is_alive(), 'shared-table duplication stalled'
            os.close(180)
            os.close(typed)
        assert not failures, failures
    print('PASS duplicate shared-table race: replacement, typed/native identity and selector publication', flush=True)


def shared_table_groups(protected):
    # Separate address spaces/thread groups, one actual CLONE_FILES table.
    # No Python threads are live here; the child leaves through _exit only.
    ready = os.pipe()
    go = os.pipe()
    parent = os.getpid()
    stack = ctypes.create_string_buffer(1024 * 1024)
    callback_type = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.c_void_p)

    @callback_type
    def reader(_):
        try:
            if protected:
                checked(libc.prctl(4, 0, 0, 0, 0))  # PR_SET_DUMPABLE
            os.write(ready[1], b'r')
            # EVENT_WAIT: parent publishes new typed descriptors/aliases.
            assert os.read(go[0], 1) == b'g'
            assert os.pread(188, 4096, 0).startswith(str(parent).encode() + b' ('), 'foreign table owner missed selector'
            os.write(ready[1], b'd')
            assert os.read(go[0], 1) == b'x'
            os._exit(0)
        except BaseException:
            traceback.print_exc()
            os._exit(1)

    libc.clone.argtypes = (callback_type, ctypes.c_void_p, ctypes.c_int, ctypes.c_void_p)
    pid = checked(libc.clone(reader, ctypes.addressof(stack) + len(stack), 0x400 | 17, None))
    try:
        assert os.read(ready[0], 1) == b'r'
        source = os.open('/proc/self/stat', os.O_RDONLY | os.O_CLOEXEC)
        checked(libc.dup3(source, 188, os.O_CLOEXEC))
        os.close(source)
        os.write(go[1], b'g')
        poll = select.poll()
        poll.register(ready[0], select.POLLIN)
        assert poll.poll(5000), 'foreign owner admission did not complete'
        assert os.read(ready[0], 1) == b'd'
        os.write(go[1], b'x')
        assert os.waitpid(pid, 0)[1] == 0
        pid = 0
    finally:
        if pid:
            os.kill(pid, 9)
            os.waitpid(pid, 0)
        for fd in (*ready, *go, 188):
            os.close(fd)
    print('PASS selector shared-table groups: new open, dup3, protected=' + str(protected), flush=True)


if __name__ == '__main__':
    if sys.argv[1:2] == ['--inherited']:
        proc(int(sys.argv[2]))
        watch(int(sys.argv[2]))
        ordinary(int(sys.argv[2]))
    elif sys.argv[1:2] == ['--budget']:
        selector_budget()
    else:
        child(selector_budget)
        child(lambda: shared_table_groups(False))
        child(lambda: shared_table_groups(True))
        child(duplicate_errors)
        child(duplicate_race)
        child(typed_duplicates)
        child(lambda: ordered(proc, watch))
        child(lambda: ordered(watch, proc))
