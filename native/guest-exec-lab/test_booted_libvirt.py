#!/usr/bin/env python3
"""Run stock system libvirt or direct QEMU TCG in an installed Linux session."""
import argparse
import tempfile
from pathlib import Path
from boot_fixture import run_fixture
from init_fixture import start_service


def prepare_libvirt(state):
    init = state['init']
    if init == 'systemd':
        return '''for policy in enabled loaded; do
  for service in virtqemud libvirtd; do
    unit=$service.socket
    if [ "$policy" = enabled ]; then
      [ "$(systemctl is-enabled "$unit" 2>/dev/null)" = enabled ] || continue
    else
      [ "$(systemctl show -p LoadState --value "$unit")" = loaded ] || continue
    fi
    systemctl start "$unit"
    exit $?
  done
done
echo "No packaged libvirt service found" >&2
exit 1'''
    branches = []
    for service in ('virtqemud', 'libvirtd'):
        if init == 'openrc':
            present = f'[ -x /etc/init.d/{service} ]'
            start = start_service(init, 'virtlogd') + ' && ' + start_service(init, service)
        elif init == 'runit':
            present = f'[ -f /etc/sv/{service}/run ] || [ -f /etc/runit/sv/{service}/run ]'
            start = start_service(init, 'virtlogd') + ' && ' + start_service(init, service)
        else:
            raise ValueError('Unsupported init: ' + init)
        branches.append(('if' if not branches else 'elif') + f' {present}; then\n{start}\n')
    return ''.join(branches) + 'else\necho "No packaged libvirt service found" >&2\nexit 1\nfi'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--session', type=Path, required=True)
    parser.add_argument('--kernel', type=Path, required=True)
    parser.add_argument('--initrd', type=Path, required=True)
    parser.add_argument('--direct', action='store_true', help='Run QEMU without libvirt')
    parser.add_argument('--network', action='store_true', help='Libvirt bridge, DHCP, DNS and bidirectional VM packets')
    parser.add_argument('--network-forward', choices=('nat', 'open'), default='nat',
                        help='Libvirt forwarding policy; open tests managed networking without firewall rules')
    parser.add_argument('--build', type=Path, default=Path('build/shroot-image-tests'))
    args = parser.parse_args()
    if args.network:
        if args.direct:
            parser.error('--network requires managed libvirt')
        from probe_packet_runtime import vm_archive
        with tempfile.TemporaryDirectory(prefix='md-libvirt-packet-') as temporary:
            archive = vm_archive(Path(temporary), args.initrd, args.build / 'libmagicdesk_guest_packet_vm.so')
            run_fixture(args.session, 'libvirt', [args.kernel, archive, '--network',
                        '--network-forward', args.network_forward],
                        [args.kernel, archive, Path(__file__).with_name('probe_packet_runtime.py'),
                         Path(__file__).with_name('fixtures') / 'libvirt_network.py'], prepare=prepare_libvirt)
        return
    run_fixture(args.session, 'qemu' if args.direct else 'libvirt',
                [args.kernel, args.initrd], [args.kernel, args.initrd],
                prepare=None if args.direct else prepare_libvirt)


if __name__ == '__main__':
    main()
