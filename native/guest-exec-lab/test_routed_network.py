#!/usr/bin/env python3
"""Exercise Shroot's network backend through stock Linux tools as UID 2000."""
import ctypes
import errno
import json
import os
from pathlib import Path
import select
import shutil
import socket
import struct
import subprocess
import sys

class Vector(ctypes.Structure):
    _fields_ = [('base', ctypes.c_void_p), ('length', ctypes.c_size_t)]


class Header(ctypes.Structure):
    _fields_ = [('name', ctypes.c_void_p), ('namelen', ctypes.c_uint),
               ('iov', ctypes.POINTER(Vector)), ('count', ctypes.c_size_t),
               ('control', ctypes.c_void_p), ('control_size', ctypes.c_size_t), ('flags', ctypes.c_int)]


class Message(ctypes.Structure):
    _fields_ = [('header', Header), ('length', ctypes.c_uint)]


def socket_table(name, process='self'):
    with open(f'/proc/{process}/net/{name}') as source:
        return {int(parts[9]): parts for line in source if len(parts := line.split()) >= 10 and parts[0] != 'sl'}


def check_socket_table(sock, name, connected=False):
    inode = os.fstat(sock.fileno()).st_ino
    def address(pair):
        return f'{int.from_bytes(socket.inet_aton(pair[0]), sys.byteorder):08X}:{pair[1]:04X}'
    row = socket_table(name)[inode]
    assert row[1] == address(sock.getsockname()), row
    assert row[2] == (address(sock.getpeername()) if connected else '00000000:0000'), row
    assert int(row[7]) == os.geteuid(), row
    assert socket_table(name, os.getpid())[inode] == row
    return inode


def socket_tables():
    for path in ('/proc/self', f'/proc/{os.getpid()}', f'/proc/self/task/{os.getpid()}'):
        info = os.stat(path)
        assert (info.st_uid, info.st_gid) == (os.geteuid(), os.getegid()), (path, info)
        fd = os.open(path, os.O_PATH | os.O_DIRECTORY)
        try:
            info = os.fstat(fd)
            assert (info.st_uid, info.st_gid) == (os.geteuid(), os.getegid()), (path, info)
        finally:
            os.close(fd)
    with socket.socket() as listener, socket.socket() as client:
        listener.bind(('127.0.0.1', 0))
        listener.listen(1)
        client.connect(listener.getsockname())
        accepted, _ = listener.accept()
        with accepted:
            inode = check_socket_table(listener, 'tcp')
            check_socket_table(client, 'tcp', True)
            check_socket_table(accepted, 'tcp', True)
            if shutil.which('lsof'):
                # Steam uses exactly this owner/parent lookup to admit its IPC peer.
                result = subprocess.run(['lsof', '-nP', '-F', 'upnR', '-a', '-p', str(os.getpid()),
                    '-i', f'TCP@127.0.0.1:{client.getsockname()[1]}'],
                    capture_output=True, text=True, timeout=30)
                assert result.returncode == 0, result
                assert f'p{os.getpid()}\n' in result.stdout and f'u{os.geteuid()}\n' in result.stdout, result.stdout
            result = subprocess.run(['unshare', '-n', 'cat', '/proc/net/tcp'],
                                    capture_output=True, text=True, timeout=15)
            assert result.returncode == 0 and len(result.stdout.splitlines()) == 1, result
        with open('/proc/net/tcp') as table:
            first = table.readlines()
            table.seek(0)
            second = table.readlines()
            assert first[0] == second[0]
            # TCP timers and closing peers may advance between snapshots.
            for lines in (first, second):
                row = next(line.split() for line in lines[1:] if int(line.split()[9]) == inode)
                assert row[1] == f'0100007F:{listener.getsockname()[1]:04X}' and row[3] == '0A', row
    assert inode not in socket_table('tcp')
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
        udp.bind(('127.0.0.1', 0))
        check_socket_table(udp, 'udp')
    assert not socket_table('tcp6') and not socket_table('udp6')
    print('PASS proc socket tables: logical ports, peers, UID/inode, lsof, rewind, close and private namespace', flush=True)

