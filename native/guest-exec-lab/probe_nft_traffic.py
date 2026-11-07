#!/usr/bin/env python3
"""Stock nft, live flow policy and externally observed TCP/UDP DNAT."""
import errno
import json
from pathlib import Path
import socket
import socketserver
import statistics
import subprocess
import sys
import threading
import time


def guest(observer):
    from docker_network_fixture import observe
    report = {'passed': False, 'commands': [], 'checks': []}

    def nft(program, success=True):
        # EVENT_WAIT: atomic nft transaction completion; timeout is failure.
        result = subprocess.run(['nft', '-f', '-'], input=program, text=True,
                                capture_output=True, timeout=20)
        report['commands'].append({'program': program, 'code': result.returncode,
                                   'stdout': result.stdout, 'stderr': result.stderr})
        if success and result.returncode:
            trace = subprocess.run(['strace', '-f', '-xx', '-s', '16384', '-e',
                                    'trace=sendmsg,sendto,recvmsg', 'nft', '--debug', 'netlink', '-f', '-'],
                                   input=program, text=True, capture_output=True, timeout=20)
            report['failureTrace'] = trace.stdout + trace.stderr
        assert (result.returncode == 0) == success, report['commands'][-1]

    class TCP(socketserver.BaseRequestHandler):
        def handle(self):
            self.request.settimeout(20)
            while data := self.request.recv(65536):
                self.request.sendall(b'nft-ports' if data == b'PING' else data)

    class UDP(socketserver.BaseRequestHandler):
        def handle(self):
            data, sock = self.request
            sock.sendto(b'nft-ports' if data == b'PING' else data, self.client_address)

    class TCPServer(socketserver.ThreadingTCPServer):
        allow_reuse_address = True
        daemon_threads = True

    class UDPServer(socketserver.UDPServer):
        allow_reuse_address = True
        daemon_threads = True

    def receive(sock, data):
        result = bytearray()
        while len(result) < len(data):
            chunk = sock.recv(len(data) - len(result))
            assert chunk, 'premature EOF'
            result.extend(chunk)
        assert result == data

    def benchmark(port):
        payload = b'x' * 65536
        samples = []
        for _ in range(5):
            with socket.create_connection(('127.0.0.1', port), timeout=10) as connection:
                for _ in range(16):
                    connection.sendall(payload)
                    receive(connection, payload)
                start = time.monotonic()
                for _ in range(512):
                    connection.sendall(payload)
                    receive(connection, payload)
                samples.append(time.monotonic() - start)
                connection.shutdown(socket.SHUT_WR)
                assert connection.recv(1) == b''
        return {'bytesEachDirection': 512 * len(payload), 'samples': samples,
                'medianSeconds': statistics.median(samples)}

    def datagram_benchmark(port):
        samples = []
        payload = b'u' * 128
        for _ in range(5):
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as connection:
                connection.settimeout(5)
                connection.sendto(payload, ('127.0.0.1', port))
                assert connection.recv(256) == payload
                start = time.monotonic()
                for _ in range(256):
                    connection.sendto(payload, ('127.0.0.1', port))
                    assert connection.recv(256) == payload
                samples.append(time.monotonic() - start)
        return {'roundTrips': 256, 'payloadBytes': len(payload), 'samples': samples,
                'medianSeconds': statistics.median(samples)}

    with TCPServer(('127.0.0.1', 38981), TCP) as tcp, UDPServer(('127.0.0.1', 38981), UDP) as udp, \
            TCPServer(('127.0.0.1', 0), TCP) as automatic, UDPServer(automatic.server_address, UDP) as automatic_udp:
        servers = (tcp, udp, automatic, automatic_udp)
        workers = [threading.Thread(target=server.serve_forever) for server in servers]
        for worker in workers:
            worker.start()
        try:
            port = automatic.server_address[1]
            observe(observer, operation='exchange', port=port, token='nft-ports')
            nft('add table inet autoport\n'
                'add chain inet autoport inbound { type filter hook input priority 0; policy accept; }\n'
                f'add rule inet autoport inbound meta l4proto {{ tcp, udp }} th dport {port} drop\n')
            observe(observer, operation='denied', port=port)
            nft('delete table inet autoport\n')
            observe(observer, operation='exchange', port=port, token='nft-ports')
            report['checks'].append('automatic-port-input-filter')
            nft('add table ip accounting\n'
                'add chain ip accounting output { type filter hook output priority 0; policy accept; }\n'
                'add chain ip accounting local\n'
                'add map ip accounting interfaces { type ifname : verdict; elements = { "lo" : jump local }; }\n'
                'add rule ip accounting output oifname vmap @interfaces\n'
                'add rule ip accounting local udp dport 38981 counter accept\n'
                'add rule ip accounting local tcp dport 38981 counter accept\n')

            def counters():
                output = subprocess.check_output(['nft', '-j', 'list', 'table', 'ip', 'accounting'], text=True)
                return [expr['counter'] for item in json.loads(output)['nftables']
                        for expr in item.get('rule', {}).get('expr', []) if 'counter' in expr]

            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as connection:
                connection.settimeout(5)
                for _ in range(5):
                    connection.sendto(b'c' * 64, ('127.0.0.1', 38981))
                    assert connection.recv(128) == b'c' * 64
                before = counters()
                assert before[0] == {'packets': 5, 'bytes': 320}, before
                assert counters() == before, 'query counted as traffic'
                nft('add table ip unrelated\n')
                connection.sendto(b'd' * 64, ('127.0.0.1', 38981))
                assert connection.recv(128) == b'd' * 64
                after = counters()
                assert after[0] == {'packets': 6, 'bytes': 384}, after
                nft('delete table ip unrelated\n')
            with socket.create_connection(('127.0.0.1', 38981), timeout=5) as connection:
                connection.sendall(b't' * 4096)
                receive(connection, b't' * 4096)
                totals = counters()
                assert totals[1]['bytes'] == 4096 and totals[1]['packets'] >= 1, totals
            nft('delete table ip accounting\n')
            report['checks'].append('verdict-map-traffic-and-generation-safe-counters')
            report['direct'] = benchmark(38981)
            with socket.create_connection(('127.0.0.1', 38981), timeout=5) as connection:
                nft('add table inet mdrollback\n'
                    'add chain inet mdrollback outbound { type filter hook output priority 0; policy accept; }\n'
                    'add rule inet mdrollback outbound tcp dport 38981 drop\n', success=False)
                assert 'busy' in report['commands'][-1]['stderr'].lower()
                connection.sendall(b'atomic-rejection')
                receive(connection, b'atomic-rejection')
            report['checks'].append('direct-flow-revocation-is-atomic-busy')
            nft('add table inet mdtraffic\n'
                'add chain inet mdtraffic outbound { type filter hook output priority 0; policy accept; }\n'
                'add rule inet mdtraffic outbound tcp dport 38981 drop\n')
            try:
                connection = socket.create_connection(('127.0.0.1', 38981), timeout=2)
            except OSError as error:
                assert error.errno == errno.EACCES, error
            else:
                connection.close()
                raise AssertionError('Denied TCP connection was admitted')
            report['checks'].append('tcp-denied')
            nft('flush chain inet mdtraffic outbound\n'
                'add rule inet mdtraffic outbound ct state invalid drop\n')
            report['filtered'] = benchmark(38981)
            report['udpFiltered'] = datagram_benchmark(38981)
            with socket.create_connection(('127.0.0.1', 38981), timeout=5) as connection:
                connection.sendall(b'before')
                receive(connection, b'before')
                nft('add rule inet mdtraffic outbound tcp dport 38981 drop\n')
                connection.sendall(b'held')
                connection.settimeout(0.3)
                try:
                    connection.recv(4)
                except TimeoutError:
                    pass
                else:
                    raise AssertionError('Established TCP ignored new denial')
                nft('flush chain inet mdtraffic outbound\n')
                connection.settimeout(5)
                receive(connection, b'held')
            report['checks'].append('established-tcp-policy-change')
            nft('add rule inet mdtraffic outbound ct state invalid drop\n')
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as connection:
                connection.settimeout(5)
                connection.connect(('127.0.0.1', 38981))
                connection.send(b'udp-before')
                assert connection.recv(100) == b'udp-before'
                nft('add rule inet mdtraffic outbound udp dport 38981 drop\n')
                connection.send(b'udp-denied')
                connection.settimeout(0.3)
                try:
                    connection.recv(100)
                except TimeoutError:
                    pass
                else:
                    raise AssertionError('Connected UDP ignored new denial')
                nft('flush chain inet mdtraffic outbound\n')
                connection.settimeout(5)
                connection.send(b'udp-after')
                assert connection.recv(100) == b'udp-after'
            report['checks'].append('connected-udp-policy-change')
            nft('add rule inet mdtraffic outbound ct state invalid drop\n')
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as connection:
                connection.settimeout(5)
                connection.sendto(b'addressed-before', ('127.0.0.1', 38981))
                assert connection.recv(100) == b'addressed-before'
                nft('add rule inet mdtraffic outbound ct state new udp dport 38981 drop\n')
                connection.sendto(b'addressed-established', ('127.0.0.1', 38981))
                assert connection.recv(100) == b'addressed-established'
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as fresh:
                    try:
                        fresh.sendto(b'new-denied', ('127.0.0.1', 38981))
                    except OSError as error:
                        assert error.errno == errno.EACCES, error
                    else:
                        raise AssertionError('NEW addressed UDP ignored denial')
            nft('flush chain inet mdtraffic outbound\n')
            report['checks'].append('addressed-udp-retains-established-state')
            nft('add chain inet mdtraffic inbound { type nat hook prerouting priority -100; policy accept; }\n'
                'add rule inet mdtraffic inbound ip saddr 127.0.0.0/8 tcp dport 38982 dnat ip to 127.0.0.1:38981\n'
                'add rule inet mdtraffic inbound ip saddr 127.0.0.0/8 udp dport 38982 dnat ip to 127.0.0.1:38981\n'
                'add chain inet mdtraffic localnat { type nat hook output priority -100; policy accept; }\n'
                'add rule inet mdtraffic localnat ip saddr 127.0.0.0/8 tcp dport 38982 dnat ip to 127.0.0.1:38981\n'
                'add rule inet mdtraffic localnat ip saddr 127.0.0.0/8 udp dport 38982 dnat ip to 127.0.0.1:38981\n')
            report['external'] = observe(observer, operation='exchange', port=38982, token='nft-ports')
            report['nat'] = benchmark(38982)
            report['udpNat'] = datagram_benchmark(38982)
            report['checks'].append('external-tcp-udp-dnat')
            observe(observer, operation='occupy', port=38983)
            nft('add rule inet mdtraffic inbound tcp dport 38984 dnat ip to 127.0.0.1:38981\n'
                'add rule inet mdtraffic inbound tcp dport 38983 dnat ip to 127.0.0.1:38981\n', success=False)
            report['rollback'] = observe(observer, operation='released', port=38984)
            nft('delete table inet mdtraffic\n')
            report['released'] = observe(observer, operation='released', port=38982)
            report['checks'].append('atomic-port-reservation-and-release')
            report['udpDirect'] = datagram_benchmark(38981)
            report['passed'] = True
        except Exception as error:
            report['failure'] = repr(error)
        finally:
            for server in servers:
                server.shutdown()
            for worker in workers:
                # EVENT_WAIT: server stop acknowledgement; timeout fails cleanup.
                worker.join(10)
                assert not worker.is_alive()
    print('NFT_TRAFFIC_REPORT ' + json.dumps(report), flush=True)


