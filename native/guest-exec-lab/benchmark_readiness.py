#!/usr/bin/env python3
"""Interleave Base/lazy entry filesystem, readiness, idle and startup against a reference."""
import argparse
import json
from pathlib import Path
import re
import statistics

from test_oci_services import Suite
from benchmark_cpu import observe_suite_cpu


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build', required=True, type=Path)
    parser.add_argument('--reference', required=True, type=Path)
    parser.add_argument('--store', required=True)
    parser.add_argument('--rounds', type=int, default=5)
    parser.add_argument('--iterations', type=int, default=10000)
    parser.add_argument('--entry-mode', choices=('both', 'base', 'lazy'), default='both',
                        help='Compare both modes, or interleave only the selected mode')
    parser.add_argument('--file-blocks', type=int, default=1,
                        help='Consecutive 1024-operation file blocks per launch (1-64); retain first and tail timings')
    parser.add_argument('--cpu-mask', type=lambda value: int(value, 16),
                        help='Optional native taskset hexadecimal mask for controlled comparisons')
    parser.add_argument('--workload', action='append', choices=(
        'poll', 'epoll', 'epoll-ready', 'idle', 'python-start', 'ls-start', 'file-stat', 'file-open', 'file-cycle'),
        help='Limit a focused repeat; defaults to the complete matrix')
    parser.add_argument('--output', type=Path, default=Path('build/readiness-performance'))
    args = parser.parse_args()
    assert 1 <= args.rounds <= 100 and 1 <= args.iterations <= 1000000
    if not 1 <= args.file_blocks <= 64:
        parser.error('--file-blocks must be between 1 and 64')
    if args.cpu_mask is not None and args.cpu_mask <= 0:
        parser.error('--cpu-mask must be a positive hexadecimal mask')
    suite = Suite(args.output, args.build)
    target = '/opt/md-readiness-' + suite.report['id']
    base = ['--store', args.store, '--user', '0:0', '--bind', suite.base, target,
            '--deadline-seconds', '60']
    try:
        suite.command([suite.runner, '--store', args.store, '--user', '0:0', '--', '/bin/mkdir', target])
        suite.transport.upload(suite.client, Path(__file__).with_name('fixtures') / 'readiness-benchmark.c',
                               suite.base + '/benchmark.c')
        suite.command([suite.runner, *base, '--', '/usr/bin/cc', '-O2', '-Wall', '-Wextra',
                       target + '/benchmark.c', '-o', target + '/benchmark'])
        reference = suite.base + '/reference'
        suite.command(['mkdir', reference])
        suite.report['referenceHashes'] = suite.stage_runtime(args.reference, reference)
        suite.report['scope'] = dict(store=args.store,
            fixture='same guest binary and installed userspace; no timing diagnostics',
            cpuMask=None if args.cpu_mask is None else format(args.cpu_mask, 'x'),
            timing='loop wall/caller CPU; time -p covers full launch and reaped descendants',
            fileBlocks=args.file_blocks,
            blockScope='first block follows fixture setup, not a dropped filesystem cache; tail is the last half',
            cpuScope='block CPUs are caller endpoints, not worker placement; frequencies cover the command envelope')
        suite.report['fixtureHash'] = suite.command(['sha256sum', suite.base + '/benchmark'], quiet=True)
        placement = [] if args.cpu_mask is None else ['/system/bin/taskset', format(args.cpu_mask, 'x')]
        modes = {}
        for name, runner in [('current', suite.runner), ('reference', reference + '/libmagicdesk_guest_run.so')]:
            for mode, enabled in (('base', '0'), ('lazy', '1')):
                if args.entry_mode in ('both', mode):
                    modes[name + '-' + mode] = [runner, *base, '--env', 'SHROOT_MACHINE_ENTRY=' + enabled]
        for name, prefix in modes.items():
            output = suite.command([*prefix, '--statistics', '--', target + '/benchmark', 'file-stat', '1024'])
            counts = re.findall(r'cooperativeCompleted=(\d+)', output)
            assert counts and (sum(map(int, counts)) > 0 if name.endswith('-lazy')
                               else sum(map(int, counts)) == 0), (name, output)
        workloads = {'poll': [target + '/benchmark', 'poll', str(args.iterations)],
                     'epoll': [target + '/benchmark', 'epoll', str(args.iterations)],
                     'epoll-ready': [target + '/benchmark', 'epoll-ready', str(args.iterations)],
                     'idle': [target + '/benchmark', 'idle', '1'],
                     'python-start': ['/usr/bin/python3', '-c', 'import ssl,asyncio,sqlite3,multiprocessing'],
                     'ls-start': ['/usr/bin/ls', '--version']}
        file_workloads = {'file-stat', 'file-open', 'file-cycle'}
        for workload in ('file-stat', 'file-open', 'file-cycle'):
            workloads[workload] = [target + '/benchmark', workload, '1024', str(args.file_blocks)]
        if args.workload:
            workloads = {name: command for name, command in workloads.items() if name in args.workload}
        measured = {'poll', 'epoll', 'epoll-ready', 'idle', 'file-stat', 'file-open', 'file-cycle'}
        suite.report['samples'] = samples = []
        order = list(modes)
        for round_number in range(args.rounds):
            offset = round_number % len(order)
            for name in order[offset:] + order[:offset]:
                for workload, command in workloads.items():
                    with observe_suite_cpu(suite) as cpu:
                        output = suite.command(['/system/bin/time', '-p', *placement, *modes[name], '--', *command])
                    measurement = dict(re.findall(r'^(real|user|sys)\s+(\d+\.\d+)\s*$', output, re.M))
                    assert len(measurement) == 3, output
                    row = dict(mode=name, workload=workload, round=round_number, cpu=cpu,
                               launchSeconds=float(measurement['real']),
                               totalCpuSeconds=float(measurement['user']) + float(measurement['sys']))
                    records = [json.loads(line.removeprefix('READINESS_BENCH '))
                               for line in output.splitlines() if line.startswith('READINESS_BENCH ')]
                    if workload in measured:
                        expected = args.file_blocks if workload in file_workloads else 1
                        assert len(records) == expected, output
                        assert [record['block'] for record in records] == list(range(expected)), output
                        assert all(record['workload'] == workload for record in records), output
                        row['blocks'] = records
                        for field in ('iterations', 'seconds', 'callerCpuSeconds',
                                      'voluntarySwitches', 'involuntarySwitches'):
                            row[field] = sum(record[field] for record in records)
                        if workload in file_workloads:
                            row['firstBlockSeconds'] = records[0]['seconds']
                            row['tailBlockSeconds'] = statistics.median(
                                record['seconds'] for record in records[len(records) // 2:])
                    samples.append(row)
        suite.report['medians'] = {
            name: {workload: {field: statistics.median(row[field] for row in samples
                if row['mode'] == name and row['workload'] == workload)
                for field in ('launchSeconds', 'totalCpuSeconds') +
                    (('seconds', 'callerCpuSeconds', 'voluntarySwitches', 'involuntarySwitches')
                     if workload in measured else ()) +
                    (('firstBlockSeconds', 'tailBlockSeconds') if workload in file_workloads else ())}
                for workload in workloads} for name in modes}
        print(json.dumps(suite.report['medians'], indent=2), flush=True)
        suite.report['passed'] = True
    finally:
        try:
            suite.command([suite.runner, '--store', args.store, '--user', '0:0', '--', '/bin/rmdir', target])
        finally:
            suite.finish()


if __name__ == '__main__':
    main()