def send_batch(udp, address):
    native = ctypes.create_string_buffer(struct.pack('=H', socket.AF_INET)
        + struct.pack('!H', address[1]) + socket.inet_aton(address[0]) + bytes(8))
    payloads = [ctypes.create_string_buffer(b'FIRST'), ctypes.create_string_buffer(b'SECOND')]
    vectors = [Vector(ctypes.addressof(payload), len(payload)-1) for payload in payloads]
    messages = (Message * 2)()
    for i, vector in enumerate(vectors):
        messages[i].header = Header(ctypes.addressof(native), 16, ctypes.pointer(vector), 1, None, 0, 0)
    libc = ctypes.CDLL(None, use_errno=True)
    libc.sendmmsg.argtypes = [ctypes.c_int, ctypes.POINTER(Message), ctypes.c_uint, ctypes.c_int]
    assert libc.sendmmsg(udp.fileno(), messages, 2, 0) == 2, ctypes.get_errno()
    assert [m.length for m in messages] == [5, 6]


def receive_info_without_name(udp, capacity):
    data, control = ctypes.create_string_buffer(100), ctypes.create_string_buffer(capacity)
    vector = Vector(ctypes.addressof(data), 100)
    message = Header(None, 0, ctypes.pointer(vector), 1, ctypes.addressof(control), capacity, 0)
    libc = ctypes.CDLL(None, use_errno=True)
    libc.recvmsg.argtypes = [ctypes.c_int, ctypes.POINTER(Header), ctypes.c_int]
    # EVENT_WAIT: settimeout makes the FD nonblocking. A direct libc call does
    # not execute Python socket's readiness wait; fail if no datagram arrives.
    assert select.select([udp], [], [], udp.gettimeout())[0], 'No pktinfo datagram'
    result = libc.recvmsg(udp.fileno(), ctypes.byref(message), 0)
    assert result == 7 and data.raw[:result] == b'NONAME!', (result, ctypes.get_errno())
    assert message.namelen == 0
    length, level, kind = struct.unpack_from('=Qii', control.raw)
    assert (level, kind) == (socket.IPPROTO_IP, 8)
    expected = struct.pack('=i4s4s', socket.if_nametoindex('eth0'),
                           socket.inet_aton('172.18.0.2'), socket.inet_aton('172.18.0.2'))
    assert control.raw[16:length] == expected[:length-16], (control.raw, expected)
    assert message.flags == (socket.MSG_CTRUNC if capacity < socket.CMSG_LEN(12) else 0)


