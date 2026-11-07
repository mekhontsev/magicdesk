#!/usr/bin/env python3
"""Verify stock libc contracts and compare native, Base and lazy entry."""
import argparse
import hashlib
import json
import statistics
from pathlib import Path
from benchmark_cpu import observe_suite_cpu
from test_oci_services import Suite
from test_svc_patch import records
from audit_svc_payload import audit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('stock', type=Path)
    parser.add_argument('--baseline-build', type=Path, help='Interleave the same workloads with a second runtime build')
    parser.add_argument('--rounds', type=int, default=8)
    parser.add_argument('--cpu-mask', default='3')
    parser.add_argument('--checks-only', action='store_true')
    parser.add_argument('--workload', default='metadata', choices=['metadata', 'opens'])
    parser.add_argument('--threaded', action='store_true', help='enter libc threaded mode before first open')
    parser.add_argument('--sandbox', action='store_true', help='time original-SVC fallback after an application filter')
    parser.add_argument('--dynamic-loader', type=Path)
    parser.add_argument('--dynamic-libc', type=Path)
    parser.add_argument('--dynamic-extra', type=Path, action='append', default=[])
    parser.add_argument('--interpreter', default='ld-linux-aarch64.so.1')
    parser.add_argument('--signal-storm', action='store_true', help='additional adversarial asynchronous signal acceptance')
    parser.add_argument('--signal-rounds', type=int, default=1, help='repeat each signal scenario independently')
    parser.add_argument('--condition', default='unspecified')
    args = parser.parse_args()
    if args.signal_rounds<1:
        parser.error('--signal-rounds must be positive')
    if args.rounds<1:
        parser.error('--rounds must be positive')
    suite = Suite(Path('build/svc-libc-results'), args.build)
    suite.report['hashes'] = {}
    suite.report['samples'] = []
    suite.report['entryEvidence'] = []
    suite.report['payloadAudit'] = audit(args.build)
    suite.report['condition'] = args.condition
    suite.report['sandbox'] = args.sandbox
    suite.report['cpuMask'] = args.cpu_mask
    try:
        suite.report['kernel'] = suite.command(['uname', '-a'], quiet=True).strip()
        root, native = suite.base + '/root', suite.base + '/native'
        suite.command(['mkdir', '-p', root + '/bin', root + '/tmp', root + '/lib', native])
        files = [(args.stock, root + '/bin/stock'),
                 (args.build / 'libmagicdesk_guest_service.so', suite.base + '/service'),
                 (args.build / 'libmagicdesk_guest_test_signal_delivery.so', suite.base + '/signals')]
        if args.dynamic_loader:
            assert args.dynamic_libc and '/' not in args.interpreter
            files += [(args.dynamic_loader, root + '/lib/' + args.interpreter),
                      (args.dynamic_libc, root + '/lib/' + args.dynamic_libc.name)]
            files += [(extra, root + '/lib/' + extra.name) for extra in args.dynamic_extra]
        for source, target in files:
            digest = hashlib.sha256(source.read_bytes()).hexdigest()
            suite.upload(source, target)
            assert suite.command(['sha256sum', target], quiet=True).split()[0] == digest
            suite.chmod('700', target)
            suite.report['hashes'][str(source)] = digest
        suite.command([suite.base + '/signals'])
        suite.command([suite.base + '/service', '--import', root, suite.base + '/store'])
        guest = [suite.runner, '--store', suite.base + '/store', '--user', '0:0',
                 '--env', 'MD_BENCH_WARMUP=1']
        if args.threaded: guest += ['--env', 'MD_LIBC_THREADED=1']
        guest += ['--']
        launches = {
            'native': ['env', 'MD_BENCH_WARMUP=1', *(['MD_LIBC_THREADED=1'] if args.threaded else []), root + '/bin/stock', native],
            'base': [*guest[:-1], '--env', 'SHROOT_MACHINE_ENTRY=0', '--', '/bin/stock', '/tmp'],
            'machine': [*guest[:-1], '--statistics', '--', '/bin/stock', '/tmp'],
        }
        if args.baseline_build:
            baseline = suite.base + '/baseline'
            suite.command(['mkdir', baseline])
            suite.report['baselineBuild'] = str(args.baseline_build)
            suite.report['baselineHashes'] = suite.stage_runtime(args.baseline_build, baseline)
            for mode in ('base', 'machine'):
                launches['baseline-' + mode] = [baseline + '/libmagicdesk_guest_run.so', *launches[mode][1:]]
        def machine_evidence(output):
            items = records(output, 'SVC_RUNTIME_STATS ')
            assert items and not any(item['unknown'] for item in items), output
            return max(items, key=lambda item: item['completed'])
        if args.dynamic_loader:
            at = launches['native'].index(root + '/bin/stock')
            launches['native'][at:at] = [root + '/lib/' + args.interpreter, '--library-path', root + '/lib']
        for name, command in launches.items():
            # Full guest contracts include privileged fsopen(tmpfs), which is
            # intentionally unavailable to the native shell UID control.
            output = suite.command([*command, 'abi' if name == 'native' else 'check'], quiet=True)
            assert ('ABI: absent provider' if name == 'native' else 'signal longjmp, fork/exec and seccomp') in output
            if name == 'machine':
                evidence = machine_evidence(output)
                assert evidence['completed'] > 0
                suite.report['entryEvidence'].append(evidence)
            print('libc contracts', name, 'PASS', flush=True)
        output = suite.command([*launches['machine'], 'thread-churn'], quiet=True)
        evidence = machine_evidence(output)
        assert '256 retired owners' in output and evidence['retiredClients'] >= 256
        assert evidence['completed'] >= 8190
        suite.report['churnEvidence'] = evidence
        print('libc thread churn PASS', evidence, flush=True)
        if args.signal_storm:
            for signal_round in range(args.signal_rounds):
                for scenario in ('signal-storm', 'signal-blocked'):
                    for name, command in launches.items():
                        output = suite.command([*command, scenario], quiet=True, timeout=120)
                        assert 'original stack and siginfo' in output
                        print('libc', scenario, signal_round, name, 'PASS', flush=True)
        modes = tuple(launches)
        count = 32768 if args.workload == 'metadata' else 4096
        workload = [args.workload, str(count), *(['filtered'] if args.sandbox else [])]
        timed_launches = dict(launches)
        # Counter collection includes clocks in the engine's hot path.
        # Verify real completions separately, then time every mode without it.
        for mode, command in launches.items():
            timed_launches[mode] = [arg for arg in command if arg != '--statistics']
        suite.report['timedRuntimeStatistics'] = False
        if not args.checks_only:
            output = suite.command(['taskset', args.cpu_mask, *launches['machine'], *workload], quiet=True)
            evidence = machine_evidence(output)
            if args.sandbox:
                assert evidence['revoked'] and 0 < evidence['completed'] < count
            else:
                assert evidence['completed'] >= count
            suite.report['workloadEntryEvidence'] = evidence
        for round_number in range(0 if args.checks_only else args.rounds):
            rotation = round_number // 2 % len(modes)
            order = modes[rotation:] + modes[:rotation]
            if round_number % 2: order = order[::-1]
            for mode in order:
                with observe_suite_cpu(suite) as cpu:
                    output = suite.command(['taskset', args.cpu_mask, *timed_launches[mode], *workload], quiet=True)
                sample, = [json.loads(line) for line in output.splitlines() if line.startswith('{"workload"')]
                sample.update(mode=mode, round=round_number, cpu=cpu)
                suite.report['samples'].append(sample)
                print('libc', args.workload, round_number, mode, sample['nanoseconds']/1e6, 'ms', flush=True)
        if not args.checks_only:
            series = {mode: [s['nanoseconds'] for s in suite.report['samples'] if s['mode'] == mode] for mode in modes}
            suite.report['mediansMs'] = {mode: statistics.median(values)/1e6 for mode, values in series.items()}
            suite.report['machineToBasePaired'] = statistics.median(a/b for a, b in zip(series['machine'], series['base']))
            if args.baseline_build:
                suite.report['baselinePaired'] = {
                    mode: statistics.median(a/b for a, b in zip(series[mode], series['baseline-' + mode]))
                    for mode in ('base', 'machine')
                }
            print(suite.report['mediansMs'], flush=True)
        suite.report['passed'] = True
        suite.command(['rm', '-rf', suite.base])
    finally:
        suite.finish()


if __name__ == '__main__':
    main()
