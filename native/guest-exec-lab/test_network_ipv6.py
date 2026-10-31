#!/usr/bin/env python3
"""IPv6 socket, rtnetlink, nftables, DNS and publication paths in a recipe guest."""
import argparse
import ctypes
import errno
import json
import mmap
import os
from pathlib import Path
import socket
import stat
import struct
import subprocess
import sys


def legacy_ioctl():
    libc = ctypes.CDLL(None, use_errno=True)
    libc.ioctl.argtypes = (ctypes.c_int, ctypes.c_ulong, ctypes.c_void_p)
    libc.mprotect.argtypes = (ctypes.c_void_p, ctypes.c_size_t, ctypes.c_int)
    class Route(ctypes.Structure):
        _fields_ = [('destination', ctypes.c_ubyte * 16), ('source', ctypes.c_ubyte * 16),
                    ('gateway', ctypes.c_ubyte * 16), ('type', ctypes.c_uint32),
                    ('prefix', ctypes.c_uint16), ('source_prefix', ctypes.c_uint16),
                    ('metric', ctypes.c_uint32), ('info', ctypes.c_ulong),
                    ('flags', ctypes.c_uint32), ('index', ctypes.c_int)]
    index = socket.if_nametoindex('lo')
    with mmap.mmap(-1, 2 * mmap.PAGESIZE) as memory:
        base = ctypes.addressof(ctypes.c_char.from_buffer(memory))
        assert libc.mprotect(base + mmap.PAGESIZE, mmap.PAGESIZE, 0) == 0
        try:
            for kind, protocol in ((socket.SOCK_STREAM, 0), (socket.SOCK_RAW, socket.IPPROTO_UDP)):
                with socket.socket(socket.AF_INET6, kind, protocol) as stream:
                    address = struct.pack('=16sIi', socket.inet_pton(socket.AF_INET6, 'fd42:9::1'), 128, index)
                    route = Route(prefix=64, metric=123, flags=1, index=index)
                    route.destination[:] = socket.inet_pton(socket.AF_INET6, 'fd42:10::')
                    for request, data in ((0x8916, address), (0x890B, bytes(route)),
                                          (0x890C, bytes(route)), (0x8936, address)):
                        pointer = base + mmap.PAGESIZE - len(data)
                        ctypes.memmove(pointer, data, len(data))
                        assert libc.ioctl(stream.fileno(), request, pointer) == 0, (request, ctypes.get_errno())
                        assert ctypes.string_at(pointer, len(data)) == data, 'input ioctl overwrote caller memory'
        finally:
            assert libc.mprotect(base + mmap.PAGESIZE, mmap.PAGESIZE, mmap.PROT_READ | mmap.PROT_WRITE) == 0
    print('PASS IPv6 legacy ioctl: exact guarded address/route ABI on native and raw sockets', flush=True)


def routing_policy():
    for family, flag, address in ((socket.AF_INET, '-4', '127.0.0.1'), (socket.AF_INET6, '-6', '::1')):
        command('ip', flag, 'rule', 'add', 'pref', '50', 'fwmark', '0x1200/0xff00', 'lookup', 'local')
        command('ip', flag, 'rule', 'add', 'pref', '100', 'prohibit')
        command('ip', flag, 'rule', 'del', 'pref', '0', 'lookup', 'local')
        try:
            with socket.socket(family, socket.SOCK_DGRAM) as server:
                server.setsockopt(socket.SOL_SOCKET, 36, 0x1201)  # SO_MARK
                server.settimeout(5)
                server.bind((address, 0))
                with socket.socket(family, socket.SOCK_DGRAM) as blocked:
                    try:
                        blocked.sendto(b'denied', server.getsockname())
                    except OSError as error:
                        assert error.errno == errno.EACCES, error
                    else:
                        raise AssertionError('prohibit rule did not deny socket traffic')
                with socket.socket(family, socket.SOCK_DGRAM) as client:
                    client.setsockopt(socket.SOL_SOCKET, 36, 0x1201)
                    assert client.getsockopt(socket.SOL_SOCKET, 36) == 0x1201
                    client.settimeout(5)
                    client.sendto(b'policy', server.getsockname())
                    data, peer = server.recvfrom(32)
                    assert data == b'policy'
                    server.sendto(b'reply', peer)
                    assert client.recv(32) == b'reply'
            route = json.loads(command('ip', flag, '-j', 'route', 'get', address, 'mark', '0x1201'))
            assert route[0]['dev'] == 'lo', route
        finally:
            command('ip', flag, 'rule', 'add', 'pref', '0', 'lookup', 'local')
            command('ip', flag, 'rule', 'del', 'pref', '50')
            command('ip', flag, 'rule', 'del', 'pref', '100')
    print('PASS policy routing: stock ip rules, SO_MARK IPv4/IPv6 traffic and immediate prohibit', flush=True)