def receive_batch():
    class Timespec(ctypes.Structure):
        _fields_ = [('seconds', ctypes.c_long), ('nanoseconds', ctypes.c_long)]
    libc = ctypes.CDLL(None, use_errno=True)
    libc.recvmmsg.argtypes = [ctypes.c_int, ctypes.POINTER(Message), ctypes.c_uint,
                             ctypes.c_int, ctypes.POINTER(Timespec)]
    for family, loopback, level, option, cmsg_type in (
            (socket.AF_INET, '127.0.0.1', socket.IPPROTO_IP, 8, 8),
            (socket.AF_INET6, '::1', socket.IPPROTO_IPV6, socket.IPV6_RECVPKTINFO, socket.IPV6_PKTINFO)):
        with socket.socket(family, socket.SOCK_DGRAM) as receiver, socket.socket(family, socket.SOCK_DGRAM) as sender:
            receiver.bind((loopback, 0))
            sender.bind((loopback, 0))
            receiver.setsockopt(level, option, 1)
            receiver.setblocking(False)
            count = 128
            payloads = [ctypes.create_string_buffer(16) for _ in range(count)]
            addresses = [ctypes.create_string_buffer(128) for _ in range(count)]
            controls = [ctypes.create_string_buffer(8192 if i == 0 else 256) for i in range(count)]
            vectors = [Vector(ctypes.addressof(p), len(p)) for p in payloads]
            messages = (Message * count)()

            def reset():
                for i in range(count):
                    messages[i].header = Header(ctypes.addressof(addresses[i]) if i % 2 == 0 else None,
                        128 if i % 2 == 0 else 0, ctypes.pointer(vectors[i]), 1,
                        ctypes.addressof(controls[i]), len(controls[i]), 0)
                    messages[i].length = 0xffffffff

            def send(data):
                assert sender.sendto(data, receiver.getsockname()) == len(data)

            reset()
            for i in range(count):
                send(struct.pack('=I', i))
            remaining = Timespec(1, 0)
            result = libc.recvmmsg(receiver.fileno(), messages, count, 0, ctypes.byref(remaining))
            assert result == count, (family, result, ctypes.get_errno())
            assert 0 <= remaining.seconds * 1000000000 + remaining.nanoseconds <= 1000000000
            for i, message in enumerate(messages):
                assert message.length == 4 and payloads[i].raw[:4] == struct.pack('=I', i)
                if i % 2 == 0:
                    raw = addresses[i].raw
                    assert struct.unpack_from('=H', raw)[0] == family
                    assert struct.unpack_from('!H', raw, 2)[0] == sender.getsockname()[1]
                    start, size = (4, 4) if family == socket.AF_INET else (8, 16)
                    assert socket.inet_ntop(family, raw[start:start+size]) == loopback
                else:
                    assert message.header.namelen == 0
                length, actual_level, kind = struct.unpack_from('=Qii', controls[i].raw)
                assert (actual_level, kind) == (level, cmsg_type)
                info = controls[i].raw[16:length]
                address = socket.inet_pton(family, loopback)
                expected = (struct.pack('=i', socket.if_nametoindex('lo')) + address + address
                            if family == socket.AF_INET else address + struct.pack('=i', socket.if_nametoindex('lo')))
                assert info == expected, (family, info, expected)
            reset()
            assert libc.recvmmsg(receiver.fileno(), messages, count, 0, None) == -1
            assert ctypes.get_errno() == errno.EAGAIN
            send(b'peek')
            reset()
            assert libc.recvmmsg(receiver.fileno(), messages, 1, socket.MSG_PEEK, None) == 1
            assert payloads[0].raw[:4] == b'peek'
            reset()
            vectors[0].length = 1
            assert libc.recvmmsg(receiver.fileno(), messages, 1, socket.MSG_TRUNC, None) == 1
            assert messages[0].length == 4 and messages[0].header.flags & socket.MSG_TRUNC
            vectors[0].length = 16
            reset()
            send(b'prefix')
            send(b'retained')
            messages[1].header.iov = ctypes.cast(1, ctypes.POINTER(Vector))
            assert libc.recvmmsg(receiver.fileno(), messages, 2, 0, None) == 1
            assert messages[0].length == 6 and messages[1].length == 0xffffffff
            assert receiver.getsockopt(socket.SOL_SOCKET, socket.SO_ERROR) == errno.EFAULT
            assert receiver.recv(16) == b'retained'
            reset()
            invalid = Timespec(-1, 0)
            assert libc.recvmmsg(receiver.fileno(), messages, 1, 0, ctypes.byref(invalid)) == -1
            assert ctypes.get_errno() == errno.EINVAL
            assert libc.recvmmsg(receiver.fileno(), messages, 0, 0, None) == 0
            receiver.setblocking(True)
            receiver.setsockopt(socket.SOL_SOCKET, socket.SO_RCVTIMEO, struct.pack('@ll', 2, 0))
            send(b'wait-one')
            reset()
            assert libc.recvmmsg(receiver.fileno(), messages, count, 0x10000, None) == 1  # MSG_WAITFORONE
            assert messages[0].length == 8
    print('PASS recvmmsg: IPv4/IPv6 128-packet batches, names/pktinfo, partial faults, peek/truncation, timeout and WAITFORONE', flush=True)


def datagram_lifetime():
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as receiver:
        receiver.bind(('127.0.0.1', 0))
        receiver.settimeout(5)
        address = receiver.getsockname()
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sender:
            sender.bind(('127.0.0.1', 0))
            source = sender.getsockname()
            sender.sendto(b'QUEUED', address)
            # EVENT_WAIT: datagram has reached the receiver before sender close.
            assert select.select([receiver], [], [], 5)[0], 'No queued datagram'
        assert receiver.recvfrom(100) == (b'QUEUED', source)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sender:
        sender.settimeout(5)
        for payload in (b'FIRST_OWNER', b'REBOUND_OWNER'):
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as receiver:
                receiver.settimeout(5)
                receiver.bind(address)
                sender.sendto(payload, address)
                data, peer = receiver.recvfrom(100)
                assert data == payload
                receiver.sendto(payload, peer)
                assert sender.recvfrom(100) == (payload, address)
    print('PASS private UDP lifetime: queued source after close, receiver rebind and fresh reply mapping', flush=True)


