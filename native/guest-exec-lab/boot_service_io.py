#!/usr/bin/env python3
"""Protocol readiness checks for packaged services during the stock boot test."""
import http.client
import ipaddress
import json
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


def firewalld():
    def handles():
        rules = json.loads(command('nft', '-j', 'list', 'table', 'inet', 'firewalld'))
        return {(item['rule']['chain'], item['rule']['handle'])
                for item in rules['nftables'] if 'rule' in item}

    assert command('firewall-cmd', '--state') == 'running'
    command('firewall-cmd', '--get-active-zones')
    before = handles()
    assert before, 'firewalld installed no rules'
    occupied = set()
    for entry in command('firewall-cmd', '--zone=public', '--list-ports').split():
        ports, protocol = entry.split('/')
        if protocol == 'udp':
            limits = [int(value) for value in ports.split('-')]
            occupied.update(range(limits[0], limits[-1] + 1))
    first = next((value for value in range(1, 65535)
                  if value not in occupied and value + 1 not in occupied), None)
    assert first is not None, 'no unused UDP port range for the lifecycle check'
    port = '%d-%d/udp' % (first, first + 1)
    query = ['firewall-cmd', '--zone=public', '--query-port=' + port]
    result = subprocess.run(query, text=True, capture_output=True, timeout=10)
    assert result.returncode == 1 and result.stdout.strip() == 'no', result
    assert command('firewall-cmd', '--zone=public', '--add-port=' + port) == 'success'
    try:
        assert command(*query) == 'yes'
        assert handles() > before, 'port addition did not install a new nft rule'
    finally:
        assert command('firewall-cmd', '--zone=public', '--remove-port=' + port) == 'success'
    result = subprocess.run(query, text=True, capture_output=True, timeout=10)
    assert result.returncode == 1 and result.stdout.strip() == 'no', result
    assert handles() == before, 'port removal left changed nft rules'
    print('PASS firewalld control plane, rule addition/removal and installed rules', flush=True)


if __name__ == '__main__':
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
