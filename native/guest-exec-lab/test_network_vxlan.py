#!/usr/bin/env python3
"""VXLAN with stock iproute2: namespaces, neighbors, packet traffic and lifetime."""
import argparse
import ctypes
import errno
import json
import os
from pathlib import Path
import select
import socket
import subprocess
import sys
import time
import traceback


def run(*args, success=True):
    result = subprocess.run(args, text=True, capture_output=True, timeout=15)
    print(json.dumps({'argv': args, 'status': result.returncode,
                      'out': result.stdout, 'err': result.stderr}), flush=True)
    assert (result.returncode == 0) == success, result
    return result.stdout


def wire():
    run('ip', 'link', 'add', 'md-wire-a', 'type', 'veth', 'peer', 'name', 'md-wire-b')
    try:
        for name, address in (('md-wire-a', '192.0.2.1/24'), ('md-wire-b', '192.0.2.2/24')):
            run('ip', 'addr', 'add', address, 'dev', name)
            run('ip', 'link', 'set', name, 'up')
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as peer:
            peer.bind(('192.0.2.2', 4790))
            peer.settimeout(5)
            def receive(timeout=5):
                deadline = time.monotonic() + timeout
                while time.monotonic() < deadline:
                    peer.settimeout(max(0.001, deadline - time.monotonic()))
                    data, address = peer.recvfrom(4096)
                    assert len(data) >= 22 and data[:4] == bytes((8, 0, 0, 0)), data
                    # IPv6 link-local discovery is legitimate background traffic.
                    if data[20:22] == bytes.fromhex('88b5'):
                        return data, address
                raise TimeoutError('wire payload did not arrive')
            for vni in (81, 82):
                run('ip', 'link', 'add', f'md-v{vni}', 'address', '02:ab:cd:ef:00:01',
                    'type', 'vxlan', 'id', str(vni), 'dev', 'md-wire-a', 'local', '192.0.2.1',
                    'remote', '192.0.2.2', 'dstport', '4790', 'nolearning')
                run('ip', 'link', 'set', f'md-v{vni}', 'up')
            for vni in (81, 82):
                with socket.socket(socket.AF_PACKET, socket.SOCK_RAW, socket.htons(0x88b5)) as packet:
                    packet.bind((f'md-v{vni}', 0))
                    packet.settimeout(5)
                    frame = bytes.fromhex('02abcdef000202abcdef000188b5') + b'wire-out'
                    header = bytes((8, 0, 0, 0, 0, 0, vni, 0))
                    assert packet.send(frame) == len(frame)
                    received, address = receive()
                    assert received == header + frame and address[0] == '192.0.2.1', (received, address)
                    response = frame[6:12] + frame[:6] + frame[12:14] + b'wire-in'
                    for invalid in (b'\0' * 8, bytes((8, 1, 0, 0, 0, 0, vni, 0)),
                                    bytes((8, 0, 0, 0, 0, 0, 90, 0))):
                        peer.sendto(invalid + response[:-1] + b'!', address)
                    peer.sendto(header + response, address)
                    assert packet.recv(4096) == response, 'malformed/foreign VNI frame delivered'
                    try:
                        packet.send(frame[:14] + b'x' * 1451)
                    except OSError as error:
                        assert error.errno == errno.EMSGSIZE, error
                    else:
                        raise AssertionError('VXLAN MTU was ignored')
                    rules = ('add table ip md_vxlan\n'
                             'add chain ip md_vxlan out { type filter hook output priority 0; policy accept; }\n'
                             'add rule ip md_vxlan out udp dport 4790 counter drop\n')
                    subprocess.run(['nft', '-f', '-'], input=rules, text=True, check=True)
                    try:
                        try:
                            packet.send(frame + b'-blocked')
                        except OSError as error:
                            assert error.errno in (errno.EACCES, errno.EPERM), error
                        # The outer datagram is filtered by the shared relay;
                        # successful Ethernet enqueue is not delivery proof.
                        try:
                            receive(0.2)
                        except TimeoutError:
                            pass
                        else:
                            raise AssertionError('VXLAN bypassed output filter')
                        ruleset = json.loads(run('nft', '-j', 'list', 'table', 'ip', 'md_vxlan'))
                        counters = [expression['counter']['packets'] for entry in ruleset['nftables'] if 'rule' in entry
                                    for expression in entry['rule']['expr'] if 'counter' in expression]
                        assert counters and sum(counters) > 0, counters
                    finally:
                        run('nft', 'delete', 'table', 'ip', 'md_vxlan')
                    frame += b'-after-policy'
                    packet.send(frame)
                    assert receive()[0] == header + frame
                run('ip', 'link', 'del', f'md-v{vni}')
            # The shared UDP port is released only after its last VNI.
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as released:
                released.bind(('192.0.2.1', 4790))
        print('PASS independent VXLAN wire peer, VNI isolation, MTU, filtering and shared-port retirement', flush=True)
    finally:
        run('ip', 'link', 'del', 'md-wire-a')


