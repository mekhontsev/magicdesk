#!/usr/bin/env python3
"""Anonymous descriptor ownership and lifetime under the real shell executor."""
import argparse
import array
import ctypes
import errno
import fcntl
import json
import os
from pathlib import Path
import select
import socket
import stat
import subprocess
import sys
import tempfile
import termios
from contextlib import ExitStack


def inherited(cases):
    for fd, kept, mode, address in cases:
        if not kept:
            try:
                fcntl.fcntl(fd, fcntl.F_GETFD)
            except OSError as error:
                assert error.errno == errno.EBADF, error
            else:
                raise AssertionError(('CLOEXEC descriptor survived', fd))
            continue
        assert fcntl.fcntl(fd, fcntl.F_GETFD) == 0
        assert stat.S_IFMT(os.fstat(fd).st_mode) == mode
        if address is not None:
            family, kind, expected = address
            with socket.socket(family, kind, fileno=fd) as sock:
                assert list(sock.getsockname()) == expected
        else:
            os.close(fd)


def inheritability(host_network=False):
    with ExitStack() as cleanup:
        regular = cleanup.enter_context(tempfile.TemporaryFile())
        descriptors = [('file', regular.fileno(), None)]
        for fd in os.pipe2(os.O_CLOEXEC):
            cleanup.callback(os.close, fd)
            descriptors.append(('pipe', fd, None))
        sockets = [(socket.AF_UNIX, socket.SOCK_STREAM), (socket.AF_INET, socket.SOCK_STREAM),
                   (socket.AF_INET6, socket.SOCK_DGRAM)]
        # Android can deny native netlink ioctls; the managed provider must support them.
        if not host_network:
            sockets.append((socket.AF_NETLINK, socket.SOCK_RAW))
        for family, kind in sockets:
            sock = cleanup.enter_context(socket.socket(family, kind))
            for keep in (True, False):
                sock.set_inheritable(keep)
                assert sock.get_inheritable() == keep
            if family in (socket.AF_INET, socket.AF_INET6):
                sock.bind(('127.0.0.1' if family == socket.AF_INET else '::1', 0))
            address = None if family == socket.AF_UNIX else (family, kind, list(sock.getsockname()))
            descriptors.append(('socket-' + str(family), sock.fileno(), address))
        proc = os.open('/proc/self/uid_map', os.O_RDONLY | os.O_CLOEXEC)
        cleanup.callback(os.close, proc)
        descriptors.append(('proc', proc, None))
        epoll = cleanup.enter_context(select.epoll())
        descriptors.append(('epoll', epoll.fileno(), None))
        event = os.eventfd(0, os.EFD_CLOEXEC)
        cleanup.callback(os.close, event)
        descriptors.append(('eventfd', event, None))
        cases = []
        for name, fd, address in descriptors:
            for command, expected in ((termios.FIONCLEX, 0), (termios.FIOCLEX, fcntl.FD_CLOEXEC)) * 2:
                assert fcntl.ioctl(fd, command) == 0
                assert fcntl.fcntl(fd, fcntl.F_GETFD) == expected, name
            for keep in (True, False):
                os.set_inheritable(fd, keep)
                assert os.get_inheritable(fd) == keep, name
                copy = fcntl.fcntl(fd, fcntl.F_DUPFD_CLOEXEC, 200)
                cleanup.callback(os.close, copy)
                assert fcntl.ioctl(copy, termios.FIONCLEX if keep else termios.FIOCLEX) == 0
                cases.append((copy, keep, stat.S_IFMT(os.fstat(fd).st_mode), address))
            print('PASS descriptor inheritability:', name, flush=True)
        path = os.open('/proc/self', os.O_PATH | os.O_CLOEXEC)
        cleanup.callback(os.close, path)
        closed = os.dup(regular.fileno())
        os.close(closed)
        for fd in (closed, path):
            for command in (termios.FIOCLEX, termios.FIONCLEX):
                try:
                    fcntl.ioctl(fd, command)
                except OSError as error:
                    assert error.errno == errno.EBADF, error
                else:
                    raise AssertionError(('invalid ioctl succeeded', fd, command))
        child = os.fork()
        if not child:
            os.execv(sys.executable, [sys.executable, __file__, '--inherited', json.dumps(cases)])
        assert os.waitpid(child, 0)[1] == 0
        print('PASS FIOCLEX/FIONCLEX: kernel flags, Python API, dup, exec and retained object identity', flush=True)