def router_discovery():
    command('ip', 'link', 'add', 'mdrd0', 'type', 'veth', 'peer', 'name', 'mdrd1')
    try:
        links = []
        for name in ('mdrd0', 'mdrd1'):
            command('ip', 'link', 'set', name, 'up')
            link = json.loads(command('ip', '-j', '-6', 'address', 'show', 'dev', name))[0]
            links.append((link['ifindex'], next(a['local'] for a in link['addr_info'] if a['scope'] == 'link')))

        def allow(stream, kind):
            mask = [0xffffffff] * 8
            mask[kind // 32] &= ~(1 << (kind % 32))
            stream.setsockopt(socket.IPPROTO_ICMPV6, 1, struct.pack('=8I', *mask))

        with socket.socket(socket.AF_INET6, socket.SOCK_RAW, socket.IPPROTO_ICMPV6) as client, \
                socket.socket(socket.AF_INET6, socket.SOCK_RAW, socket.IPPROTO_ICMPV6) as router, \
                socket.socket(socket.AF_PACKET, socket.SOCK_DGRAM, socket.htons(0x86dd)) as capture:
            capture.bind(('mdrd1', 0))
            capture.settimeout(5)
            for stream, name, group, (index, _) in zip((client, router), ('mdrd0', 'mdrd1'),
                                                       ('ff02::1', 'ff02::2'), links):
                stream.settimeout(5)
                stream.setsockopt(socket.SOL_SOCKET, socket.SO_BINDTODEVICE, name.encode() + b'\0')
                stream.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_JOIN_GROUP,
                                  socket.inet_pton(socket.AF_INET6, group) + struct.pack('=I', index))
                for option, value in ((socket.IPV6_MULTICAST_IF, index), (socket.IPV6_MULTICAST_LOOP, 0),
                                      (socket.IPV6_MULTICAST_HOPS, 255), (socket.IPV6_UNICAST_HOPS, 255),
                                      (socket.IPV6_RECVHOPLIMIT, 1), (socket.IPV6_RECVPKTINFO, 1)):
                    stream.setsockopt(socket.IPPROTO_IPV6, option, value)
                stream.setsockopt(socket.SOL_SOCKET, 29, 1)  # Linux SO_TIMESTAMP
            allow(client, 134)
            allow(router, 133)
            client.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_TCLASS, 192)
            assert client.getsockopt(socket.IPPROTO_IPV6, socket.IPV6_TCLASS) == 192
            client.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_MULTICAST_HOPS, 23)
            solicitation = struct.pack('!BBHI', 133, 0, 0, 0)
            for kind, value, expected in (
                    (socket.IPV6_HOPLIMIT, struct.pack('=i', 256), errno.EINVAL),
                    (socket.IPV6_HOPLIMIT, struct.pack('=i', -2), errno.EINVAL),
                    (socket.IPV6_TCLASS, b'\x01', errno.EINVAL),
                    (socket.IPV6_PKTINFO, b'\x00' * 19, errno.EINVAL),
                    (socket.IPV6_PKTINFO, socket.inet_pton(socket.AF_INET6, links[1][1])
                     + struct.pack('=I', links[0][0]), errno.EADDRNOTAVAIL),
                    (socket.IPV6_PKTINFO, b'\x00' * 16 + struct.pack('=I', links[1][0]), errno.EINVAL)):
                try:
                    client.sendmsg([solicitation], [(socket.IPPROTO_IPV6, kind, value)], 0, ('ff02::2', 0))
                except OSError as error:
                    assert error.errno == expected, (kind, error)
                else:
                    raise AssertionError(('invalid ancillary accepted', kind, value))
            client.sendmsg([solicitation], [
                (socket.IPPROTO_IPV6, socket.IPV6_PKTINFO,
                 socket.inet_pton(socket.AF_INET6, links[0][1]) + struct.pack('=I', links[0][0])),
                (socket.IPPROTO_IPV6, socket.IPV6_HOPLIMIT, struct.pack('=i', 255)),
                (socket.IPPROTO_IPV6, socket.IPV6_TCLASS, struct.pack('=i', 160)),
            ], 0, ('ff02::2', 0))
            packet = capture.recv(256)
            assert len(packet) == 48 and (struct.unpack('!I', packet[:4])[0] >> 20) & 255 == 160, packet
            assert client.getsockopt(socket.IPPROTO_IPV6, socket.IPV6_MULTICAST_HOPS) == 23
            assert client.getsockopt(socket.IPPROTO_IPV6, socket.IPV6_TCLASS) == 192
            client.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_MULTICAST_HOPS, 255)
            client.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_TCLASS, -1)
            assert client.getsockopt(socket.IPPROTO_IPV6, socket.IPV6_TCLASS) == 0
            data, control, flags, source = router.recvmsg(64, 256)
            assert len(data) == 8 and data[0] == 133 and not flags, (data, flags)
            assert source[0] == links[0][1] and source[3] == links[1][0], source
            ancillary = {(level, kind): value for level, kind, value in control}
            assert struct.unpack('=i', ancillary[(socket.IPPROTO_IPV6, socket.IPV6_HOPLIMIT)])[0] == 255
            assert struct.unpack('=I', ancillary[(socket.IPPROTO_IPV6, socket.IPV6_PKTINFO)][16:])[0] == links[1][0]
            assert struct.unpack('=qq', ancillary[(socket.SOL_SOCKET, 29)])[0] > 0
            advertisement = struct.pack('!BBHBBHII', 134, 0, 0, 64, 0, 1800, 0, 0)
            router.sendmsg([advertisement], [], 0, ('ff02::1', 0))
            peek = client.recvmsg(64, 256, socket.MSG_PEEK)
            received = client.recvmsg(64, 256)
            assert peek == received and received[0][0] == 134 and len(received[0]) == 16, received
            assert received[3][0] == links[1][1] and received[3][3] == links[0][0], received
            for hook, device in (('input', 'iifname mdrd1'), ('output', 'oifname mdrd0')):
                command('nft', '-f', '-', data=f'''
table ip6 md_icmp_policy {{
    chain check {{ type filter hook {hook} priority 0; policy accept;
        {device} ip6 saddr {{ fe80::/64, fd42::/64 }} icmpv6 type nd-router-solicit counter drop
    }}
}}
''')
                try:
                    if hook == 'output':
                        try:
                            client.sendto(solicitation, ('ff02::2', 0))
                        except OSError as error:
                            assert error.errno == errno.EACCES, error
                        else:
                            raise AssertionError('output firewall accepted ICMPv6')
                    else:
                        client.sendto(solicitation, ('ff02::2', 0))
                    router.settimeout(0.1)
                    try:
                        router.recv(64)
                    except socket.timeout:
                        pass
                    else:
                        raise AssertionError('firewall delivered blocked ICMPv6')
                    rules = command('nft', 'list', 'table', 'ip6', 'md_icmp_policy')
                    assert 'counter packets 1 bytes 8' in rules, rules
                finally:
                    router.settimeout(5)
                    command('nft', 'delete', 'table', 'ip6', 'md_icmp_policy')
                client.sendto(solicitation, ('ff02::2', 0))
                assert router.recv(64)[0] == 133
            allow(client, 129)
            client.sendto(struct.pack('!BBHHH', 128, 0, 0, 123, 1) + b'echo',
                          (links[1][1], 0, 0, links[0][0]))
            data, source = client.recvfrom(64)
            assert data[0] == 129 and data[4:] == struct.pack('!HH', 123, 1) + b'echo', data
            allow(client, 136)
            target = socket.inet_pton(socket.AF_INET6, links[1][1])
            solicited = socket.inet_ntop(socket.AF_INET6, bytes.fromhex('ff0200000000000000000001ff') + target[-3:])
            client.sendto(struct.pack('!BBHI', 135, 0, 0, 0) + target, (solicited, 0, 0, links[0][0]))
            data, source = client.recvfrom(64)
            assert data[0] == 136 and data[8:24] == target and data[4] & 0x40, data
    finally:
        command('ip', 'link', 'del', 'mdrd0')
    print('PASS ICMPv6: RS/RA, interface-scoped multicast, hop limit, timestamps, echo, neighbor discovery and live firewall with IPv6 interval sets', flush=True)


