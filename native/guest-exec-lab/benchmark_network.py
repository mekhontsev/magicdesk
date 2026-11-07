#!/usr/bin/env python3
"""Same static Linux executable: native shell, Shroot host/routed/filter/NAT.

Transport timing excludes warmup. Application CPU includes both echo processes;
a native wait4 wrapper records the invocation, including Shroot owners and nft setup.
Do not treat lifecycle CPU as transport-only CPU or compare runs under load.
"""
import argparse
import json
from pathlib import Path
import shlex
import statistics
import subprocess

from docker_network_fixture import Observer
from test_oci_services import Suite
from benchmark_cpu import observe_suite_cpu


def main():
    profiles = ('host', 'routed', 'filtered', 'nat')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('store')
    parser.add_argument('--sysroot', type=Path, required=True)
    parser.add_argument('--rounds', type=int, default=5)
    parser.add_argument('--profile', action='append', choices=('native', *profiles),
                        help='Limit an interleaved comparison; defaults to all profiles')
    parser.add_argument('--reference', type=Path,
                        help='Interleave another runtime build against the same guest and fixture')
    parser.add_argument('--output', type=Path, default=Path('build/network-benchmark'))
    args = parser.parse_args()
    if not 1 <= args.rounds <= 100:
        parser.error('--rounds must be between 1 and 100')
    root = args.sysroot.resolve()
    lib = root / 'usr/lib/aarch64-linux-gnu'
    gcc = root / 'usr/lib/gcc/aarch64-linux-gnu/14'
    binary = args.build / 'md-network-benchmark'
    subprocess.run(['clang', '--target=aarch64-linux-gnu', '--sysroot=' + str(root),
        '-isystem', str(root / 'usr/include/aarch64-linux-gnu'), '-fuse-ld=lld',
        '-std=c17', '-O2', '-Wall', '-Wextra', '-Werror', '-nostdlib', '-static', '-fno-pie',
        str(lib / 'crt1.o'), str(lib / 'crti.o'), str(gcc / 'crtbeginT.o'),
        str(Path(__file__).with_suffix('.c')), '-L' + str(lib), '-L' + str(gcc),
        '-Wl,--start-group', '-lc', '-lgcc', '-lgcc_eh', '-Wl,--end-group',
        str(gcc / 'crtend.o'), str(lib / 'crtn.o'), '-o', str(binary)], check=True)
    suite = Suite(args.output, args.build)
    observer = Observer()
    guest = ['--store', args.store, '--user', '0:0']
    prefix = [suite.runner, *guest]
    target = '/tmp/md-network-benchmark-' + suite.report['id']
    modes = {'native': ('native', suite.runner)} if not args.profile or 'native' in args.profile else {}
    groups = [['native']] if modes else []
    created = False
    try:
        suite.upload(binary, suite.base + '/bench')
        suite.chmod('700', suite.base + '/bench')
        if args.reference:
            reference = suite.base + '/reference'
            suite.command(['mkdir', reference])
            suite.report['referenceHashes'] = suite.stage_runtime(args.reference, reference)
        for mode in profiles:
            if args.profile and mode not in args.profile:
                continue
            modes[mode] = (mode, suite.runner)
            group = [mode]
            if args.reference:
                modes['reference-' + mode] = (mode, reference + '/libmagicdesk_guest_run.so')
                group.append('reference-' + mode)
            groups.append(group)
        samples = {name: [] for name in modes}
        suite.command(prefix + ['--', '/bin/mkdir', target])
        created = True
        # Keep reference pairs adjacent; reverse and rotate their order so
        # neither build always follows the same warmup or idle interval.
        for iteration in range(args.rounds):
            offset = (iteration // 2) % len(groups)
            order = [name for group in groups[offset:] + groups[:offset] for name in group]
            sequence = order if iteration % 2 == 0 else list(reversed(order))
            for name in sequence:
                mode, runner = modes[name]
                port = observer.allocate_port()
                front = observer.allocate_port()
                for sock in observer.reservations:
                    sock.close()
                observer.reservations.clear()
                rules = ''
                if mode in ('filtered', 'nat'):
                    rules = 'add table ip bench\nadd chain ip bench output { type filter hook output priority 0; policy accept; }\n'
                    rules += 'add rule ip bench output ct state invalid drop\n'
                if mode == 'nat':
                    rules += 'add chain ip bench translate { type nat hook output priority -100; policy accept; }\n'
                    for protocol in ('tcp', 'udp'):
                        rules += f'add rule ip bench translate {protocol} dport {front} dnat to 127.0.0.1:{port}\n'
                command = [suite.base + '/bench', str(port), str(front if mode == 'nat' else port)]
                if mode != 'native':
                    command[0] = target + '/bench'
                    script = ('printf %s ' + shlex.quote(rules) + ' | nft -f -\n' if rules else '')
                    script += 'exec ' + shlex.join(command)
                    command = [runner, *guest, '--network', 'host' if mode == 'host' else 'routed',
                        '--bind-ro', suite.base, target, '--deadline-seconds', '90', '--', '/bin/sh', '-ec', script]
                with observe_suite_cpu(suite) as cpu_conditions:
                    output = suite.command([suite.base + '/bench', '--time', *command], timeout=110)
                result = json.loads(next(line.removeprefix('NETWORK_BENCH ') for line in output.splitlines()
                                         if line.startswith('NETWORK_BENCH ')))
                cpu = next(line.split()[1:] for line in output.splitlines() if line.startswith('NETWORK_CPU '))
                result['lifecycleCpuSeconds'] = sum(map(float, cpu))
                result['cpu'] = cpu_conditions
                result['round'] = iteration
                samples[name].append(result)
        suite.report['samples'] = samples
        suite.report['medians'] = {mode: {
            'tcpSeconds': statistics.median(row['tcp']['seconds'] for row in rows),
            'udpSeconds': statistics.median(row['udp']['seconds'] for row in rows),
            'lifecycleCpuSeconds': statistics.median(row['lifecycleCpuSeconds'] for row in rows),
            'applicationCpuSeconds': statistics.median(row['tcp']['applicationCpuSeconds'] +
                                                      row['udp']['applicationCpuSeconds'] for row in rows)
        } for mode, rows in samples.items()}
        print(json.dumps(suite.report['medians'], indent=2))
        suite.report['passed'] = True
    finally:
        observer.close()
        try:
            if created:
                suite.command(prefix + ['--', '/bin/rmdir', target])
        finally:
            suite.finish()


if __name__ == '__main__':
    main()
