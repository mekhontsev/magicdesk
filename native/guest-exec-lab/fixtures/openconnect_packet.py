"""Distribution ocserv/OpenConnect with certificate authentication and stock scripts."""
import socket
import subprocess
import sys
import time

from openvpn_packet import certificates
from probe_packet_runtime import run, service, echoes, wait_port, tunnel_network


def openconnect_guest(directory, assets):
    run('openconnect', '--version')
    run('ocserv', '--version')
    certificates(directory)
    # The unprivileged server worker must traverse its private control directory.
    directory.chmod(0o755)
    config = directory / 'ocserv.conf'
    config.write_text(f'''auth = "certificate"
cert-user-oid = 2.5.4.3
listen-host = 192.0.2.1
tcp-port = 4443
udp-port = 4443
run-as-user = nobody
run-as-group = nogroup
socket-file = {directory}/ocserv.sock
pid-file = {directory}/ocserv.pid
server-cert = {directory}/server.crt
server-key = {directory}/server.key
ca-cert = {directory}/ca.crt
device = ocvpn
ipv4-network = 198.18.0.0/24
ipv6-network = fd42:8::/48
ipv6-subnet-prefix = 64
route = 198.51.100.1/32
route = fd42:7::1/128
dns = 198.51.100.1
max-clients = 4
max-same-clients = 2
isolate-workers = true
''')
    fixture = str(assets / 'fixture.py')
    with tunnel_network(directory) as (client, network_config):
        original = (network_config / 'resolv.conf').read_bytes()
        before = [subprocess.check_output([*client, 'ip', family, 'route', 'show']) for family in ('-4', '-6')]
        run(*client, sys.executable, fixture, '--disconnected', timeout=5)
        expected = {socket.AF_INET: ('198.18.0.0/24', 1, 65535),
                    socket.AF_INET6: ('fd42:8::/48', 1, 65535)}
        dns = ['dnsmasq', '--keep-in-foreground', '--no-resolv', '--no-hosts', '--bind-interfaces',
               '--listen-address=198.51.100.1', '--address=/vpn.test/198.51.100.1',
               '--pid-file=' + str(directory / 'dns.pid')]
        with echoes(expected_peer=expected), service(dns, directory, 'dns') as resolver, service(
                ['ocserv', '-f', '-d', '3', '-c', str(config)], directory, 'server') as server:
            wait_port(resolver, ('198.51.100.1', 53))
            wait_port(server, ('192.0.2.1', 4443))
            command = [*client, 'openconnect', '--non-inter', '--verbose', '--interface=occlient',
                       '--cafile=' + str(directory / 'ca.crt'), '--certificate=' + str(directory / 'client.crt'),
                       '--sslkey=' + str(directory / 'client.key'), 'https://192.0.2.1:4443']
            with service(command, directory, 'client') as tunnel:
                deadline = time.monotonic() + 30
                while True:
                    assert tunnel.poll() is None and server.poll() is None, 'VPN exited before route setup'
                    routes = [subprocess.run([*client, 'ip', family, 'route', 'get', address],
                              capture_output=True, text=True).stdout for family, address in
                              (('-4', '198.51.100.1'), ('-6', 'fd42:7::1'))]
                    if all('dev occlient' in route for route in routes):
                        break
                    assert time.monotonic() < deadline, ('OpenConnect route deadline', routes)
                    # Bounded observation of script-installed routes, not a settling delay.
                    time.sleep(.05)
                run(*client, sys.executable, fixture, '--exchange', timeout=100)
                run(*client, sys.executable, '-c',
                    "import socket; assert socket.gethostbyname('vpn.test') == '198.51.100.1'", timeout=10)
                assert tunnel.poll() is None and server.poll() is None
                assert 'Established DTLS connection' in (directory / 'client.log').read_text(), 'DTLS data channel was not established'
            after = [subprocess.check_output([*client, 'ip', family, 'route', 'show']) for family in ('-4', '-6')]
            assert after == before, (before, after)
            assert (network_config / 'resolv.conf').read_bytes() == original
            run(*client, sys.executable, fixture, '--disconnected', timeout=5)
        print('PASS OpenConnect/ocserv, TLS/DTLS, IPv4/IPv6, DNS and disconnect restoration', flush=True)