def raw_udp():
    with socket.socket(socket.AF_INET6, socket.SOCK_RAW, socket.IPPROTO_UDP) as raw, \
            socket.socket(socket.AF_INET6, socket.SOCK_DGRAM) as server, \
            socket.socket(socket.AF_INET6, socket.SOCK_DGRAM) as client:
        for stream in (raw, server, client):
            stream.settimeout(5)
        raw.setsockopt(socket.SOL_SOCKET, socket.SO_BINDTODEVICE, b'lo\0')
        assert raw.getsockopt(socket.IPPROTO_IPV6, 7) == -1  # IPV6_CHECKSUM
        try:
            raw.setsockopt(socket.IPPROTO_IPV6, 7, 5)
        except OSError as error:
            assert error.errno == errno.EINVAL, error
        else:
            raise AssertionError('odd raw checksum offset accepted')
        raw.setsockopt(socket.IPPROTO_IPV6, 7, 6)
        assert raw.getsockopt(socket.IPPROTO_IPV6, 7) == 6
        server.bind(('::1', 0))
        client.bind(('::1', 0))
        src, dst = client.getsockname()[1], server.getsockname()[1]
        packet = struct.pack('!HHHH', src, dst, 12, 0) + b'raw6'
        assert raw.sendto(packet, ('::1', 0)) == len(packet)
        data, peer = server.recvfrom(128)
        assert data == b'raw6' and peer[0:2] == ('::1', src), (data, peer)
        outgoing, _ = raw.recvfrom(128)
        assert outgoing[:6] == packet[:6] and outgoing[8:] == b'raw6' and outgoing[6:8] != b'\0\0', outgoing
        server.sendto(b'answer', peer)
        assert client.recv(128) == b'answer'
        incoming, peer = raw.recvfrom(128)
        assert struct.unpack('!HHH', incoming[:6]) == (dst, src, 14) and incoming[8:] == b'answer', incoming
        assert peer[0] == '::1', peer
    print('PASS raw UDPv6: checksum option, raw/native delivery, retained reply and ordinary UDP peer', flush=True)


