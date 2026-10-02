#!/usr/bin/env python3
"""Stock sandbox-enabled VS Code plus Microsoft C/C++ and live GDB on a private display."""
import argparse
import base64
import json
from pathlib import Path
import re
import shlex
import socket
import subprocess
from test_oci_services import Suite, write_script


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('build', type=Path)
    p.add_argument('--store', required=True)
    p.add_argument('--recipes', type=Path, required=True)
    p.add_argument('--runtime', required=True, help='Directory containing md-await-exit')
    p.add_argument('--keyboard-directory', required=True)
    p.add_argument('--debug-transport', choices=['terminal', 'pipe'], default='terminal')
    args = p.parse_args()
    suite = Suite(args.build, args.build)
    tag = suite.report['id']
    home = '/home/mdcode'
    project = home + '/fixture-' + tag
    extension = project + '/driver'
    log = suite.base + '/vscode.log'
    receipt = log + '.exit'
    session = display = connection = None
    listener = socket.socket()
    listener.bind(('127.0.0.1', 0))
    listener.listen(1)
    listener.settimeout(120)
    suite.report.update(instance=args.store, sandboxFlagsDisabled=False, isolationEstablished=False,
                        debugTransport=args.debug_transport)

    def wait(condition, **selection):
        # EVENT_WAIT: named graphics/display event; missing event fails this run.
        value = suite.client.call('wait_for_state', {'condition': condition, 'timeoutMillis': 30000, **selection})
        assert value.get('matched'), value
        return value

    try:
        before = suite.client.call('get_state')
        assert before['readiness']['interactive'] and not before['readiness']['deviceLocked']
        suite.command(suite.run(args.store, '--', '/bin/mkdir', '-p', extension, project + '/data/User'))
        write_script(suite, args.store, project + '/main.c', '#include <stdio.h>\nint main(void) {\n'
                     '    volatile int value = 40;\n    puts("GDB fixture");\n    value += 2;\n'
                     '    printf("value=%d\\n", value);\n    return value == 42 ? 0 : 1;\n}\n')
        write_script(suite, args.store, extension + '/package.json', json.dumps({
            'name': 'guest-debug-fixture', 'publisher': 'magicdesk-fixture', 'version': '1.0.0',
            'engines': {'vscode': '^1.90.0'}, 'main': './extension.js',
            'activationEvents': ['onStartupFinished'], 'extensionDependencies': ['ms-vscode.cpptools']}))
        write_script(suite, args.store, extension + '/extension.js',
                     (Path(__file__).parent / 'fixtures/vscode-debug.js').read_text())
        write_script(suite, args.store, project + '/data/User/settings.json', json.dumps({
            'telemetry.telemetryLevel': 'off', 'update.mode': 'none', 'extensions.autoUpdate': False,
            'workbench.startupEditor': 'none', 'C_Cpp.intelliSenseEngine': 'disabled'}))
        suite.command(suite.run(args.store, '--', '/bin/sh', '-ec',
            'gcc -g -O0 ' + project + '/main.c -o ' + project + '/main\nchown -R mdcode:mdcode ' + project))
        wrapper = ('#!/system/bin/sh\nexec ' + shlex.quote(suite.runner)
                   + ' --diagnostics --admit-elf /opt/md-vscode/chrome-sandbox "$@"\n')
        suite.report['admittedElf'] = '/opt/md-vscode/chrome-sandbox'
        suite.command(['/system/bin/sh', '-c', 'printf %s "$1" > "$2"; chmod 700 "$2"',
                       'fixture', wrapper, suite.base + '/magicdesk-guest'])
        display = suite.client.call('create_display', {'type': 'virtual', 'width': 1280, 'height': 900, 'densityDpi': 160})
        session = suite.client.call('graphics.start', {'protocol': 'x11', 'backend': 'shell', 'connection': 'routed',
            'name': 'VS Code sandbox and GDB', 'keyboardDirectory': args.keyboard_directory})['sessionId']
        state = wait('graphics_ready', sessionId=session)['session']
        assert state['executorUid'] == 2000 and state['serverUid'] not in (0, 2000)
        program = shlex.join(['/usr/bin/env', 'MD_VSCODE_PORT=' + str(listener.getsockname()[1]),
            'MD_VSCODE_TOKEN=' + tag, 'MD_DEBUG_TRANSPORT=' + args.debug_transport,
            'LIBGL_ALWAYS_SOFTWARE=1', '/opt/md-vscode/code', '--ozone-platform=x11',
            '--enable-logging=stderr', '--skip-welcome', '--skip-release-notes', '--disable-workspace-trust',
            '--password-store=basic', '--user-data-dir=' + project + '/data',
            '--extensions-dir=' + home + '/extensions', '--extensionDevelopmentPath=' + extension, project])
        recipe = subprocess.check_output(['java', '-cp', str(args.recipes),
            'io.github.mekhontsev.magicdesk.GraphicalRecipe', 'routed', 'x11', args.store, home,
            'exec ' + program, 'mdcode'], text=True)
        launch = ('{ timeout -k 10 240 env PATH=' + shlex.quote(suite.base + ':/system/bin')
                  + ' /system/bin/sh -c ' + shlex.quote(recipe)
                  + '; r=$?; printf "%s\\n" "$r" > ' + shlex.quote(receipt) + '; } > ' + shlex.quote(log) + ' 2>&1')
        suite.report['launch'] = launch
        suite.client.call('graphics.execute', {'sessionId': session, 'command': launch})
        state = wait('graphics_window_present', sessionId=session)['session']
        window = next(w for w in state['windows'] if w['mapped'] and not w['parentWindowId'])
        suite.report['window'] = window
        suite.client.call('graphics.open_window', {'sessionId': session, 'windowId': window['windowId'],
            'placement': 'display', 'displayId': display['id'], 'uniqueId': display['uniqueId']})
        host = wait('graphics_host_attached', sessionId=session, windowId=window['windowId'])['matchingHosts'][0]
        assert not host['managed']
        # EVENT_WAIT: extension connection and DAP-backed records; EOF/deadline fails.
        connection, _ = listener.accept()
        connection.settimeout(120)
        with connection.makefile('r') as stream:
            for line in stream:
                record = json.loads(line)
                assert record['token'] == tag
                suite.report['cases'].append(record)
                print(json.dumps({k: v for k, v in record.items() if k != 'messages'}), flush=True)
                assert record['stage'] != 'failed', record.get('error')
                if record['stage'] == 'breakpoint':
                    capture = suite.client.call_result('capture_screenshot', {'taskId': host['taskId']})
                    png = base64.b64decode(next(c['data'] for c in capture['content'] if c['type'] == 'image'))
                    picture = args.build / ('vscode-debug-' + tag + '.png')
                    picture.write_bytes(png)
                    suite.report['capture'] = str(picture)
                    connection.sendall(b'continue\n')
                elif record['stage'] == 'passed':
                    break
            else:
                raise AssertionError('Extension ended without debugger completion')
        suite.client.call('graphics.close_window', {'sessionId': session, 'windowId': window['windowId']})
        wait('graphics_window_absent', sessionId=session, windowId=window['windowId'])
        suite.command(['timeout', '30', args.runtime + '/md-await-exit', receipt])
        assert suite.command(['cat', receipt]).strip() == '0'
        output = suite.command(['cat', log], quiet=True)
        suite.report['filtersInstalled'] = len(re.findall('PROBE installed-filter', output))
        assert suite.report['filtersInstalled'] > 0
        assert 'PROBE FAIL' not in output
        after = suite.client.call('get_state')
        assert before['homeLease'] == after['homeLease'] and before['workspaces'] == after['workspaces']
        suite.report['passed'] = True
    except Exception as error:
        suite.report['failure'] = repr(error)
        raise
    finally:
        errors = []
        if connection:
            connection.close()
        listener.close()
        for action in ([lambda: suite.client.call('graphics.stop', {'sessionId': session}),
                        lambda: wait('graphics_session_absent', sessionId=session)] if session else []) + (
                       [lambda: suite.client.call('remove_display', {'displayId': display['id'], 'uniqueId': display['uniqueId']}),
                        lambda: wait('display_absent', displayId=display['id'])] if display else []):
            try:
                action()
            except Exception as error:
                errors.append(str(error))
        try:
            suite.report['log'] = suite.command(['/system/bin/sh', '-c', 'test ! -f "$1" || cat "$1"', 'fixture', log], quiet=True)
        except Exception as error:
            errors.append(str(error))
        suite.report['cleanupErrors'] = errors
        suite.report['passed'] &= not errors
        suite.finish()
        if errors:
            raise RuntimeError(errors)


if __name__ == '__main__':
    main()
