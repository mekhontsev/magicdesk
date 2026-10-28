#!/usr/bin/env python3
"""Boot an activated NixOS generation, optionally against a private DHCP peer."""
import argparse
from contextlib import ExitStack
from pathlib import Path
import select
import shlex
import socket
import struct
import subprocess
import sys
import tempfile
import threading


def client(port):
    def output(*args):
        return subprocess.check_output(args, text=True)

    assert '10.203.0.2/24' in output('ip', '-4', '-o', 'addr', 'show', 'dev', 'eth0')
    assert 'default via 10.203.0.1' in output('ip', '-4', 'route', 'show', 'default')
    assert 'nameserver 10.203.0.1' in Path('/etc/resolv.conf').read_text()
    pid = output('systemctl', 'show', '-p', 'MainPID', '--value', 'dhcpcd.service').strip()
    status = dict(line.split(':', 1) for line in Path('/proc/' + pid + '/status').read_text().splitlines()
                  if ':' in line)
    assert int(status['Uid'].split()[1]) != 0, status
    assert int(status['CapEff'].strip(), 16) & 0x3400 == 0x3400, status
    assert int(status['NoNewPrivs']) == 1, status
    assert int(status['Seccomp']) == 2, status
    resolved = socket.getaddrinfo('shroot-dhcp.test', port, socket.AF_INET, socket.SOCK_DGRAM)
    assert resolved and all(item[4][0] == '10.203.0.1' for item in resolved), resolved
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as peer:
        peer.settimeout(5)
        peer.sendto(b'nixos-lease', ('10.203.0.1', port))
        assert peer.recv(128) == b'nixos-lease'
    print('PASS stock DHCP identity, capabilities, sandbox, address, route, DNS and UDP', flush=True)


