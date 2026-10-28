#!/usr/bin/env python3
"""Shared direct-entry contracts through the production bootstrap and syscall engine."""
import argparse
import re
from pathlib import Path
from test_oci_services import Suite, add_machine_entry_argument


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('--entry-only', action='store_true')
    parser.add_argument('--diagnostics', action='store_true')
    add_machine_entry_argument(parser)
    parser.add_argument('--case', choices=('all', 'birth-retirement', 'bursts', 'fd-discovery', 'site-discovery', 'proc-discovery', 'cursor-lease'), default='all')
    args = parser.parse_args()
    suite = Suite(Path('build/cooperative-runtime'), args.build, machine_entry=args.machine_entry)
    runner = [suite.runner, *suite.entry_options]
    try:
        root = suite.base + '/rootfs'
        suite.command(['mkdir', '-p', root + '/bin', root + '/tmp'])
        for name, destination in (('service', suite.base + '/service'),
                                  ('cooperative_guest', root + '/bin/probe'),
                                  ('test_vdso', suite.base + '/vdso'),
                                  ('test_signal_delivery', suite.base + '/signals'),
                                  ('test_cooperative_native', suite.base + '/native-journal'),
                                  ('test_guest_copy', suite.base + '/guest-copy'),
                                  ('test_freestanding_memory', suite.base + '/freestanding-memory'),
                                  ('test_task_resources', suite.base + '/task-resources'),
                                  ('test_inodes', suite.base + '/inodes'),
                                  ('test_mounts', suite.base + '/mounts'),
                                  ('test_publication', suite.base + '/publication'),
                                  ('test_native_leaf', suite.base + '/native-leaf'),
                                  ('test_interception_buffers', suite.base + '/buffers'),
                                  ('test_mapping_reader', suite.base + '/mapping-reader'),
                                  ('test_task_memory', suite.base + '/task-memory'),
                                  ('test_received_fds', suite.base + '/received-fds'),
                                  ('test_netlink_attributes', suite.base + '/netlink-attributes'),
                                  ('test_directory_record', suite.base + '/directory-record'),
                                  ('fuse_transport', suite.base + '/fuse-transport'),
                                  ('fs_dispatch', suite.base + '/dispatch')):
            suite.transport.upload(suite.client, args.build / ('libmagicdesk_guest_' + name + '.so'), destination)
            suite.command(['chmod', '700', destination])
        if not args.entry_only:
            suite.command(['timeout', '10', suite.base + '/mapping-reader'])
            suite.command(['timeout', '10', suite.base + '/task-memory'])
            suite.command(['timeout', '10', suite.base + '/received-fds'])
            suite.command(['timeout', '10', suite.base + '/netlink-attributes'])
            suite.command(['timeout', '10', suite.base + '/directory-record'])
            suite.command(['timeout', '30', suite.base + '/fuse-transport'])
            suite.command(['timeout', '90', suite.base + '/dispatch', '--fuse-device',
                           suite.base + '/fuse-store'])
            suite.command(['timeout', '20', suite.base + '/signals'])
            suite.command(['timeout', '15', suite.base + '/guest-copy'])
            suite.command(['timeout', '15', suite.base + '/freestanding-memory'])
            suite.command(['timeout', '45', suite.base + '/native-journal'])
            suite.command(['timeout', '90', suite.base + '/task-resources'])
            suite.command(['timeout', '60', suite.base + '/inodes', suite.base + '/inode-store'])
            suite.command(['timeout', '120', suite.base + '/mounts', suite.base + '/mount-store'])
            suite.command(['timeout', '120', suite.base + '/publication', suite.base + '/publication-store'])
            suite.command(['timeout', '120', suite.base + '/native-leaf', suite.base + '/leaf-store'])
            suite.command(['timeout', '10', suite.base + '/vdso'])
            suite.command(['timeout', '90', suite.base + '/buffers'])
            suite.command(['timeout', '120', suite.base + '/dispatch', suite.base + '/dispatch-store'], quiet=True)
            output = suite.command(['timeout', '90', suite.base + '/dispatch', '--broker-directory',
                                    suite.base + '/broker-store'], quiet=True)
            assert 'PASS notification result: exact cancelled-token replacement' in output
        # Distribution imports carry virtual ownership; a host-directory import
        # without it deliberately retains real Android permission semantics.
        suite.command([suite.base + '/service', '--import', root, suite.base + '/store', '--preserve-ownership'])
        if args.case in ('all', 'proc-discovery'):
            output = suite.command(['timeout', '30', *runner, '--store', suite.base + '/store',
                                   '--user', '0:0', '--', '/bin/probe', '--proc-discovery'])
            assert 'PASS native proc discovery:' in output
            if args.case == 'proc-discovery':
                suite.report['passed'] = True
                suite.command(['rm', '-rf', suite.base])
                return
        if args.case in ('all', 'site-discovery'):
            output = suite.command([*runner, '--statistics', '--store', suite.base + '/store',
                                   '--user', '0:0', '--', '/bin/probe', '--site-discovery'])
            assert 'PASS native site discovery:' in output
            count = re.search(r'cooperativeCompleted=(\d+)', output)
            assert count and (int(count[1]) == 0 if args.machine_entry is False
                              else int(count[1]) >= 32 * 16), 'first calls at new SVC sites missed direct admission'
            if args.case == 'site-discovery':
                suite.report['passed'] = True
                suite.command(['rm', '-rf', suite.base])
                return
        if args.case in ('all', 'fd-discovery'):
            output = suite.command([*runner, '--statistics', '--store', suite.base + '/store',
                                   '--user', '0:0', '--', '/bin/probe', '--fd-discovery'])
            assert 'PASS native FD discovery:' in output
            seeks = re.search(r'nativeSeek=(\d+)', output)
            assert seeks and int(seeks[1]) < 512, 'ordinary repeated seeks still enter the worker'
            for key, minimum in (('nativeClose', 250), ('nativeDuplicate', 120),
                                 ('nativeFlags', 120), ('nativeSeek', 120)):
                count = re.search(rf'{key}=(\d+)', output)
                assert count and (int(count[1]) == 0 if args.machine_entry is False
                                  else int(count[1]) >= minimum), f'{key} missed lazy FD discovery'
            if args.case == 'fd-discovery':
                suite.report['passed'] = True
                suite.command(['rm', '-rf', suite.base])
                return
        if args.case != 'all':
            output = suite.command(['timeout', '90', *runner,
                                    *(['--diagnostics'] if args.diagnostics else []),
                                    '--statistics', '--store', suite.base + '/store',
                                    '--user', '0:0', '--', '/bin/probe', '--' + args.case])
            assert ('PASS native birth retirement:' if args.case == 'birth-retirement'
                    else 'PASS native cursor lease:' if args.case == 'cursor-lease'
                    else 'PASS execution bursts:') in output
            suite.report['passed'] = True
            suite.command(['rm', '-rf', suite.base])
            return
        output = suite.command(['timeout', '90', *runner, '--store', suite.base + '/store',
                                '--user', '0:0', '--', '/bin/probe', '--cursor-lease'])
        assert 'PASS native cursor lease:' in output
        output = suite.command(['timeout', '15', *runner, '--store', suite.base + '/store',
                                '--user', '0:0', '--', '/bin/probe', '--initial-stack', '-silent'], quiet=True)
        assert output.count('PASS initial stack:') == 2
        output = suite.command(['timeout', '15', *runner, '--diagnostics', '--store', suite.base + '/store',
                                '--user', '0:0', '--', '/bin/probe', '--jit-resume'], quiet=True)
        assert 'PASS cooperative JIT resume:' in output
        assert 'PASS JIT internal wait:' in output
        assert len(re.findall(r'PROBE memory-repair .* result=0 scope=address-space', output)) == 8
        output = suite.command(['timeout', '30', *runner, '--store', suite.base + '/store',
                                '--user', '0:0', '--', '/bin/probe', '--jit-busy'], quiet=True)
        assert 'PASS cooperative JIT busy:' in output
        output = suite.command(['timeout', '90', *runner, *(['--diagnostics'] if args.diagnostics else []), '--statistics', '--store', suite.base + '/store',
                               '--user', '0:0', '--', '/bin/probe'])
        assert 'PASS cooperative entry: metadata' in output
        assert 'PASS cooperative ABI: runtime-owned offers' in output
        assert 'PASS cooperative protected copy:' in output
        assert 'PASS native FD plans: 192 live opens' in output
        assert 'PASS native FD watches: exactly one open and one close event' in output
        assert 'PASS native signal boundaries:' in output
        assert 'PASS cooperative JIT resume:' in output
        assert 'PASS native writable plans:' in output
        assert 'PASS native create admission:' in output
        assert 'PASS filtered close: actual syscall FD' in output
        assert 'PASS native open exhaustion:' in output
        assert 'PASS execution bursts:' in output
        assert 'PASS filter exec: leader and nonleader retain registration revocation' in output
        for key, minimum in (('nativeOpen', 1000), ('nativeClose', 990), ('nativeFlags', 576),
                             ('nativeDuplicate', 576), ('nativeSeek', 384), ('nativeDeferred', 1)):
            count = re.search(rf'{key}=(\d+)', output)
            assert count and int(count[1]) >= minimum, f'{key} did not exercise native FD plans'
        descriptors = re.search(r'cooperativeDescriptors=(\d+)', output)
        assert descriptors and int(descriptors[1]) >= 128, 'FD checks did not exercise cooperative admission'
        suite.command(['mkdir', '-p', root + '/tmp/entry-emfile-dir', root + '/tmp/entry-birth-watch-dir'])
        suite.command([suite.base + '/service', '--import', root, suite.base + '/host-store'])
        output = suite.command(['timeout', '90', *runner, '--store', suite.base + '/host-store',
                               '--user', '0:0', '--', '/bin/probe', '--host-births'])
        assert 'PASS catalogue births:' in output
        suite.report['passed'] = True
        suite.command(['rm', '-rf', suite.base])
    finally:
        suite.finish()


if __name__ == '__main__':
    main()
