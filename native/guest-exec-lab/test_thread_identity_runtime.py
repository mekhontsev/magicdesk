#!/usr/bin/env python3
"""Check libc thread identity during PID-namespace birth publication."""
import argparse
from pathlib import Path
from fixture_executor import FixtureExecutor
from test_oci_services import Suite, add_machine_entry_argument


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('store')
    parser.add_argument('fixture', type=Path, nargs='?',
                        help='Prebuilt fixture; otherwise compile with the guest compiler and libc')
    parser.add_argument('--debuggers', action='store_true', help='Also exercise installed strace and GDB')
    parser.add_argument('--case', choices=('all', 'identity', 'pi', 'robust'), default='all')
    parser.add_argument('--native-control', action='store_true',
                        help='Build a static fixture and compare the native Shell executor outside Shroot')
    parser.add_argument('--output', type=Path, default=Path('build/thread-identity'))
    FixtureExecutor.add_argument(parser)
    add_machine_entry_argument(parser)
    args = parser.parse_args()
    if args.native_control and args.executor != 'shell':
        parser.error('Native ELF control requires the Shell executor executable directory')
    suite = Suite(args.output, args.build, executor=args.executor, machine_entry=args.machine_entry)
    target = '/tmp/md-thread-' + suite.report['id']
    base = suite.guest(args.store, '--user', '0:0')
    try:
        suite.command(base + ['--', '/bin/mkdir', target])
        if args.fixture:
            suite.upload(args.fixture, suite.base + '/thread')
            suite.chmod('700', suite.base + '/thread')
        else:
            source = Path(__file__).with_name('test_thread_identity.c')
            suite.upload(source, suite.base + '/thread.c')
            suite.command(base + ['--bind', suite.base, target, '--deadline-seconds', '60',
                                 '--', '/usr/bin/cc', '-O2', '-pthread']
                          + (['-static'] if args.native_control else []) + [target + '/thread.c',
                                 '-o', target + '/thread'], timeout=70)
        variants = [([], False), (['/usr/bin/unshare', '--pid', '--fork', '--mount-proc'], False)]
        if args.native_control:
            variants.insert(0, ([], True))
        for wrapper, native in variants:
            command = ([suite.base + '/thread'] if native else base
                       + ['--bind-ro', suite.base, target, '--deadline-seconds', '20', '--']
                       + wrapper + [target + '/thread'])
            if args.case in ('all', 'identity'):
                suite.command(command, timeout=30)
            if args.case in ('all', 'pi'):
                output = suite.command(command + ['--pid-futex'], timeout=30)
                assert 'PASS PI futex admission' in output, output
            if args.case in ('all', 'robust'):
                for case in ('query', 'thread', 'exit', 'kill', 'exec', 'thread-exec',
                             'children', 'thread-children', 'notices', 'orphan'):
                    record = dict(name='robust-' + case, pidNamespace=bool(wrapper), native=native, passed=False)
                    suite.report['cases'].append(record)
                    try:
                        output = suite.command(command + ['--robust-' + case], timeout=30, quiet=True)
                        assert 'PASS robust' in output, output
                        record['passed'] = True
                    except Exception as error:
                        record['error'] = str(error)
                    print(('PASS' if record['passed'] else 'FAIL')
                          + f' robust-{case} pidNamespace={bool(wrapper)} native={native}', flush=True)
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
