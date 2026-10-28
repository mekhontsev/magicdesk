#!/usr/bin/env python3
"""Classic socket filters on guest netlink queues, including OFD sharing."""
import argparse
import ctypes
import errno
import fcntl
import os
from pathlib import Path
import socket
import struct
import sys


def guest():
    libc = ctypes.CDLL(None, use_errno=True)

    class Instruction(ctypes.Structure):
        _fields_ = [('code', ctypes.c_ushort), ('jt', ctypes.c_ubyte),
                    ('jf', ctypes.c_ubyte), ('k', ctypes.c_uint)]

    class Program(ctypes.Structure):
        _fields_ = [('len', ctypes.c_ushort), ('filter', ctypes.POINTER(Instruction))]

    def install(fd, code, expected=0):
        instructions = (Instruction * len(code))(*(Instruction(*i) for i in code))
        program = Program(len(code), instructions)
        result = libc.setsockopt(fd, socket.SOL_SOCKET, 26, ctypes.byref(program), ctypes.sizeof(program))
        assert result == (-1 if expected else 0), (result, ctypes.get_errno())
        if expected:
            assert ctypes.get_errno() == expected, ctypes.get_errno()

    def read_filter(fd, capacity=0, expected=0):
        length = ctypes.c_uint(capacity)
        code = (Instruction * max(capacity, 1))()
        result = libc.getsockopt(fd, socket.SOL_SOCKET, 26, code, ctypes.byref(length))
        assert result == (-1 if expected else 0), (result, ctypes.get_errno())
        if expected:
            assert ctypes.get_errno() == expected
            return
        return length.value, bytes(code)[:length.value * ctypes.sizeof(Instruction)]

    def missing(receiver):
        try:
            receiver.recv(4096, socket.MSG_DONTWAIT)
        except BlockingIOError:
            return
        raise AssertionError('filtered packet was delivered')

    # BPF byte loads address the netlink header, not the private transport.
    code = [(0x80, 0, 0, 0), (0x15, 0, 3, 32),
            (0x30, 0, 0, 4), (0x15, 0, 1, 18), (0x06, 0, 0, 7), (0x06, 0, 0, 0)]
    with socket.socket(socket.AF_NETLINK, socket.SOCK_RAW, 0) as sender, \
            socket.socket(socket.AF_NETLINK, socket.SOCK_RAW, 0) as receiver:
        sender.bind((0, 0))
        receiver.bind((0, 1))
        alias = os.dup(receiver.fileno())
        try:
            install(alias, code)
            assert read_filter(receiver.fileno())[0] == len(code)
            count, original = read_filter(receiver.fileno(), len(code))
            assert count == len(code) and original == b''.join(struct.pack('=HBBI', *i) for i in code)
            read_filter(alias, len(code) - 1, errno.EINVAL)
            request = struct.pack('=IHHII', 32, 18, 0x301, 71, 0) + bytes(16)

            def send():
                assert sender.sendto(request, (0, 1)) == len(request)
                while True:
                    response = sender.recv(65536)
                    offset = 0
                    done = False
                    while offset < len(response):
                        size, kind = struct.unpack_from('=IH', response, offset)
                        done |= kind == 3
                        offset += (size + 3) & ~3
                    if done:
                        return

            send()
            queued = fcntl.ioctl(receiver.fileno(), 0x541b, bytes(4))
            assert struct.unpack('=I', queued)[0] == 7
            payload, source = receiver.recvfrom(4096)
            assert payload == request[:7] and source == (sender.getsockname()[0], 1)
            send()
            payload, control, flags, source = receiver.recvmsg(4096)
            assert payload == request[:7] and source == (sender.getsockname()[0], 1)
            assert not control and not flags
            # A rejected replacement must not remove the existing filter.
            install(alias, [(0x60, 0, 0, 0), (0x16, 0, 0, 0)], errno.EINVAL)
            assert read_filter(alias)[0] == len(code)
            child = os.fork()
            if not child:
                install(alias, [(0x06, 0, 0, 0)])
                os._exit(0)
            assert os.waitpid(child, 0) == (child, 0)
            send()
            missing(receiver)
            receiver.setsockopt(socket.SOL_SOCKET, 27, 0)
            assert read_filter(alias)[0] == 0
            send()
            assert receiver.recv(4096) == request
            install(alias, code)
            receiver.setsockopt(socket.SOL_SOCKET, 44, 1)
            assert receiver.getsockopt(socket.SOL_SOCKET, 44) == 1
            install(alias, code, errno.EPERM)
            try:
                receiver.setsockopt(socket.SOL_SOCKET, 27, 0)
            except OSError as error:
                assert error.errno == errno.EPERM
            else:
                raise AssertionError('locked filter detached')
        finally:
            os.close(alias)
    print('PASS netlink BPF: filtering, truncation, original query, dup/fork, detach, replacement and lock', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('store')
    args = parser.parse_args()
    from test_oci_services import Suite
    suite = Suite(args.build, args.build)
    target = '/tmp/md-netlink-filter-' + suite.report['id']
    try:
        suite.transport.upload(suite.client, Path(__file__), suite.base + '/test.py')
        suite.command(suite.guest(args.store, '--user', '0:0', '--', '/bin/mkdir', target))
        suite.command(suite.guest(args.store, '--user', '0:0', '--network', 'bridge',
                                 '--bind-ro', suite.base, target, '--deadline-seconds', '60', '--',
                                 '/usr/bin/python3', target + '/test.py', '--guest'), timeout=80)
        suite.report['passed'] = True
    finally:
        try:
            suite.command(suite.guest(args.store, '--user', '0:0', '--', '/bin/rmdir', target))
        finally:
            suite.finish()


if __name__ == '__main__':
    guest() if sys.argv[1:] == ['--guest'] else main()
