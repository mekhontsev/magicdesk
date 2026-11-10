#!/usr/bin/env python3
"""Protocol readiness checks for packaged services during the stock boot test."""
import http.client
import errno
import ipaddress
import json
import os
from pathlib import Path
import shlex
import socket
import struct
import subprocess
import sys
import time


def command(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT, timeout=10).strip()


def dns_name(packet, offset):
    labels, end, visited = [], None, set()
    while True:
        assert offset not in visited and offset < len(packet), 'invalid DNS name'
        visited.add(offset)
        length = packet[offset]
        offset += 1
        if length & 0xc0 == 0xc0:
            assert offset < len(packet)
            if end is None:
                end = offset + 1
            offset = ((length & 0x3f) << 8) | packet[offset]
        elif not length:
            return '.'.join(labels).lower(), end if end is not None else offset
        else:
            assert length <= 63 and offset + length <= len(packet)
            labels.append(packet[offset:offset + length].decode('ascii'))
            offset += length


def dns_addresses(packet, hostname, qtype):
    assert len(packet) >= 12
    _, flags, questions, answers, authority, additional = struct.unpack_from('!6H', packet)
    if not flags & 0x8000:
        return []
    offset, result = 12, []
    for _ in range(questions):
        _, offset = dns_name(packet, offset)
        offset += 4
    for _ in range(answers + authority + additional):
        name, offset = dns_name(packet, offset)
        kind, _, _, length = struct.unpack_from('!HHIH', packet, offset)
        offset += 10
        assert offset + length <= len(packet)
        if name == hostname.lower() and kind == qtype:
            result.append(str(ipaddress.ip_address(packet[offset:offset + length])))
        offset += length
    return result


def avahi():
    hostname = shlex.split(command('busctl', '--system', 'call', 'org.freedesktop.Avahi', '/',
                                   'org.freedesktop.Avahi.Server', 'GetHostNameFqdn'))[1]
    links = json.loads(command('ip', '-j', 'address', 'show', 'up'))
    for family, tag, qtype, group in ((socket.AF_INET, 'inet', 1, '224.0.0.251'),
                                      (socket.AF_INET6, 'inet6', 28, 'ff02::fb')):
        candidates = [(link, addr['local']) for link in links if link['ifname'] != 'lo'
                      for addr in link['addr_info'] if addr['family'] == tag]
        assert candidates, 'no ' + tag + ' interface for mDNS'
        link, local = candidates[0]
        query = struct.pack('!6H', 0, 0, 1, 0, 0, 0)
        query += b''.join(bytes([len(label)]) + label.encode() for label in hostname.split('.'))
        query += b'\0' + struct.pack('!HH', qtype, 0x8001)
        with socket.socket(family, socket.SOCK_DGRAM) as client:
            if family == socket.AF_INET:
                client.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(local))
                client.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 255)
                destination = (group, 5353)
                client.bind((local, 0))
            else:
                client.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_MULTICAST_IF, link['ifindex'])
                client.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_MULTICAST_HOPS, 255)
                destination = (group, 5353, 0, link['ifindex'])
                client.bind((local, 0, 0, link['ifindex']))
            deadline = time.monotonic() + 10
            while True:
                remaining = deadline - time.monotonic()
                assert remaining > 0, 'mDNS did not publish ' + hostname + ' on ' + tag
                client.sendto(query, destination)
                client.settimeout(min(1, remaining))
                # EVENT_WAIT: a DNS reply proves registration. Retransmission
                # follows the query deadline; elapsed time never implies ready.
                try:
                    response, peer = client.recvfrom(65535)
                except socket.timeout:
                    continue
                if peer[1] == 5353 and local in dns_addresses(response, hostname, qtype):
                    state = command('busctl', '--system', 'call', 'org.freedesktop.Avahi', '/',
                                    'org.freedesktop.Avahi.Server', 'GetState')
                    if state == 'i 2':
                        break
        print('PASS Avahi mDNS', tag, hostname, local, flush=True)


def cups():
    def attribute(tag, name, value):
        return bytes([tag]) + struct.pack('!H', len(name)) + name + struct.pack('!H', len(value)) + value
    request = struct.pack('!BBHI', 2, 0, 0x4002, 1) + b'\x01'
    request += attribute(0x47, b'attributes-charset', b'utf-8')
    request += attribute(0x48, b'attributes-natural-language', b'en') + b'\x03'
    connection = http.client.HTTPConnection('127.0.0.1', 631, timeout=5)
    try:
        started = time.monotonic()
        deadline = started + 5
        while True:
            try:
                connection.connect()
                break
            except ConnectionRefusedError:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise
                # EVENT_WAIT: the snap launcher can be active before cupsd
                # binds. Bounded connection retries never imply readiness.
                time.sleep(min(0.05, remaining))
        connection.request('POST', '/', request, {'Content-Type': 'application/ipp'})
        response = connection.getresponse()
        body = response.read(1024 * 1024)
        assert response.status == 200 and len(body) >= 8, (response.status, body[:200])
        _, _, status, ident = struct.unpack_from('!BBHI', body)
        # A clean installation may have no printers (client-error-not-found).
        assert ident == 1 and status in (0, 0x0406), (status, body[:200])
    finally:
        connection.close()
    print('PASS CUPS IPP Get-Printers', hex(status), 'readySeconds=%.3f' % (time.monotonic() - started), flush=True)


