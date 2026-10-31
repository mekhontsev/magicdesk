#!/usr/bin/env python3
"""Boot the kernel-contract initramfs through stock system libvirt and TCG."""
import argparse
import contextlib
import json
import os
from pathlib import Path
import socket
import shutil
import subprocess
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('kernel', type=Path)
    parser.add_argument('initrd', type=Path)
    parser.add_argument('report', type=Path)
    parser.add_argument('--network', action='store_true')
    parser.add_argument('--network-forward', choices=('nat', 'open'), default='nat')
    args = parser.parse_args()
    images = Path('/var/lib/libvirt/images')
    images.mkdir(parents=True, exist_ok=True)
    temporary = tempfile.TemporaryDirectory(prefix='md-libvirt-', dir=images)
    work = Path(temporary.name)
    work.chmod(0o755)
    name = 'md-libvirt-' + uuid.uuid4().hex
    report = {'name': name, 'passed': False, 'commands': [],
              'networkForward': args.network_forward if args.network else None}
    prefix = ['virsh', '-c', 'qemu:///system']
    defined = False
    resources = contextlib.ExitStack()

    def run(*args, required=True):
        print('RUN', args, flush=True)
        result = subprocess.run([*prefix, *args], capture_output=True, text=True, timeout=90,
                                env={**os.environ, 'LC_ALL': 'C'})
        report['commands'].append(dict(args=args, code=result.returncode,
                                       stdout=result.stdout, stderr=result.stderr))
        print(result.stdout + result.stderr, end='', flush=True)
        if required:
            assert result.returncode == 0, report['commands'][-1]
        return result

    try:
        for source, filename in ((args.kernel, 'kernel'), (args.initrd, 'initrd')):
            shutil.copyfile(source, work / filename)
            (work / filename).chmod(0o644)
        report['versions'] = dict(libvirt=run('version').stdout)
        if args.network:
            from libvirt_network import managed_network, verify_peer, MAC
            resources.enter_context(managed_network(run, work, name, args.network_forward))
        domain = ET.Element('domain', type='qemu')
        ET.SubElement(domain, 'name').text = name
        ET.SubElement(domain, 'memory', unit='MiB').text = '512'
        ET.SubElement(domain, 'vcpu').text = '2'
        cpu = ET.SubElement(domain, 'cpu', mode='custom', match='exact')
        ET.SubElement(cpu, 'model', fallback='forbid').text = 'cortex-a72'
        boot = ET.SubElement(domain, 'os')
        ET.SubElement(boot, 'type', arch='aarch64', machine='virt').text = 'hvm'
        ET.SubElement(boot, 'kernel').text = str(work / 'kernel')
        ET.SubElement(boot, 'initrd').text = str(work / 'initrd')
        ET.SubElement(boot, 'cmdline').text = 'console=ttyAMA0 rdinit=/init ' + (
            'magicdesk.packet_dhcp=1' if args.network else 'magicdesk.kernel_fixture=1')
        ET.SubElement(domain, 'on_poweroff').text = 'destroy'
        ET.SubElement(domain, 'on_crash').text = 'destroy'
        devices = ET.SubElement(domain, 'devices')
        if args.network:
            interface = ET.SubElement(devices, 'interface', type='network')
            ET.SubElement(interface, 'source', network=name)
            ET.SubElement(interface, 'mac', address=MAC)
            ET.SubElement(interface, 'model', type='virtio')
        serial = ET.SubElement(devices, 'serial', type='unix')
        console = str(work / 'serial.sock')
        ET.SubElement(serial, 'source', mode='connect', path=console)
        target = ET.SubElement(serial, 'target', type='system-serial', port='0')
        ET.SubElement(target, 'model', name='pl011')
        xml = work / 'domain.xml'
        ET.ElementTree(domain).write(xml, encoding='unicode')
        run('define', str(xml)); defined = True
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as listener:
            listener.bind(console)
            os.chmod(console, 0o666)
            listener.listen(1)
            listener.settimeout(90)
            run('start', name, '--paused')
            assert run('domstate', name).stdout.strip() == 'paused'
            # EVENT_WAIT: exact QEMU serial connection; failure bound is 90 s.
            stream, _ = listener.accept()
        with stream:
            stream.settimeout(180)
            run('resume', name)
            deadline = time.monotonic() + 180
            chunks = []
            checked_peer = False
            # EVENT_WAIT: guest serial EOF on QEMU exit; deadline fails a stalled VM.
            while True:
                stream.settimeout(max(0.001, deadline - time.monotonic()))
                data = stream.recv(65536)
                if not data:
                    break
                chunks.append(data)
                if args.network and not checked_peer and b'READY VM reverse traffic' in b''.join(chunks):
                    verify_peer(run, name)
                    checked_peer = True
            output = b''.join(chunks).decode(errors='replace')
            args.report.with_suffix('.serial.log').write_text(output)
            marker = 'PASS packet VM completed' if args.network else 'PASS kernel contract in VM status=0'
            assert marker in output, output[-4000:]
            if args.network:
                assert checked_peer
        assert run('domstate', name).stdout.strip() == 'shut off'
        report['passed'] = True
        print('PASS libvirt: define, paused start, serial, resume, kernel boot and poweroff', flush=True)
    finally:
        try:
            if defined:
                run('destroy', name, required=False)
                run('undefine', name)
        finally:
            try:
                resources.close()
            finally:
                temporary.cleanup()
                args.report.write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
