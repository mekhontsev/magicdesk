"""Stock TLS OpenVPN on guest-only links, including DNS and disconnect restoration."""
import socket
import subprocess
import sys
import time

from probe_packet_runtime import run, service, echoes, wait_port, tunnel_network


def certificates(directory):
    run('openssl', 'req', '-new', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:P-256',
        '-nodes', '-subj', '/CN=MagicDesk packet fixture CA', '-days', '1',
        '-addext', 'basicConstraints=critical,CA:TRUE', '-addext', 'keyUsage=critical,keyCertSign',
        '-keyout', str(directory / 'ca.key'), '-out', str(directory / 'ca.crt'))
    for name, usage in (('server', 'serverAuth'), ('client', 'clientAuth')):
        run('openssl', 'req', '-new', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:P-256',
            '-nodes', '-subj', '/CN=' + name, '-keyout', str(directory / (name + '.key')),
            '-out', str(directory / (name + '.csr')))
        extensions = directory / (name + '.ext')
        extensions.write_text('basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\n'
                              'extendedKeyUsage=' + usage + '\n'
                              + ('subjectAltName=IP:192.0.2.1\n' if name == 'server' else ''))
        run('openssl', 'x509', '-req', '-in', str(directory / (name + '.csr')), '-days', '1',
            '-CA', str(directory / 'ca.crt'), '-CAkey', str(directory / 'ca.key'),
            '-set_serial', '1' if name == 'server' else '2', '-extfile', str(extensions),
            '-out', str(directory / (name + '.crt')))
        (directory / (name + '.key')).chmod(0o600)


def vpn_guest(directory, assets, container=None):
    run('openvpn', '--version')
    with tunnel_network(directory) as (client, config):
        original = (config / 'resolv.conf').read_bytes()
        before = [subprocess.check_output([*client, 'ip', family, 'route', 'show']) for family in ('-4', '-6')]
        fixture = assets / 'fixture.py'
        if container:
            run('mount', '-t', 'sysfs', 'sysfs', '/sys')
            run('mount', '-t', 'cgroup2', 'none', '/sys/fs/cgroup')
            image, cache, runroot = container
            consumer = ['podman', '--root', cache, '--runroot', runroot,
                        'run', '--rm', '--pull=never', '--log-driver=k8s-file',
                        '--network=ns:/run/netns/' + client[3], '--volume=' + str(fixture) + ':/probe.py:ro',
                        image, 'python3', '/probe.py']
        else:
            consumer = [*client, sys.executable, str(fixture)]
        run(*client, sys.executable, str(fixture), '--disconnected', timeout=5)
        if container:
            run(*consumer, '--disconnected', timeout=30)
        certificates(directory)
        common = ['openvpn', '--dev', 'tun0', '--proto', 'udp', '--verb', '3',
                  '--ca', str(directory / 'ca.crt'), '--data-ciphers', 'AES-128-GCM']
        server = [*common, '--local', '192.0.2.1', '--port', '1194', '--mode', 'server', '--tls-server',
                  '--cert', str(directory / 'server.crt'), '--key', str(directory / 'server.key'),
                  '--dh', 'none', '--topology', 'subnet', '--server', '198.18.0.0', '255.255.255.0',
                  '--server-ipv6', 'fd42:8::/64', '--push', 'route 198.51.100.1 255.255.255.255',
                  '--push', 'route-ipv6 fd42:7::1/128', '--push', 'dhcp-option DNS 198.51.100.1']
        hook = str(assets / 'openvpn-dns.py')
        tunnel = [*client, *common, '--client', '--remote', '192.0.2.1', '1194', '--nobind',
                  '--cert', str(directory / 'client.crt'), '--key', str(directory / 'client.key'),
                  '--remote-cert-tls', 'server', '--script-security', '2', '--up', hook,
                  '--route-up', hook, '--down', hook, '--setenv', 'MD_VPN_WORK', str(directory)]
        dns = ['dnsmasq', '--keep-in-foreground', '--no-resolv', '--no-hosts', '--bind-interfaces',
               '--listen-address=198.51.100.1', '--address=/vpn.test/198.51.100.1',
               '--pid-file=' + str(directory / 'dns.pid')]
        with echoes(), service(dns, directory, 'dns') as dns_process, service(server, directory, 'server') as peer:
            wait_port(dns_process, ('198.51.100.1', 53))
            with service(tunnel, directory, 'client') as process:
                deadline = time.monotonic() + 30
                while not (directory / 'ready').exists():
                    assert process.poll() is None and peer.poll() is None, 'OpenVPN exited during handshake'
                    assert time.monotonic() < deadline, 'TLS/route readiness timeout'
                    # Bounded fixture polling of the actual route-up hook, not a settling delay.
                    time.sleep(.05)
                for family, address in (('-4', '198.51.100.1'), ('-6', 'fd42:7::1')):
                    route = subprocess.check_output([*client, 'ip', family, 'route', 'get', address], text=True)
                    assert 'dev tun0' in route, route
                run(*consumer, '--exchange', timeout=100)
                run(*client, sys.executable, '-c',
                    "import socket; assert socket.gethostbyname('vpn.test') == '198.51.100.1'", timeout=10)
                assert process.poll() is None and peer.poll() is None
            assert (directory / 'restored').exists(), 'VPN down hook did not run'
            assert (config / 'resolv.conf').read_bytes() == original
            after = [subprocess.check_output([*client, 'ip', family, 'route', 'show']) for family in ('-4', '-6')]
            assert after == before, (before, after)
            run(*client, sys.executable, str(fixture), '--disconnected', timeout=5)
            if container:
                run(*consumer, '--disconnected', timeout=30)
        print('PASS OpenVPN TLS, IPv4/IPv6 TCP/UDP, DNS, routes and disconnect restoration', flush=True)
