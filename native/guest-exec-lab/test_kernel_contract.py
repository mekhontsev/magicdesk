#!/usr/bin/env python3
"""Compare one static fixture natively and through Shroot under one executor."""
import argparse
import hashlib
from pathlib import Path

from test_oci_services import Suite
from fixture_executor import FixtureExecutor

CASES = ('waitid-peek', 'wait-errors', 'traceme-late-wait4', 'traceme-late-waitid',
         'debug-wait4-signal', 'debug-waitid-signal', 'debug-wait4-restart', 'debug-waitid-restart',
         'traceme-thread-wait4', 'traceme-thread-waitid', 'ptrace-options', 'syscall-info',
         'signal-delivery', 'file-lifetime', 'resolution', 'resolution-race', 'proc-owners',
         'rights-peek', 'rights-truncate', 'rights-limit', 'rights-batch', 'rights-fault',
         'rights-control-fault', 'rights-header-outputs', 'rights-name', 'rights-timeout-fault',
         'rights-batch-fault', 'rights-socket-error', 'rights-publication-error',
         'rights-error-consumers', 'rights-publication-consumers', 'rights-watch-fault',
         'receive-entry', 'receive-entry-signals', 'receive-vectors', 'receive-full-table', 'receive-large-batch', 'receive-batch-snapshot')


def observations(name, output):
    lines = output.splitlines()
    assert 'PASS ' + name in lines, output
    records = [line for line in lines if line.startswith('OBS ')]
    assert records, output
    return records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--fixture', type=Path, required=True)
    parser.add_argument('--instance', required=True, help='Disposable prepared store')
    parser.add_argument('--build', type=Path)
    parser.add_argument('--case', action='append', choices=CASES)
    parser.add_argument('--filtered', action='store_true', help='Deny private guest threads and use protected callers')
    FixtureExecutor.add_argument(parser)
    args = parser.parse_args()
    suite = Suite(args.output, args.build, executor=args.executor)
    suite.report['filtered'] = args.filtered
    try:
        with args.fixture.open('rb') as source:
            digest = hashlib.file_digest(source, 'sha256').hexdigest()
        fixture = suite.base + '/kernel-contract'
        suite.upload(args.fixture, fixture)
        assert suite.command(['sha256sum', fixture], quiet=True).split()[0] == digest
        suite.report['fixtureSha256'] = digest
        suite.chmod('755', fixture)
        native_openat2 = suite.native_capability(fixture, 'openat2')
        guest_root = '/tmp/kernel-contract-' + suite.report['id']
        suite.command(suite.run(args.instance, '--user', '0:0', '--', 'mkdir', '-p', '/fixtures'))
        suite.command(suite.run(args.instance, '--user', '0:0', '--', 'mkdir', guest_root))
        suite.command(suite.run(args.instance, '--user', '0:0', '--', 'chown', '2000:2000', guest_root))
        for name in args.case or CASES:
            case = {'name': name, 'passed': False}
            suite.report['cases'].append(case)
            native_dir, guest_dir = suite.base + '/' + name, guest_root + '/' + name
            suite.command(['mkdir', native_dir])
            suite.command(suite.run(args.instance, '--user', '2000:2000', '--', 'mkdir', guest_dir))
            commands = {
                'native': [fixture, name, native_dir],
                'guest': suite.run(args.instance, '--user', '2000:2000', '--bind-ro',
                                   suite.base, '/fixtures', '--', '/bin/sh', '-c',
                                   'exec "$@"', 'fixture', '/fixtures/kernel-contract', name, guest_dir),
            }
            for backend, command in commands.items():
                if backend == 'native' and name in ('resolution', 'resolution-race') and not native_openat2:
                    case['nativeUnavailable'] = 'openat2 denied by host policy or unavailable'
                    continue
                if args.filtered:
                    command += ['--filtered']
                try:
                    output = suite.command(command, timeout=30)
                    unavailable = [line for line in output.splitlines() if line.startswith('UNAVAILABLE ')]
                    if backend == 'guest':
                        assert not unavailable, unavailable
                    elif unavailable:
                        case['nativeUnavailableSubchecks'] = unavailable
                    case[backend] = observations(name, output)
                except Exception as error:
                    case[backend + 'Error'] = str(error)
            case['compared'] = 'native' in case and 'guest' in case
            case['passed'] = ('guest' in case and (case.get('nativeUnavailable') is not None
                              or case.get('native') == case['guest']))
            print(name, case, flush=True)
        suite.command(suite.run(args.instance, '--user', '0:0', '--', 'rm', '-rf', guest_root))
        suite.report['passed'] = all(case['passed'] for case in suite.report['cases'])
    finally:
        try:
            suite.command(['rm', '-rf', '--', suite.base], quiet=True)
        finally:
            suite.finish()
    return 0 if suite.report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