def udp_reconnect():
    libc = ctypes.CDLL(None, use_errno=True)
    with socket.socket(socket.AF_INET6, socket.SOCK_DGRAM) as ipv6, \
            socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as ipv4:
        ipv6.bind(('::1', 0))
        ipv4.bind(('127.0.0.1', 0))
        for explicit in (False, True):
            with socket.socket(socket.AF_INET6, socket.SOCK_DGRAM) as client:
                if explicit:
                    client.bind(('::', 0))
                # Linux retains the assigned source without AF_UNSPEC; a
                # cross-family reconnect needs disconnect even natively.
                for mapped, disconnect in ((False, False), (False, False), (True, True), (False, True), (True, True)):
                    if disconnect:
                        address = struct.pack('=H14x', socket.AF_UNSPEC)
                        assert libc.connect(client.fileno(), address, len(address)) == 0, ctypes.get_errno()
                        try:
                            client.getpeername()
                        except OSError as error:
                            assert error.errno == errno.ENOTCONN, error
                        else:
                            raise AssertionError('AF_UNSPEC retained the previous peer')
                    server = ipv4 if mapped else ipv6
                    peer = server.getsockname()
                    client.connect(('::ffff:' + peer[0] if mapped else peer[0], peer[1]))
                    assert client.getsockname()[0] == ('::ffff:127.0.0.1' if mapped else '::1')
                    client.send(b'reconnect')
                    server.settimeout(5)
                    data, sender = server.recvfrom(64)
                    assert data == b'reconnect'
                    server.sendto(b'reply', sender)
                    client.settimeout(5)
                    assert client.recv(64) == b'reply'
    # glibc sorts wildcard listeners with the same dual-stack UDP socket.
    assert socket.getaddrinfo(None, 22, socket.AF_UNSPEC, socket.SOCK_STREAM, 0, socket.AI_PASSIVE)
    print('PASS UDP reconnect: dual-stack source selection, AF_UNSPEC, explicit/implicit binding and getaddrinfo', flush=True)


