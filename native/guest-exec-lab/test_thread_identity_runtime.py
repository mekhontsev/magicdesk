#!/usr/bin/env python3
"""Check libc thread identity during PID-namespace birth publication."""
import argparse
from pathlib import Path
from fixture_executor import FixtureExecutor
from test_oci_services import Suite


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('store')
    parser.add_argument('fixture', type=Path, nargs='?',
                        help='Prebuilt fixture; otherwise compile with the guest compiler and libc')
    parser.add_argument('--debuggers', action='store_true', help='Also exercise installed strace and GDB')
    parser.add_argument('--case', choices=('all', 'identity', 'pi', 'robust'), default='all')
    parser.add_argument('--output', type=Path, default=Path('build/thread-identity'))
    FixtureExecutor.add_argument(parser)
    args = parser.parse_args()
    suite = Suite(args.output, args.build, executor=args.executor)
    target = '/tmp/md-thread-' + suite.report['id']
    base = [suite.runner, '--store', args.store, '--user', '0:0']
    try:
        suite.command(base + ['--', '/bin/mkdir', target])
        if args.fixture:
            suite.upload(args.fixture, suite.base + '/thread')
            suite.chmod('700', suite.base + '/thread')
        else:
            source = Path(__file__).with_name('test_thread_identity.c')
            suite.upload(source, suite.base + '/thread.c')
            suite.command(base + ['--bind', suite.base, target, '--deadline-seconds', '60',
                                 '--', '/usr/bin/cc', '-O2', '-pthread', target + '/thread.c',
                                 '-o', target + '/thread'], timeout=70)
        for wrapper in ([], ['/usr/bin/unshare', '--pid', '--fork', '--mount-proc']):
            if args.case in ('all', 'identity'):
                suite.command(base + ['--bind-ro', suite.base, target, '--deadline-seconds', '20', '--']
                              + wrapper + [target + '/thread'], timeout=30)
            if args.case in ('all', 'pi'):
                output = suite.command(base + ['--bind-ro', suite.base, target, '--deadline-seconds', '20', '--']
                                       + wrapper + [target + '/thread', '--pid-futex'], timeout=30)
                assert 'PASS PI futex admission' in output, output
            if args.case in ('all', 'robust'):
                for case in ('query', 'thread', 'exit', 'kill', 'exec'):
                    record = dict(name='robust-' + case, pidNamespace=bool(wrapper), passed=False)
                    suite.report['cases'].append(record)
                    try:
                        output = suite.command(base + ['--bind-ro', suite.base, target,
                            '--deadline-seconds', '20', '--'] + wrapper
                            + [target + '/thread', '--robust-' + case], timeout=30, quiet=True)
                        assert 'PASS robust' in output, output
                        record['passed'] = True
                    except Exception as error:
                        record['error'] = str(error)
                    print(('PASS' if record['passed'] else 'FAIL')
                          + f' robust-{case} pidNamespace={bool(wrapper)}', flush=True)
        if args.debuggers:
            namespace = ['/usr/bin/unshare', '--pid', '--fork', '--mount-proc']
            trace = ['/usr/bin/strace', '-f', '-qq', '-o', '/dev/null']
            # Trace from the parent namespace as well: the tracer and signal
            # recipient must not share the same siginfo PID translation.
            for wrapper in (trace, trace + namespace):
                output = suite.command(base + ['--bind-ro', suite.base, target, '--deadline-seconds', '60', '--']
                                       + wrapper + [target + '/thread'], timeout=70)
                assert 'PASS thread identity:' in output, output
            for debugger in (['/usr/bin/strace', '-f', '-qq', '-o', '/dev/null'],
                             ['/usr/bin/gdb', '-nx', '-batch',
                              '-ex', 'set debuginfod enabled off', '-ex', 'set disable-randomization off',
                              '-ex', 'run', '--args']):
                output = suite.command(base + ['--bind-ro', suite.base, target, '--deadline-seconds', '60', '--']
                                       + namespace + debugger + [target + '/thread'], timeout=70)
                assert 'PASS thread identity:' in output, output
        suite.report['passed'] = all(case['passed'] for case in suite.report['cases'])
    finally:
        try:
            suite.command(base + ['--', '/bin/rmdir', target])
            suite.command(['rm', '-rf', suite.base], quiet=True)
        finally:
            suite.finish()
    assert suite.report['passed'], 'Thread identity failures: ' + str(suite.output)


if __name__ == '__main__':
    main()
