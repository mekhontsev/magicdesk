#!/usr/bin/env python3
"""Measure a workload-scoped userspace policy prototype, not browser confinement.

Inputs are raw captured AArch64 sock_filter arrays. The fixture validates each
filter with the kernel and checks ERRNO/TRAP before timing. Only timed calls
and the ERRNO negative control use userspace policy in the prototype mode.
"""
import argparse
import hashlib
import json
import re
import statistics
from pathlib import Path
from benchmark_cpu import observe_suite_cpu
from fixture_executor import FixtureExecutor
from test_oci_services import Suite


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('file_filter', type=Path)
    parser.add_argument('fd_filter', type=Path)
    parser.add_argument('--rounds', type=int, default=5, help='Timing rounds; 0 runs routing controls only')
    parser.add_argument('--condition', default='unspecified')
    FixtureExecutor.add_argument(parser)
    args = parser.parse_args()
    assert 0 <= args.rounds <= 20
    suite = Suite(Path('build/seccomp-entry-results'), args.build, executor=args.executor)
    suite.report.update(condition=args.condition, scope=__doc__, samples=[], controls=[], hashes={})
    modes = ('native-kernel', 'base-kernel', 'lazy-kernel', 'lazy-userspace', 'lazy-none')
    cases = [('stat', 'files', 32768), ('opens', 'files', 4096), ('fstat', 'fds', 32768)]
    try:
        root, store = suite.base + '/root', suite.base + '/store'
        suite.command(['mkdir', '-p', root + '/bin', root + '/tmp', root + '/filters'])
        uploads = [(args.build / 'libmagicdesk_guest_test_seccomp_entry.so', root + '/bin/entry'),
                   (args.build / 'libmagicdesk_guest_service.so', suite.base + '/service'),
                   (args.file_filter, root + '/filters/files'), (args.fd_filter, root + '/filters/fds')]
        for source, destination in uploads:
            digest = hashlib.sha256(source.read_bytes()).hexdigest()
            suite.report['hashes'][str(source)] = digest
            suite.upload(source, destination)
            assert suite.command(['sha256sum', destination], quiet=True).split()[0] == digest
            suite.chmod('700', destination)
        suite.command([suite.base + '/service', '--import', root, store, '--preserve-ownership'])
        suite.report['kernel'] = suite.command(['uname', '-a'], quiet=True).strip()

        def run(mode, workload, policy, count, counters=False):
            kind = mode.split('-', 1)[1]
            command = ([root + '/bin/entry', root + '/filters/' + policy, root + '/tmp']
                       if mode.startswith('native-') else
                       [suite.runner, '--store', store, '--user', '0:0', '--deadline-seconds', '60',
                        '--env', 'SHROOT_MACHINE_ENTRY=' + ('0' if mode.startswith('base-') else '1'),
                        *(['--statistics'] if counters else []), '--',
                        '/bin/entry', '/filters/' + policy, '/tmp'])
            output = suite.command([*command, kind, workload, str(count)], quiet=True, timeout=90)
            rows = [json.loads(line) for line in output.splitlines() if line.startswith('{"workload":')]
            assert len(rows) == 1, output
            row = rows[0]
            assert row['checksum'] == count * (1 if workload == 'opens' else 4), row
            assert row['denied'] == (1 if kind == 'userspace' else 0), row
            assert row['evaluated'] == (count * (2 if workload == 'opens' else 1) + 1
                                        if kind == 'userspace' else 0), row
            if counters:
                completions = re.search(r'cooperativeCompleted=(\d+)\b', output)
                assert completions, output
                completed = int(completions[1])
                if mode.startswith('base-'):
                    assert completed == 0, output
                else:
                    assert completed >= count * (2 if workload == 'opens' else 1), output
                row['cooperativeCompleted'] = completed
            return dict(mode=mode, **row)

        # Instrumented routing checks are separate from timing samples.
        for workload, policy, _ in cases:
            for mode in modes[1:]:
                row = run(mode, workload, policy, 128, counters=True)
                suite.report['controls'].append(row)
                print('CONTROL', json.dumps(row), flush=True)
        for round_number in range(args.rounds):
            order = modes if round_number % 2 == 0 else modes[::-1]
            for workload, policy, count in cases:
                for mode in order:
                    with observe_suite_cpu(suite) as cpu:
                        row = run(mode, workload, policy, count)
                    row.update(round=round_number, cpu=cpu)
                    suite.report['samples'].append(row)
                    print('SAMPLE', mode, workload, round_number,
                          round(row['elapsedNs'] / 1e6, 3), 'ms', flush=True)
        summary = {}
        for workload, _, _ in cases if args.rounds else []:
            summary[workload] = {mode: statistics.median(
                row['elapsedNs'] / 1e6 for row in suite.report['samples']
                if row['mode'] == mode and row['workload'] == workload) for mode in modes}
        suite.report.update(summaryMs=summary, passed=True)
        print(json.dumps(summary, indent=2), flush=True)
    finally:
        try:
            suite.command(['rm', '-rf', '--', suite.base], quiet=True)
        finally:
            suite.finish()


if __name__ == '__main__':
    main()