def command(*argv, data=None):
    result = subprocess.run(argv, input=data, text=True, capture_output=True, timeout=15)
    assert result.returncode == 0, (argv, result.stdout, result.stderr)
    assert 'Dump terminated' not in result.stderr, (argv, result.stderr)
    return result.stdout


def proc_directories():
    roots = [os.open('/proc/sys/net/' + family + '/conf', os.O_RDONLY | os.O_DIRECTORY)
             for family in ('ipv4', 'ipv6')]
    command('ip', 'link', 'add', 'mdproc', 'type', 'bridge')
    try:
        for root, leaf in zip(roots, ('src_valid_mark', 'disable_ipv6')):
            assert 'mdproc' in os.listdir(root)
            for flags in (os.O_RDONLY | os.O_DIRECTORY, os.O_PATH | os.O_DIRECTORY,
                          os.O_PATH | os.O_DIRECTORY | os.O_NOFOLLOW):
                child = os.open('mdproc', flags, dir_fd=root)
                try:
                    assert stat.S_ISDIR(os.fstat(child).st_mode)
                    fd = os.open(leaf, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=child)
                    try:
                        assert os.read(fd, 16) == b'0\n'
                    finally:
                        os.close(fd)
                finally:
                    os.close(child)
    finally:
        command('ip', 'link', 'del', 'mdproc')
        for root in roots:
            try:
                assert 'mdproc' not in os.listdir(root)
                try:
                    os.stat('mdproc', dir_fd=root, follow_symlinks=False)
                except FileNotFoundError:
                    pass
                else:
                    raise AssertionError('removed interface retained in proc')
            finally:
                os.close(root)
    print('PASS proc interface directories: relative opens, nofollow, metadata and removal', flush=True)