def setup(number, version, routed):
    underlay = f'192.0.2.{number}' if version == 4 else f'fd00:123::{number}'
    peer = '192.0.2.2' if version == 4 else 'fd00:123::2'
    if number == 2:
        peer = '192.0.2.1' if version == 4 else 'fd00:123::1'
    run('ip', 'link', 'set', 'lo', 'up')
    run('ip', 'link', 'set', f'md-u{number}', 'up')
    run('ip', 'addr', 'add', underlay + ('/24' if version == 4 else '/64'),
        'dev', f'md-u{number}', *(['nodad'] if version == 6 else []))
    run('ip', 'link', 'add', 'md-vx', 'address', f'02:aa:bb:cc:dd:0{number}', 'type',
        'vxlan', 'id', '77', 'dev', f'md-u{number}', 'local', underlay,
        'dstport', '4789', 'nolearning')
    run('ip', 'link', 'set', 'md-vx', 'mtu', '1400', 'up')
    if routed:
        run('ip', 'link', 'add', 'md-lan', 'type', 'bridge')
        run('ip', 'link', 'set', 'md-lan', 'up')
    for address in (f'198.51.100.{number}/24', f'fd00:456::{number}/64'):
        if routed:
            address = address.split('/')[0] + ('/128' if ':' in address else '/32')
        run('ip', 'addr', 'add', address, 'dev', 'md-lan' if routed else 'md-vx',
            *(['nodad'] if ':' in address else []))
    remote = 3 - number
    if routed:
        for local, gateway, destination in ((f'198.18.{number}.0/32', f'198.18.{remote}.0', f'198.51.100.{remote}/32'),
                                           (f'fd00:789::{number}/128', f'fd00:789::{remote}', f'fd00:456::{remote}/128')):
            run('ip', 'addr', 'add', local, 'dev', 'md-vx', *(['nodad'] if ':' in local else []))
            run('ip', 'route', 'add', destination, 'via', gateway, 'dev', 'md-vx', 'onlink')
    for address in (f'198.51.100.{remote}', f'fd00:456::{remote}'):
        if routed:
            address = f'fd00:789::{remote}' if ':' in address else f'198.18.{remote}.0'
        run('ip', 'neigh', 'replace', address, 'lladdr', f'02:aa:bb:cc:dd:0{remote}',
            'nud', 'permanent', 'dev', 'md-vx')
    run('bridge', 'fdb', 'replace', f'02:aa:bb:cc:dd:0{remote}', 'dev', 'md-vx', 'dst', peer, 'self', 'permanent')
    assert '77' in run('ip', '-d', 'link', 'show', 'md-vx')
    assert f'02:aa:bb:cc:dd:0{remote}' in run('bridge', 'fdb', 'show', 'dev', 'md-vx')
    for family in ('-4', '-6'):
        assert 'PERMANENT' in run('ip', family, 'neigh', 'show', 'dev', 'md-vx')


def serve(channel, version, routed):
    libc = ctypes.CDLL(None, use_errno=True)
    assert libc.unshare(0x40000000) == 0, ctypes.get_errno()
    channel.send(b'namespace')
    assert channel.recv(32) == b'linked'
    setup(2, version, routed)
    servers = []
    try:
        for family, host in ((socket.AF_INET, '198.51.100.2'), (socket.AF_INET6, 'fd00:456::2')):
            for kind in (socket.SOCK_STREAM, socket.SOCK_DGRAM):
                server = socket.socket(family, kind)
                server.bind((host, 18080))
                if kind == socket.SOCK_STREAM:
                    server.listen(8)
                servers.append(server)
        channel.send(b'ready')
        while True:
            # EVENT_WAIT: traffic or explicit cancellation; silence fails the fixture.
            ready, _, _ = select.select([channel, *servers], [], [], 30)
            assert ready, 'no traffic or completion request'
            if channel in ready:
                assert channel.recv(32) == b'stop'
                break
            for server in ready:
                if server.type == socket.SOCK_DGRAM:
                    data, peer = server.recvfrom(65535)
                    server.sendto(data, peer)
                else:
                    client, _ = server.accept()
                    with client:
                        client.settimeout(5)
                        while data := client.recv(4096):
                            client.sendall(data)
        run('ip', 'link', 'del', 'md-vx')
        assert not run('ip', 'neigh', 'show')
        channel.send(b'done')
    finally:
        for server in servers:
            server.close()


