#!/usr/bin/env python3
"""Matched debugger lifecycle and profiler workflows for Shroot and Termux PRoot."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import tempfile

from test_oci_services import Suite, write_script
from test_proot_debugger import run
from test_development_runtime import development_sources

TARGET = '/tmp/md-debugger-tests'
GDB_ATTACH = r'''
import subprocess
import sys
target = sys.argv[1] if len(sys.argv) > 1 else 'gdb-target'
child = subprocess.Popen(['/tmp/md-debugger-tests', target],
                         stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
try:
    # EVENT_WAIT: target readiness and debugger completion; outer launch bounds failures.
    assert child.stdout.readline().strip() == 'READY'
    result = subprocess.run(['gdb', '--batch', '-nx',
        '-ex', 'set debuginfod enabled off',
        '-ex', 'attach '+str(child.pid), '-ex', 'echo MD_ATTACHED\\n',
        '-ex', 'python assert len(gdb.selected_inferior().threads()) == '
               + ('41' if target == 'gdb-thread-target' else '1'),
        '-ex', 'thread apply all bt 4', '-ex', 'echo MD_BACKTRACE\\n',
        '-ex', 'python assert int(gdb.parse_and_eval("magic_value")) == 42',
        '-ex', 'set var magic_value=43', '-ex', 'detach', '-ex', 'echo MD_DETACHED\\n'],
        capture_output=True, text=True, timeout=20)
    print(result.stdout, result.stderr)
    assert result.returncode == 0
    output, _ = child.communicate('x', timeout=10)
    assert child.returncode == 0 and 'PASS '+target in output
    assert all(stage in result.stdout for stage in ('MD_ATTACHED', 'MD_BACKTRACE', 'MD_DETACHED'))
    print('PASS gdb-attach' + ('-threads' if target == 'gdb-thread-target' else ''))
except subprocess.TimeoutExpired as error:
    print(error.stdout, error.stderr, flush=True)
    raise
finally:
    if child.poll() is None: child.kill()
    child.wait()
'''
LLDB = r'''
import subprocess
try:
    # EVENT_WAIT: LLDB completes the breakpoint/step/exit workflow; expiry fails it.
    result = subprocess.run(['lldb', '--batch', '/tmp/md-debugger-step',
        '-o', 'settings set target.disable-aslr false',
        '-o', 'breakpoint set -n answer', '-o', 'run',
        '-o', 'frame variable input', '-o', 'next', '-o', 'frame variable result',
        '-o', 'continue'], capture_output=True, text=True, timeout=25)
except subprocess.TimeoutExpired as error:
    print(error.stdout, error.stderr, flush=True)
    raise
print(result.stdout, result.stderr)
assert result.returncode == 0
assert 'input = 41' in result.stdout and 'result = 42' in result.stdout
assert 'exited with status = 0' in result.stdout
print('PASS lldb')
'''


def commands():
    cases = [(name, [TARGET, name]) for name in ('attach', 'seize', 'trace-exit', 'syscall', 'mixed',
                                               'kill-stopped', 'owner-exit', 'exitkill', 'thread-exec', 'perf-event')]
    cases.append(('gdb-attach', ['python3', '-c', GDB_ATTACH]))
    cases.append(('gdb-attach-threads', ['python3', '-c', GDB_ATTACH, 'gdb-thread-target']))
    cases.append(('lldb', ['python3', '-c', LLDB]))
    cases.append(('gdbserver', ['gdb', '--batch', '-nx', '-x', '/tmp/md-gdbserver.commands']))
    cases.append(('gprof', ['/bin/sh', '-ec',
        'cd /tmp\ngcc -pg -O0 -g md-debugger-step.c -o md-debugger-profile\n'
        './md-debugger-profile\ntest -s gmon.out\n'
        'gprof ./md-debugger-profile gmon.out | grep answer\necho PASS gprof']))
    cases.append(('callgrind', ['/bin/sh', '-ec',
        'valgrind --tool=callgrind --callgrind-out-file=/tmp/md-callgrind.out /tmp/md-debugger-step\n'
        'grep "fn=.*answer" /tmp/md-callgrind.out\n'
        'grep -E "summary: [1-9]" /tmp/md-callgrind.out\necho PASS callgrind']))
    cases.append(('strace', ['/bin/sh', '-ec',
        "strace -f -qq -o /tmp/md-debugger-strace.log /bin/sh -c 'cat /etc/passwd >/dev/null'\n"
        "grep '/etc/passwd' /tmp/md-debugger-strace.log\n"
        "grep 'execve(' /tmp/md-debugger-strace.log\n"
        "grep -E 'clone3?\\(' /tmp/md-debugger-strace.log\necho PASS strace"]))
    return cases


def outcome(name, output):
    if name == 'perf-event' and 'UNAVAILABLE perf-event errno=' in output:
        return {'passed': None, 'status': 'unavailable'}
    passed = 'PASS '+name in output
    return {'passed': passed, 'status': 'passed' if passed else 'failed'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    backend = parser.add_mutually_exclusive_group(required=True)
    backend.add_argument('--proot')
    backend.add_argument('--instance')
    parser.add_argument('--build', type=Path)
    parser.add_argument('--case', action='append', choices=[name for name, _ in commands()])
    parser.add_argument('--diagnostics', action='store_true')
    parser.add_argument('--gdbserver-binary', type=Path,
                        help='Optional standalone guest binary; never replaces the installed package')
    args = parser.parse_args()
    if args.proot and (args.build or args.diagnostics):
        parser.error('--build and --diagnostics apply only to Shroot')
    args.output.mkdir(parents=True, exist_ok=True)
    source = Path(__file__).with_name('test_debugger_guest.c')
    sources = development_sources()
    step = sources['/tmp/md-debug.c']
    remote = sources['/tmp/md-gdb.commands'].replace('/tmp/md-debug', '/tmp/md-debugger-step')
    remote = remote.replace('run\n', 'target remote | gdbserver --once --no-startup-with-shell --no-disable-randomization - /tmp/md-debugger-step\ncontinue\n')
    if args.gdbserver_binary:
        remote = remote.replace('| gdbserver ', '| /tmp/md-gdbserver ')
    remote += 'echo PASS gdbserver\\n\n'
    cases = [(n, a) for n, a in commands() if not args.case or n in args.case]
    assert cases
    if args.proot:
        env = os.environ.copy()
        report = {'uid': os.getuid(), 'kernel': os.uname().release, 'cases': []}
        with tempfile.TemporaryDirectory(prefix='md-debugger-') as temporary:
            Path(temporary, 'md-debugger-step.c').write_text(step)
            Path(temporary, 'md-gdbserver.commands').write_text(remote)
            prefix = ['proot-distro', 'login', '--isolated', '--bind',
                      str(source.resolve()) + ':' + TARGET + '.c', '--bind',
                      temporary + ':/tmp']
            if args.gdbserver_binary:
                prefix += ['--bind', str(args.gdbserver_binary.resolve()) + ':/tmp/md-gdbserver']
            prefix += [args.proot, '--']
            setup = run(prefix + ['gcc', '-O0', '-g', '-pthread', TARGET + '.c', '-o', TARGET], env, 30)
            assert setup['exitCode'] == 0, setup
            setup = run(prefix + ['gcc', '-O0', '-g', '/tmp/md-debugger-step.c', '-o', '/tmp/md-debugger-step'], env, 30)
            assert setup['exitCode'] == 0, setup
            for name, argv in cases:
                result = run(prefix + argv, env, 35)
                result.update(name=name, **(outcome(name, result['output']) if result['exitCode'] == 0
                                           else {'passed': False, 'status': 'failed'}))
                report['cases'].append(result)
                print(name, result, flush=True)
        report['passed'] = all(c['passed'] is not False for c in report['cases'])
        (args.output / 'proot.json').write_text(json.dumps(report, indent=2)+'\n')
    else:
        suite = Suite(args.output, args.build)
        try:
            if args.gdbserver_binary:
                suite.transport.upload(suite.client, args.gdbserver_binary, suite.base+'/md-gdbserver')
                suite.command(suite.run(args.instance, '--bind-ro', suite.base, '/mnt', '--',
                                        '/bin/sh', '-ec', 'cp /mnt/md-gdbserver /tmp/md-gdbserver; chmod 755 /tmp/md-gdbserver'))
            write_script(suite, args.instance, TARGET+'.c', source.read_text())
            write_script(suite, args.instance, '/tmp/md-debugger-step.c', step)
            write_script(suite, args.instance, '/tmp/md-gdbserver.commands', remote)
            suite.command(suite.run(args.instance, '--', 'gcc', '-O0', '-g', '-pthread', TARGET+'.c', '-o', TARGET))
            suite.command(suite.run(args.instance, '--', 'gcc', '-O0', '-g', '/tmp/md-debugger-step.c', '-o', '/tmp/md-debugger-step'))
            for name, argv in cases:
                case = {'name': name, 'passed': False}; suite.report['cases'].append(case)
                try:
                    command = ([suite.runner, '--diagnostics', '--store', args.instance,
                                '--user', '0:0', '--', '/bin/sh', '-c', 'exec "$@"', 'debugger-test', *argv] if args.diagnostics
                               else suite.run(args.instance, '--', *argv))
                    output = suite.command(command, timeout=35)
                    case.update(outcome(name, output))
                    assert case['passed'] is not False, output
                except Exception as error:
                    case['error'] = str(error)
                print(name, case, flush=True)
            suite.report['passed'] = all(c['passed'] is not False for c in suite.report['cases'])
        finally:
            suite.finish()
        report = suite.report
    if args.gdbserver_binary:
        with args.gdbserver_binary.open('rb') as binary:
            report['gdbserverBinary'] = {'path': str(args.gdbserver_binary),
                'sha256': hashlib.file_digest(binary, 'sha256').hexdigest()}
        destination = args.output/'proot.json' if args.proot else suite.output
        destination.write_text(json.dumps(report, indent=2)+'\n')
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