def guest():
    proc_directories()
    command('unshare', '-m', '--', sys.executable, __file__, '--proc-guest')
    legacy_ioctl()
    routing_policy()
    data_maps()
    router_discovery()
    raw_udp()
    udp_reconnect()
    addresses = json.loads(command('ip', '-j', '-6', 'address'))
    assert any(a['local'] == '::1' for link in addresses for a in link['addr_info'])
    assert any(a['local'] == 'fd6d:6473:1::2' for link in addresses for a in link['addr_info'])
    route = json.loads(command('ip', '-j', '-6', 'route', 'get', '2001:db8::1'))
    assert route[0]['dev'] == 'eth0' and route[0]['gateway'] == 'fd6d:6473:1::1', route
    command('ip', '-6', 'address', 'add', 'fd12:3456:789a::42/128', 'dev', 'lo')
    shown = command('ip', '-6', 'address', 'show', 'dev', 'lo')
    assert 'fd12:3456:789a::42' in shown, shown
    command('ip', '-6', 'address', 'del', 'fd12:3456:789a::42/128', 'dev', 'lo')
    print('PASS IPv6 address and route mutation/query through iproute2', flush=True)
    command('ip', 'link', 'add', 'mdv6', 'type', 'bridge')
    command('ip', 'link', 'set', 'mdv6', 'up')
    try:
        link = json.loads(command('ip', '-j', '-6', 'address', 'show', 'dev', 'mdv6'))[0]
        index = link['ifindex']
        local = next(a['local'] for a in link['addr_info'] if a['scope'] == 'link')
        route = json.loads(command('ip', '-j', '-6', 'route', 'get', 'fe80::1234', 'oif', 'mdv6'))
        assert route[0]['dev'] == 'mdv6', route
        with socket.socket(socket.AF_INET6, socket.SOCK_DGRAM) as server, socket.socket(socket.AF_INET6, socket.SOCK_DGRAM) as client:
            server.bind((local, 0, 0, index)); server.settimeout(5); client.settimeout(5)
            client.sendto(b'scope', server.getsockname())
            data, peer = server.recvfrom(32)
            assert data == b'scope' and peer[3] == index, peer
            server.sendto(b'reply', peer)
            assert client.recv(32) == b'reply'
        with socket.socket(socket.AF_INET6, socket.SOCK_DGRAM) as invalid:
            try:
                invalid.bind((local, 0, 0, 1))
            except OSError as error:
                assert error.errno == errno.EADDRNOTAVAIL, error
            else:
                raise AssertionError('link-local address accepted on the wrong interface')
        command('ip', '-6', 'address', 'del', local + '/64', 'dev', 'mdv6')
        command('ip', 'link', 'set', 'mdv6', 'txqueuelen', '100')
        assert local not in command('ip', '-6', 'address', 'show', 'dev', 'mdv6')
        command('ip', 'link', 'set', 'mdv6', 'down')
        command('ip', 'link', 'set', 'mdv6', 'up')
        assert local in command('ip', '-6', 'address', 'show', 'dev', 'mdv6')
        command('ip', 'link', 'set', 'mdv6', 'mtu', '1200')
        assert local not in command('ip', '-6', 'address', 'show', 'dev', 'mdv6')
        command('ip', 'link', 'set', 'mdv6', 'mtu', '1500')
        assert local in command('ip', '-6', 'address', 'show', 'dev', 'mdv6')
    finally:
        command('ip', 'link', 'del', 'mdv6')
    print('PASS scoped link-local route and UDP peer identity', flush=True)
    command('ip', 'link', 'add', 'dad0', 'type', 'veth', 'peer', 'name', 'dad1')
    try:
        for name in ('dad0', 'dad1'):
            command('ip', 'link', 'set', name, 'up')
            command('ip', '-6', 'address', 'add', 'fdab::5/64', 'dev', name)
        assert 'dadfailed' in command('ip', '-6', 'address', 'show', 'dev', 'dad1')
        command('ip', '-6', 'address', 'del', 'fdab::5/64', 'dev', 'dad1')
        Path('/proc/sys/net/ipv6/conf/all/accept_dad').write_text('0')
        Path('/proc/sys/net/ipv6/conf/dad1/accept_dad').write_text('0')
        command('ip', '-6', 'address', 'add', 'fdab::5/64', 'dev', 'dad1')
        assert 'dadfailed' not in command('ip', '-6', 'address', 'show', 'dev', 'dad1')
    finally:
        Path('/proc/sys/net/ipv6/conf/all/accept_dad').write_text('1')
        command('ip', 'link', 'del', 'dad0')
    print('PASS IPv6 address lifecycle and duplicate detection on the shared link', flush=True)
    for family in (socket.AF_INET, socket.AF_INET6):
        address = '::1' if family == socket.AF_INET6 else '127.0.0.1'
        for kind in (socket.SOCK_STREAM, socket.SOCK_DGRAM):
            with socket.socket(socket.AF_INET6, kind) as server, socket.socket(family, kind) as client:
                server.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
                server.bind(('::', 0))
                for stream in (server, client):
                    stream.settimeout(5)
                if kind == socket.SOCK_STREAM:
                    server.listen()
                client.connect((address, server.getsockname()[1]))
                client.sendall(b'ping')
                if kind == socket.SOCK_STREAM:
                    accepted, peer = server.accept()
                    with accepted:
                        assert accepted.getsockname()[0] in ('::1', '::ffff:127.0.0.1')
                        assert accepted.recv(4) == b'ping'
                        accepted.sendall(b'pong')
                else:
                    data, peer = server.recvfrom(32)
                    assert data == b'ping'
                    server.sendto(b'pong', peer)
                assert client.recv(4) == b'pong'
    print('PASS guest dual-stack TCP/UDP and accepted address identity', flush=True)
    with socket.socket(socket.AF_INET6) as server:
        server.bind(('::1', 0)); server.listen()
        port = server.getsockname()[1]
        command('nft', '-f', '-', data=f'''
table ip6 md_ipv6_test {{
 chain output {{ type filter hook output priority 0; policy accept;
  ip6 daddr ::1 tcp dport {port} counter drop
 }}
}}
''')
        try:
            with socket.socket(socket.AF_INET6) as client:
                client.settimeout(5)
                try:
                    client.connect(('::1', port))
                except OSError as error:
                    assert error.errno == errno.EACCES, error
                else:
                    raise AssertionError('IPv6 drop rule was bypassed')
            rules = command('nft', 'list', 'table', 'ip6', 'md_ipv6_test')
            assert 'packets 0 bytes 0' in rules, rules
        finally:
            command('nft', 'delete', 'table', 'ip6', 'md_ipv6_test')
        with socket.create_connection(('::1', port), timeout=5) as client:
            accepted, _ = server.accept()
            accepted.close()
    with socket.socket(socket.AF_INET6, socket.SOCK_DGRAM) as server:
        server.bind(('::1', 0)); server.settimeout(5)
        port = server.getsockname()[1]
        command('nft', '-f', '-', data=f'''
table inet md_ipv6_test {{
 chain output {{ type filter hook output priority 0; policy accept;
  ip6 daddr ::1 udp dport {port} counter accept
 }}
}}
''')
        try:
            with socket.socket(socket.AF_INET6, socket.SOCK_DGRAM) as client:
                client.sendto(b'count', ('::1', port))
                assert server.recv(32) == b'count'
            rules = command('nft', 'list', 'table', 'inet', 'md_ipv6_test')
            assert 'packets 1 bytes 5' in rules, rules
        finally:
            command('nft', 'delete', 'table', 'inet', 'md_ipv6_test')
    print('PASS nft ip6/inet filter, payload counters and rule removal', flush=True)
    conf = Path('/proc/sys/net/ipv6')
    assert '::1' in command('ip', '-6', 'address', 'show', 'dev', 'lo')
    (conf / 'conf/lo/disable_ipv6').write_text('1')
    assert '::1' not in command('ip', '-6', 'address', 'show', 'dev', 'lo')
    assert '127.0.0.1' in command('ip', '-4', 'address', 'show', 'dev', 'lo')
    (conf / 'conf/lo/disable_ipv6').write_text('0')
    assert '::1' in command('ip', '-6', 'address', 'show', 'dev', 'lo')
    (conf / 'bindv6only').write_text('1')
    with socket.socket(socket.AF_INET6) as stream:
        assert stream.getsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY) == 1
    (conf / 'bindv6only').write_text('0')
    print('PASS IPv6 sysctl changes without modifying IPv4', flush=True)


