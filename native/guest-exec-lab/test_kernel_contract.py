#!/usr/bin/env python3
"""Compare one static fixture under native shell and Shroot on the same kernel."""
import argparse
import hashlib
from pathlib import Path

from test_oci_services import Suite

CASES = ('waitid-peek', 'wait-errors', 'ptrace-options', 'syscall-info',
         'signal-delivery', 'file-lifetime', 'resolution', 'resolution-race')


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
    args = parser.parse_args()
    suite = Suite(args.output, args.build)
    try:
        with args.fixture.open('rb') as source:
            digest = hashlib.file_digest(source, 'sha256').hexdigest()
        fixture = suite.base + '/kernel-contract'
        suite.transport.upload(suite.client, args.fixture, fixture)
        assert suite.command(['sha256sum', fixture], quiet=True).split()[0] == digest
        suite.report['fixtureSha256'] = digest
        suite.command(['chmod', '755', fixture])
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
            case['passed'] = ('native' in case and 'guest' in case and case['native'] == case['guest'])
            print(name, case, flush=True)
        suite.command(suite.run(args.instance, '--user', '0:0', '--', 'rm', '-rf', guest_root))
        suite.report['passed'] = all(case['passed'] for case in suite.report['cases'])
    finally:
        suite.finish()
    return 0 if suite.report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
