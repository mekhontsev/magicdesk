#!/usr/bin/env python3
"""Stock namespace consumers in a prepared userspace, under either executor."""
import argparse
from pathlib import Path
import shlex
from fixture_executor import FixtureExecutor
from test_oci_services import Suite


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('store')
    parser.add_argument('--trace', action='store_true')
    parser.add_argument('--case', choices=('all', 'basic', 'split-root', 'readonly', 'identity', 'nested', 'pid', 'pid-debugger'), default='all')
    FixtureExecutor.add_argument(parser)
    args = parser.parse_args()
    suite = Suite(args.build, args.build, executor=args.executor)
    suite.report['store'] = args.store

    def command(argv, marker=None, logged=False):
        output = suite.command(argv, quiet=logged)
        assert marker is None or marker in output, output
        return {'output': output, 'log': str(suite.output)}

    try:
        command(['id'])
        run = [suite.runner, '--store', args.store,
               '--user', '2000:2000', '--deadline-seconds', '60', '--diagnostics', '--']
        command(run + ['/usr/bin/bwrap', '--version'])
        trace = ['/usr/bin/strace', '-f', '-s', '160'] if args.trace else []
        # EVENT_WAIT: the runtime observes owned children; its deadline cancels
        # a stalled process tree rather than treating elapsed time as readiness.
        cases = {
            'split-root': (['--ro-bind', '/etc', '/etc', '--ro-bind', '/usr', '/usr',
                            '--symlink', 'usr/bin', '/bin', '--symlink', 'usr/lib', '/lib',
                            '--symlink', 'usr/lib', '/lib64', '--symlink', 'usr/bin', '/sbin',
                            '--proc', '/proc', '--dev-bind', '/dev', '/dev', '--tmpfs', '/tmp',
                            '--', '/bin/sh', '-ec',
                            'test -d /etc; test -d /usr; '
                            'test "$(realpath /etc)" = /etc; '
                            'test "$(readlink /bin)" = usr/bin; '
                            'shared=$(mktemp /dev/shm/md-bwrap.XXXXXXXX); '
                            'printf shm >"$shared"; test "$(cat "$shared")" = shm; rm "$shared"; '
                            'exec 3</tmp; printf data >/tmp/file-a; '
                            'mv /proc/self/fd/3/file-a /proc/self/fd/3/file-b; '
                            'test "$(cat /tmp/file-b)" = data; echo MD-BWRAP-SPLIT-ROOT'],
                           'MD-BWRAP-SPLIT-ROOT'),
            'pid-debugger': (['--unshare-pid', '--ro-bind', '/', '/', '--proc', '/proc',
                             '--dev-bind', '/dev', '/dev', '--tmpfs', '/tmp', '--',
                             '/usr/bin/gdb', '-nx', '-batch',
                             '-ex', 'set disable-randomization off', '-ex', 'starti',
                             '-ex', 'info inferiors', '-ex', 'continue', '--args', '/usr/bin/true'],
                            'exited normally'),
            'pid': (['--unshare-pid', '--ro-bind', '/', '/', '--proc', '/proc', '--dev-bind', '/dev', '/dev',
                     '--tmpfs', '/tmp', '--', '/bin/sh', '-exc',
                     'test -r /proc/1/status; test -r /proc/$$/status; '
                     'test "$(awk \'/^Pid:/{print $2}\' /proc/1/status)" = 1; '
                     'test -c /dev/null; printf test >/dev/null; '
                     'shared=$(mktemp /dev/shm/md-bwrap.XXXXXXXX); '
                     'printf shm >"$shared"; test "$(cat "$shared")" = shm; rm "$shared"; '
                     'test "$(readlink /proc/1/ns/pid)" = "$(readlink /proc/self/ns/pid)"; '
                     'echo MD-BWRAP-PID'], 'MD-BWRAP-PID'),
            'basic': (['--bind', '/', '/', '--', '/usr/bin/id'], 'uid=2000'),
            'readonly': (['--ro-bind', '/', '/', '--tmpfs', '/tmp', '--', '/bin/sh', '-ec',
                          'printf writable >/tmp/probe; test "$(cat /tmp/probe)" = writable; '
                          'test -r /usr/bin/id; echo MD-BWRAP-READONLY'], 'MD-BWRAP-READONLY'),
            'identity': (['--uid', '0', '--gid', '0', '--hostname', 'shroot-private',
                          '--ro-bind', '/', '/', '--tmpfs', '/tmp', '--', '/bin/sh', '-ec',
                          'test "$(id -u)" = 0; test "$(id -g)" = 0; '
                          'test "$(uname -n)" = shroot-private; '
                          'if touch /forbidden-write; then exit 19; fi; '
                          'printf private >/tmp/probe; echo MD-BWRAP-IDENTITY'], 'MD-BWRAP-IDENTITY'),
            'nested': (['--hostname', 'outer-scope', '--ro-bind', '/', '/', '--tmpfs', '/tmp',
                        '--', '/bin/sh', '-ec',
                        'test "$(uname -n)" = outer-scope; printf outer >/tmp/outer; '
                        '/usr/bin/bwrap --unshare-user --unshare-uts --unshare-ipc '
                        '--hostname inner-scope --ro-bind / / --tmpfs /tmp -- /bin/sh -ec '
                        + shlex.quote('test "$(uname -n)" = inner-scope; test ! -e /tmp/outer; '
                                      'printf inner >/tmp/inner; test "$(cat /tmp/inner)" = inner')
                        + '; test "$(uname -n)" = outer-scope; test "$(cat /tmp/outer)" = outer; '
                          'test ! -e /tmp/inner; echo MD-BWRAP-NESTED'], 'MD-BWRAP-NESTED'),
        }
        for name, (arguments, marker) in cases.items():
            if args.case not in ('all', name):
                continue
            result = command(run + trace + ['/usr/bin/bwrap', '--unshare-user', '--unshare-uts',
                                           '--unshare-ipc'] + arguments, marker, logged=True)
            assert 'ptrace_syscall_info.op ==' not in result['output'], result['log']
        suite.report['passed'] = True
    finally:
        try:
            suite.command(['rm', '-rf', suite.base], quiet=True)
        finally:
            suite.finish()


if __name__ == '__main__':
    main()
