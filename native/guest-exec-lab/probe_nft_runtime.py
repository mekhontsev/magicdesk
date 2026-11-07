#!/usr/bin/env python3
"""Exercise a distribution's unmodified nft against a private Shroot ruleset.

Catalogue/transaction coverage includes iptables-nft CRUD and an xt NAT exchange.
The separate traffic fixture covers broader filtering/NAT behavior. Unsupported
expressions must fail without committing earlier batch changes.
Routed mode publishes the DNS test port on Android and needs native bind access;
bridge mode checks the same rules and traffic in a private guest network.
"""
import argparse
import errno
import json
import socket
import struct
from pathlib import Path
import subprocess
import sys
import time


def guest():
    report = {'commands': [], 'passed': False}

    def run(argv, text=None, success=True):
        # EVENT_WAIT: exact nft child completion; timeout fails the fixture.
        result = subprocess.run(argv, input=text, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=20)
        report['commands'].append({'argv': argv, 'input': text, 'exitCode': result.returncode,
                                   'stdout': result.stdout, 'stderr': result.stderr})
        assert (result.returncode == 0) == success, report['commands'][-1]
        if success and argv[0].startswith('iptables'):
            assert not result.stderr, report['commands'][-1]
        return result.stdout

    def tables():
        ruleset = json.loads(run(['nft', '-j', 'list', 'ruleset']))
        return {(entry['table']['family'], entry['table']['name'])
                for entry in ruleset['nftables'] if 'table' in entry}

    def conntrack(operation, payload=b'', family=socket.AF_INET, expected=0):
        with socket.socket(socket.AF_NETLINK, socket.SOCK_RAW, 12) as control:
            control.bind((0, 0))
            control.settimeout(5)
            dump = operation == 1 and not payload
            flags = 1 | (0x300 if dump else 4)
            request = struct.pack('=IHHII', 20 + len(payload), 0x100 | operation, flags, 73, 0)
            control.sendto(request + struct.pack('!BBH', family, 0, 0) + payload, (0, 0))
            entries = []
            while True:
                # EVENT_WAIT: matching netlink ACK/DONE; timeout is failure.
                packet = control.recv(65536)
                offset = 0
                while offset < len(packet):
                    length, kind, _, sequence, _ = struct.unpack_from('=IHHII', packet, offset)
                    assert sequence == 73 and 16 <= length <= len(packet) - offset
                    data = packet[offset + 16:offset + length]
                    if kind == 2:
                        error, = struct.unpack_from('=i', data)
                        assert error == -expected, (error, expected)
                        return entries
                    if kind == 3:
                        assert expected == 0
                        return entries
                    assert kind == 0x100 and data[0] == family
                    entries.append(data[4:])
                    offset += (length + 3) & ~3

    def attributes(data):
        out = {}
        offset = 0
        while offset < len(data):
            length, kind = struct.unpack_from('=HH', data, offset)
            assert 4 <= length <= len(data) - offset
            out[kind & 0x3fff] = data[offset + 4:offset + length]
            offset += (length + 3) & ~3
        return out

    def destination_port(entry):
        return struct.unpack('!H', attributes(attributes(attributes(entry)[1])[2])[3])[0]

    try:
        run(['nft', '--version'])
        modules = Path('/proc/modules').read_text()
        assert any(line.split()[0] == 'nf_tables' for line in modules.splitlines())
        run(['unshare', '-m', '/bin/sh', '-ec',
             'mount -t proc proc /proc; grep -q "^nf_tables " /proc/modules'])
        controls = Path('/proc/sys/net/netfilter')
        expected = {'nf_conntrack_tcp_timeout_established', 'nf_conntrack_tcp_timeout_close_wait'}
        assert expected <= {entry.name for entry in controls.iterdir()}
        for name in expected:
            node = controls / name
            original = node.read_text()
            run(['unshare', '-n', '/bin/sh', '-ec', 'printf "123\\n" > "$1"; cat "$1"',
                 'test', str(node)])
            assert node.read_text() == original, 'Child namespace altered parent timeout'
        assert not tables()
        run(['nft', '-f', '-'], 'add table inet first\nadd table ip second\n')
        assert tables() == {('inet', 'first'), ('ip', 'second')}
        run(['nft', '-f', '-'], 'add table inet aborted\ncreate table inet first\n', success=False)
        assert tables() == {('inet', 'first'), ('ip', 'second')}
        run(['nft', '-f', '-'], 'add table inet unenforced\n'
            'add chain inet unenforced input { type filter hook input priority 0; policy accept; }\n'
            'add rule inet unenforced input limit rate 1/second drop\n', success=False)
        assert tables() == {('inet', 'first'), ('ip', 'second')}
        nested = json.loads(run(['unshare', '-n', 'nft', '-j', 'list', 'ruleset']))
        assert not any('table' in entry for entry in nested['nftables'])
        run(['nft', 'delete', 'table', 'inet', 'first'])
        assert tables() == {('ip', 'second')}
        run(['nft', 'flush', 'ruleset'])
        assert not tables()
        for command in ('iptables', 'ip6tables'):
            run([command, '-A', 'OUTPUT', '-m', 'conntrack', '!', '--ctstate',
                 'RELATED,ESTABLISHED,DNAT', '-j', 'ACCEPT'])
            run([command, '-t', 'nat', '-A', 'POSTROUTING', '-j', 'MASQUERADE', '--random-fully'])
            run([command, '-t', 'nat', '-S'])
        run(['nft', 'flush', 'ruleset'])
        for family, host, table, command in (
                (socket.AF_INET, '127.0.0.1', 'ip', 'iptables'),
                (socket.AF_INET6, '::1', 'ip6', 'ip6tables')):
            with socket.socket(family, socket.SOCK_STREAM) as server, \
                    socket.socket(family, socket.SOCK_DGRAM) as udp:
                server.bind((host, 0))
                server.listen(1)
                udp.bind((host, 0))
                udp.settimeout(2)
                port = server.getsockname()[1]
                for compat in (False, True):
                    if compat:
                        run([command, '-A', 'OUTPUT', '-p', 'tcp', '--dport', str(port),
                             '-j', 'REJECT', '--reject-with', 'tcp-reset'])
                    else:
                        run(['nft', '-f', '-'], f'add table {table} refused\n'
                            f'add chain {table} refused out {{ type filter hook output priority 0; policy accept; }}\n'
                            f'add rule {table} refused out tcp dport {port} reject with tcp reset\n')
                    with socket.socket(family, socket.SOCK_STREAM) as client:
                        client.settimeout(2)
                        # EVENT_WAIT: connection rejection; timeout is not a passing REJECT.
                        assert client.connect_ex((host, port)) == errno.ECONNREFUSED
                    with socket.socket(family, socket.SOCK_DGRAM) as client:
                        client.settimeout(2)
                        client.sendto(b'unaffected', udp.getsockname())
                        payload, peer = udp.recvfrom(64)
                        udp.sendto(payload, peer)
                        assert client.recv(64) == b'unaffected'
                    before = json.loads(run(['nft', '-j', 'list', 'ruleset']))
                    run(['nft', '-f', '-'], 'add table inet unsupported_limit\n'
                        'add chain inet unsupported_limit out { type filter hook output priority 0; policy accept; }\n'
                        'add rule inet unsupported_limit out limit rate 1/second drop\n', success=False)
                    assert json.loads(run(['nft', '-j', 'list', 'ruleset'])) == before
                    run(['nft', 'flush', 'ruleset'])
                with socket.socket(family, socket.SOCK_STREAM) as client:
                    run(['nft', '-f', '-'], f'add table {table} tracked\n'
                        f'add chain {table} tracked out {{ type filter hook output priority 0; policy accept; }}\n'
                        f'add rule {table} tracked out tcp dport {port} counter accept\n')
                    client.settimeout(2)
                    client.connect((host, port))
                    peer, _ = server.accept()
                    peer.settimeout(2)
                    client.sendall(b'live')
                    assert peer.recv(4) == b'live'
                    tracked = [entry for entry in conntrack(1, family=family) if destination_port(entry) == port]
                    assert len(tracked) == 1
                    conntrack(2, tracked[0], family=family)
                    assert not [entry for entry in conntrack(1, family=family) if destination_port(entry) == port]
                    client.sendall(b'kept')
                    assert peer.recv(4) == b'kept'
                    fresh = [entry for entry in conntrack(1, family=family) if destination_port(entry) == port]
                    assert len(fresh) == 1 and attributes(fresh[0])[12] != attributes(tracked[0])[12]
                    timeout_file = Path('/proc/sys/net/netfilter/nf_conntrack_tcp_timeout_established')
                    original = timeout_file.read_text()
                    timeout_file.write_text('1\n')
                    try:
                        deadline = time.monotonic() + 5
                        while time.monotonic() < deadline:
                            if not [entry for entry in conntrack(1, family=family) if destination_port(entry) == port]:
                                break
                            time.sleep(.05)  # Bounded observation of tracking expiration.
                        else:
                            raise AssertionError('TCP tracking did not expire')
                        peer.sendall(b'reply')
                        assert client.recv(5) == b'reply'
                        renewed = [entry for entry in conntrack(1, family=family) if destination_port(entry) == port]
                        assert len(renewed) == 1 and attributes(renewed[0])[12] != attributes(fresh[0])[12]
                    finally:
                        timeout_file.write_text(original)
                    peer.close()
                run(['nft', 'flush', 'ruleset'])
        run(['nft', '-f', '-'], ''.join(f'add table ip table{i}\n' for i in range(150)))
        assert tables() == {('ip', f'table{i}') for i in range(150)}
        run(['nft', 'flush', 'ruleset'])
        assert not tables()
        run(['nft', '-f', '-'], 'add table inet mdfilter\n'
            'add chain inet mdfilter forward { type filter hook forward priority 0; policy accept; }\n'
            'add chain inet mdfilter post { type nat hook postrouting priority 100; policy accept; }\n'
            'add chain inet mdfilter subchain\n')
        for chain, expression in [
                ('forward', 'meta mark set meta mark | 0x2000'),
                ('forward', 'meta mark & 0x2000 == 0x2000 accept'),
                ('forward', 'fib daddr type local jump subchain'),
                ('forward', 'ct state invalid drop'),
                ('forward', 'ct mark set ct mark | 0x1000'),
                ('forward', 'iifname "br-test" oifname != "br-test" jump subchain'),
                ('forward', 'ip daddr 10.88.0.0/16 ct state related,established accept'),
                ('forward', 'ip saddr 10.88.0.0/16 meta l4proto { tcp, udp } th dport 53 accept'),
                ('post', 'ip daddr != 224.0.0.0/4 masquerade')]:
            run(['nft', '--debug', 'netlink', '-f', '-'], f'add rule inet mdfilter {chain} {expression}\n')
        rules = json.loads(run(['nft', '-j', 'list', 'ruleset']))
        assert sum('rule' in item for item in rules['nftables']) == 9
        run(['nft', '-f', '-'], 'add rule inet mdfilter forward log accept\n', success=False)
        assert json.loads(run(['nft', '-j', 'list', 'ruleset'])) == rules
        run(['nft', 'delete', 'chain', 'inet', 'mdfilter', 'subchain'], success=False)
        assert json.loads(run(['nft', '-j', 'list', 'ruleset'])) == rules
        run(['nft', 'delete', 'chain', 'inet', 'mdfilter', 'forward'])
        run(['nft', 'delete', 'chain', 'inet', 'mdfilter', 'subchain'])
        remaining = json.loads(run(['nft', '-j', 'list', 'ruleset']))
        assert sum('rule' in item for item in remaining['nftables']) == 1
        run(['nft', 'flush', 'ruleset'])
        run(['nft', '-f', '-'], 'add table ip maps\n'
            'add chain ip maps input { type filter hook input priority 0; policy accept; }\n'
            'add chain ip maps target\n'
            'add map ip maps interfaces { type ifname : verdict; }\n'
            'add element ip maps interfaces { "lo" : jump target }\n'
            'add rule ip maps input iifname vmap @interfaces\n'
            'add rule ip maps target fib saddr type local counter packets 7 bytes 99 accept\n')
        run(['nft', '-f', '-'], 'table ip maps {\n map interfaces { type ifname : verdict; }\n}\n'
            'add element ip maps interfaces { "eth0" : jump target }\n'
            'add rule ip maps input oifname vmap @interfaces\n')
        maps = json.loads(run(['nft', '-j', 'list', 'ruleset']))
        assert any(item.get('map', {}).get('name') == 'interfaces' for item in maps['nftables'])
        counters = [expr['counter'] for item in maps['nftables'] for expr in item.get('rule', {}).get('expr', []) if 'counter' in expr]
        assert counters == [{'packets': 7, 'bytes': 99}], counters
        run(['nft', '-f', '-'], 'add rule ip maps target iifname vmap @interfaces\n', success=False)
        assert json.loads(run(['nft', '-j', 'list', 'ruleset'])) == maps
        run(['nft', 'delete', 'chain', 'ip', 'maps', 'target'], success=False)
        run(['nft', 'delete', 'map', 'ip', 'maps', 'interfaces'], success=False)
        run(['nft', '-f', '-'], 'flush chain ip maps input\nflush map ip maps interfaces\n'
            'delete chain ip maps target\ndelete map ip maps interfaces\ndelete table ip maps\n')
        run(['iptables', '-t', 'nat', '-N', 'MD_TEST'])
        run(['iptables', '-t', 'nat', '-I', 'OUTPUT', '-d', '127.0.0.11', '-j', 'MD_TEST'])
        run(['iptables', '-t', 'nat', '-S'])
        run(['iptables', '-t', 'nat', '-D', 'OUTPUT', '-d', '127.0.0.11', '-j', 'MD_TEST'])
        run(['iptables', '-t', 'nat', '-X', 'MD_TEST'])
        run(['iptables', '-t', 'filter', '-A', 'OUTPUT', '-p', 'tcp', '--dport', '1234', '-j', 'ACCEPT'])
        run(['nft', 'flush', 'ruleset'])
        # Exercise xt NAT translation with real traffic, not just accepted ABI.
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as server, socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client:
            server.settimeout(5)
            client.settimeout(5)
            server.bind(('127.0.0.11', 0))
            port = server.getsockname()[1]
            rule = ['-t', 'nat', '-A', 'OUTPUT', '-d', '127.0.0.11', '-p', 'udp', '--dport', '53',
                    '-j', 'DNAT', '--to-destination', f'127.0.0.11:{port}']
            run(['iptables', *rule])
            run(['iptables', '-t', 'nat', '-A', 'POSTROUTING', '-p', 'udp', '--dport', str(port),
                 '-j', 'MASQUERADE', '--to-ports', '40000-40003', '--random-fully'])
            run(['iptables', '-A', 'INPUT', '-p', 'udp', '--dport', str(port), '-m',
                 'conntrack', '!', '--ctstate', 'DNAT', '-j', 'DROP'])
            client.connect(('127.0.0.11', 53))
            client.send(b'XT_NAT')
            data, peer = server.recvfrom(100)
            assert data == b'XT_NAT'
            assert 40000 <= peer[1] <= 40003, peer
            server.sendto(b'REPLY', peer)
            assert client.recvfrom(100) == (b'REPLY', ('127.0.0.11', 53))
            entries = [entry for entry in conntrack(1) if destination_port(entry) == 53]
            assert len(entries) == 1, entries
            conntrack(1, entries[0][:4], expected=errno.EINVAL)
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as replacement:
                replacement.bind(('127.0.0.11', 0))
                replacement.settimeout(5)
                replacement_port = replacement.getsockname()[1]
                run(['iptables', '-t', 'nat', '-R', 'OUTPUT', '1', '-d', '127.0.0.11', '-p', 'udp',
                     '--dport', '53', '-j', 'DNAT', '--to-destination', f'127.0.0.11:{replacement_port}'])
                client.send(b'RETAINED')
                assert server.recvfrom(100)[0] == b'RETAINED'
                conntrack(2, entries[0])
                assert not [entry for entry in conntrack(1) if destination_port(entry) == 53]
                conntrack(2, entries[0], expected=errno.ENOENT)
                # The same connected/native carrier must survive deletion.
                client.send(b'FRESH')
                data, peer = replacement.recvfrom(100)
                assert data == b'FRESH'
                replacement.sendto(b'FRESH_REPLY', peer)
                assert client.recvfrom(100) == (b'FRESH_REPLY', ('127.0.0.11', 53))
                assert len([entry for entry in conntrack(1) if destination_port(entry) == 53]) == 1
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as untranslated:
                untranslated.settimeout(0.2)
                try:
                    untranslated.sendto(b'NO_NAT', server.getsockname())
                except OSError as error:
                    assert error.errno in (errno.EACCES, errno.EPERM)
                server.settimeout(0.2)
                try:
                    received = server.recvfrom(100)
                    raise AssertionError(('untranslated packet crossed DNAT-only rule', received))
                except socket.timeout:
                    pass
            run(['iptables-save', '-c'])
            run(['iptables', '-t', 'nat', '-A', 'POSTROUTING', '-s', '127.0.0.11', '-p', 'udp',
                 '--sport', str(port), '-j', 'SNAT', '--to-source', ':53'])
            run(['iptables', '-t', 'nat', '-S'])
        before = json.loads(run(['nft', '-j', 'list', 'ruleset']))
        run(['iptables', '-t', 'nat', '-A', 'OUTPUT', '-p', 'udp', '--dport', '54', '-j',
             'DNAT', '--to-destination', '127.0.0.11:8000-8001'], success=False)
        assert json.loads(run(['nft', '-j', 'list', 'ruleset'])) == before
        run(['nft', 'flush', 'ruleset'])
        run(['nft', '-f', '-'], 'add table ip anonymous\n'
            'add chain ip anonymous input { type filter hook input priority 0; policy accept; }\n'
            'add rule ip anonymous input iifname vmap { "lo" : accept, "eth0" : drop }\n')
        run(['nft', '-j', 'list', 'ruleset'])
        run(['nft', '-f', '-'], 'add table ip unrelated\n')
        run(['nft', '-j', 'list', 'ruleset'])
        run(['nft', 'flush', 'ruleset'])
        report['passed'] = True
    except Exception as error:
        report['failure'] = repr(error)
    print('NFT_REPORT ' + json.dumps(report), flush=True)


