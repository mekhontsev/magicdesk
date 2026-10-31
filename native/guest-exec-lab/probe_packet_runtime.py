#!/usr/bin/env python3
"""Stock TUN/TAP applications under Shroot, with local test topology.

TUN uses stock tun2socks and GOST; TAP uses distribution QEMU and a trusted
Alpine kernel/initramfs pair. VPN uses packaged OpenVPN and an ephemeral TLS peer.
No Android network configuration is changed.
All processes, links and test files belong to one bounded launch.
"""
import argparse
import contextlib
import concurrent.futures
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import queue
import re
import shutil
import socket
import socketserver
import statistics
import subprocess
import sys
import tempfile
import threading
import time


def run(*command, **kwargs):
    print('RUN', *command, flush=True)
    return subprocess.run(command, check=True, **kwargs)


def receive(stream, size):
    data = bytearray()
    while len(data) < size:
        part = stream.recv(size - len(data))
        assert part, (len(data), size)
        data.extend(part)
    return data


class TCPHandler(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(20)
        while data := self.request.recv(65536):
            self.request.sendall(data)


class UDPHandler(socketserver.BaseRequestHandler):
    def handle(self):
        data, stream = self.request
        stream.sendto(data, self.client_address)


@contextlib.contextmanager
def echoes(addresses=('198.51.100.1', 'fd42:7::1'), expected_peer=None):
    servers = []
    threads = []
    try:
        for family, address in zip((socket.AF_INET, socket.AF_INET6), addresses):
            for cls, handler in ((socketserver.ThreadingTCPServer, TCPHandler),
                                 (socketserver.ThreadingUDPServer, UDPHandler)):
                class Server(cls):
                    address_family = family
                    daemon_threads = True
                    allow_reuse_address = True
                    max_packet_size = 65535
                    def verify_request(self, request, client_address):
                        if expected_peer is not None:
                            ip, low, high = expected_peer[self.address_family]
                            assert ipaddress.ip_address(client_address[0]) in ipaddress.ip_network(ip) and low <= client_address[1] <= high, client_address
                        return True
                server = Server((address, 18080), handler)
                thread = threading.Thread(target=server.serve_forever)
                servers.append(server)
                threads.append(thread)
                thread.start()
        yield
    finally:
        for server in servers:
            server.shutdown()
            server.server_close()
        for thread in threads:
            thread.join(5)
            assert not thread.is_alive()


def exchange(addresses=('198.51.100.1', 'fd42:7::1'), port=18080):
    for family, address in zip((socket.AF_INET, socket.AF_INET6), addresses):
        with socket.socket(family, socket.SOCK_STREAM) as stream:
            stream.settimeout(20)
            stream.connect((address, port))
            for size in (1, 4096, 1024 * 1024):
                print('TCP transfer', address, size, flush=True)
                data = bytes(index % 251 for index in range(size))
                stream.sendall(data)
                if size == 1024 * 1024:
                    stream.shutdown(socket.SHUT_WR)
                assert receive(stream, size) == data
            assert stream.recv(1) == b''
        print('PASS TCP bulk and half-close', address, flush=True)
        with socket.socket(family, socket.SOCK_DGRAM) as stream:
            stream.settimeout(20)
            for size in (1, 1200, 4000, 16000):
                data = bytes(index % 251 for index in range(size))
                stream.sendto(data, (address, port))
                result, peer = stream.recvfrom(65535)
                assert result == data and peer[:2] == (address, port), (size, len(result), peer)
        print('PASS UDP datagrams and fragmentation', address, flush=True)
    if os.environ.get('MD_PACKET_LOAD') == '1':
        packet_load(addresses, port)


def packet_load(addresses, port):
    descriptors = len(os.listdir('/proc/self/fd'))
    for family, address in zip((socket.AF_INET, socket.AF_INET6), addresses):
        barrier = threading.Barrier(4, timeout=20)
        def transfer(index):
            payload = bytes((offset + index) % 251 for offset in range(65536))
            latency = []
            with socket.socket(family, socket.SOCK_STREAM) as stream:
                stream.settimeout(30)
                stream.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 16384)
                stream.connect((address, port))
                barrier.wait()
                for _ in range(32):
                    started = time.monotonic()
                    stream.sendall(payload[:16])
                    assert receive(stream, 16) == payload[:16]
                    latency.append(time.monotonic() - started)
                def send():
                    for _ in range(64):
                        stream.sendall(payload)
                    stream.shutdown(socket.SHUT_WR)
                with concurrent.futures.ThreadPoolExecutor(max_workers=1) as sender:
                    sent = sender.submit(send)
                    # Intentional fixture backpressure, not a readiness assumption.
                    time.sleep(.1)
                    for _ in range(64):
                        assert receive(stream, len(payload)) == payload
                    assert stream.recv(1) == b''
                    sent.result(timeout=30)
            return latency
        started, cpu = time.monotonic(), time.process_time()
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as clients:
            samples = list(clients.map(transfer, range(4)))
        elapsed = time.monotonic() - started
        print('PACKET_LOAD ' + json.dumps(dict(address=address, connections=4,
              bytesEachDirection=4 * 64 * 65536, seconds=elapsed,
              clientCpuSeconds=time.process_time() - cpu,
              latencyMedianSeconds=statistics.median(value for row in samples for value in row))), flush=True)
    assert len(os.listdir('/proc/self/fd')) == descriptors
    print('PASS concurrent packet streams, slow receivers, half-close and descriptor release', flush=True)


