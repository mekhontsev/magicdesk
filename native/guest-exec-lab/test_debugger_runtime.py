#!/usr/bin/env python3
"""Matched debugger lifecycle and profiler workflows for Shroot and Termux PRoot."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import tempfile

from test_oci_services import Suite, add_machine_entry_argument, write_script
from test_proot_debugger import run
from test_development_runtime import development_sources

TARGET = '/tmp/md-debugger-tests'
STRACE_DETACH = r'''
import selectors
from pathlib import Path
import signal
import socket
import subprocess
import sys
import time
socket_mode = len(sys.argv) > 1 and sys.argv[1] == '--socket'
pair = socket.socketpair() if socket_mode else None
program = ['python3', '-u', '-c',
    'import socket; s=socket.socket(fileno=0); print("READY"); '
    'assert s.recv(1)==b"x"; print("PASS strace-target")'] if socket_mode else ['/tmp/md-debugger-tests', 'strace-target']
child = subprocess.Popen(program, stdin=pair[1] if pair else subprocess.PIPE,
                         stdout=subprocess.PIPE, text=True)
if pair:
    pair[1].close()
tracer = None
try:
    assert child.stdout.readline().strip() == 'READY'
    tracer = subprocess.Popen(['strace', '-f', '-o', '/tmp/md-strace-detach.log',
                               '-p', str(child.pid)], stderr=subprocess.PIPE)
    with selectors.DefaultSelector() as selector:
        selector.register(tracer.stderr, selectors.EVENT_READ)
        messages = b''
        deadline = time.monotonic() + 10
        # EVENT_WAIT: strace acknowledges attachment; expiry fails the workflow.
        while b'attached' not in messages:
            assert selector.select(max(0, deadline-time.monotonic())), messages
            data = tracer.stderr.read1(4096)
            assert data, messages
            messages += data
    print(messages.decode(), end='', flush=True)
    deadline = time.monotonic() + 10
    previous, since = None, time.monotonic()
    # Bounded observation: syscall 260 is ARM64 wait4, 73 is its interruptible
    # runtime carrier. Signal the waiting tracer, not its attachment setup.
    while True:
        current = Path('/proc/'+str(tracer.pid)+'/syscall').read_text().split()
        if current != previous:
            previous, since = current, time.monotonic()
        # Repeated identical observations exclude a transient attachment probe.
        if current and current[0] in ('260', '73') and time.monotonic()-since >= 0.05:
            break
        assert time.monotonic() < deadline, current
        time.sleep(0.001)
    tracer.send_signal(signal.SIGINT)
    # EVENT_WAIT: SIGINT must detach from idle tracees, without target activity.
    _, errors = tracer.communicate(timeout=10)
    print(errors.decode(), end='', flush=True)
    assert tracer.returncode in (0, -signal.SIGINT), tracer.returncode
    if pair:
        pair[0].sendall(b'x')
    output, _ = child.communicate(None if pair else 'x', timeout=10)
    assert child.returncode == 0 and 'PASS strace-target' in output, output
    print('PASS strace-detach' + ('-socket' if pair else ''))
finally:
    for process in (tracer, child):
        if process is not None:
            if process.poll() is None: process.kill()
            process.wait(timeout=10)
    if pair:
        pair[0].close()
'''
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
        '-ex', 'thread apply all bt 8', '-ex', 'echo MD_BACKTRACE\\n',
        '-ex', 'python assert int(gdb.parse_and_eval("magic_value")) == 42',
        '-ex', 'set var magic_value=43', '-ex', 'detach', '-ex', 'echo MD_DETACHED\\n'],
        capture_output=True, text=True, timeout=20)
    print(result.stdout, result.stderr)
    assert result.returncode == 0
    if target == 'gdb-poll-target':
        assert 'gdb_poll_target' in result.stdout, result.stdout
        assert 'corrupt stack' not in result.stdout, result.stdout
    output, _ = child.communicate('x', timeout=10)
    assert child.returncode == 0 and 'PASS '+target in output
    assert all(stage in result.stdout for stage in ('MD_ATTACHED', 'MD_BACKTRACE', 'MD_DETACHED'))
    suffix = {'gdb-thread-target': '-threads', 'gdb-poll-target': '-poll'}.get(target, '')
    print('PASS gdb-attach' + suffix)
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
GDB_IO = r'''
import subprocess
child = subprocess.Popen(['/tmp/md-debugger-tests', 'gdb-io-target'],
                         stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
debugger = None
try:
    assert child.stdout.readline().strip() == 'READY'
    commands = ['gdb', '--batch', '-nx', '-ex', 'set debuginfod enabled off',
                '-ex', 'attach '+str(child.pid), '-ex', 'break io_marker',
                '-ex', 'echo MD_ATTACHED\\n']
    for iteration in range(16):
        commands += ['-ex', 'continue',
                     '-ex', 'python assert int(gdb.parse_and_eval("iteration")) == '+str(iteration),
                     '-ex', 'echo MD_IO_BREAK\\n']
    commands += ['-ex', 'detach', '-ex', 'echo MD_DETACHED\\n']
    debugger = subprocess.Popen(commands, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    output = []
    # EVENT_WAIT: attach acknowledgement releases target; outer timeout cancels failures.
    for line in debugger.stdout:
        print(line, end='', flush=True)
        output.append(line)
        if line.strip() == 'MD_ATTACHED':
            child.stdin.write('x'*16)
            child.stdin.flush()
    assert debugger.wait(timeout=10) == 0
    remaining, _ = child.communicate(timeout=10)
    print(remaining)
    assert child.returncode == 0 and 'PASS gdb-io-target' in remaining
    output = ''.join(output)
    assert output.count('MD_IO_BREAK') == 16 and 'MD_DETACHED' in output
    assert 'Error while executing Python code' not in output
    print('PASS gdb-active-io')
finally:
    if debugger is not None:
        if debugger.poll() is None: debugger.kill()
        debugger.wait(timeout=10)
    if child.poll() is None: child.kill()
    child.wait(timeout=10)
'''
GDB_FORK = r'''
import os
from pathlib import Path
import select
import signal
import subprocess
import sys
import time
# A retired virtual source leaves the selector installed, as inherited from init.
# PRoot compares the same target lifecycle without Shroot's virtual source.
if '--selector' in sys.argv[1:]:
    fd = os.open('/proc/sys/kernel/hostname', os.O_RDONLY)
    with select.epoll() as queue:
        queue.register(fd, select.EPOLLIN | select.EPOLLERR)
    os.close(fd)
child = subprocess.Popen(['/tmp/md-debugger-tests', 'gdb-fork-target'],
                         stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
debugger = None
try:
    assert child.stdout.readline().strip() == 'READY'
    debugger = subprocess.Popen(['gdb', '--batch', '-nx',
        '-ex', 'set debuginfod enabled off', '-ex', 'attach '+str(child.pid),
        '-ex', 'python assert len(gdb.selected_inferior().threads()) == 41',
        '-ex', 'set var magic_value=43', '-ex', 'echo MD_ATTACHED\\n',
        '-ex', 'continue'], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    output = []
    # EVENT_WAIT: attachment releases target; fixture alarm and launch bound failures.
    for line in debugger.stdout:
        print(line, end='', flush=True)
        output.append(line)
        if line.strip() == 'MD_ATTACHED':
            deadline = time.monotonic() + 5
            # Bounded observation: release only after GDB enters its idle native poll.
            while True:
                call = Path('/proc/'+str(debugger.pid)+'/syscall').read_text().split()
                if call and call[0] == '73' and int(call[2], 16):
                    status = Path('/proc/'+str(debugger.pid)+'/status').read_text()
                    mask = int(next(line.split()[1] for line in status.splitlines()
                                    if line.startswith('SigBlk:')), 16)
                    assert not mask & (1 << (signal.SIGCHLD-1)), hex(mask)
                    break
                assert time.monotonic() < deadline, call
                time.sleep(.001)
            child.stdin.write('x')
            child.stdin.flush()
    assert debugger.wait(timeout=10) == 0
    remaining, _ = child.communicate(timeout=10)
    print(remaining)
    assert child.returncode == 0 and 'PASS gdb-fork-target' in remaining
    output = ''.join(output)
    assert output.count('Detaching after fork') == 4 and 'exited normally' in output
    assert 'Error while executing Python code' not in output
    print('PASS gdb-attach-fork')
finally:
    for process in (debugger, child):
        if process is not None:
            if process.poll() is None: process.kill()
            process.wait(timeout=10)
'''
GDB_SHM = r'''
import subprocess
commands = ['gdb', '--batch', '-nx', '/tmp/md-debugger-tests',
    '-ex', 'set debuginfod enabled off', '-ex', 'set args shm-target',
    '-ex', 'break shm_marker', '-ex', 'run']
for _ in range(4):
    commands += ['-ex', 'python assert len(gdb.selected_inferior().threads()) == 1',
                 '-ex', 'python assert int(gdb.parse_and_eval("*address")) == ord("S")',
                 '-ex', 'echo MD_SHM_BREAK\\n', '-ex', 'continue']
# EVENT_WAIT: four breakpoints and inferior completion; expiry is a failure.
result = subprocess.run(commands, capture_output=True, text=True, timeout=25)
print(result.stdout, result.stderr)
assert result.returncode == 0 and result.stdout.count('MD_SHM_BREAK') == 4
assert 'PASS shm-target' in result.stdout and 'exited normally' in result.stdout
assert 'Error while executing Python code' not in result.stderr
assert '[New ' not in result.stdout
print('PASS gdb-shm')
'''
GDB_JIT = r'''
import subprocess
commands = ['gdb', '--batch', '-nx', '/tmp/md-debugger-tests',
    '-ex', 'set debuginfod enabled off', '-ex', 'set args jit-target',
    '-ex', 'break jit_marker', '-ex', 'run']
for value in range(37, 41):
    commands += ['-ex', 'python assert len(gdb.selected_inferior().threads()) == 1',
                 '-ex', 'python assert int(gdb.parse_and_eval("value")) == '+str(value),
                 '-ex', 'python assert int(gdb.parse_and_eval("address[0]")) == '+str(0x52800000 | value << 5),
                 '-ex', 'echo MD_JIT_BREAK\\n', '-ex', 'continue']
# EVENT_WAIT: each executable view reaches its breakpoint; expiry fails the run.
result = subprocess.run(commands, capture_output=True, text=True, timeout=25)
print(result.stdout, result.stderr)
assert result.returncode == 0 and result.stdout.count('MD_JIT_BREAK') == 4
assert 'PASS jit-target' in result.stdout and 'exited normally' in result.stdout
assert 'Error while executing Python code' not in result.stderr
assert '[New ' not in result.stdout
print('PASS gdb-jit')
'''


def commands(*, proot=False):
    cases = [(name, [TARGET, name]) for name in ('attach', 'seize', 'trace-exit', 'syscall', 'syscall-edit-close', 'syscall-exec', 'syscall-signal', 'credential-transition', 'mixed',
                                               'kill-stopped', 'owner-exit', 'exitkill', 'thread-exec', 'syscall-thread-exec', 'clone-untraced', 'shared-files-debugger', 'perf-event')]
    cases.append(('strace-detach', ['python3', '-c', STRACE_DETACH]))
    cases.append(('strace-detach-socket', ['python3', '-c', STRACE_DETACH, '--socket']))
    cases.append(('gdb-attach', ['python3', '-c', GDB_ATTACH]))
    cases.append(('gdb-attach-threads', ['python3', '-c', GDB_ATTACH, 'gdb-thread-target']))
    cases.append(('gdb-attach-poll', ['python3', '-c', GDB_ATTACH, 'gdb-poll-target']))
    cases.append(('gdb-active-io', ['python3', '-c', GDB_IO]))
    cases.append(('gdb-attach-fork', ['python3', '-c', GDB_FORK, *([] if proot else ['--selector'])]))
    cases.append(('gdb-shm', ['python3', '-c', GDB_SHM]))
    cases.append(('gdb-jit', ['python3', '-c', GDB_JIT]))
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
        "strace -f -qq -o /tmp/md-debugger-strace.log /bin/sh -c 'cat /etc/passwd >/dev/null' 2>/tmp/md-debugger-strace.err\n"
        "cat /tmp/md-debugger-strace.err\n"
        "test ! -s /tmp/md-debugger-strace.err\n"
        "grep -F 'openat(AT_FDCWD, \"/etc/passwd\", O_RDONLY)' /tmp/md-debugger-strace.log\n"
        "grep 'execve(' /tmp/md-debugger-strace.log\n"
        "grep -E 'clone3?\\(' /tmp/md-debugger-strace.log\necho PASS strace"]))
    cases.append(('strace-shm', ['/bin/sh', '-ec',
        "strace -f -qq -o /tmp/md-debugger-shm.log /tmp/md-debugger-tests shm-target 2>/tmp/md-debugger-shm.err\n"
        "cat /tmp/md-debugger-shm.err\n"
        "test ! -s /tmp/md-debugger-shm.err\n"
        "test \"$(grep -c '^.*shmat(' /tmp/md-debugger-shm.log)\" = 4\n"
        "test \"$(grep -c '^.*shmdt(' /tmp/md-debugger-shm.log)\" = 4\n"
        "! grep -E 'clone3?\\(|close_range\\(0,' /tmp/md-debugger-shm.log\necho PASS strace-shm"]))
    cases.append(('strace-jit', ['/bin/sh', '-ec',
        "strace -f -qq -e trace=mprotect,mremap,clone,clone3 -o /tmp/md-debugger-jit.log "
        "/tmp/md-debugger-tests jit-target 2>/tmp/md-debugger-jit.err\n"
        "cat /tmp/md-debugger-jit.err\n"
        "test ! -s /tmp/md-debugger-jit.err\n"
        "test \"$(grep -c 'mprotect(.*PROT_READ|PROT_EXEC) *= 0' /tmp/md-debugger-jit.log)\" = 4\n"
        "! grep -E 'mremap\\(|clone3?\\(|= -1' /tmp/md-debugger-jit.log\necho PASS strace-jit"]))
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
    add_machine_entry_argument(parser)
    parser.add_argument('--pid-namespace', action='store_true')
    parser.add_argument('--gdbserver-binary', type=Path,
                        help='Optional standalone guest binary; never replaces the installed package')
    args = parser.parse_args()
    if args.proot and (args.build or args.diagnostics or args.pid_namespace or args.machine_entry is not None):
        parser.error('runtime and namespace options apply only to Shroot')
    args.output.mkdir(parents=True, exist_ok=True)
    source = Path(__file__).with_name('test_debugger_guest.c')
    sources = development_sources()
    step = sources['/tmp/md-debug.c']
    remote = sources['/tmp/md-gdb.commands'].replace('/tmp/md-debug', '/tmp/md-debugger-step')
    remote = remote.replace('run\n', 'target remote | gdbserver --once --no-startup-with-shell --no-disable-randomization - /tmp/md-debugger-step\ncontinue\n')
    if args.gdbserver_binary:
        remote = remote.replace('| gdbserver ', '| /tmp/md-gdbserver ')
    remote += 'echo PASS gdbserver\\n\n'
    cases = [(n, a) for n, a in commands(proot=bool(args.proot)) if not args.case or n in args.case]
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
        suite = Suite(args.output, args.build, machine_entry=args.machine_entry)
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
                    options = ['--diagnostics'] if args.diagnostics else []
                    child = (['unshare', '--mount', '--pid', '--fork', '--mount-proc']
                             if args.pid_namespace else []) + argv
                    if args.diagnostics or args.pid_namespace:
                        command = [suite.runner, *suite.entry_options, *options, '--store', args.instance,
                                   '--user', '0:0', '--', '/bin/sh', '-c', 'exec "$@"',
                                   'debugger-test', *child]
                    else:
                        command = suite.run(args.instance, *(['--user', '0:0']
                            if name == 'credential-transition' else []), '--', *argv)
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