def firewalld_ports(protocol):
    occupied = set()
    for entry in command('firewall-cmd', '--zone=public', '--list-ports').split():
        ports, kind = entry.split('/')
        if kind == protocol:
            limits = [int(value) for value in ports.split('-')]
            occupied.update(range(limits[0], limits[-1] + 1))
    return occupied


def firewalld():
    def handles(port_range=None):
        rules = json.loads(command('nft', '-j', 'list', 'table', 'inet', 'firewalld'))
        return {(item['rule']['chain'], item['rule']['handle'])
                for item in rules['nftables'] if 'rule' in item and (port_range is None or
                    any(expression.get('match', {}).get('left') ==
                            {'payload': {'protocol': 'udp', 'field': 'dport'}} and
                        expression['match'].get('right') == {'range': port_range}
                        for expression in item['rule']['expr']))}

    assert command('firewall-cmd', '--state') == 'running'
    command('firewall-cmd', '--get-active-zones')
    assert handles(), 'firewalld installed no rules'
    occupied = firewalld_ports('udp')
    first = next((value for value in range(1, 65535)
                  if value not in occupied and value + 1 not in occupied), None)
    assert first is not None, 'no unused UDP port range for the lifecycle check'
    port_range = [first, first + 1]
    assert not handles(port_range)
    port = '%d-%d/udp' % (first, first + 1)
    query = ['firewall-cmd', '--zone=public', '--query-port=' + port]
    result = subprocess.run(query, text=True, capture_output=True, timeout=10)
    assert result.returncode == 1 and result.stdout.strip() == 'no', result
    assert command('firewall-cmd', '--zone=public', '--add-port=' + port) == 'success'
    try:
        assert command(*query) == 'yes'
        # NetworkManager may independently update interface/zone rules. Verify
        # the exact rule we own, not equality of the entire changing table.
        assert handles(port_range), 'port addition installed no matching nft rule'
    finally:
        assert command('firewall-cmd', '--zone=public', '--remove-port=' + port) == 'success'
    result = subprocess.run(query, text=True, capture_output=True, timeout=10)
    assert result.returncode == 1 and result.stdout.strip() == 'no', result
    assert not handles(port_range), 'port removal left its nft rule installed'
    print('PASS firewalld control plane, rule addition/removal and installed rules', flush=True)
    firewalld_traffic()


def firewalld_peer(descriptor):
    with socket.socket(fileno=descriptor) as channel:
        channel.settimeout(30)
        channel.send(b'namespace')
        assert channel.recv(32) == b'linked'
        command('ip', 'link', 'set', 'lo', 'up')
        command('ip', 'addr', 'add', '192.0.2.2/30', 'dev', 'eth0')
        command('ip', '-6', 'addr', 'add', 'fd92:32::2/64', 'dev', 'eth0')
        command('ip', 'link', 'set', 'eth0', 'up')
        channel.send(b'ready')
        # Retain the ports until peer teardown: a new policy verdict must use a
        # fresh conntrack tuple, not a previous accepted flow.
        clients = []
        try:
            while data := channel.recv(256):
                if data == b'stop':
                    break
                family, kind, address, port = json.loads(data)
                client = socket.socket(family, kind)
                clients.append(client)
                client.settimeout(3)
                client.bind(('192.0.2.2' if family == socket.AF_INET else 'fd92:32::2', 0))
                stage = 'connect'
                try:
                    client.connect((address, port))
                    stage = 'send'
                    client.sendall(b'firewalld')
                    stage = 'receive'
                    assert client.recv(16) == b'reply'
                    result = dict(ok=True)
                except OSError as error:
                    result = dict(ok=False, errno=error.errno, stage=stage,
                                  address=address, port=port, local=client.getsockname())
                channel.send(json.dumps(result).encode())
        finally:
            for client in clients:
                client.close()