def guest(target):
    from test_network_dhcp import reply_dhcp4

    def ip(*args):
        subprocess.run(['ip', *args], check=True)

    done = threading.Event()
    errors, exchanges, peers, queries = [], [], [], []
    worker = None
    link = namespace = False
    try:
        ip('netns', 'add', 'md-nixos-client')
        namespace = True
        ip('link', 'add', 'md-nixos-server', 'type', 'veth', 'peer', 'name', 'md-nixos-peer')
        link = True
        ip('link', 'set', 'md-nixos-peer', 'netns', 'md-nixos-client')
        ip('-n', 'md-nixos-client', 'link', 'set', 'md-nixos-peer', 'name', 'eth0')
        ip('-n', 'md-nixos-client', 'link', 'set', 'lo', 'up')
        ip('-n', 'md-nixos-client', 'link', 'set', 'eth0', 'up')
        ip('addr', 'add', '10.203.0.1/24', 'dev', 'md-nixos-server')
        ip('link', 'set', 'md-nixos-server', 'up')
        with ExitStack() as stack:
            wire = stack.enter_context(socket.socket(socket.AF_PACKET, socket.SOCK_RAW, socket.htons(0x800)))
            wire.bind(('md-nixos-server', 0))
            unicast = stack.enter_context(socket.socket(socket.AF_INET, socket.SOCK_DGRAM))
            unicast.bind(('10.203.0.1', 67))
            traffic = stack.enter_context(socket.socket(socket.AF_INET, socket.SOCK_DGRAM))
            traffic.bind(('10.203.0.1', 0))
            dns = stack.enter_context(socket.socket(socket.AF_INET, socket.SOCK_DGRAM))
            dns.bind(('10.203.0.1', 53))

            def serve():
                try:
                    while not done.is_set():
                        # EVENT_WAIT: protocol requests; the bound only observes teardown.
                        for source in select.select([wire, unicast, traffic, dns], [], [], 0.5)[0]:
                            if source is traffic:
                                data, peer = traffic.recvfrom(128)
                                peers.append(peer[0])
                                traffic.sendto(data, peer)
                            elif source is dns:
                                request, peer = dns.recvfrom(4096)
                                question = b'\x0bshroot-dhcp\x04test\x00\x00\x01\x00\x01'
                                assert request[4:6] == b'\x00\x01' and request[12:12 + len(question)] == question, request
                                queries.append(peer[0])
                                answer = request[:2] + struct.pack('!5H', 0x8180, 1, 1, 0, 0) + question
                                answer += b'\xc0\x0c' + struct.pack('!HHIH', 1, 1, 0, 4) + socket.inet_aton('10.203.0.1')
                                dns.sendto(answer, peer)
                            else:
                                reply_dhcp4(source, wire, unicast, exchanges)
                except BaseException as error:
                    errors.append(repr(error))

            worker = threading.Thread(target=serve)
            worker.start()
            command = ['unshare', '--mount', '--pid', '--fork', '--mount-proc', '--uts', '--ipc',
                       '--cgroup', '/bin/sh', target + '/boot.sh', target, 'normal', str(traffic.getsockname()[1])]
            try:
                result = subprocess.run(['ip', 'netns', 'exec', 'md-nixos-client', 'script', '-q', '-e', '-c',
                                         shlex.join(command), '/dev/null'], stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT, text=True, timeout=150)
                print(result.stdout, end='', flush=True)
                result.check_returncode()
                assert 'Failed unmounting ' not in result.stdout, result.stdout
            finally:
                done.set()
                worker.join(timeout=3)
                assert not worker.is_alive()
            assert not errors, errors
            assert Path(target + '/result').read_text().strip() == '0'
            assert any(kind == 1 for kind, _ in exchanges), exchanges
            assert sum(kind == 3 for kind, _ in exchanges) >= 2, exchanges
            assert len(peers) >= 2 and set(peers) == {'10.203.0.2'}, peers
            assert queries and set(queries) == {'10.203.0.2'}, queries
            log = Path(target + '/journal.log').read_text()
            assert 'dhcp_start1:' not in log and 'timed out' not in log, log
            print('PASS NixOS default boot, stock DHCP restart and guest-only network', flush=True)
    finally:
        done.set()
        if worker is not None:
            worker.join(timeout=3)
        if link:
            ip('link', 'del', 'md-nixos-server')
        if namespace:
            ip('netns', 'del', 'md-nixos-client')


def boot_script(system, python, target, dhcp):
    from init_fixture import PREPARE
    from test_os_boot import SYSTEMD
    path = system + '/sw/bin:/bin'
    boot = PREPARE.replace('export PATH=/usr/sbin:/usr/bin:/sbin:/bin', 'export PATH=' + shlex.quote(path))
    detach = '''
# The fixture owns these handles outside PID1. Detach only the private copies
# before covering /run, so systemd does not inherit inaccessible mount targets.
umount /run/netns/md-nixos-client
umount /run/netns
''' if dhcp else ''
    boot = boot.replace('test "$$" = 1', 'test "$$" = 1\n' + detach + 'mkdir -p /run /tmp /var/tmp')
    boot += '\n' + shlex.quote(system + '/activate') + '\n'
    observer = SYSTEMD.replace('set -ux\n', 'set -ux\nexport PATH=' + shlex.quote(path) + '\n', 1)
    if dhcp:
        observer = observer.replace('mkdir -p /run/systemd/system/default.target.wants',
                                'printf "%s\\n" "$3" > "$1/port"\nmkdir -p /run/systemd/system/default.target.wants', 1)
        check = shlex.join([python, target + '/test_nixos_boot.py', '--client']) + ' "$(cat "$1/port")"'
        observer = observer.replace('        test "$(systemctl is-system-running)" = running',
            '        test "$(systemctl is-system-running)" = running\n' +
            '        ' + check + '\n        systemctl restart dhcpcd.service\n        ' + check)
    observer = observer.replace('exec /usr/lib/systemd/systemd --system --show-status=yes --crash-shell=no --crash-reboot=no',
                                'exec /sbin/init')
    return boot + observer


