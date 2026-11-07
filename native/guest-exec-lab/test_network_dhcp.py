#!/usr/bin/env python3
"""Stock dhcpcd lease and renewal over a private Ethernet fixture."""
import argparse
from contextlib import ExitStack
import json
import os
from pathlib import Path
import select
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time


def options(data):
    result = {}
    at = 240
    while at < len(data):
        kind = data[at]
        at += 1
        if kind == 255:
            break
        if not kind:
            continue
        assert at < len(data) and at + 1 + data[at] <= len(data), 'short DHCP option'
        size = data[at]
        result[kind] = data[at + 1:at + 1 + size]
        at += 1 + size
    return result


def checksum(data):
    if len(data) % 2:
        data += b'\0'
    value = sum(struct.unpack('!' + 'H' * (len(data) // 2), data))
    while value >> 16:
        value = (value & 65535) + (value >> 16)
    return value ^ 65535


def dhcp6_option(kind, data):
    return struct.pack('!HH', kind, len(data)) + data


def dhcp6_options(data):
    result = {}
    at = 4
    while at < len(data):
        assert at + 4 <= len(data), 'short DHCPv6 option header'
        kind, size = struct.unpack('!HH', data[at:at + 4])
        at += 4
        assert at + size <= len(data), 'short DHCPv6 option'
        result[kind] = data[at:at + size]
        at += size
    return result


def reply_dhcp4(source, wire, unicast, exchanges):
    """One fixture lease reply, shared by standalone and booted stock clients."""
    peer = None
    if source is unicast:
        request, peer = unicast.recvfrom(4096)
    else:
        frame = wire.recv(4096)
        if len(frame) < 14 + 20 + 8 + 240 or frame[23] != 17:
            return
        header_size = (frame[14] & 15) * 4
        udp = frame[14 + header_size:]
        src, dst, length, _ = struct.unpack('!HHHH', udp[:8])
        if (src, dst) != (68, 67):
            return
        request = udp[8:length]
    assert request[0:3] == b'\x01\x01\x06' and request[236:240] == b'\x63\x82\x53\x63'
    query = options(request)
    kind = query[53][0]
    if kind not in (1, 3):
        return
    exchanges.append((kind, request[12:16]))
    print('DHCP request', kind, socket.inet_ntoa(request[12:16]), flush=True)
    server, leased = socket.inet_aton('10.203.0.1'), socket.inet_aton('10.203.0.2')
    answer = bytearray(request[:240])
    answer[0] = 2
    answer[16:20] = leased
    answer[20:24] = server
    answer += b'\x35\x01' + bytes([2 if kind == 1 else 5])
    answer += b'\x36\x04' + server + b'\x01\x04\xff\xff\xff\0'
    answer += b'\x03\x04' + server + b'\x06\x04' + server
    for code, value in ((51, 20), (58, 3), (59, 8)):
        answer += bytes([code, 4]) + struct.pack('!I', value)
    answer += b'\xff'
    if peer:
        # RFC 2131: renewal replies target ciaddr, not necessarily the raw sender.
        unicast.sendto(answer, (socket.inet_ntoa(request[12:16]), 68))
        return
    length = 8 + len(answer)
    header = struct.pack('!BBHHHBBH4s4s', 0x45, 0, 20 + length, 1, 0, 64, 17, 0,
                         server, b'\xff' * 4)
    header = header[:10] + struct.pack('!H', checksum(header)) + header[12:]
    wire.send(b'\xff' * 6 + wire.getsockname()[4] + b'\x08\0' + header
              + struct.pack('!HHHH', 67, 68, length, 0) + answer)


def guest(ipv6=False):
    def ip(*args):
        return subprocess.check_output(['ip', *args], text=True)

    ip('link', 'add', 'md-dhcp-a', 'type', 'veth', 'peer', 'name', 'md-dhcp-b')
    errors, exchanges = [], []
    done = threading.Event()
    daemon = None
    worker = None
    namespace = False
    try:
        ip('netns', 'add', 'md-dhcp-server')
        namespace = True
        ip('link', 'set', 'md-dhcp-b', 'netns', 'md-dhcp-server')
        ip('-n', 'md-dhcp-server', 'addr', 'add', '10.203.0.1/24', 'dev', 'md-dhcp-b')
        ip('-n', 'md-dhcp-server', 'link', 'set', 'md-dhcp-b', 'up')
        ip('-n', 'md-dhcp-server', 'link', 'set', 'lo', 'up')
        ip('link', 'set', 'md-dhcp-a', 'up')
        with tempfile.TemporaryDirectory(prefix='md-dhcp-') as directory, ExitStack() as stack:
            # Sockets retain their creation namespace; only their owning thread
            # changes back before starting the client and hook observer.
            with open('/proc/self/ns/net', 'rb') as client_ns, \
                    open('/run/netns/md-dhcp-server', 'rb') as server_ns:
                os.setns(server_ns.fileno(), os.CLONE_NEWNET)
                try:
                    wire = stack.enter_context(socket.socket(socket.AF_PACKET, socket.SOCK_RAW, socket.htons(0x800)))
                    unicast = stack.enter_context(socket.socket(socket.AF_INET, socket.SOCK_DGRAM))
                    traffic = stack.enter_context(socket.socket(socket.AF_INET, socket.SOCK_DGRAM))
                    wire.bind(('md-dhcp-b', 0))
                    wire.settimeout(1)
                    unicast.bind(('10.203.0.1', 67))
                    traffic.bind(('10.203.0.1', 0))
                    traffic.settimeout(5)
                    router = server6 = None
                    if ipv6:
                        ip('-6', 'addr', 'add', 'fd92:203::1/64', 'dev', 'md-dhcp-b')
                        traffic.close()
                        traffic = stack.enter_context(socket.socket(socket.AF_INET6, socket.SOCK_DGRAM))
                        traffic.bind(('fd92:203::1', 0))
                        traffic.settimeout(5)
                        index = socket.if_nametoindex('md-dhcp-b')
                        router = stack.enter_context(socket.socket(socket.AF_INET6, socket.SOCK_RAW, socket.IPPROTO_ICMPV6))
                        mask = [0xffffffff] * 8
                        mask[133 // 32] &= ~(1 << (133 % 32))
                        router.setsockopt(socket.IPPROTO_ICMPV6, 1, struct.pack('=8I', *mask))
                        router.setsockopt(socket.SOL_SOCKET, socket.SO_BINDTODEVICE, b'md-dhcp-b\0')
                        router.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_MULTICAST_IF, index)
                        router.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_MULTICAST_HOPS, 255)
                        router.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_MULTICAST_LOOP, 0)
                        router.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_JOIN_GROUP,
                                          socket.inet_pton(socket.AF_INET6, 'ff02::2') + struct.pack('=I', index))
                        server6 = stack.enter_context(socket.socket(socket.AF_INET6, socket.SOCK_DGRAM))
                        server6.setsockopt(socket.SOL_SOCKET, socket.SO_BINDTODEVICE, b'md-dhcp-b\0')
                        server6.bind(('::', 547))
                        server6.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_JOIN_GROUP,
                                           socket.inet_pton(socket.AF_INET6, 'ff02::1:2') + struct.pack('=I', index))
                finally:
                    os.setns(client_ns.fileno(), os.CLONE_NEWNET)
            events = stack.enter_context(socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM))
            events.bind(directory + '/events')
            events.settimeout(20)
            leased = socket.inet_aton('10.203.0.2')
            mac = wire.getsockname()[4]

            def serve():
                try:
                    while not done.is_set():
                        # EVENT_WAIT: DHCP request or teardown bound.
                        ready = select.select([router, server6] if ipv6 else [wire, unicast], [], [], 1)[0]
                        if not ready:
                            continue
                        if ready[0] is router:
                            request, source = router.recvfrom(4096)
                            assert request[0] == 133, request
                            print('IPv6 router solicitation', source, flush=True)
                            advertisement = struct.pack('!BBHBBHII', 134, 0, 0, 64, 0x80, 300, 0, 0)
                            advertisement += struct.pack('!BBBBIII16s', 3, 4, 64, 0x80, 300, 120, 0,
                                                         socket.inet_pton(socket.AF_INET6, 'fd92:203::'))
                            router.sendto(advertisement, ('ff02::1', 0))
                            continue
                        if ready[0] is server6:
                            request, peer = server6.recvfrom(4096)
                            assert len(request) >= 4, request
                            kind = request[0]
                            query = dhcp6_options(request)
                            print('DHCPv6 request', kind, peer, flush=True)
                            if kind not in (1, 3, 5, 6):
                                continue
                            assert 1 in query and 3 in query and len(query[3]) >= 12, query
                            exchanges.append((kind, peer[0]))
                            answer = bytes([2 if kind == 1 else 7]) + request[1:4]
                            answer += dhcp6_option(1, query[1]) + dhcp6_option(2, b'\x00\x03\x00\x01' + mac)
                            address = socket.inet_pton(socket.AF_INET6, 'fd92:203::2')
                            answer += dhcp6_option(3, query[3][:4] + struct.pack('!II', 3, 8)
                                                   + dhcp6_option(5, address + struct.pack('!II', 20, 30)))
                            answer += dhcp6_option(23, socket.inet_pton(socket.AF_INET6, 'fd92:203::1'))
                            server6.sendto(answer, peer)
                            continue
                        reply_dhcp4(ready[0], wire, unicast, exchanges)
                except BaseException as error:
                    errors.append(error)

            hook = Path(directory) / 'hook'
            hook.write_text(f'#!{sys.executable}\nimport json, os, socket\n'
                            's=socket.socket(socket.AF_UNIX,socket.SOCK_DGRAM)\n'
                            f's.sendto(json.dumps(dict(os.environ)).encode(), {directory + "/events"!r})\n')
            hook.chmod(0o700)
            worker = threading.Thread(target=serve)
            worker.start()
            with open(directory + '/dhcpcd.log', 'w+') as log:
                print('Starting dhcpcd', flush=True)
                daemon = subprocess.Popen(['dhcpcd', '-B', '-d', '-6' if ipv6 else '-4', '-f', '/dev/null', '-c', str(hook),
                                           'md-dhcp-a'], stdout=log, stderr=subprocess.STDOUT)
                print('Started dhcpcd', daemon.pid, flush=True)
                try:
                    reasons = []
                    deadline = time.monotonic() + 25
                    # EVENT_WAIT: dhcpcd hook datagrams; expiry fails lease/renewal.
                    while ('RENEW6' if ipv6 else 'RENEW') not in reasons:
                        events.settimeout(max(0.001, deadline - time.monotonic()))
                        environment = json.loads(events.recv(16384))
                        reason = environment.get('reason')
                        print('DHCP hook', reason, flush=True)
                        reasons.append(reason)
                        if ipv6:
                            if reason in ('BOUND6', 'RENEW6'):
                                assert environment['new_dhcp6_ia_na1_ia_addr1'] == 'fd92:203::2', environment
                                assert environment['new_dhcp6_name_servers'] == 'fd92:203::1', environment
                            continue
                        if reason in ('BOUND', 'RENEW'):
                            assert environment['new_ip_address'] == '10.203.0.2', environment
                            assert environment['new_domain_name_servers'] == '10.203.0.1', environment
                    if ipv6:
                        assert 'BOUND6' in reasons and any(kind == 1 for kind, _ in exchanges), reasons
                        assert any(kind == 5 for kind, _ in exchanges), exchanges
                        route = ip('-6', 'route', 'get', 'fd92:203::1', 'oif', 'md-dhcp-a')
                        assert 'src fd92:203::2' in route, route
                    else:
                        assert 'BOUND' in reasons and any(kind == 1 for kind, _ in exchanges), reasons
                        assert any(kind == 3 and source == leased for kind, source in exchanges), exchanges
                        route = ip('route', 'get', '10.203.0.1', 'oif', 'md-dhcp-a')
                        assert 'src 10.203.0.2' in route, route
                    with socket.socket(socket.AF_INET6 if ipv6 else socket.AF_INET, socket.SOCK_DGRAM) as client:
                        client.setsockopt(socket.SOL_SOCKET, socket.SO_BINDTODEVICE, b'md-dhcp-a\0')
                        client.settimeout(5)
                        client.sendto(b'lease-traffic', traffic.getsockname())
                        data, peer = traffic.recvfrom(128)
                        assert data == b'lease-traffic' and peer[0] == ('fd92:203::2' if ipv6 else '10.203.0.2'), peer
                        traffic.sendto(b'reply', peer)
                        assert client.recv(128) == b'reply'
                    assert not errors, errors
                finally:
                    daemon.terminate()
                    daemon.wait(timeout=10)
                    daemon = None
                    log.seek(0)
                    print(log.read(), flush=True)
                    done.set()
                    worker.join(timeout=3)
                    assert not worker.is_alive()
    finally:
        done.set()
        if daemon is not None:
            daemon.kill()
            daemon.wait()
        if worker is not None:
            worker.join(timeout=3)
        ip('link', 'del', 'md-dhcp-a')
        if namespace:
            ip('netns', 'del', 'md-dhcp-server')
    print(f'PASS DHCPv{6 if ipv6 else 4}: stock client, separate netns, lease, renewal, DNS option, '
          'source route and bidirectional UDP', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('store')
    parser.add_argument('--python', default='/usr/bin/python3', help='Guest Python executable')
    parser.add_argument('--path', help='Guest executable search path')
    parser.add_argument('--ipv6', action='store_true')
    args = parser.parse_args()
    from test_oci_services import Suite
    suite = Suite(args.build, args.build)
    target = '/tmp/md-dhcp-' + suite.report['id']
    environment = ['--env', 'PATH=' + args.path] if args.path else []
    try:
        suite.upload(Path(__file__), suite.base + '/test.py')
        suite.command(suite.guest(args.store, '--user', '0:0', *environment,
                                 '--', '/bin/sh', '-ec', 'mkdir "$1"', 'dhcp', target))
        for entry in (0, 1):
            suite.command(suite.guest(args.store, '--user', '0:0', '--network', 'bridge',
                                     '--dns', '10.203.0.1',
                                     *environment,
                                     '--env', f'SHROOT_MACHINE_ENTRY={entry}', '--bind-ro', suite.base, target,
                                     '--deadline-seconds', '80', '--', '/bin/sh', '-ec',
                                     'mkdir -p /run/dhcpcd /var/lib/dhcpcd; '
                                     'mount -t tmpfs tmpfs /run/dhcpcd; mount -t tmpfs tmpfs /var/lib/dhcpcd; '
                                     'exec "$2" "$1/test.py" "$3"', 'dhcp', target, args.python,
                                     '--guest6' if args.ipv6 else '--guest'), timeout=100)
        suite.report['passed'] = True
    finally:
        try:
            suite.command(suite.guest(args.store, '--user', '0:0', *environment,
                                     '--', '/bin/sh', '-ec', 'rmdir "$1"', 'dhcp', target))
        finally:
            suite.finish()


if __name__ == '__main__':
    guest(sys.argv[1:] == ['--guest6']) if sys.argv[1:] in (['--guest'], ['--guest6']) else main()
