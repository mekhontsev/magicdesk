#!/usr/bin/env python3
"""Run Shroot's debugger fixtures under the installed Termux PRoot distribution."""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile

from test_development_runtime import development_sources


def run(argv, env, timeout):
    process = subprocess.Popen(argv, env=env, stdin=subprocess.DEVNULL,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               text=True, start_new_session=True)
    expired = False
    try:
        # EVENT_WAIT: child exit and pipe EOF; timeout fails the case and kills its group.
        output, _ = process.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        expired = True
        os.killpg(process.pid, signal.SIGKILL)
        output, _ = process.communicate()
    return {'argv': argv, 'exitCode': process.returncode,
            'timedOut': expired, 'output': output}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--distribution', default='ubuntu')
    parser.add_argument('--timeout', type=int, default=45)
    args = parser.parse_args()
    env = os.environ.copy()
    env.pop('PROOT_NO_SECCOMP', None)
    report = {'uid': os.getuid(), 'kernel': os.uname().release,
              'distribution': args.distribution, 'cases': [], 'passed': False}
    report['proot'] = run(['proot', '--version'], env, args.timeout)
    target = '/tmp/md-gdb-comparison'
    try:
        with tempfile.TemporaryDirectory(prefix='md-gdb-comparison-') as directory:
            for name, content in development_sources().items():
                if name.startswith(('/tmp/md-debug', '/tmp/md-gdb')):
                    Path(directory, Path(name).name).write_text(
                        content.replace('/tmp/md-', target + '/md-'))
            prefix = ['proot-distro', 'login', '--isolated', '--bind',
                      directory + ':' + target, args.distribution, '--']
            report['gdb'] = run(prefix + ['gdb', '--version'], env, args.timeout)
            setup = '\n'.join([
                f'gcc -O0 -g {target}/md-debug.c -o {target}/md-debug',
                f'gcc -O0 -g -pthread {target}/md-debug-events.c -o {target}/md-debug-events',
                f'{target}/md-debug', f'{target}/md-debug-events'])
            report['control'] = run(prefix + ['/bin/sh', '-ec', setup], env, args.timeout)
            if report['control']['exitCode'] or report['control']['timedOut']:
                raise RuntimeError('Compilation or undebugged control failed')
            fixtures = [
                ('step', 'md-gdb.commands',
                 'PASS GDB breakpoint, step, locals, backtrace and normal exit'),
                ('events', 'md-gdb-events.commands',
                 'PASS GDB threads, signal delivery, fork detach and child exec')]
            for mode in ('default', 'no-seccomp'):
                case_env = env.copy()
                if mode == 'no-seccomp':
                    case_env['PROOT_NO_SECCOMP'] = '1'
                for name, filename, marker in fixtures:
                    result = run(prefix + ['gdb', '--batch', '-nx', '-x',
                                          target + '/' + filename], case_env, args.timeout)
                    result.update(name=name, mode=mode)
                    result['passed'] = (not result['timedOut'] and
                                        result['exitCode'] == 0 and marker in result['output'])
                    report['cases'].append(result)
                    print(f"{mode}/{name}: {'PASS' if result['passed'] else 'FAIL'}", flush=True)
                    print(result['output'], flush=True)
            report['passed'] = all(case['passed'] for case in report['cases'])
    except Exception as error:
        report['failure'] = repr(error)
        raise
    finally:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + '\n')
        print('Report: ' + str(args.output), flush=True)
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
