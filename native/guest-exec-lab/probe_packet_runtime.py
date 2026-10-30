#!/usr/bin/env python3
"""Real TUN proxy or QEMU TAP traffic under Shroot, with local test topology.

TUN uses stock tun2socks and GOST; TAP uses distribution QEMU and a trusted
Alpine kernel/initramfs pair. No Android network configuration is changed.
All processes, links and test files belong to one bounded launch.
"""
import argparse
import contextlib
import hashlib
import json
import os
from pathlib import Path
import queue
import shutil
import socket
import socketserver
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
def echoes():
    servers = []
    threads = []
    try:
        for family, address in ((socket.AF_INET, '198.51.100.1'),
                                (socket.AF_INET6, 'fd42:7::1')):
            for cls, handler in ((socketserver.ThreadingTCPServer, TCPHandler),
                                 (socketserver.ThreadingUDPServer, UDPHandler)):
                class Server(cls):
                    address_family = family
                    daemon_threads = True
                    allow_reuse_address = True
                    max_packet_size = 65535
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


def disconnected():
    for family, address in ((socket.AF_INET, '198.51.100.1'), (socket.AF_INET6, 'fd42:7::1')):
        with socket.socket(family, socket.SOCK_STREAM) as stream:
            stream.settimeout(.5)
            try:
                stream.connect((address, 18080))
            except OSError:
                continue
            raise AssertionError('traffic bypassed the stopped TUN application: ' + address)
    print('PASS no route bypass without tun2socks', flush=True)


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
    shutil.copyfile(test, root / 'packet-test')
    (root / 'init').chmod(0o755)
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
    parser.add_argument('--kind', choices=('tun', 'tap'), default='tun')
    parser.add_argument('--kernel', type=Path)
    parser.add_argument('--initramfs', type=Path)
    parser.add_argument('--build', type=Path, default=Path('build/shroot-image-tests'))
    parser.add_argument('--environment', default='debian-turnip')
    parser.add_argument('--base', action='store_true')
    args = parser.parse_args()
    if args.exchange:
        exchange()
        return
    if args.disconnected:
        disconnected()
        return
    if (args.guest or args.kind == 'tun') and args.assets is None:
        parser.error('--assets is required for this mode')
    if not args.guest and args.kind == 'tap' and (args.kernel is None or args.initramfs is None):
        parser.error('--kernel and --initramfs are required for TAP')
    if args.guest:
        os.environ['PATH'] = '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'
        with tempfile.TemporaryDirectory(prefix='md-packet-') as temporary:
            (tun_guest if args.kind == 'tun' else tap_guest)(Path(temporary), args.assets)
        return
    from test_oci_services import Suite
    suite = Suite(Path('build/packet-apps/results'), args.build,
                  machine_entry=False if args.base else None)
    target = '/tmp/md-packet-' + suite.report['id']
    runner = None
    try:
        store = suite.installed_environment(args.environment)['store']
        runner = suite.guest(store, '--user', '0:0', '--network', 'routed')
        suite.command([*runner, '--', '/bin/mkdir', target])
        assets = {'fixture.py': Path(__file__)}
        with tempfile.TemporaryDirectory(prefix='md-packet-vm-') as temporary:
            if args.kind == 'tun':
                assets.update(gost=args.assets / 'gost', tun2socks=args.assets / 'tun2socks-linux-arm64')
            else:
                assets.update(kernel=args.kernel)
                assets['initramfs.cpio'] = vm_archive(Path(temporary), args.initramfs,
                                                    args.build / 'libmagicdesk_guest_packet_vm.so')
            stage_assets(suite, assets)
        suite.command([*runner, '--bind-ro', suite.base, target, '--deadline-seconds', '180', '--',
                       '/usr/bin/python3', target + '/fixture.py', '--guest', '--assets', target,
                       '--kind', args.kind], timeout=200)
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
