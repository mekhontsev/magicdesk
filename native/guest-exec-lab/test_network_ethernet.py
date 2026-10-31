#!/usr/bin/env python3
"""Guest packet sockets on virtual links; no Android raw socket or privilege."""
import argparse
import ctypes
import errno
import json
import os
from pathlib import Path
import select
import socket
import struct
import subprocess
import sys


def interface_addresses():
    class Ifconf(ctypes.Structure):
        _fields_ = [('length', ctypes.c_int), ('buffer', ctypes.c_void_p)]
    libc = ctypes.CDLL(None, use_errno=True)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as channel:
        def query(value, error=0):
            result = libc.ioctl(channel.fileno(), 0x8912, ctypes.byref(value))
            assert (ctypes.get_errno() if result < 0 else 0) == error
        value = Ifconf(-1, None)
        query(value)
        length = value.length
        assert length >= 80 and length % 40 == 0
        buffer = ctypes.create_string_buffer(length + 40)
        value = Ifconf(length, ctypes.addressof(buffer))
        query(value)
        entries = {}
        for offset in range(0, value.length, 40):
            name = buffer.raw[offset:offset + 16].split(b'\0', 1)[0].decode()
            assert struct.unpack_from('=H', buffer, offset + 16)[0] == socket.AF_INET
            entries[name] = socket.inet_ntoa(buffer.raw[offset + 20:offset + 24])
        assert entries['md-raw-a'] == '192.0.2.1' and entries['md-raw-b'] == '192.0.2.2', entries
        for size in (-1, 0, 39, 40, 79):
            ctypes.memset(buffer, 0xa5, len(buffer))
            value = Ifconf(size, ctypes.addressof(buffer))
            query(value)
            assert value.length == max(0, size) // 40 * 40
            assert buffer.raw[value.length:] == b'\xa5' * (len(buffer) - value.length)
        query(Ifconf(length, 1), errno.EFAULT)
        assert libc.ioctl(channel.fileno(), 0x8912, ctypes.c_void_p(1)) == -1
        assert ctypes.get_errno() == errno.EFAULT
    print('PASS SIOCGIFCONF: guest addresses, sizing, whole entries and invalid pointers', flush=True)