def device_binding():
    bind_index = getattr(socket, 'SO_BINDTOIFINDEX', 62)
    loopback = socket.if_nametoindex('lo')
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as server:
        assert server.getsockopt(socket.SOL_SOCKET, socket.SO_BINDTODEVICE, 16) == b''
        assert server.getsockopt(socket.SOL_SOCKET, bind_index) == 0
        try:
            server.setsockopt(socket.SOL_SOCKET, socket.SO_BINDTODEVICE, b'no-such-device')
        except OSError as error:
            assert error.errno == errno.ENODEV, error
        else:
            raise AssertionError('Missing device was accepted')
        server.setsockopt(socket.SOL_SOCKET, bind_index, loopback)
        server.setsockopt(socket.SOL_SOCKET, socket.SO_PRIORITY, 7)
        with server.dup() as shared:
            assert shared.getsockopt(socket.SOL_SOCKET, socket.SO_BINDTODEVICE, 16) == b'lo\0'
            assert shared.getsockopt(socket.SOL_SOCKET, bind_index) == loopback
            assert shared.getsockopt(socket.SOL_SOCKET, socket.SO_PRIORITY) == 7
            shared.setsockopt(socket.SOL_SOCKET, bind_index, 0)
            assert server.getsockopt(socket.SOL_SOCKET, socket.SO_BINDTODEVICE, 16) == b''
        server.setsockopt(socket.SOL_SOCKET, socket.SO_BINDTODEVICE, b'lo')
        server.bind(('0.0.0.0', 0))
        server.settimeout(5)
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client:
            client.settimeout(5)
            client.sendto(b'DEVICE', ('127.0.0.1', server.getsockname()[1]))
            data, peer = server.recvfrom(100)
            assert data == b'DEVICE'
            server.sendto(b'REPLY', peer)
            assert client.recvfrom(100)[0] == b'REPLY'
        try:
            server.sendto(b'WRONG', ('192.0.2.1', 9))
        except OSError as error:
            assert error.errno == errno.ENETUNREACH, error
        else:
            raise AssertionError('Bound socket escaped its interface')
    with socket.socket() as server, socket.socket() as client:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_BINDTODEVICE, b'lo')
        server.setsockopt(socket.SOL_SOCKET, socket.SO_PRIORITY, 7)
        server.bind(('127.0.0.1', 0))
        server.listen(1)
        server.settimeout(5)
        client.settimeout(5)
        client.connect(server.getsockname())
        accepted, _ = server.accept()
        with accepted:
            accepted.settimeout(5)
            assert accepted.getsockopt(socket.SOL_SOCKET, socket.SO_BINDTODEVICE, 16) == b'lo\0'
            assert accepted.getsockopt(socket.SOL_SOCKET, bind_index) == loopback
            assert accepted.getsockopt(socket.SOL_SOCKET, socket.SO_PRIORITY) == 7
            client.sendall(b'INHERITED')
            assert accepted.recv(100) == b'INHERITED'
            accepted.sendall(b'REPLY')
            assert client.recv(100) == b'REPLY'
    print('PASS device-bound sockets: options, dup, UDP, routing exclusion and accepted TCP inheritance', flush=True)


