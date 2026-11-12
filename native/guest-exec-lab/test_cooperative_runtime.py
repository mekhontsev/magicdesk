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
    parser.add_argument('--case', choices=('all', 'birth-retirement', 'bursts', 'fd-discovery', 'site-discovery', 'proc-discovery', 'socket-discovery', 'cursor-lease', 'object-queries', 'xattr-pins', 'signal-abandon', 'native-payload', 'jit-resume', 'jit-busy'), default='all')
    args = parser.parse_args()
    # Focused runtime cases share no state with the standalone unit fixtures.
    # The default full run retains both layers of coverage.
    entry_only = args.entry_only or args.case != 'all'
    suite = Suite(Path('build/cooperative-runtime'), args.build, machine_entry=args.machine_entry)
    runner = [suite.runner, *suite.entry_options]
    try:
        root = suite.base + '/rootfs'
        suite.command(['mkdir', '-p', root + '/bin', root + '/tmp'])
        suite.chmod('755', root, root + '/bin')
        suite.chmod('1777', root + '/tmp')
        for name, destination in (('service', suite.base + '/service'),
                                  ('cooperative_guest', root + '/bin/probe'),
                                  ('test_signal_delivery', suite.base + '/signals'),
                                  ('test_cooperative_native', suite.base + '/native-journal'),
                                  ('test_guest_copy', suite.base + '/guest-copy'),
                                  ('test_freestanding_memory', suite.base + '/freestanding-memory'),
                                  ('test_task_resources', suite.base + '/task-resources'),
                                  ('test_render_device', suite.base + '/render-device'),
                                  ('test_input_devices', suite.base + '/input-devices'),
                                  ('test_input_uinput', suite.base + '/input-uinput'),
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
            if entry_only and name not in ('service', 'cooperative_guest'):
                continue
            suite.upload(args.build / ('libmagicdesk_guest_' + name + '.so'), destination)
            suite.chmod('700', destination)
        if not entry_only:
            suite.command([suite.base + '/mapping-reader'], timeout=10)
            suite.command([suite.base + '/task-memory'], timeout=10)
            suite.command([suite.base + '/received-fds'], timeout=10)
            suite.command([suite.base + '/netlink-attributes'], timeout=10)
            suite.command([suite.base + '/directory-record'], timeout=10)
            suite.command([suite.base + '/fuse-transport'], timeout=30)
            suite.command([suite.base + '/dispatch', '--fuse-device',
                           suite.base + '/fuse-store'], timeout=90)
            suite.command([suite.base + '/signals'], timeout=20)
            suite.command([suite.base + '/guest-copy'], timeout=15)
            suite.command([suite.base + '/freestanding-memory'], timeout=15)
            suite.command([suite.base + '/native-journal'], timeout=45)
            suite.command([suite.base + '/task-resources'], timeout=90)
            suite.command([suite.base + '/render-device'], timeout=15)
            suite.command([suite.base + '/input-devices'], timeout=15)
            suite.command([suite.base + '/input-uinput'], timeout=30)
            suite.command([suite.base + '/inodes', suite.base + '/inode-store'], timeout=60)
            suite.command([suite.base + '/mounts', suite.base + '/mount-store'], timeout=120)
            suite.command([suite.base + '/publication', suite.base + '/publication-store'], timeout=120)
            suite.command([suite.base + '/native-leaf', suite.base + '/leaf-store'], timeout=120)
            suite.command([suite.base + '/buffers'], timeout=90)
            suite.command([suite.base + '/dispatch', suite.base + '/dispatch-store'], quiet=True, timeout=120)
            output = suite.command([suite.base + '/dispatch', '--broker-directory',
                                    suite.base + '/broker-store'], quiet=True, timeout=90)
            assert 'PASS notification result: exact cancelled-token replacement' in output
        # Distribution imports carry virtual ownership; a host-directory import
        # without it deliberately retains real Android permission semantics.
        suite.command([suite.base + '/service', '--import', root, suite.base + '/store', '--preserve-ownership'])
        if args.case in ('all', 'object-queries'):
            output = suite.command([*runner, '--statistics', '--store', suite.base + '/store',
                                   '--user', '0:0', '--', '/bin/probe', '--object-queries'])
            assert 'PASS object queries:' in output
            controls = {int(command): int(count) for command, count in
                        re.findall(r'MD_BUFFER command=(\d+) calls=(\d+)', output)}
            assert controls.get(84, 0) >= 64, 'Typed schemas did not use the combined buffer query'
            assert controls.get(81, 0) < 8, 'Immutable queries still execute a second owner exchange'
            if args.case == 'object-queries':
                suite.report['passed'] = True
                suite.command(['rm', '-rf', suite.base])
                return
        if args.case in ('all', 'native-payload'):
            for filtered in (0, 1):
                output = suite.command([*runner, '--statistics', '--store', suite.base + '/store',
                                        '--user', '0:0', '--', '/bin/probe', '--native-payload', str(filtered)])
                assert f'PASS native payload: filtered={filtered}' in output
                if args.machine_entry is not False:
                    stops = sum(int(trace) + int(notifications) for trace, notifications in re.findall(
                        r'MD_SYSCALL nr=(?:206|207) trace=(\d+) notification=(\d+)', output))
                    assert stops <= 4, ('native payload repeated interception after discovery', filtered, stops)
            if args.case == 'native-payload':
                suite.report['passed'] = True
                suite.command(['rm', '-rf', suite.base])
                return
        if args.case in ('all', 'socket-discovery'):
            for filtered in (0, 1):
                output = suite.command(['timeout', '30', *runner, *(['--diagnostics'] if args.diagnostics else []), '--statistics', '--store', suite.base + '/store',
                                        '--user', '0:0', '--', '/bin/probe', '--socket-discovery', str(filtered)])
                assert 'PASS resident socket IO:' in output
                if args.machine_entry is not False:
                    stops = sum(int(trace) + int(notifications) for trace, notifications in re.findall(
                        r'MD_SYSCALL nr=(?:211|212) trace=(\d+) notification=(\d+)', output))
                    assert stops <= 12, ('socket IO still requires stopped registers', filtered, stops)
                    assert 'MD_EXECUTION_STOP reason=wait ' not in output, 'socket readiness still stops the task'
            if args.case == 'socket-discovery':
                suite.report['passed'] = True
                suite.command(['rm', '-rf', suite.base])
                return
        if args.case in ('all', 'proc-discovery'):
            for filtered in (0, 1):
                output = suite.command(['timeout', '30', *runner, '--statistics', '--store', suite.base + '/store',
                                        '--user', '0:0', '--', '/bin/probe', '--proc-discovery', str(filtered)])
                assert 'PASS native proc discovery:' in output
                assert 'PASS resident provider wait:' in output
                if args.machine_entry is not False:
                    stops = sum(int(trace) + int(notifications) for trace, notifications in re.findall(
                        r'MD_SYSCALL nr=61 trace=(\d+) notification=(\d+)', output))
                    assert stops <= 40, ('typed directory lookup still requires stopped registers', filtered, stops)
                    stops = sum(int(trace) + int(notifications) for trace, notifications in re.findall(
                        r'MD_SYSCALL nr=67 trace=(\d+) notification=(\d+)', output))
                    assert stops <= 4, ('typed proc reads still require stopped registers', filtered, stops)
                    stops = sum(int(trace) + int(notifications) for trace, notifications in re.findall(
                        r'MD_SYSCALL nr=62 trace=(\d+) notification=(\d+)', output))
                    assert stops <= 8, ('typed cursor delegation still falls back to Base', filtered, stops)
                    for number in (63, 64):
                        stops = sum(int(trace) + int(notifications) for trace, notifications in re.findall(
                            rf'MD_SYSCALL nr={number} trace=(\d+) notification=(\d+)', output))
                        assert stops <= 16, ('typed stream IO still falls back to Base', filtered, number, stops)
                    entries = re.search(r'MD_EXECUTION taskEntries=(\d+)', output)
                    assert entries and int(entries[1]) >= 1024, 'typed provider did not exercise shared execution'
                    assert 'MD_EXECUTION_STOP reason=wait ' not in output, 'provider readiness still stops the task'
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
        if args.case in ('jit-resume', 'jit-busy'):
            jit_case(suite, runner, args.case)
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
                    else 'PASS native xattr pins:' if args.case == 'xattr-pins'
                    else 'PASS cooperative entry: signal longjmp' if args.case == 'signal-abandon'
                    else 'PASS execution bursts:') in output
            if args.case == 'cursor-lease' and args.machine_entry is not False:
                stops = sum(int(trace) + int(notifications) for trace, notifications in re.findall(
                    r'MD_SYSCALL nr=29 trace=(\d+) notification=(\d+)', output))
                assert stops < 32, ('admitted file queries still stop in the raw gate', stops)
            if args.case == 'cursor-lease':
                output = suite.command(['timeout', '90', *runner, '--store', suite.base + '/store',
                                        '--user', '0:0', '--', '/bin/probe', '--cursor-signals'])
                assert 'PASS native cursor lease:' in output
            suite.report['passed'] = True
            suite.command(['rm', '-rf', suite.base])
            return
        for case in ('--cursor-lease', '--cursor-signals'):
            output = suite.command(['timeout', '90', *runner, '--store', suite.base + '/store',
                                    '--user', '0:0', '--', '/bin/probe', case])
            assert 'PASS native cursor lease:' in output
        output = suite.command(['timeout', '15', *runner, '--store', suite.base + '/store',
                                '--user', '0:0', '--', '/bin/probe', '--initial-stack', '-silent'], quiet=True)
        assert output.count('PASS initial stack:') == 2
        jit_case(suite, runner, 'jit-resume')
        jit_case(suite, runner, 'jit-busy')
        output = suite.command(['timeout', '90', *runner, *(['--diagnostics'] if args.diagnostics else []), '--statistics', '--store', suite.base + '/store',
                               '--user', '0:0', '--', '/bin/probe'])
        assert 'PASS cooperative entry: metadata' in output
        assert 'PASS cooperative ABI: runtime-owned offers' in output
        assert 'PASS cooperative protected copy:' in output
        assert 'PASS native FD plans: 192 live opens' in output
        assert 'PASS native FD watches: exactly one open and one close event' in output
        assert 'PASS native signal boundaries:' in output
        assert 'PASS signal frame failure:' in output
        assert 'PASS cooperative JIT resume:' in output
        assert 'PASS native writable plans:' in output
        assert 'PASS native create admission:' in output
        assert 'PASS filtered close: actual syscall FD' in output
        assert 'PASS native open exhaustion:' in output
        assert 'PASS execution bursts:' in output
        assert 'PASS filter exec: leader and nonleader retain policy and internal registration' in output
        for key, minimum in (('nativeOpen', 1000), ('nativeClose', 990), ('nativeFlags', 576),
                             ('nativeDuplicate', 576), ('nativeSeek', 384), ('nativeDeferred', 1)):
            count = re.search(rf'{key}=(\d+)', output)
            assert count and int(count[1]) >= minimum, f'{key} did not exercise native FD plans'
        # The host may deliver a carrier through SIGSYS instead of user_notify.
        # Both routes must reach the same owner; the guest checks each actual FD.
        descriptors = re.search(r'MD_FS operation=\d+ calls=(\d+) ns=\d+ name=FSOPEN', output)
        assert descriptors and int(descriptors[1]) >= 128, 'FD checks did not reach the shared filesystem owner'
        suite.command(['mkdir', '-p', root + '/tmp/entry-emfile-dir', root + '/tmp/entry-birth-watch-dir'])
        suite.command([suite.base + '/service', '--import', root, suite.base + '/host-store'])
        output = suite.command(['timeout', '90', *runner, '--store', suite.base + '/host-store',
                               '--user', '0:0', '--', '/bin/probe', '--host-births'])
        assert 'PASS catalogue births:' in output
        suite.report['passed'] = True
        suite.command(['rm', '-rf', suite.base])
    finally:
        suite.finish()


def jit_case(suite, runner, case):
    resume = case == 'jit-resume'
    output = suite.command(['timeout', '15' if resume else '30', *runner,
                            *(['--diagnostics'] if resume else []),
                            '--store', suite.base + '/store', '--user', '0:0',
                            '--', '/bin/probe', '--' + case], quiet=True)
    if resume:
        assert 'PASS cooperative JIT resume:' in output
        assert 'PASS JIT internal wait:' in output
        assert 'PASS JIT application signal:' in output
        assert len(re.findall(r'PROBE memory-repair .* result=0 scope=address-space', output)) == 8
    else:
        assert 'PASS cooperative JIT busy:' in output


if __name__ == '__main__':
    main()
