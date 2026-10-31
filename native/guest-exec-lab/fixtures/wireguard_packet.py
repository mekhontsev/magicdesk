"""Stock userspace WireGuard, including abrupt termination and interface reuse."""
import os
import socket
import subprocess
import sys
import time

from probe_packet_runtime import run, service, echoes


def wait_interface(command, name, process):
    deadline = time.monotonic() + 15
    while True:
        assert process.poll() is None, 'WireGuard exited before interface creation'
        result = subprocess.run([*command, 'wg', 'show', name], capture_output=True)
        if result.returncode == 0:
            return
        assert time.monotonic() < deadline, result.stderr.decode(errors='replace')
        # Bounded observation of the actual UAPI listener, not a settling delay.
        time.sleep(.05)


def wireguard_guest(directory, assets):
    namespace = 'packet-wg-' + directory.name.removeprefix('md-packet-')
    client = ['ip', 'netns', 'exec', namespace]
    fixture = str(assets / 'fixture.py')
    os.environ['LOG_LEVEL'] = 'verbose'
    run('wireguard-go', '--version')
    keys = {}
    for name in ('server', 'client'):
        private = subprocess.check_output(['wg', 'genkey'])
        path = directory / (name + '.key')
        path.write_bytes(private)
        path.chmod(0o600)
        keys[name] = subprocess.check_output(['wg', 'pubkey'], input=private).decode().strip()
    run('ip', 'netns', 'add', namespace)
    addresses = []
    try:
        run('ip', 'link', 'add', 'wg-host', 'type', 'veth', 'peer', 'name', 'wg-peer')
        run('ip', 'link', 'set', 'wg-peer', 'netns', namespace)
        run('ip', 'addr', 'add', '192.0.2.1/24', 'dev', 'wg-host')
        run('ip', 'link', 'set', 'wg-host', 'up')
        run(*client, 'ip', 'link', 'set', 'lo', 'up')
        run(*client, 'ip', 'addr', 'add', '192.0.2.2/24', 'dev', 'wg-peer')
        run(*client, 'ip', 'link', 'set', 'wg-peer', 'up')
        for family, address in (('-4', '198.51.100.1/32'), ('-6', 'fd42:7::1/128')):
            run('ip', family, 'addr', 'add', address, 'dev', 'lo')
            addresses.append((family, address))
        before = [subprocess.check_output([*client, 'ip', family, 'route', 'show']) for family in ('-4', '-6')]
        run(*client, sys.executable, fixture, '--disconnected', timeout=5)
        expected = {socket.AF_INET: ('198.18.0.2/32', 1, 65535),
                    socket.AF_INET6: ('fd42:8::2/128', 1, 65535)}
        with echoes(expected_peer=expected), service(['wireguard-go', '-f', 'wg-server'], directory, 'server') as server:
            wait_interface([], 'wg-server', server)
            run('wg', 'set', 'wg-server', 'private-key', str(directory / 'server.key'),
                'listen-port', '51820', 'peer', keys['client'], 'allowed-ips', '198.18.0.2/32,fd42:8::2/128')
            run('ip', 'addr', 'add', '198.18.0.1/24', 'dev', 'wg-server')
            run('ip', '-6', 'addr', 'add', 'fd42:8::1/64', 'dev', 'wg-server')
            run('ip', 'link', 'set', 'wg-server', 'up')
            for iteration in range(2):
                with service([*client, 'wireguard-go', '-f', 'wg-client'], directory, 'client-' + str(iteration)) as tunnel:
                    wait_interface(client, 'wg-client', tunnel)
                    run(*client, 'wg', 'set', 'wg-client', 'private-key', str(directory / 'client.key'),
                        'peer', keys['server'], 'endpoint', '192.0.2.1:51820',
                        'allowed-ips', '198.51.100.1/32,fd42:7::1/128')
                    run(*client, 'ip', 'addr', 'add', '198.18.0.2/24', 'dev', 'wg-client')
                    run(*client, 'ip', '-6', 'addr', 'add', 'fd42:8::2/64', 'dev', 'wg-client')
                    run(*client, 'ip', 'link', 'set', 'wg-client', 'up')
                    run(*client, 'ip', 'route', 'add', '198.51.100.1/32', 'dev', 'wg-client')
                    run(*client, 'ip', '-6', 'route', 'add', 'fd42:7::1/128', 'dev', 'wg-client')
                    run(*client, sys.executable, fixture, '--exchange', timeout=100)
                    stats = subprocess.check_output([*client, 'wg', 'show', 'wg-client', 'transfer'], text=True).split()
                    assert len(stats) == 3 and min(map(int, stats[1:])) > 1024 * 1024, stats
                    assert server.poll() is None and tunnel.poll() is None
                    if iteration == 0:
                        tunnel.kill()
                        assert tunnel.wait(timeout=10) == -9
                after = [subprocess.check_output([*client, 'ip', family, 'route', 'show']) for family in ('-4', '-6')]
                assert before == after, (before, after)
                run(*client, sys.executable, fixture, '--disconnected', timeout=5)
        print('PASS WireGuard encrypted IPv4/IPv6, TCP/UDP, SIGKILL cleanup and restart', flush=True)
    finally:
        run('ip', 'netns', 'delete', namespace)
        for family, address in addresses:
            run('ip', family, 'addr', 'del', address, 'dev', 'lo')
        links = subprocess.check_output(['ip', '-o', 'link', 'show'], text=True)
        assert 'wg-host' not in links and 'wg-server' not in links, links
