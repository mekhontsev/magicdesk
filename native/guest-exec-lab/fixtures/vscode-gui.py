#!/usr/bin/env python3
"""Run the shared VS Code/GDB fixture inside an existing recipe desktop."""
import argparse
import json
import os
from pathlib import Path
import re
import select
import signal
import shutil
import socket
import subprocess
import tempfile
import time
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--driver', type=Path, required=True)
    parser.add_argument('--code', default='code')
    parser.add_argument('--timeout', type=int, default=300)
    parser.add_argument('--reload', action='store_true',
                        help='Reload the real window once before checking the extension host')
    parser.add_argument('--require-gpu', action='store_true')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix='shroot-vscode-', dir=Path.home() / '.cache'))
    extension = work / 'driver'
    extension.mkdir()
    (extension / 'package.json').write_text(json.dumps({
        'name': 'guest-debug-fixture', 'publisher': 'magicdesk-fixture', 'version': '1.0.0',
        'engines': {'vscode': '^1.90.0'}, 'main': './extension.js',
        'activationEvents': ['onStartupFinished'], 'extensionDependencies': ['ms-vscode.cpptools']}))
    shutil.copyfile(args.driver, extension / 'extension.js')
    project = work / 'project'
    project.mkdir()
    (project / 'editor.txt').touch()
    (project / 'main.c').write_text('#include <stdio.h>\nint main(void) {\n'
        '    volatile int value = 40;\n    puts("GDB fixture");\n    value += 2;\n'
        '    printf("value=%d\\n", value);\n    return value == 42 ? 0 : 1;\n}\n')
    settings = work / 'data/User'
    settings.mkdir(parents=True)
    (settings / 'settings.json').write_text(json.dumps({
        'telemetry.telemetryLevel': 'off', 'update.mode': 'none', 'extensions.autoUpdate': False,
        'workbench.startupEditor': 'none', 'C_Cpp.intelliSenseEngine': 'disabled'}))
    report = {'passed': False, 'work': str(work), 'stages': []}
    child = None
    editor_fd = None
    try:
        debugger = shutil.which('gdb')
        if not debugger:
            raise RuntimeError('The VS Code debugger fixture requires gdb in PATH')
        installed = subprocess.check_output(
            [args.code, '--list-extensions'], text=True, timeout=args.timeout)
        if 'ms-vscode.cpptools' not in installed.lower().splitlines():
            raise RuntimeError('Install ms-vscode.cpptools through the selected code launcher; '
                               'its extension directory is package-specific')
        subprocess.run(['gcc', '-g', '-O0', str(project / 'main.c'), '-o', str(project / 'main')], check=True)
        with socket.socket() as listener, (args.output / 'code.log').open('w') as log:
            listener.bind(('127.0.0.1', 0))
            listener.listen(1)
            listener.settimeout(args.timeout)
            token = uuid.uuid4().hex
            env = dict(os.environ, MD_VSCODE_PORT=str(listener.getsockname()[1]),
                       MD_VSCODE_TOKEN=token, MD_DEBUG_TRANSPORT='terminal',
                       MD_DEBUGGER_PATH=debugger,
                       MD_VSCODE_DATA=str(work / 'data'),
                       MD_VSCODE_RELOAD='1' if args.reload else '0', MD_VSCODE_CLOSE='1')
            report['reload'] = args.reload
            command = [args.code, '--wait', '--new-window', '--skip-welcome', '--skip-release-notes',
                       '--log', 'trace', '--remote-debugging-port=0',
                       '--disable-workspace-trust', '--user-data-dir=' + str(work / 'data'),
                       '--extensionDevelopmentPath=' + str(extension), str(project)]
            report['command'] = command
            child = subprocess.Popen(command, env=env, stdout=log, stderr=subprocess.STDOUT,
                                     start_new_session=True)
            deadline = time.monotonic() + args.timeout
            # EVENT_WAIT: extension socket and debugger records; EOF/deadline fails.
            connection, _ = listener.accept()
            with connection, connection.makefile('r') as stream:
                connection.settimeout(max(1, deadline - time.monotonic()))
                for line in stream:
                    record = json.loads(line)
                    assert record.pop('token') == token
                    report['stages'].append(record)
                    (args.output / 'progress.json').write_text(json.dumps(report, indent=2))
                    if record['stage'] == 'failed':
                        raise AssertionError(record['error'])
                    if record['stage'] == 'workspace':
                        editors = [p['id'] for p in record['processes'] if p['type'] == 'browser']
                        assert len(editors) == 1, record['processes']
                        editor_fd = os.pidfd_open(editors[0])
                        report['editorPid'] = editors[0]
                        status = subprocess.check_output([args.code, '--status',
                            '--user-data-dir=' + str(work / 'data')], env=env, text=True,
                            stderr=subprocess.STDOUT, timeout=max(1, deadline - time.monotonic()))
                        (args.output / 'status.txt').write_text(status)
                        features = dict(re.findall(r'^\s*(\w+):\s*(\w+)\s*$', status, re.MULTILINE))
                        report['gpuFeatures'] = features
                        if args.require_gpu:
                            assert features.get('gpu_compositing') == 'enabled', status
                        renderers = [p['id'] for p in record['processes'] if p['type'] == 'renderer']
                        assert renderers, 'No live editor renderer in Electron process information'
                        report['rendererSandboxes'] = []
                        for pid in renderers:
                            proc = Path('/proc') / str(pid)
                            fields = dict(line.split(':', 1) for line in (proc / 'status').read_text().splitlines()
                                          if ':' in line)
                            state = {key: fields[key].strip() for key in ('Uid', 'NoNewPrivs', 'Seccomp')}
                            report['rendererSandboxes'].append(dict(pid=int(pid), **state))
                            assert state['NoNewPrivs'] == '1' and state['Seccomp'] == '2', state
                            assert int(state['Uid'].split()[0]) == os.getuid() != 0, state
                        connection.sendall(b'workspace-checked\n')
                    if record['stage'] == 'breakpoint':
                        connection.sendall(b'continue\n')
                    if record['stage'] == 'passed':
                        connection.sendall(b'close\n')
                        break
                else:
                    raise AssertionError('Extension ended without debugger completion')
            # EVENT_WAIT: normal user window closure; deadline is not a passing exit.
            assert editor_fd is not None, 'Editor process identity was not observed'
            closed = select.poll()
            closed.register(editor_fd, select.POLLIN)
            assert closed.poll(max(0, int((deadline - time.monotonic()) * 1000))), \
                'Editor process did not exit after window closure'
            report['editorExited'] = True
            report['exitCode'] = child.wait(timeout=max(1, deadline - time.monotonic()))
            assert report['exitCode'] == 0
            assert any(s['stage'] == 'workspace' for s in report['stages'])
            report['passed'] = True
    except Exception as error:
        report['failure'] = repr(error)
        raise
    finally:
        if editor_fd is not None:
            if not report['passed']:
                # Electron can outlive the CLI's process group. The pidfd
                # identifies only the editor launched by this fixture.
                try:
                    signal.pidfd_send_signal(editor_fd, signal.SIGTERM)
                    closed = select.poll()
                    closed.register(editor_fd, select.POLLIN)
                    # EVENT_WAIT: exact failed editor exit, then forced cleanup.
                    if not closed.poll(10000):
                        signal.pidfd_send_signal(editor_fd, signal.SIGKILL)
                        if not closed.poll(10000):
                            report['cleanupFailure'] = 'Editor did not exit after SIGKILL'
                except ProcessLookupError:
                    pass
            os.close(editor_fd)
        if child and child.poll() is None:
            # The CLI owns an Electron child; terminate this fixture's process
            # group on failure rather than leaving its editor window running.
            os.killpg(child.pid, signal.SIGTERM)
            child.wait(timeout=10)
        (args.output / 'gui-report.json').write_text(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
