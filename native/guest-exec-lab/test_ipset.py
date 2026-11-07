#!/usr/bin/env python3
"""Stock ipset/iptables-nft: shared policy, atomic replacement and live traffic."""
import argparse
import json
from pathlib import Path
import socket
import struct
import select
import subprocess
import sys


def guest():
    def run(*args, text=None, success=True):
        # EVENT_WAIT: exact command exit; timeout fails, never implies success.
        result = subprocess.run(args, input=text, text=True, capture_output=True, timeout=20)
        print(json.dumps({'argv': args, 'status': result.returncode,
                          'out': result.stdout, 'err': result.stderr}), flush=True)
        assert (result.returncode == 0) == success, result
        if success:
            assert 'not supported' not in result.stderr.lower(), result.stderr
        return result.stdout

    assert run('ipset', 'save') == ''
    for family, address, network, outside, command in (
            ('inet', '127.0.0.1', '127.0.0.0/24', '127.0.1.1', 'iptables-nft'),
            ('inet6', '::1', '::/120', '::101', 'ip6tables-nft')):
        run('ipset', 'create', 'members', 'hash:net', 'family', family)
        run('ipset', 'add', 'members', network)
        run('ipset', 'test', 'members', address)
        run('ipset', 'test', 'members', outside, success=False)
        run('ipset', 'add', 'members', address, 'nomatch')
        run('ipset', 'test', 'members', address, success=False)
        saved = run('ipset', 'save')
        assert 'nomatch' in saved and 'members' in saved
        run('ipset', 'destroy', 'members')
        run('ipset', 'restore', text=saved)
        run('ipset', 'test', 'members', address, success=False)
        run('ipset', 'del', 'members', address)
        run('ipset', 'test', 'members', address)
        run('ipset', 'flush', 'members')
        run('ipset', 'create', 'replacement', 'hash:net', 'family', family)
        af = socket.AF_INET if family == 'inet' else socket.AF_INET6
        with socket.socket(af, socket.SOCK_STREAM) as listener:
            listener.bind((address, 0))
            listener.listen(1)
            listener.settimeout(5)
            port = listener.getsockname()[1]
            rule = ['-p', 'tcp', '-m', 'multiport', '--dports', str(port - 1) + ':' + str(port),
                    '-m', 'set', '--match-set', 'members', 'dst',
                    '-j', 'REJECT', '--reject-with', 'tcp-reset']
            run(command, '-A', 'OUTPUT', *rule)
            run(command, '-C', 'OUTPUT', *rule)
            assert 'members' in run(command + '-save')
            with socket.create_connection((address, port), timeout=5) as client:
                peer, _ = listener.accept()
                with peer:
                    peer.settimeout(5)
                    client.sendall(b'before')
                    assert peer.recv(6) == b'before'
                    run('ipset', 'add', 'replacement', address)
                    run('ipset', 'swap', 'members', 'replacement')
                    # EVENT_WAIT: policy revalidation must reject/reset the retained flow.
                    try:
                        client.sendall(b'after')
                        assert client.recv(1) == b''
                    except ConnectionResetError:
                        pass
            run('ipset', 'destroy', 'members', success=False)
            try:
                socket.create_connection((address, port), timeout=3).close()
            except ConnectionRefusedError:
                pass
            else:
                raise AssertionError('set policy did not deny a new connection')
            run('ipset', 'flush', 'members')
            with socket.create_connection((address, port), timeout=5) as client:
                peer, _ = listener.accept()
                with peer:
                    client.sendall(b'restored')
                    assert peer.recv(8) == b'restored'
            run(command, '-D', 'OUTPUT', *rule)
            physical = ['-p', 'tcp', '--dport', str(port), '-m', 'physdev',
                        '!', '--physdev-is-bridged', '-j', 'REJECT', '--reject-with', 'tcp-reset']
            run(command, '-A', 'OUTPUT', *physical)
            try:
                socket.create_connection((address, port), timeout=3).close()
            except ConnectionRefusedError:
                pass
            else:
                raise AssertionError('unbridged predicate did not match loopback')
            run(command, '-D', 'OUTPUT', *physical)
            with socket.socket(socket.AF_NETLINK, socket.SOCK_RAW, 12) as logs:
                logs.bind((0, 0))
                logs.settimeout(3)

                def config(kind, value):
                    attribute = struct.pack('HH', 4 + len(value), kind) + value
                    attribute += bytes((-len(attribute)) % 4)
                    payload = struct.pack('!BBH', af, 0, 100) + attribute
                    logs.send(struct.pack('IHHII', 16 + len(payload), 4 << 8 | 1, 5, 1, 0) + payload)
                    reply = logs.recv(4096)
                    assert struct.unpack_from('H', reply, 4)[0] == 2 and struct.unpack_from('i', reply, 16)[0] == 0, reply.hex()

                config(1, bytes([1]))
                config(2, struct.pack('!IBB', 0, 1, 0))
                diagnostic = ['-p', 'tcp', '--dport', str(port), '-m', 'limit', '--limit', '1/hour',
                              '--limit-burst', '2', '-j', 'NFLOG', '--nflog-group', '100', '--nflog-prefix', 'md-policy']
                run(command, '-A', 'OUTPUT', *diagnostic)
                for _ in range(3):
                    with socket.create_connection((address, port), timeout=5):
                        peer, _ = listener.accept()
                        peer.close()
                events = []
                while select.select([logs], [], [], 0)[0]:
                    events.append(logs.recv(4096))
                assert len(events) == 2 and all(b'md-policy' in event for event in events), events
                run(command, '-D', 'OUTPUT', *diagnostic)
                # Never accept an unenforced rate-dependent forwarding policy.
                run(command, '-A', 'OUTPUT', '-m', 'limit', '--limit', '1/hour', '-j', 'DROP', success=False)
                config(1, bytes([2]))
        run('ipset', 'destroy')
    run('ipset', 'create', 'unsupported', 'hash:ip', 'timeout', '10', success=False)
    assert run('ipset', 'save') == ''
    # A single nft batch exceeds the inline adapter frame. Its replacement
    # remains atomic, including when the last rule is rejected.
    rules = '\n'.join('-A md-batch -p tcp --dport ' + str(1000 + i)
                      + ' -m comment --comment "' + 'x' * 180 + '" -j RETURN'
                      for i in range(180))
    batch = '*filter\n:md-batch - [0:0]\n' + rules + '\nCOMMIT\n'
    run('iptables-nft-restore', '--noflush', text=batch)
    saved = run('iptables-nft-save')
    assert saved.count('-A md-batch ') == 180
    rejected = batch.replace('\nCOMMIT\n', '\n-A md-batch -m limit --limit 1/hour -j DROP\nCOMMIT\n')
    run('iptables-nft-restore', text=rejected, success=False)
    assert [line for line in run('iptables-nft-save').splitlines() if not line.startswith('#')] == [
        line for line in saved.splitlines() if not line.startswith('#')]
    run('iptables-nft', '-F', 'md-batch')
    run('iptables-nft', '-X', 'md-batch')
    print('PASS ipset: stock CLI, IPv4/IPv6, prefix exclusions, restore, xt match, live swap/reset and cleanup', flush=True)


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
    target = '/tmp/md-ipset-' + suite.report['id']
    created = False
    prefix = None
    try:
        store = suite.installed_environment(args.environment)['store']
        prefix = suite.guest(store, '--user', '0:0')
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
    guest() if sys.argv[1:] == ['--guest'] else main()