def disconnected(addresses=('198.51.100.1', 'fd42:7::1')):
    for family, address in zip((socket.AF_INET, socket.AF_INET6), addresses):
        with socket.socket(family, socket.SOCK_STREAM) as stream:
            stream.settimeout(.5)
            try:
                stream.connect((address, 18080))
            except OSError:
                continue
            raise AssertionError('traffic bypassed the stopped TUN application: ' + address)
    print('PASS no route bypass without the tunnel', flush=True)


@contextlib.contextmanager
def service(command, directory, name):
    log = directory / (name + '.log')
    with log.open('wb') as output:
        process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT)
        try:
            yield process
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
            print(name + ' log:\n' + log.read_text(errors='replace'), flush=True)


def wait_port(process, address):
    deadline = time.monotonic() + 15
    while True:
        assert process.poll() is None, 'service exited before listening'
        try:
            with socket.create_connection(address, timeout=1):
                return
        except OSError:
            assert time.monotonic() < deadline, 'listener readiness timeout'
            # Bounded fixture polling observes bind completion, not assumed readiness.
            time.sleep(.05)


@contextlib.contextmanager
def tunnel_network(directory):
    namespace = 'packet-vpn-' + directory.name.removeprefix('md-packet-')
    config = Path('/etc/netns') / namespace
    config.mkdir(parents=True)
    (config / 'resolv.conf').write_text('nameserver 192.0.2.1\n')
    addresses = []
    run('ip', 'netns', 'add', namespace)
    client = ['ip', 'netns', 'exec', namespace]
    try:
        run('ip', 'link', 'add', 'vpn-host', 'type', 'veth', 'peer', 'name', 'vpn-client')
        run('ip', 'link', 'set', 'vpn-client', 'netns', namespace)
        run('ip', 'addr', 'add', '192.0.2.1/24', 'dev', 'vpn-host')
        run('ip', 'link', 'set', 'vpn-host', 'up')
        for family, address in (('-4', '198.51.100.1/32'), ('-6', 'fd42:7::1/128')):
            run('ip', family, 'addr', 'add', address, 'dev', 'lo')
            addresses.append((family, address))
        run(*client, 'ip', 'link', 'set', 'lo', 'up')
        run(*client, 'ip', 'addr', 'add', '192.0.2.2/24', 'dev', 'vpn-client')
        run(*client, 'ip', 'link', 'set', 'vpn-client', 'up')
        yield client, config
    finally:
        run('ip', 'netns', 'delete', namespace)
        links = subprocess.check_output(['ip', '-o', 'link', 'show'], text=True)
        assert 'vpn-host' not in links, links
        for family, address in addresses:
            run('ip', family, 'addr', 'del', address, 'dev', 'lo')
        shutil.rmtree(config)