def firewalld_traffic():
    interface = 'mdfw' + str(os.getpid())
    remote = interface + 'p'
    channel, child = socket.socketpair(socket.AF_UNIX, socket.SOCK_SEQPACKET)
    channel.settimeout(10)
    peer = subprocess.Popen(['unshare', '--net', sys.executable, __file__, 'firewalld-peer',
                             str(child.fileno())], pass_fds=(child.fileno(),))
    child.close()
    linked = assigned = False
    try:
        assert channel.recv(32) == b'namespace'
        command('ip', 'link', 'add', interface, 'type', 'veth', 'peer', 'name', remote)
        linked = True
        command('ip', 'link', 'set', remote, 'netns', str(peer.pid), 'name', 'eth0')
        command('ip', 'addr', 'add', '192.0.2.1/30', 'dev', interface)
        command('ip', '-6', 'addr', 'add', 'fd92:32::1/64', 'dev', interface)
        command('ip', 'link', 'set', interface, 'up')
        channel.send(b'linked')
        assert channel.recv(32) == b'ready'
        assert command('firewall-cmd', '--zone=public', '--add-interface=' + interface) == 'success'
        assigned = True
        for family, address in ((socket.AF_INET, '192.0.2.1'), (socket.AF_INET6, 'fd92:32::1')):
            for kind, protocol in ((socket.SOCK_STREAM, 'tcp'), (socket.SOCK_DGRAM, 'udp')):
                with socket.socket(family, kind) as server:
                    server.settimeout(5)
                    occupied = firewalld_ports(protocol)
                    # SteamOS opens high ports by default. Admission must be
                    # tested on a port outside the distribution's allowlist.
                    for candidate in range(1023, 511, -1):
                        if candidate in occupied:
                            continue
                        try:
                            server.bind((address, candidate))
                            break
                        except OSError as error:
                            if error.errno != errno.EADDRINUSE:
                                raise
                    else:
                        raise AssertionError('No unused port outside the zone allowlist')
                    if kind == socket.SOCK_STREAM:
                        server.listen()
                    port = str(server.getsockname()[1]) + '/' + protocol
                    opened = False
                    try:
                        for allowed in (False, True, False):
                            if allowed:
                                assert command('firewall-cmd', '--zone=public', '--add-port=' + port) == 'success'
                                opened = True
                            elif opened:
                                assert command('firewall-cmd', '--zone=public', '--remove-port=' + port) == 'success'
                                opened = False
                            channel.send(json.dumps([family, kind, address, server.getsockname()[1]]).encode())
                            if allowed:
                                if kind == socket.SOCK_STREAM:
                                    accepted, _ = server.accept()
                                    with accepted:
                                        accepted.settimeout(5)
                                        assert accepted.recv(16) == b'firewalld'
                                        accepted.sendall(b'reply')
                                else:
                                    data, sender = server.recvfrom(16)
                                    assert data == b'firewalld'
                                    server.sendto(b'reply', sender)
                            result = json.loads(channel.recv(256))
                            assert result['ok'] == allowed, (family, protocol, allowed, result)
                            if not allowed:
                                assert result['errno'] in (errno.ECONNREFUSED, errno.EHOSTUNREACH,
                                    errno.ENETUNREACH, errno.EACCES, errno.EPERM), result
                    finally:
                        if opened:
                            command('firewall-cmd', '--zone=public', '--remove-port=' + port)
                print('PASS firewalld traffic', family, protocol, 'reject/accept/reject', flush=True)
        channel.send(b'stop')
        assert peer.wait(timeout=10) == 0
    except Exception:
        for args in [('ip', '-j', 'address'), ('ip', '-j', 'route', 'show', 'table', 'all'),
                     ('firewall-cmd', '--get-active-zones'),
                     ('nft', '-j', 'list', 'table', 'inet', 'firewalld')]:
            try:
                print('TRAFFIC_DIAGNOSTICS', args, command(*args), flush=True)
            except Exception as error:
                print('TRAFFIC_DIAGNOSTICS', args, repr(error), flush=True)
        raise
    finally:
        try:
            if assigned:
                command('firewall-cmd', '--zone=public', '--remove-interface=' + interface)
            if linked:
                subprocess.run(['ip', 'link', 'del', interface], check=False, timeout=10)
        finally:
            channel.close()
            if peer.poll() is None:
                peer.kill()
                peer.wait(timeout=10)


if __name__ == '__main__':
    if sys.argv[1] == 'firewalld-peer':
        firewalld_peer(int(sys.argv[2]))
        sys.exit(0)
    try:
        {'avahi': avahi, 'cups': cups, 'firewalld': firewalld}[sys.argv[1]]()
    except Exception:
        if sys.argv[1] == 'cups':
            for filename in ('/var/snap/cups/common/var/log/cups/error_log',
                             '/snap/cups/current/scripts/run-cupsd'):
                path = Path(filename)
                if path.is_file():
                    print(filename, path.read_text(errors='replace')[-16384:], flush=True)
            subprocess.run(['ps', '-eo', 'pid,ppid,stat,wchan:30,args'], check=False)
            subprocess.run(['ss', '-lnt'], check=False)
        raise