def main():
    from test_oci_services import Suite, add_machine_entry_argument
    from kernel_profile import KernelProfile
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('store')
    parser.add_argument('--network', choices=('routed', 'bridge'), default='routed')
    add_machine_entry_argument(parser)
    KernelProfile.add_argument(parser)
    args = parser.parse_args()
    suite = Suite(Path('build/nft-results'), args.build, machine_entry=args.machine_entry, kernel_profile=args.kernel_profile)
    suite.report['network'] = args.network
    target = '/tmp/md-nft-' + suite.report['id']
    runner = suite.guest(args.store, '--user', '0:0', '--network', args.network)
    created = False
    try:
        suite.upload(Path(__file__), suite.base + '/probe.py')
        suite.command(runner + ['--', '/bin/mkdir', target], quiet=True)
        created = True
        output = suite.command(runner + ['--bind-ro', suite.base, target, '--deadline-seconds', '180', '--',
                                        '/usr/bin/python3', target + '/probe.py', '--guest'], quiet=True, timeout=200)
        reports = [line.removeprefix('NFT_REPORT ') for line in output.splitlines() if line.startswith('NFT_REPORT ')]
        assert len(reports) == 1, output[-2000:]
        report = json.loads(reports[0])
        suite.report['nftables'] = report
        assert report['passed'], report.get('failure')
        suite.report['passed'] = True
        print('PASS stock nft/iptables-nft: TCP REJECT, catalogue, rollback, verdict maps, chain counters, xt NAT and cleanup')
    finally:
        try:
            if created:
                suite.command(runner + ['--', '/bin/rmdir', target], quiet=True)
        finally:
            try:
                suite.command(['rm', '-rf', suite.base], quiet=True)
            finally:
                suite.finish()


if __name__ == '__main__':
    guest() if '--guest' in sys.argv else main()