def raw_ipv4():
    def ip(*args):
        subprocess.run(['ip', *args], check=True)

    ip('link', 'add', 'md-raw-a', 'type', 'veth', 'peer', 'name', 'md-raw-b')
    try:
        for name, address in (('md-raw-a', '192.0.2.1/24'), ('md-raw-b', '192.0.2.2/24')):
            ip('addr', 'add', address, 'dev', name)
            ip('link', 'set', name, 'up')
        interface_addresses()
        with socket.socket(socket.AF_INET, socket.SOCK_RAW, socket.IPPROTO_UDP) as sender, \
                socket.socket(socket.AF_INET, socket.SOCK_RAW, socket.IPPROTO_UDP) as receiver:
            sender.setsockopt(socket.SOL_SOCKET, socket.SO_BINDTODEVICE, b'md-raw-a\0')
            receiver.setsockopt(socket.SOL_SOCKET, socket.SO_BINDTODEVICE, b'md-raw-b\0')
            receiver.settimeout(5)
            receiver.setsockopt(socket.IPPROTO_IP, 8, 1)  # IP_PKTINFO
            receiver.setsockopt(socket.IPPROTO_IP, 12, 1)  # IP_RECVTTL
            sender.setsockopt(socket.IPPROTO_IP, socket.IP_TTL, 37)
            datagram = struct.pack('!HHHH', 68, 67, 12, 0) + b'dhcp'
            for include in (False, True):
                sender.setsockopt(socket.IPPROTO_IP, socket.IP_HDRINCL, int(include))
                header = struct.pack('!BBHHHBBH4s4s', 0x45, 0, 0, 17, 0, 39, socket.IPPROTO_UDP, 0,
                                     b'\0' * 4, socket.inet_aton('192.0.2.2'))
                payload = header + datagram if include else datagram
                assert sender.sendto(payload, ('192.0.2.2', 0)) == len(payload)
                packet, controls, flags, peer = receiver.recvmsg(512, 256)
                assert not flags and packet[20:] == datagram and peer[0] == '192.0.2.1', (packet, peer)
                words = sum(struct.unpack('!10H', packet[:20]))
                while words >> 16:
                    words = (words & 65535) + (words >> 16)
                assert words == 65535 and struct.unpack('!H', packet[2:4])[0] == len(packet)
                ancillary = {(level, kind): value for level, kind, value in controls}
                assert struct.unpack('=i', ancillary[(socket.IPPROTO_IP, socket.IP_TTL)])[0] == (39 if include else 37)
                info = ancillary[(socket.IPPROTO_IP, 8)]
                assert struct.unpack('=I', info[:4])[0] == socket.if_nametoindex('md-raw-b')
                assert info[8:12] == socket.inet_aton('192.0.2.2')
            sender.setsockopt(socket.IPPROTO_IP, socket.IP_HDRINCL, 0)
            try:
                sender.sendto(datagram, ('255.255.255.255', 0))
            except OSError as error:
                assert error.errno == errno.EACCES
            else:
                raise AssertionError('broadcast allowed without SO_BROADCAST')
            sender.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            assert sender.sendto(datagram, ('255.255.255.255', 0)) == len(datagram)
            packet, controls, flags, peer = receiver.recvmsg(512, 256)
            assert packet[20:] == datagram and not flags
            info = {(level, kind): value for level, kind, value in controls}[(socket.IPPROTO_IP, 8)]
            assert info[4:8] == socket.inet_aton('192.0.2.2')
            assert info[8:12] == socket.inet_aton('255.255.255.255')
            for hook, device in (('output', 'oifname "md-raw-a"'), ('input', 'iifname "md-raw-b"')):
                rules = ('add table ip md_raw\n'
                         f'add chain ip md_raw blocked {{ type filter hook {hook} priority 0; policy accept; }}\n'
                         f'add rule ip md_raw blocked {device} udp dport 67 counter drop\n')
                subprocess.run(['nft', '-f', '-'], input=rules, text=True, check=True)
                try:
                    try:
                        sender.sendto(datagram, ('192.0.2.2', 0))
                    except OSError as error:
                        assert hook == 'output' and error.errno in (errno.EACCES, errno.EPERM), error
                    else:
                        assert hook == 'input', 'output drop was not reported'
                    assert not select.select([receiver], [], [], 0.1)[0], 'raw socket bypassed input firewall'
                    dump = json.loads(subprocess.check_output(['nft', '-j', 'list', 'table', 'ip', 'md_raw']))
                    counters = [expr['counter'] for entry in dump['nftables'] if 'rule' in entry
                                for expr in entry['rule']['expr'] if 'counter' in expr]
                    assert len(counters) == 1 and counters[0]['packets'] == 1, counters
                finally:
                    subprocess.run(['nft', 'delete', 'table', 'ip', 'md_raw'], check=True)
                sender.sendto(datagram, ('192.0.2.2', 0))
                assert receiver.recv(512)[20:] == datagram
            ip('addr', 'add', '192.0.2.9/24', 'dev', 'md-raw-a')
            ip('route', 'add', '192.0.2.2/32', 'dev', 'md-raw-a', 'src', '192.0.2.9')
            route = subprocess.check_output(['ip', 'route', 'get', '192.0.2.2', 'oif', 'md-raw-a'], text=True)
            assert 'src 192.0.2.9' in route, route
            assert sender.sendto(datagram, ('192.0.2.2', 0)) == len(datagram)
            packet, peer = receiver.recvfrom(512)
            assert packet[20:] == datagram and peer[0] == '192.0.2.9'
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp, \
                    socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sink:
                sink.bind(('192.0.2.2', 0))
                sink.settimeout(5)
                udp.setsockopt(socket.SOL_SOCKET, socket.SO_BINDTODEVICE, b'md-raw-a\0')
                udp.sendto(b'preferred-source', sink.getsockname())
                data, peer = sink.recvfrom(128)
                assert data == b'preferred-source' and peer[0] == '192.0.2.9', peer
            invalid = subprocess.run(['ip', 'route', 'replace', '192.0.2.2/32', 'dev', 'md-raw-a',
                                      'src', '198.51.100.1'], capture_output=True)
            assert invalid.returncode != 0, 'route accepted a foreign preferred source'
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as server, \
                    socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client:
                server.bind(('192.0.2.2', 67))
                client.bind(('192.0.2.9', 68))
                server.settimeout(5)
                client.settimeout(5)
                sender.settimeout(5)
                sender.sendto(datagram, ('192.0.2.2', 0))
                data, peer = server.recvfrom(128)
                assert data == b'dhcp' and peer == ('192.0.2.9', 68), (data, peer)
                server.sendto(b'raw-to-udp-reply', peer)
                data, peer = client.recvfrom(128)
                assert data == b'raw-to-udp-reply' and peer == ('192.0.2.2', 67), (data, peer)
                packet, peer = sender.recvfrom(512)
                assert packet[28:] == b'raw-to-udp-reply' and peer[0] == '192.0.2.2', (packet, peer)
                client.close()
                sender.sendto(datagram, ('192.0.2.2', 0))
                data, peer = server.recvfrom(128)
                server.sendto(b'raw-only-reply', peer)
                packet, peer = sender.recvfrom(512)
                assert packet[28:] == b'raw-only-reply' and peer[0] == '192.0.2.2', (packet, peer)
                ip('addr', 'add', '192.0.2.10/24', 'dev', 'md-raw-b')
                sender.bind(('192.0.2.9', 0))
                rules = ('add table ip md_raw\n'
                         'add chain ip md_raw translate { type nat hook prerouting priority -100; }\n'
                         'add rule ip md_raw translate ip daddr 192.0.2.10 udp dport 67 dnat to 192.0.2.2:67\n')
                subprocess.run(['nft', '-f', '-'], input=rules, text=True, check=True)
                try:
                    for retained in (False, True):
                        if retained:
                            subprocess.run(['nft', 'flush', 'chain', 'ip', 'md_raw', 'translate'], check=True)
                        sender.sendto(datagram, ('192.0.2.10', 0))
                        data, peer = server.recvfrom(128)
                        assert data == b'dhcp' and peer == ('192.0.2.9', 68), (data, peer)
                        server.sendto(b'nat-reply', peer)
                        packet, peer = sender.recvfrom(512)
                        assert packet[28:] == b'nat-reply' and peer[0] == '192.0.2.10', (packet, peer)
                finally:
                    subprocess.run(['nft', 'delete', 'table', 'ip', 'md_raw'], check=True)
        with socket.socket(socket.AF_INET, socket.SOCK_RAW, socket.IPPROTO_ICMP) as sender, \
                socket.socket(socket.AF_INET, socket.SOCK_RAW, socket.IPPROTO_ICMP) as receiver:
            sender.setsockopt(socket.SOL_SOCKET, socket.SO_BINDTODEVICE, b'md-raw-a\0')
            receiver.setsockopt(socket.SOL_SOCKET, socket.SO_BINDTODEVICE, b'md-raw-b\0')
            receiver.settimeout(5)
            rule = ['INPUT', '-i', 'md-raw-b', '-p', 'icmp', '--icmp-type', 'echo-request', '-j', 'DROP']
            subprocess.run(['iptables', '-I', *rule], check=True)
            try:
                for kind in (8, 0):
                    words = (kind << 8) + 17 + 1
                    message = struct.pack('!BBHHH', kind, 0, (~words) & 65535, 17, 1)
                    assert sender.sendto(message, ('192.0.2.2', 0)) == len(message)
                    if kind == 8:
                        assert not select.select([receiver], [], [], 0.1)[0], 'ICMP bypassed xt input rule'
                    else:
                        assert receiver.recv(512)[20:] == message
            finally:
                subprocess.run(['iptables', '-D', *rule], check=True)
            message = struct.pack('!BBHHH', 8, 0, (~((8 << 8) + 18)) & 65535, 17, 1)
            sender.sendto(message, ('192.0.2.2', 0))
            assert receiver.recv(512)[20:] == message
    finally:
        ip('link', 'del', 'md-raw-a')
    print('PASS raw IPv4: virtual veth, IP_HDRINCL, checksum, source, packet info, TTL and broadcast authority', flush=True)


