#!/usr/bin/env python3
"""Check libc thread identity during PID-namespace birth publication."""
import argparse
from pathlib import Path
from test_oci_services import Suite


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('store')
    parser.add_argument('fixture', type=Path, nargs='?',
                        help='Prebuilt fixture; otherwise compile with the guest compiler and libc')
    parser.add_argument('--debuggers', action='store_true', help='Also exercise installed strace and GDB')
    parser.add_argument('--case', choices=('all', 'identity', 'pi'), default='all')
    args = parser.parse_args()
    suite = Suite(args.build, args.build)
    target = '/tmp/md-thread-' + suite.report['id']
    base = [suite.runner, '--store', args.store, '--user', '0:0']
    try:
        suite.command(base + ['--', '/bin/mkdir', target])
        if args.fixture:
            suite.transport.upload(suite.client, args.fixture, suite.base + '/thread')
            suite.command(['chmod', '700', suite.base + '/thread'])
        else:
            source = Path(__file__).with_name('test_thread_identity.c')
            suite.transport.upload(suite.client, source, suite.base + '/thread.c')
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
        suite.report['passed'] = True
    finally:
        try:
            suite.command(base + ['--', '/bin/rmdir', target])
        finally:
            suite.finish()


if __name__ == '__main__':
    main()
