#!/usr/bin/env python3
"""UDP multicast through libc, native queues and a namespace/bridge boundary."""
import argparse
import contextlib
import errno
import json
import os
from pathlib import Path
import select
import socket
import struct
import subprocess
import sys


def command(*args):
    result = subprocess.run(args, capture_output=True, text=True, timeout=15)
    assert result.returncode == 0, (args, result.stdout, result.stderr)
    return result.stdout


def options(family):
    if family == socket.AF_INET:
        return (socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, socket.IP_DROP_MEMBERSHIP,
                socket.IP_MULTICAST_IF, socket.IP_MULTICAST_TTL, socket.IP_MULTICAST_LOOP,
                49, 8, 12, socket.IP_TTL, '224.0.0.251', '0.0.0.0')
    return (socket.IPPROTO_IPV6, socket.IPV6_JOIN_GROUP, socket.IPV6_LEAVE_GROUP,
            socket.IPV6_MULTICAST_IF, socket.IPV6_MULTICAST_HOPS, socket.IPV6_MULTICAST_LOOP,
            29, socket.IPV6_RECVPKTINFO, socket.IPV6_RECVHOPLIMIT, socket.IPV6_HOPLIMIT, 'ff02::fb', '::')


def membership(family, group, index):
    address = socket.inet_pton(family, group)
    return address + (b'\0' * 4 if family == socket.AF_INET else b'') + struct.pack('=I', index)


def receiver(family, interface):
    level, join, _, _, _, _, all_groups, pktinfo, hops, _, group, any_address = options(family)
    stream = socket.socket(family, socket.SOCK_DGRAM)
    stream.settimeout(5)
    stream.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    stream.setsockopt(level, all_groups, 0)
    stream.setsockopt(level, pktinfo, 1)
    stream.setsockopt(level, hops, 1)
    stream.bind((any_address, 5353))
    stream.setsockopt(level, join, membership(family, group, socket.if_nametoindex(interface)))
    return stream


def received(stream, family, index, expected, hops=255):
    level, _, _, _, _, _, _, _, _, hop_kind, group, _ = options(family)
    data, ancillary, flags, source = stream.recvmsg(256, 256)
    assert data == expected and not flags, (data, flags, expected)
    values = {(lev, kind): value for lev, kind, value in ancillary}
    assert struct.unpack('=i', values[(level, hop_kind)])[0] == hops, values
    value = values[(level, 8 if family == socket.AF_INET else socket.IPV6_PKTINFO)]
    got_index = struct.unpack('=I', value[:4] if family == socket.AF_INET else value[16:])[0]
    destination = socket.inet_ntop(family, value[8:12] if family == socket.AF_INET else value[:16])
    assert got_index == index and destination == group, (got_index, destination, index, group)
    return source


def peer(family):
    with receiver(family, 'md-mc-peer') as stream:
        print('READY', flush=True)
        index = socket.if_nametoindex('md-mc-peer')
        for round_number in range(3):
            source = received(stream, family, index, str(round_number).encode(), 7 if round_number == 2 else 255)
            if family == socket.AF_INET6 and round_number != 0:
                # mDNS responders supply the interface in pktinfo, not sockaddr.
                stream.sendmsg([b'reply' + str(round_number).encode()],
                               [(socket.IPPROTO_IPV6, socket.IPV6_PKTINFO,
                                 struct.pack('=16sI', b'\0' * 16, index))],
                               0, (source[0], source[1], 0, 0))
            else:
                stream.sendto(b'reply' + str(round_number).encode(), source)
        assert not select.select([stream], [], [], .05)[0], 'duplicate bridge delivery'
    print('PASS peer', flush=True)