def guest():
    raw_ipv4()
    def ip(*args):
        subprocess.run(['ip', *args], check=True)

    def missing(receiver):
        try:
            receiver.recv(4096, socket.MSG_DONTWAIT)
        except BlockingIOError:
            return
        raise AssertionError('unexpected Ethernet frame')

    def receive(receiver, expected, interface, kind, size=4096, auxiliary=None):
        # EVENT_WAIT: packet queue readability; expiry fails delivery.
        assert select.select([receiver], [], [], 5)[0], 'Ethernet receive deadline'
        data, control, flags, source = receiver.recvmsg(size, 256)
        assert data == expected and source[0] == interface and source[1] == 0x88cc, (data, source)
        assert source[2] == kind and source[3] == 1, source
        if auxiliary is not None:
            items = {(level, kind): value for level, kind, value in control}
            status, length, snap, mac, net, vlan, tpid = struct.unpack('=IIIHHHH', items[(263, 8)])
            assert (length, snap, net) == auxiliary and status == 1 and mac == vlan == tpid == 0
            assert bool(flags & socket.MSG_TRUNC) == (size < snap)
        return source

    class Instruction(ctypes.Structure):
        _fields_ = [('code', ctypes.c_ushort), ('jt', ctypes.c_ubyte),
                    ('jf', ctypes.c_ubyte), ('k', ctypes.c_uint)]

    class Program(ctypes.Structure):
        _fields_ = [('len', ctypes.c_ushort), ('filter', ctypes.POINTER(Instruction))]

    ip('link', 'add', 'md-a', 'type', 'veth', 'peer', 'name', 'md-b')
    ip('link', 'set', 'md-a', 'up')
    ip('link', 'set', 'md-b', 'up')
    destination = b'\x01\x80\xc2\x00\x00\x0e'
    source_mac = b'\x02\x0a\x0b\x0c\x0d\x0e'
    payload = b'ethernet-packet-fixture'
    frame = destination + source_mac + b'\x88\xcc' + payload
    with socket.socket(socket.AF_PACKET, socket.SOCK_RAW, socket.htons(0x88cc)) as sender, \
            socket.socket(socket.AF_PACKET, socket.SOCK_RAW, socket.htons(0x88cc)) as receiver, \
            socket.socket(socket.AF_PACKET, socket.SOCK_DGRAM, socket.htons(0x88cc)) as datagram, \
            socket.socket(socket.AF_PACKET, socket.SOCK_RAW, socket.htons(3)) as monitor:
        sender.bind(('md-a', 0))
        receiver.bind(('md-b', 0))
        datagram.bind(('md-b', 0))
        monitor.bind(('md-a', 0))
        assert receiver.getsockopt(socket.SOL_SOCKET, 39) == socket.AF_PACKET
        membership = struct.pack('=iHH8s', socket.if_nametoindex('md-b'), 0, 6, destination)
        receiver.setsockopt(263, 1, membership)
        receiver.setsockopt(263, 1, membership)
        receiver.setsockopt(263, 2, membership)
        assert sender.send(frame) == len(frame)
        assert receive(receiver, frame, 'md-b', 2)[4] == source_mac
        receive(datagram, payload, 'md-b', 2)
        receive(monitor, frame, 'md-a', 4)
        monitor.setsockopt(263, 23, 1)
        assert monitor.getsockopt(263, 23) == 1
        receiver.setsockopt(263, 8, 1)  # PACKET_AUXDATA
        assert receiver.getsockopt(263, 8) == 1
        # Bytecode sees the Ethernet frame, not the private queue envelope.
        code = (Instruction * 4)(Instruction(0x28, 0, 0, 12), Instruction(0x15, 0, 1, 0x88cc),
                                 Instruction(0x06, 0, 0, 18), Instruction(0x06, 0, 0, 0))
        program = Program(len(code), code)
        libc = ctypes.CDLL(None, use_errno=True)
        assert libc.setsockopt(receiver.fileno(), socket.SOL_SOCKET, 26,
                               ctypes.byref(program), ctypes.sizeof(program)) == 0, ctypes.get_errno()
        alias = socket.socket(fileno=os.dup(receiver.fileno()))
        with alias:
            child = os.fork()
            if not child:
                try:
                    assert sender.sendmsg([frame[:7], frame[7:]], [], 0, ('md-a', 0x88cc)) == len(frame)
                    os._exit(0)
                except BaseException:
                    os._exit(1)
            assert os.waitpid(child, 0) == (child, 0)
            receive(alias, frame[:18], 'md-b', 2, auxiliary=(len(frame), 18, 14))
            receive(datagram, payload, 'md-b', 2)
            assert os.writev(sender.fileno(), [frame[:14], frame[14:]]) == len(frame)
            receive(alias, frame[:12], 'md-b', 2, size=12, auxiliary=(len(frame), 18, 14))
            receive(datagram, payload, 'md-b', 2)
            missing(monitor)
            receiver.setsockopt(socket.SOL_SOCKET, 27, 0)
        with socket.socket(socket.AF_PACKET, socket.SOCK_DGRAM, socket.htons(0x88cc)) as cooked:
            cooked.bind(('md-a', 0))
            assert cooked.sendto(payload, ('md-a', 0x88cc, 0, 1, destination)) == len(payload)
            data, source = receiver.recvfrom(4096)
            assert data[:6] == destination and data[12:] == b'\x88\xcc' + payload, data
            assert data[6:12] == cooked.getsockname()[4] and source[0] == 'md-b'
            receive(datagram, payload, 'md-b', 2)
        ip('link', 'set', 'md-b', 'name', 'md-renamed')
        assert receiver.getsockname()[0] == 'md-renamed'
        assert sender.send(frame) == len(frame)
        receive(receiver, frame, 'md-renamed', 2)
        receive(datagram, payload, 'md-renamed', 2)
        # A child namespace has only its own loopback and cannot bind the host link.
        child = os.fork()
        if not child:
            try:
                assert libc.unshare(0x40000000) == 0, ctypes.get_errno()
                with socket.socket(socket.AF_PACKET, socket.SOCK_RAW, socket.htons(3)) as isolated:
                    try:
                        isolated.bind(('md-a', 0))
                    except OSError as error:
                        assert error.errno in (errno.ENODEV, errno.ENXIO)
                    else:
                        raise AssertionError('foreign interface visible')
                # Retained socket authority survives entering another namespace.
                assert sender.send(frame) == len(frame)
                os.setgid(1000)
                os.setuid(1000)
                try:
                    socket.socket(socket.AF_PACKET, socket.SOCK_RAW, socket.htons(3))
                except OSError as error:
                    assert error.errno == errno.EPERM
                else:
                    raise AssertionError('CAP_NET_RAW was not checked')
                os._exit(0)
            except BaseException:
                import traceback
                traceback.print_exc()
                os._exit(1)
        assert os.waitpid(child, 0) == (child, 0)
        receive(receiver, frame, 'md-renamed', 2)
        receive(datagram, payload, 'md-renamed', 2)
        ip('link', 'del', 'md-a')
        try:
            sender.send(frame)
        except OSError as error:
            assert error.errno == errno.ENXIO
        else:
            raise AssertionError('deleted link accepted a frame')
    print('PASS packet sockets: veth, raw/dgram, source, BPF, membership, dup/fork, namespace and permissions', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('store')
    args = parser.parse_args()
    from test_oci_services import Suite
    suite = Suite(args.build, args.build)
    target = '/tmp/md-ethernet-' + suite.report['id']
    try:
        suite.transport.upload(suite.client, Path(__file__), suite.base + '/test.py')
        suite.command(suite.guest(args.store, '--user', '0:0', '--', '/bin/mkdir', target))
        for entry in (0, 1):
            suite.command(suite.guest(args.store, '--user', '0:0', '--network', 'bridge',
                                     '--env', f'SHROOT_MACHINE_ENTRY={entry}', '--bind-ro', suite.base, target,
                                     '--deadline-seconds', '60', '--', '/usr/bin/python3', target + '/test.py', '--guest'), timeout=80)
        suite.report['passed'] = True
    finally:
        try:
            suite.command(suite.guest(args.store, '--user', '0:0', '--', '/bin/rmdir', target))
        finally:
            suite.finish()


if __name__ == '__main__':
    guest() if sys.argv[1:] == ['--guest'] else main()
