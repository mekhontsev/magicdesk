#!/usr/bin/env python3
"""Verify production lazy-SVC entry, mapping protection and sandbox transitions."""
import argparse
import hashlib
import json
import re
from pathlib import Path
from test_oci_services import Suite
from fixture_executor import FixtureExecutor
from audit_svc_payload import audit


def records(output, prefix):
    return [json.loads(line[len(prefix):]) for line in output.splitlines() if line.startswith(prefix)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('go', type=Path)
    parser.add_argument('--rounds', type=int, default=5)
    parser.add_argument('--condition', default='unspecified')
    parser.add_argument('--diagnostics', action='store_true',
                        help='Retain supervisor traces for the integrated guest runs')
    parser.add_argument('--section', choices=('all', 'entry', 'channels', 'filters', 'protected', 'images', 'mappings', 'programs'), default='all')
    FixtureExecutor.add_argument(parser)
    args = parser.parse_args()
    assert 1 <= args.rounds <= 20
    suite = Suite(Path('build/svc-patch-results'), args.build, executor=args.executor)
    suite.report['condition'] = args.condition
    suite.report['section'] = args.section
    suite.report['fixtureHashes'] = {}
    suite.report['payloadAudit'] = audit(args.build)
    try:
        suite.report['kernel'] = suite.command(['uname', '-a'], quiet=True).strip()
        root = suite.base + '/root'
        native = suite.base + '/native'
        suite.command(['mkdir', '-p', root + '/bin', root + '/tmp', native])
        # Protected-proc checks drop guest credentials; host umask must not
        # turn their filesystem root into an unrelated DAC rejection.
        suite.chmod('755', root, root + '/bin', root + '/tmp')
        for name, target in [('test_svc_patch', suite.base + '/planner'),
                             ('test_svc_raw', root + '/bin/raw'),
                             ('test_svc_fault', root + '/bin/fault'),
                             ('test_svc_mapping', root + '/bin/mapping'),
                             ('test_process_image', root + '/bin/process-image'),
                             ('service', suite.base + '/service')]:
            binary = args.build / ('libmagicdesk_guest_' + name + '.so')
            suite.report['fixtureHashes'][name] = hashlib.sha256(binary.read_bytes()).hexdigest()
            suite.upload(binary, target)
            assert suite.command(['sha256sum', target], quiet=True).split()[0] == suite.report['fixtureHashes'][name]
            suite.chmod('755' if name == 'test_process_image' else '700', target)
        suite.upload(args.go, root + '/bin/go-stress')
        suite.report['fixtureHashes']['go-stress'] = hashlib.sha256(args.go.read_bytes()).hexdigest()
        assert suite.command(['sha256sum', root + '/bin/go-stress'], quiet=True).split()[0] == suite.report['fixtureHashes']['go-stress']
        suite.chmod('700', root + '/bin/go-stress')
        suite.command([suite.base + '/service', '--import', root, suite.base + '/store', '--preserve-ownership'])
        guest = [suite.runner, '--store', suite.base + '/store', '--user', '0:0']
        if args.diagnostics:
            guest.append('--diagnostics')
        if args.section in ('all', 'entry'):
            suite.command([suite.base + '/planner'])
            for mode in ('0', '1'):
                for policy in ([], ['filtered']):
                    output = suite.command([*guest, '--env', 'SHROOT_MACHINE_ENTRY='+mode,
                        '--statistics', '--', '/bin/mapping', 'debugger-rearm',
                        'patched' if mode == '1' else 'original', *policy], quiet=True, timeout=60)
                    assert 'PASS SVC debugger rearm:' in output, output
            for mode in ('0', '1'):
                for policy in ([], ['filtered']):
                    output = suite.command([*guest, '--env', 'SHROOT_MACHINE_ENTRY='+mode,
                        '--statistics', '--', '/bin/mapping', 'distant-sites',
                        'patched' if mode == '1' else 'original', *policy], quiet=True, timeout=90)
                    assert 'PASS SVC distant sites:' in output, output
            for command in ([root + '/bin/mapping'],
                            [*guest, '--env', 'SHROOT_MACHINE_ENTRY=0', '--', '/bin/mapping'],
                            [*guest, '--statistics', '--diagnostics', '--', '/bin/mapping']):
                patched = 'patched' if '--statistics' in command else 'original'
                for policy in ([], ['filtered']):
                    output = suite.command([*command, 'first-encounter', patched, *policy], quiet=True)
                    assert 'PASS SVC first encounter:' in output
                    if patched == 'patched':
                        counts = re.search(r'MD_EXECUTION taskEntries=(\d+) completed=(\d+)', output)
                        contexts = sum(map(int, re.findall(r'MD_EXECUTION_CONTEXT reason=\w+ calls=(\d+)', output)))
                        native = re.search(r'MD_EXECUTION_NATIVE calls=(\d+)', output)
                        assert counts and native and int(counts[1]) == int(counts[2]) + contexts, output
                        assert int(native[1]) <= int(counts[1]), output
                        # prctl still needs its owner; uname may read its published
                        # UTS view. Both must complete through the resident entry.
                        assert int(counts[2]) >= 32, output
                        completed = sum(item['completed'] for item in records(output, 'SVC_RUNTIME_STATS '))
                        assert completed >= 160 + (64 if policy else 0), output
                        # Ordinary mapping effects retain their memory owner,
                        # not an original-register replay. The fixture replaces
                        # a tracked file view and then protects/remaps/unmaps it.
                        assert not re.search(r'MD_EXECUTION_CONTEXT reason=memory calls=[1-9]', output), output
                        assert not re.search(r'MD_EXECUTION_CONTEXT reason=descriptor calls=[1-9]', output), output
                        assert not re.search(r'MD_EXECUTION_CONTEXT reason=wait calls=[1-9]', output), output
                        assert re.search(r'nativeEffects=[1-9]', output), output
                        local_native = re.search(r'MD_EXECUTION_LOCAL_NATIVE calls=(\d+)', output)
                        assert local_native and (policy or int(local_native[1]) >= 64), output
            rejected = suite.command([*guest, '--env', 'SHROOT_MACHINE_ENTRY=invalid',
                                      '--', '/bin/raw'], quiet=True, expected=126)
            assert 'SHROOT_MACHINE_ENTRY must be 0 or 1' in rejected
            base_output = suite.command([*guest, '--env', 'SHROOT_MACHINE_ENTRY=0',
                                         '--statistics', '--', '/bin/raw', '/tmp'], quiet=True)
            assert 'PASS raw assembly:' in base_output
            assert not records(base_output, 'SVC_RUNTIME_STATS ')
            assert re.search(r'cooperativeCompleted=0\b', base_output), base_output
            for mode in ('0', '1'):
                for case in ('task-signals', 'task-signals-exec'):
                    output = suite.command([*guest, '--env', 'SHROOT_MACHINE_ENTRY='+mode,
                                            '--statistics', '--', '/bin/mapping', case], quiet=True, timeout=120)
                    assert 'PASS SVC task signals:' in output
            for command in ([root + '/bin/mapping'],
                            [*guest, '--env', 'SHROOT_MACHINE_ENTRY=0', '--', '/bin/mapping'],
                            [*guest, '--statistics', '--', '/bin/mapping']):
                patched = '--statistics' in command
                output = suite.command([*command, 'native-waits', 'patched' if patched else 'original'],
                                       quiet=True, timeout=60)
                assert 'PASS SVC native waits:' in output
                if patched:
                    assert max(row['nativeCompleted'] for row in records(output, 'SVC_RUNTIME_STATS ')) >= 1024, output
            for command in ([root + '/bin/fault'], [*guest, '--env', 'SHROOT_MACHINE_ENTRY=0', '--', '/bin/fault'],
                            [*guest, '--', '/bin/fault']):
                output = suite.command(command, quiet=True)
                assert 'PASS SVC fault: original instruction PC' in output
        if args.section in ('all', 'channels'):
            for command in ([root + '/bin/mapping'],
                            [*guest, '--env', 'SHROOT_MACHINE_ENTRY=0', '--statistics', '--', '/bin/mapping'],
                            [*guest, '--statistics', '--', '/bin/mapping']):
                for case in ('channels', 'channels-filter', 'channels-many', 'channels-many-filter', 'channels-full-filter',
                             'channels-signals', 'channels-signals-filter'):
                    output = suite.command([*command, case], quiet=True, timeout=120)
                    assert 'PASS SVC channels:' in output
                    if '--statistics' in command and '--env' not in command:
                        counter = 'filteredCompleted' if case.endswith('-filter') else 'completed'
                        minimum = 350000 if 'many' in case else 80000
                        # Fork inherits the cumulative counter; summing child
                        # reports would count the parent's work more than once.
                        assert max(row[counter] for row in records(output, 'SVC_RUNTIME_STATS ')) >= minimum, output
            print('Integrated channel expansion PASS', flush=True)
        if args.section in ('all', 'filters'):
            for command in ([root + '/bin/mapping'],
                            [*guest, '--env', 'SHROOT_MACHINE_ENTRY=0', '--', '/bin/mapping'],
                            [*guest, '--statistics', '--', '/bin/mapping']):
                patched = '--statistics' in command
                output = suite.command([*command, 'filter-exec-waits', 'patched' if patched else 'original'],
                                       quiet=True, timeout=60)
                assert 'PASS SVC native waits:' in output
                if patched:
                    assert not re.search(r'MD_EXECUTION_CONTEXT reason=wait calls=[1-9]', output), output
                    # Two actual filter installations need kernel validation;
                    # none of the repeated IO may add a policy fallback.
                    policy = re.search(r'MD_EXECUTION_CONTEXT reason=policy calls=(\d+)', output)
                    assert not policy or int(policy[1]) <= 2, output
                    assert max(row['nativeCompleted'] for row in records(output, 'SVC_RUNTIME_STATS ')) >= 1024, output
            print('Integrated inherited native waits PASS', flush=True)
            for command in ([root + '/bin/mapping'],
                            [*guest, '--env', 'SHROOT_MACHINE_ENTRY=0', '--', '/bin/mapping'],
                            [*guest, '--statistics', '--', '/bin/mapping']):
                patched = '--statistics' in command
                output = suite.command([*command, 'native-wait-fence', 'patched' if patched else 'original'],
                                       quiet=True, timeout=60)
                assert 'PASS SVC native wait fence:' in output
                if patched:
                    assert max(row['nativeCompleted'] for row in records(output, 'SVC_RUNTIME_STATS ')) >= 2, output
            for command in ([root + '/bin/mapping'],
                            [*guest, '--env', 'SHROOT_MACHINE_ENTRY=0', '--statistics', '--', '/bin/mapping'],
                            [*guest, '--statistics', '--', '/bin/mapping']):
                for case in ('filter-probe', 'filter-exec', 'filter-probe-exec', 'filter-probe-exec-thread'):
                    output = suite.command([*command, case], quiet=True, timeout=90)
                    assert ('PASS SVC filter probe:' if case == 'filter-probe'
                            else 'PASS SVC filter exec:') in output
                    if '--statistics' in command and '--env' not in command:
                        rows = records(output, 'SVC_RUNTIME_STATS ')
                        counter = 'completed' if case == 'filter-probe' else 'filteredCompleted'
                        assert any(row[counter] >= 1000 for row in rows), output
                        if case == 'filter-probe':
                            assert not any(row['filteredCompleted'] for row in rows), output
            print('Integrated exec filter PASS', flush=True)
            for command in ([root + '/bin/mapping'],
                            [*guest, '--env', 'SHROOT_MACHINE_ENTRY=0', '--statistics', '--', '/bin/mapping'],
                            [*guest, '--statistics', '--', '/bin/mapping']):
                for case in ('filter-readiness', 'filter-readiness-trace', 'filter-readiness-notify'):
                    output = suite.command([*command, case], quiet=True, timeout=60)
                    assert 'PASS SVC filter readiness:' in output
                    if '--statistics' in command and '--env' not in command:
                        assert any(row['completed'] >= 1000 for row in records(output, 'SVC_RUNTIME_STATS ')), output
            print('Integrated original-timeout filter PASS', flush=True)
            for command in ([root + '/bin/mapping'],
                            [*guest, '--env', 'SHROOT_MACHINE_ENTRY=0', '--statistics', '--', '/bin/mapping'],
                            [*guest, '--statistics', '--', '/bin/mapping']):
                output = suite.command([*command, 'filter-actions'], quiet=True, timeout=90)
                assert 'PASS SVC filter actions:' in output
            print('Integrated filter actions PASS', flush=True)
            for command in ([root + '/bin/mapping'],
                            [*guest, '--env', 'SHROOT_MACHINE_ENTRY=0', '--statistics', '--', '/bin/mapping'],
                            [*guest, '--statistics', '--', '/bin/mapping']):
                output = suite.command([*command, 'filter-origin'], quiet=True, timeout=60)
                assert 'PASS SVC filter origin:' in output
                if '--statistics' in command and '--env' not in command:
                    assert any(row['completed'] >= 256 for row in records(output, 'SVC_RUNTIME_STATS ')), output
            print('Integrated original-PC filter PASS', flush=True)
            native_filter = suite.command([root + '/bin/mapping', 'filter-race'], quiet=True, timeout=180)
            assert 'PASS SVC filter race:' in native_filter
            for command in ([root + '/bin/mapping'], [*guest, '--env', 'SHROOT_MACHINE_ENTRY=0', '--', '/bin/mapping']):
                output = suite.command(command, quiet=True, timeout=60)
                assert 'PASS SVC mapping: ELF protection' in output
            filter_output = suite.command([*guest, '--statistics',
                                           '--deadline-seconds', '150', '--', '/bin/mapping', 'filter-race'],
                                          quiet=True, timeout=180)
            assert 'PASS SVC filter race:' in filter_output
            fences, drains = map(int, re.search(r'MD_ENTRY_FENCE transitions=(\d+) drains=(\d+)', filter_output).groups())
            assert fences == 24 and drains > 0, filter_output
            suite.report['filterFence'] = dict(installs=fences, drains=drains)
            suite.report['filterRace'] = records(filter_output, 'SVC_RUNTIME_STATS ')
            assert any(item['completed'] > 128 for item in suite.report['filterRace'])
            assert all(not item['revoked'] for item in suite.report['filterRace'])
            print('Integrated filter race PASS', flush=True)
            for command in ([root + '/bin/mapping', 'filter-wait'],
                            [*guest, '--env', 'SHROOT_MACHINE_ENTRY=0', '--statistics', '--', '/bin/mapping', 'filter-wait-provider'],
                            [*guest, '--statistics', '--', '/bin/mapping', 'filter-wait-provider']):
                output = suite.command(command, quiet=True, timeout=180)
                assert 'PASS SVC filter race:' in output
                if '--statistics' in command:
                    assert re.search(r'MD_ENTRY_FENCE transitions=24 drains=\d+ quiescences=0\b', output), output
            print('Integrated filter waiting peers PASS', flush=True)
            for command in ([root + '/bin/mapping', 'filter-fork'],
                            [*guest, '--env', 'SHROOT_MACHINE_ENTRY=0', '--statistics', '--', '/bin/mapping', 'filter-fork'],
                            [*guest, '--statistics', '--', '/bin/mapping', 'filter-fork']):
                output = suite.command(command, quiet=True, timeout=180)
                assert 'PASS SVC fork filter:' in output
                if '--statistics' in command:
                    assert re.search(r'MD_ENTRY_FENCE transitions=24 drains=\d+ quiescences=0\b', output), output
            print('Integrated fork filter PASS', flush=True)
            for command in ([root + '/bin/mapping', 'filter-exit'],
                            [*guest, '--env', 'SHROOT_MACHINE_ENTRY=0', '--statistics', '--', '/bin/mapping', 'filter-exit'],
                            [*guest, '--statistics', '--', '/bin/mapping', 'filter-exit']):
                output = suite.command(command, quiet=True, timeout=180)
                assert 'PASS SVC exit filter:' in output
                if '--statistics' in command:
                    assert re.search(r'MD_ENTRY_FENCE transitions=24 drains=\d+ quiescences=0\b', output), output
            print('Integrated exit filter PASS', flush=True)
        if args.section in ('all', 'protected'):
            for command in ([root + '/bin/mapping'], [*guest, '--env', 'SHROOT_MACHINE_ENTRY=0', '--'],
                            [*guest, '--statistics', '--']):
                if command[-1] == '--':
                    command += ['/bin/mapping']
                output = suite.command([*command, 'protected-race'], quiet=True, timeout=180)
                assert 'PASS SVC protected race:' in output
                if '--statistics' in command:
                    fences, drains = map(int, re.search(
                        r'MD_ENTRY_FENCE transitions=(\d+) drains=(\d+)', output).groups())
                    # Dumpability is guest metadata: peers retain direct entry,
                    # while the fixture checks the protected value across fork.
                    assert fences == 0 and drains == 0, output
                    suite.report['protectedFence'] = dict(transitions=fences, drains=drains)
                    evidence = records(output, 'SVC_RUNTIME_STATS ')
                    assert any(item['completed'] > 128 for item in evidence), output
                    assert not any(item['revoked'] for item in evidence), output
            print('Integrated protected race PASS', flush=True)
        if args.section in ('all', 'images'):
            native_openat2 = suite.native_capability(root + '/bin/process-image', 'openat2')
            native_protected = suite.native_capability(root + '/bin/process-image', 'protected-proc')
            for command in ([root + '/bin/process-image'], [*guest, '--env', 'SHROOT_MACHINE_ENTRY=0', '--', '/bin/process-image'],
                            [*guest, '--statistics', '--', '/bin/process-image']):
                native = len(command) == 1
                if not native or (native_openat2 and native_protected):
                    output = suite.command([*command, 'zombie'], quiet=True, timeout=60)
                    assert 'PASS zombie process image:' in output
                if native and not native_protected:
                    continue
                for mode in (['protected'] if native else ['protected', 'protected-other-user', 'protected-root']):
                    selected = list(command)
                    if not native and mode == 'protected':
                        selected[selected.index('--user') + 1] = '1000:1000'
                    output = suite.command([*selected, mode], quiet=True, timeout=60)
                    assert 'PASS protected process image:' in output
            padding = 'MD_PROCESS_TITLE_PADDING=' + 'x' * 32768
            for command in (['env', padding, suite.executor.native_path(root + '/bin/process-image'), 'title'],
                            [*guest, '--env', padding, '--env', 'SHROOT_MACHINE_ENTRY=0', '--', '/bin/process-image', 'title'],
                            [*guest, '--env', padding, '--', '/bin/process-image', 'title']):
                if command[0] == 'env' and not native_protected:
                    continue
                output = suite.command(command, quiet=True, timeout=60)
                assert 'PASS process title:' in output
            print('Protected cmdline/auxv Base and lazy PASS; native=' + str(native_protected), flush=True)
        if args.section in ('all', 'mappings'):
            for operation in ('unmap', 'protect', 'map', 'remap'):
                for command in ([root + '/bin/mapping'],
                                [*guest, '--env', 'SHROOT_MACHINE_ENTRY=0', '--', '/bin/mapping'],
                                [*guest, '--statistics', '--', '/bin/mapping']):
                    output = suite.command([*command, 'native-' + operation], quiet=True, timeout=60)
                    assert 'PASS SVC native ' + operation + ':' in output
                    if '--statistics' in command:
                        evidence = records(output, 'SVC_RUNTIME_STATS ')
                        assert any(item['completed'] >= 8000 for item in evidence), output
                        suite.report['native' + operation.title()] = evidence
                print('Native ' + operation + ', signal reentry and filter revocation PASS', flush=True)
        if args.section in ('all', 'programs'):
            suite.report['runtimeRuns'] = []
            runs = [('raw', False), ('mapping', False)]
            runs += [('go-stress', sandbox) for _ in range(args.rounds) for sandbox in (False, True)]
            for program, sandbox in runs:
                output = suite.command([*guest, '--statistics',
                                        '--deadline-seconds', '150', '--', '/bin/' + program, '/tmp',
                                        *(['sandbox'] if sandbox else [])], quiet=True, timeout=180)
                marker = {'raw': 'PASS raw assembly:', 'mapping': 'PASS SVC mapping:',
                          'go-stress': 'PASS Go stress:'}[program]
                assert marker in output
                if sandbox: assert 'PASS Go sandbox:' in output
                evidence = records(output, 'SVC_RUNTIME_STATS ')
                assert evidence and not any(item['unknown'] for item in evidence), output
                if program == 'go-stress':
                    assert any(item['completed'] > 1000 for item in evidence), output
                if program == 'mapping':
                    assert any(item['completed'] >= 64 for item in evidence), output
                    repairs = re.search(r'memoryRepairs=(\d+)', output)
                    assert repairs, output
                    suite.report['elfProtectionRepairs'] = int(repairs[1])
                suite.report['runtimeRuns'].append(dict(program=program, sandbox=sandbox, evidence=evidence))
                print('Integrated', program, 'sandbox=' + str(sandbox), 'PASS', flush=True)
        suite.report['passed'] = True
    finally:
        try:
            suite.command(['rm', '-rf', '--', suite.base], quiet=True)
        finally:
            suite.finish()


if __name__ == '__main__':
    main()
