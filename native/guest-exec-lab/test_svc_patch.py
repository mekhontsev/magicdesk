#!/usr/bin/env python3
"""Verify production lazy-SVC entry, mapping protection and sandbox transitions."""
import argparse
import hashlib
import json
import re
from pathlib import Path
from test_oci_services import Suite
from audit_svc_payload import audit


def records(output, prefix):
    return [json.loads(line[len(prefix):]) for line in output.splitlines() if line.startswith(prefix)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('go', type=Path)
    parser.add_argument('--rounds', type=int, default=5)
    parser.add_argument('--condition', default='unspecified')
    args = parser.parse_args()
    assert 1 <= args.rounds <= 20
    suite = Suite(Path('build/svc-patch-results'), args.build)
    suite.report['condition'] = args.condition
    suite.report['fixtureHashes'] = {}
    suite.report['payloadAudit'] = audit(args.build)
    try:
        suite.report['kernel'] = suite.command(['uname', '-a'], quiet=True).strip()
        root = suite.base + '/root'
        native = suite.base + '/native'
        suite.command(['mkdir', '-p', root + '/bin', root + '/tmp', native])
        for name, target in [('test_svc_patch', suite.base + '/planner'),
                             ('test_svc_raw', root + '/bin/raw'),
                             ('test_svc_fault', root + '/bin/fault'),
                             ('test_svc_mapping', root + '/bin/mapping'),
                             ('test_process_image', root + '/bin/process-image'),
                             ('service', suite.base + '/service')]:
            binary = args.build / ('libmagicdesk_guest_' + name + '.so')
            suite.report['fixtureHashes'][name] = hashlib.sha256(binary.read_bytes()).hexdigest()
            suite.transport.upload(suite.client, binary, target)
            assert suite.command(['sha256sum', target], quiet=True).split()[0] == suite.report['fixtureHashes'][name]
            suite.command(['chmod', '700', target])
        suite.transport.upload(suite.client, args.go, root + '/bin/go-stress')
        suite.report['fixtureHashes']['go-stress'] = hashlib.sha256(args.go.read_bytes()).hexdigest()
        assert suite.command(['sha256sum', root + '/bin/go-stress'], quiet=True).split()[0] == suite.report['fixtureHashes']['go-stress']
        suite.command(['chmod', '700', root + '/bin/go-stress'])
        suite.command([suite.base + '/service', '--import', root, suite.base + '/store', '--preserve-ownership'])
        guest = [suite.runner, '--store', suite.base + '/store', '--user', '0:0']
        suite.command([suite.base + '/planner'])
        rejected = suite.command([*guest, '--env', 'SHROOT_MACHINE_ENTRY=invalid',
                                  '--', '/bin/raw'], quiet=True, expected=126)
        assert 'SHROOT_MACHINE_ENTRY must be 0 or 1' in rejected
        base_output = suite.command([*guest, '--env', 'SHROOT_MACHINE_ENTRY=0',
                                     '--statistics', '--', '/bin/raw', '/tmp'], quiet=True)
        assert 'PASS raw assembly:' in base_output
        assert not records(base_output, 'SVC_RUNTIME_STATS ')
        assert re.search(r'cooperativeCompleted=0\b', base_output), base_output
        suite.report['runtimeRuns'] = []
        for command in ([root + '/bin/fault'], [*guest, '--env', 'SHROOT_MACHINE_ENTRY=0', '--', '/bin/fault'],
                        [*guest, '--', '/bin/fault']):
            output = suite.command(command, quiet=True)
            assert 'PASS SVC fault: original instruction PC' in output
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
        assert any(item['completed'] > 128 and item['revoked'] for item in suite.report['filterRace'])
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
        for command in ([root + '/bin/mapping'], [*guest, '--env', 'SHROOT_MACHINE_ENTRY=0', '--'],
                        [*guest, '--statistics', '--']):
            if command[-1] == '--':
                command += ['/bin/mapping']
            output = suite.command([*command, 'protected-race'], quiet=True, timeout=180)
            assert 'PASS SVC protected race:' in output
            if '--statistics' in command:
                fences, drains = map(int, re.search(
                    r'MD_ENTRY_FENCE transitions=(\d+) drains=(\d+)', output).groups())
                assert fences == 24 and drains > 0, output
                suite.report['protectedFence'] = dict(transitions=fences, drains=drains)
                evidence = records(output, 'SVC_RUNTIME_STATS ')
                assert any(item['completed'] > 128 and item['revoked'] for item in evidence)
        print('Integrated protected race PASS', flush=True)
        for command in ([root + '/bin/process-image'], [*guest, '--env', 'SHROOT_MACHINE_ENTRY=0', '--', '/bin/process-image'],
                        [*guest, '--statistics', '--', '/bin/process-image']):
            output = suite.command([*command, 'zombie'], quiet=True, timeout=60)
            assert 'PASS zombie process image:' in output
            for mode in (['protected'] if len(command)==1 else ['protected', 'protected-other-user']):
                output = suite.command([*command, mode], quiet=True, timeout=60)
                assert 'PASS protected process image:' in output
        padding = 'MD_PROCESS_TITLE_PADDING=' + 'x' * 32768
        for command in (['env', padding, root + '/bin/process-image', 'title'],
                        [*guest, '--env', padding, '--env', 'SHROOT_MACHINE_ENTRY=0', '--', '/bin/process-image', 'title'],
                        [*guest, '--env', padding, '--', '/bin/process-image', 'title']):
            output = suite.command(command, quiet=True, timeout=60)
            assert 'PASS process title:' in output
        print('Protected cmdline/auxv native, Base and lazy PASS', flush=True)
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
        suite.command(['rm', '-rf', suite.base])
    finally:
        suite.finish()


if __name__ == '__main__':
    main()