def main():
    from test_oci_services import Suite
    from docker_network_fixture import Observer
    from benchmark_cpu import observe_suite_cpu
    build, store = Path(sys.argv[1]), sys.argv[2]
    suite = Suite(Path('build/nft-results'), build)
    observer = Observer()
    target = '/tmp/md-nft-traffic-' + suite.report['id']
    runner = [suite.runner, '--store', store, '--user', '0:0', '--network', 'routed']
    created = False
    try:
        suite.upload(Path(__file__), suite.base + '/probe.py')
        suite.upload(Path(__file__).with_name('docker_network_fixture.py'),
                               suite.base + '/docker_network_fixture.py')
        suite.command(runner + ['--', '/bin/mkdir', target], quiet=True)
        created = True
        with observe_suite_cpu(suite) as cpu:
            suite.report['cpuFrequency'] = cpu
            output = suite.command(runner + ['--bind-ro', suite.base, target, '--deadline-seconds', '180', '--',
                                            '/usr/bin/python3', target + '/probe.py', '--guest', observer.address],
                                   quiet=True, timeout=200)
        reports = [line.removeprefix('NFT_TRAFFIC_REPORT ') for line in output.splitlines()
                   if line.startswith('NFT_TRAFFIC_REPORT ')]
        assert len(reports) == 1, output[-3000:]
        report = json.loads(reports[0])
        suite.report['traffic'] = report
        assert report['passed'], report.get('failure')
        suite.report['passed'] = True
        print('PASS nft traffic:', ', '.join(report['checks']))
        print('TCP round-trip benchmarks:', {key: report[key] for key in ('direct', 'filtered', 'nat')})
        print('UDP addressed round-trip benchmarks:', {key: report[key] for key in ('udpDirect', 'udpFiltered', 'udpNat')})
    finally:
        observer.close()
        suite.report['externalChecks'] = observer.records
        try:
            if created:
                suite.command(runner + ['--', '/bin/rmdir', target], quiet=True)
        finally:
            suite.finish()


if __name__ == '__main__':
    guest(sys.argv[2]) if len(sys.argv) > 1 and sys.argv[1] == '--guest' else main()