def guest():
    def metadata(fd):
        value = os.fstat(fd)
        assert stat.S_ISFIFO(value.st_mode), value
        return value.st_uid, value.st_gid, stat.S_IMODE(value.st_mode)

    def fails(code, action):
        try:
            action()
        except OSError as error:
            assert error.errno == code, error
        else:
            raise AssertionError('operation unexpectedly succeeded')

    r, w = os.pipe2(os.O_CLOEXEC)
    assert metadata(r) == (0, 0, 0o600)
    os.fchown(w, 1234, 2345)
    os.fchmod(r, 0o620)
    alias = '/proc/self/fd/' + str(w)
    value = os.stat(alias)
    assert (value.st_uid, value.st_gid, stat.S_IMODE(value.st_mode)) == (1234, 2345, 0o620)
    os.chown(alias, 1234, 2345)
    os.chmod(alias, 0o620)
    reopened = os.open(alias, os.O_WRONLY | os.O_CLOEXEC)
    assert metadata(reopened) == metadata(r)
    os.fchmod(reopened, 0o640)
    assert metadata(w) == (1234, 2345, 0o640)
    os.fchmod(reopened, 0o620)
    os.close(reopened)
    path = os.open(alias, os.O_PATH | os.O_CLOEXEC)
    assert metadata(path) == metadata(r)
    fails(errno.EBADF, lambda: os.fchmod(path, 0o777))
    os.close(path)
    duplicate = os.dup(w)
    assert metadata(duplicate) == metadata(r) == (1234, 2345, 0o620)
    child = os.fork()
    if not child:
        try:
            os.fchown(duplicate, 42, 42)
            os._exit(0)
        except BaseException:
            os._exit(1)
    assert os.waitpid(child, 0)[1] == 0
    assert metadata(r) == (42, 42, 0o620)
    subprocess.run([sys.executable, '-c',
                    'import os; s=os.fstat(' + str(duplicate) + '); assert (s.st_uid,s.st_gid)==(42,42)'],
                   pass_fds=(duplicate,), check=True, timeout=15)
    sender, receiver = socket.socketpair()
    sender.sendmsg([b'p'], [(socket.SOL_SOCKET, socket.SCM_RIGHTS, array.array('i', [r, w]))])
    _, ancillary, _, _ = receiver.recvmsg(1, socket.CMSG_SPACE(8))
    received = array.array('i')
    received.frombytes(ancillary[0][2])
    assert len(received) == 2
    os.fchown(received[0], 51, 52)
    assert metadata(received[1]) == metadata(r) == (51, 52, 0o620)
    for fd in received:
        os.close(fd)
    sender.close()
    receiver.close()
    child = os.fork()
    if not child:
        try:
            os.setgroups([])
            os.setgid(99)
            os.setuid(99)
            fails(errno.EPERM, lambda: os.fchown(w, 99, 99))
            fails(errno.EPERM, lambda: os.fchmod(w, 0o777))
            fails(errno.EACCES, lambda: os.open(alias, os.O_WRONLY | os.O_NONBLOCK))
            cr, cw = os.pipe()
            assert metadata(cr) == (99, 99, 0o600)
            os.close(cr)
            os.close(cw)
            os._exit(0)
        except BaseException:
            os._exit(2)
    assert os.waitpid(child, 0)[1] == 0
    os.fchmod(w, 0)
    os.write(w, b'kernel-data')
    assert os.read(r, 11) == b'kernel-data'
    os.close(w)
    os.close(duplicate)
    # EVENT_WAIT: last-writer HUP; a retained data endpoint is a lifetime failure.
    poll = select.poll()
    poll.register(r, select.POLLHUP)
    assert poll.poll(3000), 'metadata owner retained a writer'
    assert os.read(r, 1) == b''
    os.close(r)
    libc = ctypes.CDLL(None, use_errno=True)
    count = len(os.listdir('/proc/self/fd'))
    assert libc.pipe2(ctypes.c_void_p(1), os.O_CLOEXEC) == -1
    assert ctypes.get_errno() == errno.EFAULT
    assert len(os.listdir('/proc/self/fd')) == count
    for _ in range(64):
        r, w = os.pipe()
        assert metadata(r) == (0, 0, 0o600)
        os.close(r)
        os.close(w)
    assert len(os.listdir('/proc/self/fd')) == count
    print('PASS anonymous ownership, dup/fork/exec/SCM_RIGHTS, permissions, EOF and failed publication', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('store')
    parser.add_argument('--network', choices=('host', 'routed'), default='routed')
    from test_oci_services import add_machine_entry_argument
    from kernel_profile import KernelProfile
    add_machine_entry_argument(parser)
    KernelProfile.add_argument(parser)
    args = parser.parse_args()
    from test_oci_services import Server, Suite
    suite = Suite(args.build, args.build, machine_entry=args.machine_entry, kernel_profile=args.kernel_profile)
    target = '/tmp/md-descriptor-' + suite.report['id']
    prefix = [suite.runner, '--store', args.store, '--user', '0:0', '--network', args.network,
              *suite.entry_options, '--deadline-seconds', '60']
    try:
        suite.upload(Path(__file__), suite.base + '/fixture.py')
        suite.command(prefix + ['--', '/bin/mkdir', target])
        run = prefix + ['--bind-ro', suite.base, target, '--']
        for namespace in ([], ['/usr/bin/unshare', '--user', '--map-users=0:0:65536',
                              '--map-groups=0:0:65536', '--mount']):
            suite.command(run + namespace + ['/usr/bin/python3', target + '/fixture.py', '--inheritability']
                          + (['--host-network'] if args.network == 'host' else []))
            suite.command(run + namespace + ['/usr/bin/python3', target + '/fixture.py', '--guest'])
        endpoint = target + '.sock'
        server = Server(suite, run + ['/usr/bin/python3', target + '/fixture.py', '--server', endpoint])
        try:
            server.ready('READY anonymous descriptor sender')
            suite.command(run + ['/usr/bin/python3', target + '/fixture.py', '--client', endpoint])
            server.completed()
        finally:
            server.close()
        suite.report['passed'] = True
    finally:
        try:
            suite.command(prefix + ['--', '/bin/rmdir', target])
        finally:
            suite.finish()


def transfer(role, path):
    if role == '--server':
        listener = socket.socket(socket.AF_UNIX)
        listener.settimeout(20)
        listener.bind(path)
        listener.listen(1)
        r, w = os.pipe()
        os.fchown(w, 123, 124)
        os.fchmod(w, 0o640)
        print('READY anonymous descriptor sender', flush=True)
        peer, _ = listener.accept()
        peer.settimeout(20)
        peer.sendmsg([b'p'], [(socket.SOL_SOCKET, socket.SCM_RIGHTS, array.array('i', [r, w]))])
        assert peer.recv(1) == b'c'
        value = os.fstat(r)
        assert (value.st_uid, value.st_gid) == (125, 126)
        os.close(r)
        os.close(w)
        peer.close()
        listener.close()
        os.unlink(path)
    else:
        peer = socket.socket(socket.AF_UNIX)
        peer.settimeout(20)
        peer.connect(path)
        _, control, flags, _ = peer.recvmsg(1, socket.CMSG_SPACE(8))
        assert not flags & socket.MSG_CTRUNC
        fds = array.array('i')
        fds.frombytes(control[0][2])
        assert len(fds) == 2
        r, w = fds
        value = os.fstat(r)
        assert (value.st_uid, value.st_gid, stat.S_IMODE(value.st_mode)) == (123, 124, 0o640)
        os.fchown(w, 125, 126)
        peer.sendall(b'c')
        # EVENT_WAIT: sender closes its connection; socket timeout fails the test.
        assert peer.recv(1) == b''
        peer.close()
        os.close(w)
        poll = select.poll()
        poll.register(r, select.POLLHUP)
        assert poll.poll(3000), 'portable metadata retained a writer'
        assert os.read(r, 1) == b''
        value = os.fstat(r)
        assert (value.st_uid, value.st_gid) == (125, 126)
        os.close(r)
        print('PASS independent launches: live anonymous metadata and final writer release', flush=True)


if __name__ == '__main__':
    if '--inheritability' in sys.argv:
        inheritability('--host-network' in sys.argv)
    elif len(sys.argv) > 1 and sys.argv[1] == '--inherited':
        inherited(json.loads(sys.argv[2]))
    elif len(sys.argv) > 1 and sys.argv[1] in ('--server', '--client'):
        transfer(sys.argv[1], sys.argv[2])
    else:
        guest() if '--guest' in sys.argv else main()
