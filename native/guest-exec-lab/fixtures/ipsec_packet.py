"""Stock strongSwan with the userspace IPsec backend and namespace-isolated peers."""
import contextlib
import secrets
import socket
import subprocess
import sys
import time

from probe_packet_runtime import run, service, echoes, tunnel_network


def ipsec_guest(directory, assets):
    run('dpkg-query', '-W', 'strongswan-charon')
    secret = secrets.token_hex(32)
    fixture = str(assets / 'fixture.py')
    with tunnel_network(directory) as (client, _), contextlib.ExitStack() as stack:
        run(*client, 'ip', 'addr', 'add', '198.18.0.2/32', 'dev', 'lo')
        run(*client, 'ip', '-6', 'addr', 'add', 'fd42:8::2/128', 'dev', 'lo')
        run(*client, sys.executable, fixture, '--disconnected', timeout=5)
        controls = []
        processes = []
        for name, prefix, local, remote, local_ts, remote_ts in (
                ('server', [], '192.0.2.1', '192.0.2.2', '198.51.100.1/32,fd42:7::1/128', '198.18.0.2/32,fd42:8::2/128'),
                ('client', client, '192.0.2.2', '192.0.2.1', '198.18.0.2/32,fd42:8::2/128', '198.51.100.1/32,fd42:7::1/128')):
            control = directory / (name + '.vici')
            config = directory / (name + '.conf')
            config.write_text(f'''charon {{
    load = random nonce openssl kernel-libipsec kernel-netlink socket-default vici
    plugins {{
        vici {{
            socket = unix://{control}
        }}
    }}
}}
swanctl {{
    load = random nonce openssl
    socket = unix://{control}
}}
''')
            connection = directory / (name + '-connections.conf')
            connection.write_text(f'''connections {{
    packet {{
        version = 2
        local_addrs = {local}
        remote_addrs = {remote}
        proposals = aes256gcm16-prfsha256-ecp256
        encap = yes
        local {{
            auth = psk
            id = {name}
        }}
        remote {{
            auth = psk
            id = {'client' if name == 'server' else 'server'}
        }}
        children {{
            tunnel {{
                local_ts = {local_ts}
                remote_ts = {remote_ts}
                esp_proposals = aes256gcm16-ecp256
            }}
        }}
    }}
}}
secrets {{
    ike-test {{
        id-1 = server
        id-2 = client
        secret = {secret}
    }}
}}
''')
            connection.chmod(0o600)
            command = [*prefix, 'unshare', '--mount', '--', '/bin/sh', '-ec',
                       'mount -t tmpfs tmpfs /run; exec env STRONGSWAN_CONF="$1" /usr/lib/ipsec/charon',
                       'ipsec-fixture', str(config)]
            process = stack.enter_context(service(command, directory, name))
            processes.append(process)
            deadline = time.monotonic() + 15
            while not control.exists():
                assert process.poll() is None, 'charon exited before VICI publication'
                assert time.monotonic() < deadline, 'VICI readiness deadline'
                time.sleep(.05)
            ctl = [*prefix, 'env', 'STRONGSWAN_CONF=' + str(config), 'swanctl']
            run(*ctl, '--load-all', '--file', str(connection), timeout=15)
            controls.append(ctl)
        run(*controls[1], '--initiate', '--child', 'tunnel', '--timeout', '25', timeout=30)
        expected = {socket.AF_INET: ('198.18.0.2/32', 1, 65535),
                    socket.AF_INET6: ('fd42:8::2/128', 1, 65535)}
        with echoes(expected_peer=expected):
            run(*client, sys.executable, fixture, '--exchange', timeout=100)
            run(*controls[1], '--list-sas', timeout=10)
            assert all(process.poll() is None for process in processes)
            run(*controls[1], '--terminate', '--ike', 'packet', '--timeout', '10', timeout=15)
            run(*client, sys.executable, fixture, '--disconnected', timeout=5)
        print('PASS strongSwan kernel-libipsec, encrypted IPv4/IPv6 and tunnel teardown', flush=True)