def guest():
    namespace = 'md-multicast-' + str(os.getpid())
    command('ip', 'netns', 'add', namespace)
    try:
        command('ip', 'link', 'add', 'md-mc-br', 'type', 'bridge')
        command('ip', 'link', 'add', 'md-mc-port', 'type', 'veth', 'peer', 'name', 'md-mc-peer')
        command('ip', 'link', 'set', 'md-mc-peer', 'netns', namespace)
        command('ip', 'link', 'set', 'md-mc-port', 'master', 'md-mc-br')
        command('ip', 'addr', 'add', '10.204.0.1/24', 'dev', 'md-mc-br')
        for link in ('md-mc-br', 'md-mc-port'):
            command('ip', 'link', 'set', link, 'up')
        command('ip', '-n', namespace, 'link', 'set', 'lo', 'up')
        command('ip', '-n', namespace, 'link', 'set', 'md-mc-peer', 'up')
        command('ip', '-n', namespace, 'addr', 'add', '10.204.0.2/24', 'dev', 'md-mc-peer')
        index = socket.if_nametoindex('md-mc-br')
        for family in (socket.AF_INET, socket.AF_INET6):
            level, join, leave, output, ttl, loop, _, _, _, hop_kind, group, _ = options(family)
            child = subprocess.Popen(['ip', 'netns', 'exec', namespace, sys.executable, __file__, '--peer', str(family)],
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                assert select.select([child.stdout], [], [], 10)[0], 'peer readiness timeout'
                assert child.stdout.readline().strip() == 'READY', child.communicate(timeout=5)
                with contextlib.ExitStack() as stack:
                    listeners = [stack.enter_context(receiver(family, 'md-mc-br')) for _ in range(2)]
                    sender = stack.enter_context(socket.socket(family, socket.SOCK_DGRAM))
                    sender.settimeout(5)
                    sender.setsockopt(level, output, struct.pack('=4s4sI', b'\0'*4, b'\0'*4, index)
                                      if family == socket.AF_INET else index)
                    sender.setsockopt(level, ttl, 255)
                    assert sender.getsockopt(level, ttl) == 255
                    # Duplicate joins are errors, not reference-counted subscriptions.
                    try:
                        listeners[0].setsockopt(level, join, membership(family, group, index))
                    except OSError as error:
                        assert error.errno == errno.EADDRINUSE, error
                    else:
                        raise AssertionError('duplicate membership accepted')
                    for round_number in range(3):
                        if round_number == 1:
                            listeners[0].setsockopt(level, leave, membership(family, group, index))
                            for input_rule in (False, True):
                                prefix = ['ip', 'netns', 'exec', namespace] if input_rule else []
                                hook = 'input' if input_rule else 'output'
                                command(*prefix, 'nft', 'add', 'table', 'inet', 'md_multicast')
                                try:
                                    command(*prefix, 'nft', 'add', 'chain', 'inet', 'md_multicast', 'filter',
                                            '{ type filter hook ' + hook + ' priority 0; policy accept; }')
                                    command(*prefix, 'nft', 'add', 'rule', 'inet', 'md_multicast', 'filter',
                                            'meta', 'pkttype', 'multicast', 'udp', 'dport', '5353', 'counter', 'drop')
                                    sender.setsockopt(level, loop, 0)
                                    sender.sendto(b'blocked', (group, 5353))
                                    assert not select.select(listeners + [sender], [], [], .1)[0], 'firewall bypass'
                                    rules = json.loads(command(*prefix, 'nft', '-j', 'list', 'chain', 'inet', 'md_multicast', 'filter'))
                                    counters = [expr['counter'] for item in rules['nftables'] if 'rule' in item
                                                for expr in item['rule']['expr'] if 'counter' in expr]
                                    assert len(counters) == 1 and counters[0]['packets'] == 1, counters
                                finally:
                                    command(*prefix, 'nft', 'delete', 'table', 'inet', 'md_multicast')
                                    sender.setsockopt(level, loop, 1)
                        if round_number == 2:
                            sender.setsockopt(level, loop, 0)
                        data = str(round_number).encode()
                        ancillary = [(level, hop_kind, struct.pack('=i', 7))] if round_number == 2 else []
                        sender.sendmsg([data], ancillary, 0, (group, 5353))
                        if round_number < 2:
                            for stream in listeners[round_number:]:
                                received(stream, family, index, data)
                        assert sender.recv(64) == b'reply' + data
                        assert not select.select(listeners, [], [], .05)[0], 'leave/loop policy or duplicate delivery'
                    assert sender.getsockopt(level, ttl) == 255, 'per-message hops changed the socket default'
                stdout, stderr = child.communicate(timeout=5)
                assert child.returncode == 0 and 'PASS peer' in stdout, (stdout, stderr)
            finally:
                if child.poll() is None:
                    child.kill()
                    child.communicate(timeout=5)
            print(f'PASS IPv{4 if family == socket.AF_INET else 6}: namespace bridge multicast, '
                  'fanout, leave/loop, packet info, per-message hops and unicast reply', flush=True)
    finally:
        for link in ('md-mc-port', 'md-mc-br'):
            command('ip', 'link', 'del', link)
        command('ip', 'netns', 'del', namespace)


def main():
    from fixture_executor import FixtureExecutor
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('store')
    parser.add_argument('--output', type=Path, default=Path('build/network-multicast-results'))
    FixtureExecutor.add_argument(parser)
    args = parser.parse_args()
    from test_oci_services import Suite
    suite = Suite(args.output, args.build, executor=args.executor)
    target = '/tmp/md-multicast-' + suite.report['id']
    try:
        suite.upload(Path(__file__), suite.base + '/test.py')
        suite.command(suite.guest(args.store, '--user', '0:0', '--', '/bin/mkdir', target))
        for entry in (0, 1):
            suite.command(suite.guest(args.store, '--user', '0:0', '--network', 'bridge',
                                     '--env', f'SHROOT_MACHINE_ENTRY={entry}', '--bind-ro', suite.base, target,
                                     '--deadline-seconds', '80', '--', '/usr/bin/python3', target + '/test.py', '--guest'), timeout=100)
        suite.report['passed'] = True
    finally:
        try:
            suite.command(suite.guest(args.store, '--user', '0:0', '--', '/bin/rmdir', target))
        finally:
            suite.finish()


if __name__ == '__main__':
    if sys.argv[1:] == ['--guest']:
        guest()
    elif sys.argv[1:2] == ['--peer']:
        peer(int(sys.argv[2]))
    else:
        main()
