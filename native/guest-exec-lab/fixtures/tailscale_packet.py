"""Private Headscale control plane and stock Tailscale TUN peers, without an account."""
import contextlib
import json
import subprocess
import sys
import time

from probe_packet_runtime import echoes, run, service, tunnel_network
from openvpn_packet import certificates


def tailscale_guest(directory, assets):
    run(str(assets / 'tailscale'), 'version')
    run(str(assets / 'headscale'), 'version')
    certificates(directory)
    control = directory / 'headscale.sock'
    config = directory / 'headscale.json'
    config.write_text(json.dumps({
        'server_url': 'https://192.0.2.1:8443', 'listen_addr': '192.0.2.1:8443',
        'metrics_listen_addr': '', 'grpc_listen_addr': '127.0.0.1:50443',
        'noise': {'private_key_path': str(directory / 'noise.key')},
        'prefixes': {'v4': '100.64.0.0/24', 'v6': 'fd7a:115c:a1e0::/64', 'allocation': 'sequential'},
        'database': {'type': 'sqlite', 'sqlite': {'path': str(directory / 'control.db')}},
        'unix_socket': str(control), 'unix_socket_permission': '0700',
        'tls_cert_path': str(directory / 'server.crt'), 'tls_key_path': str(directory / 'server.key'),
        'derp': {'server': {'enabled': True, 'region_id': 999, 'region_code': 'fixture',
                            'region_name': 'Private fixture', 'verify_clients': True,
                            'private_key_path': str(directory / 'derp.key'),
                            'stun_listen_addr': '192.0.2.1:3478',
                            'automatically_add_embedded_derp_region': True},
                 'urls': [], 'paths': [], 'auto_update_enabled': False},
        'disable_check_updates': True, 'dns': {'magic_dns': False, 'override_local_dns': False},
        'policy': {'mode': 'file'}, 'log': {'level': 'info', 'format': 'text'},
    }))
    config.chmod(0o600)
    head = [str(assets / 'headscale'), '--config', str(config)]
    with tunnel_network(directory) as (client, _), contextlib.ExitStack() as stack:
        server = stack.enter_context(service([*head, 'serve'], directory, 'headscale'))
        deadline = time.monotonic() + 15
        while not control.exists():
            assert server.poll() is None, 'Headscale exited before control socket'
            assert time.monotonic() < deadline, 'Headscale control readiness deadline'
            time.sleep(.05)
        user = json.loads(subprocess.check_output([*head, 'users', 'create', 'packet', '--output', 'json']))
        key = json.loads(subprocess.check_output([*head, 'preauthkeys', 'create', '--user', str(user['id']),
                                                 '--reusable', '--expiration', '1h', '--output', 'json']))
        auth = directory / 'auth.key'
        auth.write_text(key['key'])
        auth.chmod(0o600)
        commands = []
        for name, prefix in (('server', []), ('client', client)):
            sock = directory / (name + '.sock')
            state = directory / (name + '-state')
            state.mkdir()
            daemon = [*prefix, 'env', 'SSL_CERT_FILE=' + str(directory / 'ca.crt'),
                      str(assets / 'tailscaled'), '--tun=tailscale0', '--state=mem:',
                      '--statedir=' + str(state), '--socket=' + str(sock)]
            process = stack.enter_context(service(daemon, directory, name))
            cli = [*prefix, str(assets / 'tailscale'), '--socket=' + str(sock)]
            deadline = time.monotonic() + 15
            while True:
                assert process.poll() is None, 'Tailscale exited before backend readiness'
                status = subprocess.run([*cli, 'status', '--json'], capture_output=True, text=True, timeout=5) if sock.exists() else None
                if status and status.returncode == 0 and json.loads(status.stdout).get('BackendState'):
                    break
                assert time.monotonic() < deadline, 'Tailscale backend readiness deadline'
                time.sleep(.05)
            run(*cli, 'up', '--login-server=https://192.0.2.1:8443', '--auth-key=file:' + str(auth),
                '--hostname=' + name, '--timeout=30s', timeout=40)
            commands.append(cli)
            expected = json.loads(subprocess.check_output([*cli, 'status', '--json']))['TailscaleIPs']
            deadline = time.monotonic() + 15
            while True:
                links = json.loads(subprocess.check_output([*prefix, 'ip', '-j', 'address', 'show', 'dev', 'tailscale0']))
                assigned = {a['local'] for link in links for a in link.get('addr_info', [])}
                if set(expected) <= assigned:
                    break
                assert process.poll() is None and time.monotonic() < deadline, (expected, links)
                time.sleep(.05)
        addresses = [subprocess.check_output([*commands[0], 'ip', flag], text=True).strip() for flag in ('-4', '-6')]
        with echoes(addresses):
            run(*client, sys.executable, str(assets / 'fixture.py'), '--exchange', '--targets', *addresses, timeout=100)
            status = json.loads(subprocess.check_output([*commands[1], 'status', '--json']))
            peers = list(status.get('Peer', {}).values())
            assert any(p.get('Active') and p.get('RxBytes', 0) > 1048576 and p.get('TxBytes', 0) > 1048576 for p in peers), peers
            run(*commands[1], 'down')
            run(*client, sys.executable, str(assets / 'fixture.py'), '--disconnected', '--targets', *addresses)
        assert server.poll() is None
    print('PASS ordinary Tailscale TUN, private control plane, IPv4/IPv6 TCP/UDP and disconnect', flush=True)