def legacy_route_dump():
    # Older netlink clients reuse their mutation header for a route dump.
    # Only NETLINK_GET_STRICT_CHK makes these fields dump selectors.
    body = struct.pack('=BBBBBBBBI', socket.AF_INET, 0, 0, 0, 254, 3, 0, 1, 0)
    with socket.socket(socket.AF_NETLINK, socket.SOCK_RAW, 0) as netlink:
        netlink.bind((0, 0))
        netlink.settimeout(5)
        netlink.sendto(struct.pack('=IHHII', 16 + len(body), 26, 0x301, 17, 0) + body, (0, 0))
        routes = 0
        while True:
            # EVENT_WAIT: complete multipart dump; timeout is failure.
            packet = netlink.recv(65536)
            offset = 0
            while offset < len(packet):
                length, kind, flags, sequence, port = struct.unpack_from('=IHHII', packet, offset)
                assert length >= 16 and offset + length <= len(packet) and sequence == 17
                if kind == 3:
                    assert routes >= 2 and (length == 16 or struct.unpack_from('=i', packet, offset + 16)[0] == 0)
                    print('PASS legacy IPv4 route dump with mutation-header defaults', flush=True)
                    return
                assert kind == 24, (kind, packet[offset:offset + length])
                routes += 1
                offset += (length + 3) & ~3