def tun_guest(directory, assets):
    run('ip', 'netns', 'add', 'packet-client')
    try:
        run('ip', 'link', 'add', 'proxy-host', 'type', 'veth', 'peer', 'name', 'proxy-client')
        run('ip', 'link', 'set', 'proxy-client', 'netns', 'packet-client')
        run('ip', 'addr', 'add', '192.0.2.1/24', 'dev', 'proxy-host')
        run('ip', 'link', 'set', 'proxy-host', 'up')
        run('ip', 'addr', 'add', '198.51.100.1/32', 'dev', 'lo')
        run('ip', '-6', 'addr', 'add', 'fd42:7::1/128', 'dev', 'lo')
        client = ['ip', 'netns', 'exec', 'packet-client']
        run(*client, 'ip', 'link', 'set', 'lo', 'up')
        run(*client, 'ip', 'addr', 'add', '192.0.2.2/24', 'dev', 'proxy-client')
        run(*client, 'ip', 'link', 'set', 'proxy-client', 'up')
        run(*client, 'ip', 'tuntap', 'add', 'mode', 'tun', 'dev', 'tun0')
        run(*client, 'ip', 'addr', 'add', '198.18.0.1/15', 'dev', 'tun0')
        run(*client, 'ip', '-6', 'addr', 'add', 'fd42:8::1/64', 'dev', 'tun0')
        run(*client, 'ip', 'link', 'set', 'tun0', 'up')
        run(*client, 'ip', 'route', 'add', 'default', 'dev', 'tun0')
        run(*client, 'ip', '-6', 'route', 'add', 'default', 'dev', 'tun0')
        with echoes(), service([str(assets / 'gost'), '-L',
                                'socks5://192.0.2.1:1080?udp=true&udpBufferSize=65535'],
                               directory, 'gost') as proxy:
            wait_port(proxy, ('192.0.2.1', 1080))
            run(*client, sys.executable, __file__, '--disconnected', timeout=5)
            with service([*client, str(assets / 'tun2socks'), '--device', 'tun0',
                          '--proxy', 'socks5://192.0.2.1:1080', '--loglevel', 'debug'],
                         directory, 'tun2socks') as tunnel:
                run(*client, sys.executable, __file__, '--exchange', timeout=100)
                assert tunnel.poll() is None and proxy.poll() is None
            run(*client, sys.executable, __file__, '--disconnected', timeout=5)
        print('PASS stock tun2socks over Shroot TUN', flush=True)
    finally:
        run('ip', 'netns', 'delete', 'packet-client')


def tap_guest(directory, assets):
    run('qemu-system-aarch64', '--version')
    run('ip', 'tuntap', 'add', 'mode', 'tap', 'dev', 'tap0')
    run('ip', 'addr', 'add', '198.51.100.1/24', 'dev', 'tap0')
    run('ip', '-6', 'addr', 'add', 'fd42:7::1/64', 'dev', 'tap0')
    run('ip', 'link', 'set', 'tap0', 'up')
    command = ['qemu-system-aarch64', '-machine', 'virt', '-cpu', 'cortex-a72',
               '-accel', 'tcg,thread=multi', '-smp', '2', '-m', '384', '-nographic',
               '-monitor', 'none', '-no-reboot', '-kernel', str(assets / 'kernel'),
               '-initrd', str(assets / 'initramfs.cpio'), '-append', 'console=ttyAMA0 rdinit=/init',
               '-netdev', 'tap,id=n0,ifname=tap0,script=no,downscript=no',
               '-device', 'virtio-net-device,netdev=n0']
    lines = queue.Queue()
    output = []
    def read(stream):
        for line in stream:
            output.append(line)
            lines.put(line)
            print('VM:', line, end='', flush=True)
        lines.put(None)
    with echoes(), subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, text=True) as process:
        reader = threading.Thread(target=read, args=(process.stdout,))
        reader.start()
        try:
            deadline = time.monotonic() + 90
            while True:
                # EVENT_WAIT: serial readiness from the actual VM, not elapsed boot time.
                line = lines.get(timeout=max(0, deadline - time.monotonic()))
                assert line is not None, 'VM exited before network readiness'
                if 'READY VM reverse traffic' in line:
                    break
            exchange(('198.51.100.2', 'fd42:7::2'), 18081)
            assert process.wait(timeout=30) == 0
        finally:
            if process.poll() is None:
                process.kill()
            process.wait(timeout=10)
            reader.join(10)
            assert not reader.is_alive()
        assert 'PASS packet VM completed\n' in output
    print('PASS stock QEMU over Shroot TAP', flush=True)


