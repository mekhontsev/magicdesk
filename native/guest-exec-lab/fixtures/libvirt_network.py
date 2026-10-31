"""Owned libvirt bridge, DHCP/DNS and off-subnet packet peers for the VM fixture."""
import contextlib
import json
from pathlib import Path
import select
import socket
import subprocess
import sys
import xml.etree.ElementTree as ET

from probe_packet_runtime import echoes, exchange

MAC = '52:54:00:12:34:56'


def serve_peer(nat):
    expected = {socket.AF_INET: ('198.51.101.254' if nat else '198.51.100.2', 1024 if nat else 1, 65535),
                socket.AF_INET6: ('fd42:7::/64', 1, 65535)}
    with echoes(('198.51.101.1', 'fd42:9::1'), expected_peer=expected):
        print('READY NAT peer', flush=True)
        sys.stdin.read()


@contextlib.contextmanager
def routed_peer(work, name, forward):
    peer = name + '-peer'
    link = 'mp' + name[-10:]
    process = None
    def command(*args):
        return subprocess.run(args, check=True)
    command('ip', 'netns', 'add', peer)
    try:
        command('ip', 'link', 'add', link, 'type', 'veth', 'peer', 'name', 'eth0', 'netns', peer)
        command('ip', 'link', 'set', link, 'up')
        command('ip', '-n', peer, 'link', 'set', 'lo', 'up')
        command('ip', '-n', peer, 'link', 'set', 'eth0', 'up')
        for family, local, remote, guest in (
                ('-4', '198.51.101.254/24', '198.51.101.1/24', '198.51.100.0/24'),
                ('-6', 'fd42:9::ff/64', 'fd42:9::1/64', 'fd42:7::/64')):
            command('ip', family, 'addr', 'add', local, 'dev', link)
            command('ip', '-n', peer, family, 'addr', 'add', remote, 'dev', 'eth0')
            command('ip', '-n', peer, family, 'route', 'add', guest, 'via', local.split('/')[0])
        with (work / 'packet-peer.log').open('w+') as log:
            code = f'from libvirt_network import serve_peer; serve_peer({forward == "nat"!r})'
            process = subprocess.Popen(['ip', 'netns', 'exec', peer, sys.executable, '-c', code],
                                       stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=log, text=True,
                                       cwd=Path(__file__).resolve().parent)
            # EVENT_WAIT: all echo sockets are bound; timeout fails preparation.
            assert select.select([process.stdout], [], [], 20)[0], 'packet peer not ready'
            assert process.stdout.readline().strip() == 'READY NAT peer'
            try:
                yield
            finally:
                process.stdin.close()
                assert process.wait(timeout=20) == 0, 'packet peer failed'
                log.seek(0)
                detail = log.read()
                assert not detail, detail
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait()
        if process is not None:
            process.stdin.close()
            process.stdout.close()
        subprocess.run(['ip', 'link', 'del', link], check=False)
        command('ip', 'netns', 'del', peer)


@contextlib.contextmanager
def managed_network(run, work, name, forward):
    bridge = 'md' + name[-10:]
    network = ET.Element('network')
    ET.SubElement(network, 'name').text = name
    ET.SubElement(network, 'bridge', name=bridge, stp='off', delay='0')
    ET.SubElement(network, 'forward', mode=forward)
    dns = ET.SubElement(network, 'dns')
    host = ET.SubElement(dns, 'host', ip='198.51.101.1')
    ET.SubElement(host, 'hostname').text = 'packet-peer.test'
    ip = ET.SubElement(network, 'ip', address='198.51.100.1', prefix='24')
    dhcp = ET.SubElement(ip, 'dhcp')
    ET.SubElement(dhcp, 'range', start='198.51.100.2', end='198.51.100.20')
    ET.SubElement(dhcp, 'host', mac=MAC, name='packet-vm', ip='198.51.100.2')
    ET.SubElement(network, 'ip', family='ipv6', address='fd42:7::1', prefix='64')
    path = work / 'network.xml'
    ET.ElementTree(network).write(path, encoding='unicode')
    run('net-define', str(path))
    try:
        run('net-start', name)
        with routed_peer(work, name, forward):
            yield
    finally:
        run('net-destroy', name, required=False)
        run('net-undefine', name)
        links = json.loads(subprocess.check_output(['ip', '-j', 'link', 'show']))
        assert not any(link['ifname'] == bridge or link.get('master') == bridge for link in links), links
        print('PASS libvirt network and TAP cleanup', flush=True)


def verify_peer(run, name):
    leases = run('net-dhcp-leases', name, '--mac', MAC).stdout
    assert '198.51.100.2/24' in leases, leases
    exchange(('198.51.100.2', 'fd42:7::2'), 18081)