def guest():
    receive_batch()
    device_binding()
    legacy_route_dump()
    def run(*args):
        # EVENT_WAIT: tool completion; timeout fails a stalled netlink exchange.
        result = subprocess.run(args, capture_output=True, text=True, timeout=15)
        print(json.dumps({'argv': args, 'code': result.returncode, 'out': result.stdout,
                          'err': result.stderr}), flush=True)
        assert result.returncode == 0, args
        return result.stdout

    links = json.loads(run('ip', '-j', 'link', 'show'))
    assert {link['ifname'] for link in links} == {'lo', 'eth0'}, links
    addresses = json.loads(run('ip', '-j', 'addr', 'show'))
    assert any(info['local'] == '10.0.2.100' for link in addresses for info in link['addr_info'])
    inet_addresses = json.loads(run('ip', '-4', '-j', 'addr', 'show'))
    assert any(info['local'] == '10.0.2.100' for link in inet_addresses for info in link['addr_info'])
    routes = json.loads(run('ip', '-j', 'route', 'show'))
    assert any(route.get('gateway') == '10.0.2.2' for route in routes), routes
    lookup = json.loads(run('ip', '-j', 'route', 'get', '1.1.1.1'))
    assert lookup[0]['dev'] == 'eth0' and lookup[0]['gateway'] == '10.0.2.2', lookup
    datagram_lifetime()
    socket_tables()
    # glibc resolves the address and dumps interfaces to order its candidates.
    if '--internet' in sys.argv:
        run('timeout', '20', 'getent', 'ahostsv4', 'example.com')
    run('ip', 'link', 'add', 'br-test', 'type', 'bridge')
    run('ip', 'addr', 'add', '172.18.0.1/16', 'dev', 'br-test')
    run('ip', 'link', 'set', 'br-test', 'up')
    run('ip', 'link', 'add', 'veth-host', 'type', 'veth', 'peer', 'name', 'veth-guest')
    run('ip', 'link', 'set', 'veth-host', 'master', 'br-test')
    run('ip', 'link', 'set', 'veth-host', 'up')
    links = json.loads(run('ip', '-j', 'link', 'show'))
    assert next(link for link in links if link['ifname'] == 'veth-host')['master'] == 'br-test'
    index = next(link for link in links if link['ifname'] == 'veth-guest')['ifindex']
    to_parent, child_write = os.pipe()
    child_read, to_child = os.pipe()
    child = os.fork()
    if not child:
        try:
            os.close(to_parent)
            os.close(to_child)
            os.unshare(os.CLONE_NEWNET)
            os.write(child_write, b'R')
            assert select.select([child_read], [], [], 15)[0], 'No namespace move acknowledgement'
            assert os.read(child_read, 1) == b'M'
            run('ip', 'addr', 'add', '172.18.0.2/16', 'dev', 'eth0')
            run('ip', 'link', 'set', 'eth0', 'up')
            lookup = json.loads(run('ip', '-j', 'route', 'get', '172.18.0.1'))
            assert lookup[0]['dev'] == 'eth0' and 'gateway' not in lookup[0], lookup
            run('ip', 'route', 'add', 'default', 'via', '172.18.0.1', 'dev', 'eth0')
            run('ip', '-j', 'addr', 'show')
            with socket.socket() as listener:
                listener.settimeout(15)
                listener.bind(('0.0.0.0', 18080))
                assert listener.getsockname() == ('0.0.0.0', 18080)
                listener.listen(1)
                os.write(child_write, b'D')
                connection, peer = listener.accept()
                with connection:
                    connection.settimeout(15)
                    check_socket_table(listener, 'tcp')
                    check_socket_table(connection, 'tcp', True)
                    assert connection.recv(4) == b'PING'
                    connection.sendall(b'PONG')
                    print('TCP child peer', peer, flush=True)
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
                udp.settimeout(15)
                udp.bind(('0.0.0.0', 18080))
                os.write(child_write, b'U')
                data, peer = udp.recvfrom(100)
                assert data == b'DATAGRAM', data
                assert peer[0] == '172.18.0.1', peer
                assert udp.sendto(b'REPLY', peer) == 5
                payload, ancillary, flags, peer = udp.recvmsg(100)
                assert payload == b'MESSAGE' and not ancillary and not flags, (payload, ancillary, flags, peer)
                assert udp.sendmsg([b'RE', b'PLY'], [], 0, peer) == 5
                assert udp.recvfrom(100)[0] == b'FIRST'
                assert udp.recvfrom(100)[0] == b'SECOND'
                udp.sendto(b'BATCH', peer)
                udp.setsockopt(socket.IPPROTO_IP, 8, 1)  # IP_PKTINFO
                for capacity in (socket.CMSG_SPACE(12), socket.CMSG_LEN(5)):
                    receive_info_without_name(udp, capacity)
                    udp.sendto(b'NONAME_OK', peer)
                payload, ancillary, flags, peer = udp.recvmsg(100, socket.CMSG_SPACE(12))
                assert payload == b'PKTINFO' and not flags
                info = next(data for level, kind, data in ancillary if level == socket.IPPROTO_IP and kind == 8)
                interface, local, destination = struct.unpack('=i4s4s', info)
                assert interface == socket.if_nametoindex('eth0')
                assert local == destination == socket.inet_aton('172.18.0.2'), info
                for invalid, code in [(struct.pack('=i4s4s', 999999, local, destination), errno.ENODEV),
                                      (struct.pack('=i4s4s', 0, socket.inet_aton('203.0.113.44'), destination), errno.EADDRNOTAVAIL)]:
                    try:
                        udp.sendmsg([b'INVALID'], [(socket.IPPROTO_IP, 8, invalid)], 0, peer)
                    except OSError as error:
                        assert error.errno == code, error
                    else:
                        raise AssertionError('Invalid source accepted')
                assert udp.sendmsg([b'INFO'], [(socket.IPPROTO_IP, 8, info)], 0, peer) == 4
            assert select.select([child_read], [], [], 15)[0], 'No namespace teardown acknowledgement'
            assert os.read(child_read, 1) == b'X'
            os._exit(0)
        except BaseException:
            import traceback
            traceback.print_exc()
            os._exit(1)
    os.close(child_write)
    os.close(child_read)
    namespace = -1
    try:
        # EVENT_WAIT: child publishes its private namespace, then confirms use.
        assert select.select([to_parent], [], [], 15)[0], 'Namespace creation stalled'
        assert os.read(to_parent, 1) == b'R'
        namespace = os.open(f'/proc/{child}/ns/net', os.O_RDONLY)
        with socket.socket(socket.AF_NETLINK, socket.SOCK_RAW, 0) as netlink:
            netlink.settimeout(15)
            netlink.bind((0, 0))
            header = struct.pack('=BBHiII', 0, 0, 0, index, 0, 0)
            name = struct.pack('=HH', 9, 3) + b'eth0\0\0\0\0'
            target = struct.pack('=HHi', 8, 28, namespace)
            body = header + name + target
            netlink.sendto(struct.pack('=IHHII', 16 + len(body), 16, 5, 1, 0) + body, (0, 0))
            reply = netlink.recv(4096)
            assert struct.unpack_from('=H', reply, 4)[0] == 2, reply
            assert struct.unpack_from('=i', reply, 16)[0] == 0, reply
        os.write(to_child, b'M')
        assert select.select([to_parent], [], [], 20)[0], 'Moved interface setup stalled'
        assert os.read(to_parent, 1) == b'D'
        child_sockets = socket_table('tcp', child)
        assert any(row[1] == '00000000:46A0' for row in child_sockets.values()), child_sockets
        assert not set(child_sockets) & set(socket_table('tcp')), child_sockets
        with socket.create_connection(('172.18.0.2', 18080), timeout=15) as connection:
            assert connection.getpeername() == ('172.18.0.2', 18080)
            connection.sendall(b'PING')
            assert connection.recv(4) == b'PONG'
        print('PASS private TCP endpoint: logical bind, native stream, accepted FD and peer', flush=True)
        assert select.select([to_parent], [], [], 20)[0], 'UDP listener setup stalled'
        assert os.read(to_parent, 1) == b'U'
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
            udp.settimeout(15)
            assert udp.sendto(b'DATAGRAM', ('172.18.0.2', 18080)) == 8
            data, peer = udp.recvfrom(100)
            assert data == b'REPLY' and peer == ('172.18.0.2', 18080), (data, peer)
            assert udp.sendmsg([b'MESS', b'AGE'], [], 0, ('172.18.0.2', 18080)) == 7
            data, ancillary, flags, peer = udp.recvmsg(100)
            assert data == b'REPLY' and peer == ('172.18.0.2', 18080) and not ancillary and not flags
            send_batch(udp, ('172.18.0.2', 18080))
            assert udp.recvfrom(100)[0] == b'BATCH'
            for _ in range(2):
                udp.sendto(b'NONAME!', ('172.18.0.2', 18080))
                assert udp.recvfrom(100) == (b'NONAME_OK', ('172.18.0.2', 18080))
            udp.sendto(b'PKTINFO', ('172.18.0.2', 18080))
            reply = udp.recvfrom(100)
            assert reply == (b'INFO', ('172.18.0.2', 18080)), reply
        print('PASS private UDP endpoint: addressed datagrams and logical reply ports', flush=True)
        run('ip', 'link', 'del', 'veth-host')
        os.write(to_child, b'X')
        assert os.waitpid(child, 0)[1] == 0
        child = 0
    finally:
        if namespace >= 0:
            os.close(namespace)
        os.close(to_parent)
        os.close(to_child)
        if child:
            os.kill(child, 9)
            os.waitpid(child, 0)
    links = json.loads(run('ip', '-j', 'link', 'show'))
    assert not any(link['ifname'].startswith('veth-') for link in links)
    run('ip', 'link', 'del', 'br-test')
    private = json.loads(run('unshare', '-n', 'ip', '-j', 'addr', 'show'))
    assert len(private) == 1 and private[0]['ifname'] == 'lo' and not private[0]['addr_info']
    print('PASS stock iproute2: isolated uplink, bridge/veth, addresses, routes and teardown', flush=True)


