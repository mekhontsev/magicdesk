#!/usr/bin/env python3
"""Docker Engine inside a TCG ARM64 VM hosted by guest UID 2000.

This is not Docker Engine directly on Android or a KVM check. The ISO digest
pins the kernel, initramfs and boot packages; APK retains its signature checks.
"""
import argparse
import functools
import hashlib
import http.server
import io
from pathlib import Path
import shlex
import subprocess
import tarfile
import threading
from test_oci_services import Suite

ISO_SHA256 = '9f220f49bdaa8ca954b97ea32d7c2652345354cf1d753e371e5b61e04ceec075'

SCRIPT = '''#!/bin/sh
exec >/dev/console 2>&1
set -ex
trap 'echo "VM fixture exit=$?"; poweroff -f' EXIT
uname -a
apk add --no-cache docker
mkdir -p /sys/fs/cgroup
mountpoint -q /sys/fs/cgroup || mount -t cgroup2 none /sys/fs/cgroup
mkdir -p /etc/docker
printf '%s\n' '{"storage-driver":"vfs"}' >/etc/docker/daemon.json
mkfifo /run/md-docker-log
dockerd --host unix:///var/run/docker.sock >/run/md-docker-log 2>&1 &
daemon=$!
exec 3</run/md-docker-log
# EVENT_WAIT: the daemon's API-listen log record; EOF fails, outer VM deadline cancels.
ready=0
while IFS= read -r line <&3; do
    printf '%s\n' "$line"
    case "$line" in *'API listen on'*) ready=1; break;; esac
done
test "$ready" = 1
cat <&3 &
logger=$!
exec 3<&-
docker version
docker info
docker run --rm alpine:3.23 sh -ec 'test "$(id -u)" = 0; test -e /proc/self/ns/mnt; echo PASS-container-process'
docker volume create md-fixture
docker run --rm -v md-fixture:/data alpine:3.23 sh -ec 'printf persistent >/data/value'
docker run --rm -v md-fixture:/data alpine:3.23 sh -ec 'test "$(cat /data/value)" = persistent'
docker volume rm md-fixture
kill "$daemon"
wait "$daemon"
wait "$logger"
echo 'PASS Docker Engine in QEMU: container process and persistent volume'
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--build', type=Path)
    parser.add_argument('--instance', required=True)
    parser.add_argument('--iso', type=Path, required=True)
    args = parser.parse_args()
    with args.iso.open('rb') as stream:
        assert hashlib.file_digest(stream, 'sha256').hexdigest() == ISO_SHA256
    suite = Suite(args.output, args.build)
    directory = args.output / ('qemu-docker-' + suite.report['id'])
    directory.mkdir()
    server = thread = None
    try:
        subprocess.run(['bsdtar', '-xf', str(args.iso), '-C', str(directory),
                        'boot/vmlinuz-virt', 'boot/initramfs-virt', 'boot/modloop-virt',
                        'apks'], check=True)
        files = {
            'etc/local.d/fixture.start': (SCRIPT, 0o755),
            'etc/apk/world': ('alpine-base\n', 0o644),
            'etc/apk/repositories': (
                'https://dl-cdn.alpinelinux.org/alpine/v3.23/main\n'
                'https://dl-cdn.alpinelinux.org/alpine/v3.23/community\n', 0o644),
            'etc/network/interfaces': ('auto lo\niface lo inet loopback\nauto eth0\niface eth0 inet dhcp\n', 0o644),
            'etc/hostname': ('md-docker-vm\n', 0o644),
        }
        with tarfile.open(directory / 'fixture.apkovl.tar.gz', 'w:gz') as archive:
            for path, (text, mode) in files.items():
                data = text.encode()
                entry = tarfile.TarInfo(path)
                entry.mode, entry.size = mode, len(data)
                archive.addfile(entry, io.BytesIO(data))
            for service, level in (('local', 'default'), ('networking', 'boot'),
                                    ('devfs', 'sysinit'), ('procfs', 'sysinit'),
                                    ('sysfs', 'sysinit'), ('mdev', 'sysinit'),
                                    ('modloop', 'sysinit')):
                entry = tarfile.TarInfo(f'etc/runlevels/{level}/{service}')
                entry.type, entry.linkname, entry.mode = tarfile.SYMTYPE, '/etc/init.d/' + service, 0o777
                archive.addfile(entry)
        handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(directory))
        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), handler)
        thread = threading.Thread(target=server.serve_forever)
        thread.start()
        endpoint = 'http://10.0.2.2:' + str(server.server_port)
        for name in ('vmlinuz-virt', 'initramfs-virt'):
            suite.transport.upload(suite.client, directory / 'boot' / name, suite.base + '/' + name)
        # Only the private fixture directory is attached. No Android root or KVM.
        suite.command(suite.run(args.instance, '--', '/bin/mkdir', '-p', '/vm'))
        command = ['qemu-system-aarch64', '-machine', 'virt', '-cpu', 'cortex-a72',
                   '-accel', 'tcg,thread=multi', '-smp', '2', '-m', '1536',
                   '-nographic', '-monitor', 'none', '-netdev', 'user,id=net',
                   '-device', 'virtio-net-pci,netdev=net,romfile=',
                   '-kernel', '/vm/vmlinuz-virt', '-initrd', '/vm/initramfs-virt',
                   '-append', 'console=ttyAMA0 modules=loop,squashfs,virtio_pci,virtio_net ip=dhcp '
                   f'alpine_repo={endpoint}/apks apkovl={endpoint}/fixture.apkovl.tar.gz '
                   f'modloop={endpoint}/boot/modloop-virt']
        suite.report.update({'isoSha256': ISO_SHA256, 'vmCommand': command, 'instance': args.instance})
        output = suite.command(suite.run(args.instance, '--bind', suite.base, '/vm', '--',
            '/bin/sh', '-c', shlex.join(command) + ' </dev/null >/vm/serial.log 2>&1; '
            'status=$?; cat /vm/serial.log; exit "$status"'), timeout=900)
        assert 'PASS Docker Engine in QEMU: container process and persistent volume' in output
        suite.report['passed'] = True
    except Exception as error:
        suite.report['failure'] = repr(error)
        raise
    finally:
        if server:
            server.shutdown()
            server.server_close()
        if thread:
            # EVENT_WAIT: HTTP server shutdown; failure bound, not a boot delay.
            thread.join(10)
            assert not thread.is_alive()
        suite.finish()


if __name__ == '__main__':
    main()