def pair(version, routed=False):
    parent, child = socket.socketpair(socket.AF_UNIX, socket.SOCK_SEQPACKET)
    parent.settimeout(30)
    child.settimeout(30)
    pid = os.fork()
    if pid == 0:
        parent.close()
        try:
            serve(child, version, routed)
        except BaseException:
            traceback.print_exc()
            os._exit(1)
        os._exit(0)
    child.close()
    linked = False
    try:
        assert parent.recv(32) == b'namespace'
        run('ip', 'link', 'add', 'md-u1', 'type', 'veth', 'peer', 'name', 'md-u2')
        linked = True
        run('ip', 'link', 'set', 'md-u2', 'netns', str(pid))
        parent.send(b'linked')
        setup(1, version, routed)
        assert parent.recv(32) == b'ready'
        for family, host in ((socket.AF_INET, '198.51.100.2'), (socket.AF_INET6, 'fd00:456::2')):
            for kind in (socket.SOCK_STREAM, socket.SOCK_DGRAM):
                with socket.socket(family, kind) as client:
                    client.settimeout(8)
                    if routed:
                        client.bind(('198.51.100.1' if family == socket.AF_INET else 'fd00:456::1', 0))
                    client.connect((host, 18080))
                    payload = bytes(range(256)) * (256 if kind == socket.SOCK_STREAM else 32)
                    for _ in range(3):
                        client.sendall(payload)
                        received = b''
                        while len(received) < len(payload):
                            chunk = client.recv(65535)
                            assert chunk
                            received += chunk
                        assert received == payload
                print('PASS VXLAN', version, family, kind, 'routed' if routed else 'local', flush=True)
        parent.send(b'stop')
        assert parent.recv(32) == b'done'
        waited, status = os.waitpid(pid, 0)
        pid = 0
        assert status == 0, status
        # The peer's namespace teardown removes the underlay veth pair and
        # every tunnel bound to it, including this namespace's endpoint.
        assert not any(link['ifname'] in ('md-vx', 'md-u1')
                       for link in json.loads(run('ip', '-j', 'link', 'show')))
        assert not run('ip', 'neigh', 'show')
    finally:
        if pid:
            os.kill(pid, 9)
            os.waitpid(pid, 0)
        parent.close()
        if linked and any(item['ifname'] == 'md-u1' for item in json.loads(run('ip', '-j', 'link', 'show'))):
            run('ip', 'link', 'del', 'md-u1')
        if routed:
            run('ip', 'link', 'del', 'md-lan')


def main():
    from test_oci_services import Suite, add_machine_entry_argument
    from kernel_profile import KernelProfile
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('environment')
    parser.add_argument('--output', type=Path)
    add_machine_entry_argument(parser)
    KernelProfile.add_argument(parser)
    args = parser.parse_args()
    suite = Suite(args.output or args.build, args.build, machine_entry=args.machine_entry, kernel_profile=args.kernel_profile)
    target = '/tmp/md-vxlan-' + suite.report['id']
    created = False
    try:
        prefix = suite.guest(suite.installed_environment(args.environment)['store'], '--user', '0:0')
        suite.upload(Path(__file__), suite.base + '/test.py')
        suite.command(prefix + ['--', '/bin/mkdir', target])
        created = True
        suite.command(prefix + ['--bind-ro', suite.base, target, '--network', 'routed', '--deadline-seconds', '180', '--',
                               '/usr/bin/python3', target + '/test.py', '--guest'], timeout=200)
        suite.report['passed'] = True
    finally:
        try:
            if created:
                suite.command(prefix + ['--', '/bin/rmdir', target])
            suite.command(['rm', '-rf', suite.base])
        finally:
            suite.finish()


if __name__ == '__main__':
    if sys.argv[1:] == ['--guest']:
        wire()
        pair(4)
        pair(6)
        pair(4, routed=True)
        pair(6, routed=True)
    else:
        main()