def main():
    from test_oci_services import Suite
    build, store = Path(sys.argv[1]), sys.argv[2]
    suite = Suite(build, build, machine_entry=True if '--machine-entry' in sys.argv else False if '--base' in sys.argv else None)
    target = '/tmp/md-network-' + suite.report['id']
    prefix = [suite.runner, '--store', store, '--user', '0:0']
    try:
        suite.transport.upload(suite.client, Path(__file__), suite.base + '/test.py')
        suite.command(prefix + ['--', '/bin/mkdir', target])
        suite.command(prefix + suite.entry_options + ['--network', 'routed', '--bind-ro', suite.base, target,
                                '--deadline-seconds', '120', '--', '/usr/bin/python3', target + '/test.py', '--guest']
                      + (['--internet'] if '--internet' in sys.argv else []))
        suite.command(prefix + suite.entry_options + ['--network', 'routed', '--bind-ro', suite.base, target,
            '--deadline-seconds', '120', '--', '/usr/bin/unshare', '--mount', '--pid', '--fork', '--mount-proc',
            '/usr/bin/python3', target + '/test.py', '--tables'])
        suite.report['passed'] = True
    finally:
        try:
            suite.command(prefix + ['--', '/bin/rmdir', target])
        finally:
            suite.finish()


if __name__ == '__main__':
    receive_batch() if '--batch' in sys.argv else socket_tables() if '--tables' in sys.argv else guest() if '--guest' in sys.argv else main()