def vm_archive(directory, initramfs, test):
    root = directory / 'root'
    root.mkdir()
    # Build artifacts from a trusted distribution initramfs; no runtime downloads.
    with subprocess.Popen(['gzip', '-dc', str(initramfs)], stdout=subprocess.PIPE) as source:
        subprocess.run(['cpio', '-id', '--no-absolute-filenames'], cwd=root, stdin=source.stdout, check=True)
        assert source.wait() == 0
    shutil.copyfile(Path(__file__).with_name('fixtures') / 'packet-vm-init.sh', root / 'init')
    shutil.copyfile(Path(__file__).with_name('fixtures') / 'packet-vm-dhcp.sh', root / 'dhcp.sh')
    shutil.copyfile(test, root / 'packet-test')
    (root / 'init').chmod(0o755)
    (root / 'dhcp.sh').chmod(0o755)
    (root / 'packet-test').chmod(0o755)
    names = b'\0'.join(os.fsencode(str(path.relative_to(root))) for path in root.rglob('*')) + b'\0'
    archive = directory / 'initramfs.cpio'
    with archive.open('wb') as output:
        subprocess.run(['cpio', '-o', '--format=newc', '--null', '--owner=0:0'],
                       cwd=root, input=names, stdout=output, check=True)
    return archive


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--guest', action='store_true')
    parser.add_argument('--exchange', action='store_true')
    parser.add_argument('--disconnected', action='store_true')
    parser.add_argument('--assets', type=Path, required=False)
    parser.add_argument('--kind', choices=('tun', 'tap', 'vpn', 'wireguard', 'openconnect', 'ipsec', 'tailscale'), default='tun')
    parser.add_argument('--targets', nargs=2, default=('198.51.100.1', 'fd42:7::1'))
    parser.add_argument('--kernel', type=Path)
    parser.add_argument('--initramfs', type=Path)
    parser.add_argument('--build', type=Path, default=Path('build/shroot-image-tests'))
    parser.add_argument('--installed', action='store_true',
                        help='Use the installed APK runtime; --build still selects the TAP test binary')
    parser.add_argument('--environment', default='debian-turnip')
    parser.add_argument('--base', action='store_true')
    parser.add_argument('--load', action='store_true', help='Add concurrent traffic and receiver backpressure')
    parser.add_argument('--container-image', help='Pre-pulled Python container for the OpenVPN consumer')
    parser.add_argument('--container-cache', default='/tmp/md-vpn-image-cache')
    parser.add_argument('--container-runroot', default='/tmp/md-vpn-image-run')
    parser.add_argument('--network', choices=('routed', 'bridge'), default='routed')
    args = parser.parse_args()
    if args.exchange:
        exchange(args.targets)
        return
    if args.disconnected:
        disconnected(args.targets)
        return
    if (args.guest or args.kind == 'tun') and args.assets is None:
        parser.error('--assets is required for this mode')
    if not args.guest and args.kind == 'tap' and (args.kernel is None or args.initramfs is None):
        parser.error('--kernel and --initramfs are required for TAP')
    if args.guest:
        os.environ['PATH'] = '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'
        with tempfile.TemporaryDirectory(prefix='md-packet-') as temporary:
            if args.kind == 'vpn':
                from openvpn_packet import vpn_guest
                vpn_guest(Path(temporary), args.assets,
                          (args.container_image, args.container_cache, args.container_runroot) if args.container_image else None)
            elif args.kind == 'wireguard':
                from wireguard_packet import wireguard_guest
                wireguard_guest(Path(temporary), args.assets)
            elif args.kind == 'openconnect':
                from openconnect_packet import openconnect_guest
                openconnect_guest(Path(temporary), args.assets)
            elif args.kind == 'ipsec':
                from ipsec_packet import ipsec_guest
                ipsec_guest(Path(temporary), args.assets)
            elif args.kind == 'tailscale':
                from tailscale_packet import tailscale_guest
                tailscale_guest(Path(temporary), args.assets)
            else:
                (tun_guest if args.kind == 'tun' else tap_guest)(Path(temporary), args.assets)
        return
    from test_oci_services import Suite
    from benchmark_cpu import observe_suite_cpu
    suite = Suite(Path('build/packet-apps/results'), None if args.installed else args.build,
                  machine_entry=False if args.base else None)
    target = '/tmp/md-packet-' + suite.report['id']
    runner = None
    try:
        store = suite.installed_environment(args.environment)['store']
        runner = suite.guest(store, '--user', '0:0', '--network', args.network)
        if args.load:
            runner += ['--env', 'MD_PACKET_LOAD=1']
        suite.command([*runner, '--', '/bin/mkdir', target])
        assets = {'fixture.py': Path(__file__)}
        with tempfile.TemporaryDirectory(prefix='md-packet-vm-') as temporary:
            if args.kind == 'tun':
                assets.update(gost=args.assets / 'gost', tun2socks=args.assets / 'tun2socks-linux-arm64')
            elif args.kind == 'tap':
                assets.update(kernel=args.kernel)
                assets['initramfs.cpio'] = vm_archive(Path(temporary), args.initramfs,
                                                    args.build / 'libmagicdesk_guest_packet_vm.so')
            else:
                assets['probe_packet_runtime.py'] = Path(__file__)
                for name in ('openvpn_packet.py', 'openvpn-dns.py', 'wireguard_packet.py', 'openconnect_packet.py', 'ipsec_packet.py', 'tailscale_packet.py'):
                    assets[name] = Path(__file__).with_name('fixtures') / name
                if args.kind == 'tailscale':
                    if args.assets is None:
                        parser.error('--assets must contain tailscale, tailscaled and headscale')
                    assets.update({name: args.assets / name for name in ('tailscale', 'tailscaled', 'headscale')})
            stage_assets(suite, assets)
        extra = ['--container-image', args.container_image, '--container-cache', args.container_cache,
                 '--container-runroot', args.container_runroot] if args.container_image else []
        command = [*runner, '--bind-ro', suite.base, target, '--deadline-seconds', '180', '--',
                   '/usr/bin/python3', target + '/fixture.py', '--guest', '--assets', target,
                   '--kind', args.kind, *extra]
        with observe_suite_cpu(suite) if args.load else contextlib.nullcontext() as cpu:
            output = suite.command((['/system/bin/time', '-p'] if args.load else []) + command, timeout=200)
        if args.load:
            suite.report['load'] = dict(cpu=cpu,
                lifecycle=dict(re.findall(r'^(real|user|sys)\s+(\d+\.\d+)\s*$', output, re.M)),
                samples=[json.loads(line.removeprefix('PACKET_LOAD ')) for line in output.splitlines()
                         if line.startswith('PACKET_LOAD ')])
            assert suite.report['load']['samples'], 'no packet load measurement'
        suite.report['passed'] = True
    finally:
        try:
            if runner:
                suite.command([*runner, '--', '/bin/rm', '-rf', target])
        finally:
            try:
                suite.command(['rm', '-rf', suite.base])
            finally:
                suite.finish()


def stage_assets(suite, assets):
    suite.report['assets'] = {}
    for name, path in assets.items():
        with path.open('rb') as source:
            suite.report['assets'][name] = hashlib.file_digest(source, 'sha256').hexdigest()
        suite.transport.upload(suite.client, path, suite.base + '/' + name)
        suite.command(['chmod', '755', suite.base + '/' + name])


if __name__ == '__main__':
    main()