def main():
    from test_oci_services import Suite

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build', type=Path, help='Stage native helpers; otherwise use the installed APK')
    parser.add_argument('--store', required=True)
    parser.add_argument('--system', default='/nix/var/nix/profiles/system', help='Installed NixOS system closure or profile')
    parser.add_argument('--python', help='Guest Python interpreter; defaults to the selected system profile')
    parser.add_argument('--entry', choices=('lazy', 'base'), default='lazy')
    parser.add_argument('--network', choices=('dhcp', 'preconfigured'), default='dhcp',
                        help='Use the initial image DHCP policy or the recipe preconfigured uplink')
    parser.add_argument('--output', type=Path, default=Path('build/nixos-boot'))
    args = parser.parse_args()
    suite = Suite(args.output, args.build)
    suite.report['entryMode'] = args.entry
    suite.report['store'] = args.store
    target = '/mnt/md-nixos-' + suite.report['id']
    verified = prepared = False
    try:
        args.system = suite.command(suite.guest(args.store, '--user', '0:0', '--',
            args.system + '/sw/bin/readlink', '-f', args.system), quiet=True).strip()
        assert args.system.startswith('/nix/store/') and '\n' not in args.system, args.system
        args.python = args.python or args.system + '/sw/bin/python3'
        path = args.system + '/sw/bin:/bin'
        environment = ['--env', 'PATH=' + path, '--env', 'SHROOT_MACHINE_ENTRY=' + ('1' if args.entry == 'lazy' else '0')]
        boot = boot_script(args.system, args.python, target, args.network == 'dhcp')
        suite.report['scope'] = dict(system=args.system, python=args.python, entry=args.entry,
            network=args.network, hostNetworkChanges=False)
        suite.command(suite.guest(args.store, '--user', '0:0', *environment, '--', '/bin/sh', '-ec',
                                  'mkdir -p /mnt; mkdir "$1"', 'setup', target))
        prepared = True
        for name in ('test_nixos_boot.py', 'test_network_dhcp.py'):
            suite.transport.upload(suite.client, Path(__file__).with_name(name), suite.base + '/' + name)
        with tempfile.TemporaryDirectory() as temporary:
            script = Path(temporary) / 'boot.sh'
            script.write_text(boot)
            suite.transport.upload(suite.client, script, suite.base + '/boot.sh')
        try:
            if args.network == 'dhcp':
                command = [args.python, target + '/test_nixos_boot.py', '--guest', target]
            else:
                command = [args.system + '/sw/bin/script', '-q', '-e', '-c', shlex.join([
                    'unshare', '--mount', '--pid', '--fork', '--mount-proc', '--uts', '--ipc',
                    '--cgroup', '/bin/sh', target + '/boot.sh', target, 'normal']), '/dev/null']
            result = suite.command([suite.runner, '--store', args.store, '--user', '0:0', '--network', 'routed',
                           *environment, '--bind', suite.base, target, '--deadline-seconds', '180', '--',
                           *command], timeout=205)
            assert 'Failed unmounting ' not in result, result
            assert suite.command(['cat', suite.base + '/result'], quiet=True).strip() == '0'
        finally:
            suite.command(['/system/bin/sh', '-c',
                'for file in boot.log services.log journal.log state result; do '
                'if test -f "$1/$file"; then printf "\\n%s\\n" "$file"; cat "$1/$file"; fi; done', 'logs', suite.base])
        verified = True
    finally:
        try:
            if prepared:
                suite.command(suite.guest(args.store, '--user', '0:0', *environment, '--', '/bin/sh', '-ec', 'rmdir "$1"', 'cleanup', target))
            suite.report['passed'] = verified
        finally:
            suite.finish()


if __name__ == '__main__':
    if sys.argv[1:2] == ['--guest']:
        guest(sys.argv[2])
    elif sys.argv[1:2] == ['--client']:
        client(int(sys.argv[2]))
    else:
        main()