def data_maps():
    for family, nft_family, loopback, datatype in (
            (socket.AF_INET, 'ip', '127.0.0.1', 'ipv4_addr'),
            (socket.AF_INET6, 'ip6', '::1', 'ipv6_addr')):
        with socket.socket(family) as tcp, socket.socket(family, socket.SOCK_DGRAM) as udp:
            for stream in (tcp, udp):
                stream.bind((loopback, 0))
                stream.settimeout(5)
            tcp.listen(1)
            command('nft', '-f', '-', data=f'''
table {nft_family} md_data_map {{
 map ports {{ type inet_proto . inet_service : {datatype} . inet_service;
  elements = {{ tcp . 38880 : {loopback} . {tcp.getsockname()[1]},
                udp . 38880 : {loopback} . {udp.getsockname()[1]} }}
 }}
 chain output {{ type nat hook output priority dstnat; policy accept;
  dnat to meta l4proto . th dport map @ports
 }}
}}
''')
            try:
                with socket.socket(family) as client:
                    client.settimeout(5)
                    client.connect((loopback, 38880))
                    accepted, _ = tcp.accept()
                    with accepted:
                        accepted.settimeout(5)
                        client.sendall(b'tcp-map')
                        assert accepted.recv(32) == b'tcp-map'
                        accepted.sendall(b'reply')
                        assert client.recv(32) == b'reply'
                with socket.socket(family, socket.SOCK_DGRAM) as client:
                    client.settimeout(5)
                    client.sendto(b'udp-map', (loopback, 38880))
                    payload, peer = udp.recvfrom(32)
                    assert payload == b'udp-map'
                    udp.sendto(b'reply', peer)
                    payload, peer = client.recvfrom(32)
                    assert payload == b'reply' and peer[:2] == (loopback, 38880), peer
                listed = command('nft', 'list', 'map', nft_family, 'md_data_map', 'ports')
                assert 'tcp . 38880' in listed and 'udp . 38880' in listed, listed
                command('nft', 'delete', 'element', nft_family, 'md_data_map', 'ports', '{ udp . 38880 }')
                listed = command('nft', 'list', 'map', nft_family, 'md_data_map', 'ports')
                assert 'tcp . 38880' in listed and 'udp . 38880' not in listed, listed
            finally:
                command('nft', 'delete', 'table', nft_family, 'md_data_map')
    print('PASS stock nft data maps: IPv4/IPv6 concatenated TCP/UDP DNAT, replies, dump and element removal', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build', type=Path, required=True)
    parser.add_argument('--name', default='arch-turnip')
    parser.add_argument('--entry', choices=('base', 'lazy'), default='lazy')
    parser.add_argument('--internet', action='store_true', help='Also require native and guest IPv6 UDP/TCP DNS and verified HTTPS')
    args = parser.parse_args()
    from test_oci_services import Suite, Server
    from test_launch_network import SERVER, exchange, reserve_pair, released
    suite = Suite(args.build, args.build)
    suite.entry_options = ['--env', 'SHROOT_MACHINE_ENTRY=' + ('1' if args.entry == 'lazy' else '0')]
    suite.report['entryMode'] = args.entry
    target = '/tmp/md-ipv6-' + suite.report['id']
    info = suite.installed_environment(args.name)
    try:
        suite.transport.upload(suite.client, Path(__file__), suite.base + '/ipv6.py')
        suite.command(suite.guest(info['store'], '--user', '0:0', '--', '/bin/mkdir', target))
        suite.command(suite.guest(info['store'], '--user', '0:0', '--network', 'bridge',
            '--dns', '2001:db8::53', '--bind-ro', suite.base, target, '--deadline-seconds', '90',
            '--', '/usr/bin/python3', target + '/ipv6.py', '--guest'), timeout=100)
        port, tcp, udp = reserve_pair(socket.AF_INET6)
        tcp.close(); udp.close()
        options = ['--network', 'bridge', '-p', f'[::1]:{port}:18080/tcp',
                   '-p', f'[::1]:{port}:18080/udp']
        server = Server(suite, suite.guest(info['store'], *options, '--deadline-seconds', '45',
            '--', '/usr/bin/python3', '-u', '-c', SERVER, 'ipv6-', '6'))
        try:
            server.ready('NETWORK_READY')
            exchange(port, b'ipv6-', socket.AF_INET6)
            with socket.create_connection(('::1', port), timeout=5) as stream:
                stream.sendall(b'stop'); assert stream.recv(64) == b'ipv6-stop'
            server.completed()
        finally:
            server.close()
        released(port, socket.AF_INET6)
        if args.internet:
            from docker_network_fixture import internet_ipv6
            internet_ipv6()
            suite.transport.upload(suite.client, Path(__file__).with_name('docker_network_fixture.py'),
                                   suite.base + '/uplink.py')
            for mode in ('routed', 'bridge'):
                suite.command(suite.guest(info['store'], '--user', '0:0', '--network', mode,
                    '--bind-ro', suite.base, target, '--deadline-seconds', '60',
                    '--', '/usr/bin/python3', target + '/uplink.py', '--internet-ipv6'), timeout=75)
            suite.report['ipv6InternetModes'] = ['native', 'routed', 'bridge']
        suite.report['passed'] = True
    finally:
        try:
            suite.command(suite.guest(info['store'], '--user', '0:0', '--', '/bin/rmdir', target))
        finally:
            suite.finish()


if __name__ == '__main__':
    if sys.argv[1:] == ['--proc-guest']:
        command('mount', '-t', 'proc', 'proc', '/proc')
        proc_directories()
    else:
        guest() if sys.argv[1:] == ['--guest'] else main()
