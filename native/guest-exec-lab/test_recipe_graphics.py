#!/usr/bin/env python3
"""Render installer entries using a selected native build and a prepared store.

Uses production graphical connection generation and an isolated display. This
checks guest GUI execution, not Android named-environment catalog registration.
Run test_installer_setup.py with xfce and weston first. No Mesa build is needed.
"""
import argparse
import base64
import configparser
from pathlib import Path
import shlex
import subprocess
import tempfile
import time

from test_oci_services import Suite, add_machine_entry_argument
from graphical_recipe import compile_recipe


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('store')
    parser.add_argument('--entry', action='append', choices=['mousepad-x11', 'mousepad-wayland', 'xfce', 'weston', 'plasma-x11', 'plasma-wayland'])
    parser.add_argument('--user', default='0:0')
    parser.add_argument('--home', default='/root')
    parser.add_argument('--lease', help='Renew an existing phone wake lease without releasing it')
    parser.add_argument('--output', type=Path, default=Path('build/recipe-graphics-results'))
    add_machine_entry_argument(parser)
    args = parser.parse_args()
    classes = args.output / 'recipe-classes'
    hashes = compile_recipe(classes)
    suite = Suite(args.output, args.build, machine_entry=args.machine_entry)
    client = suite.client
    suite.report['store'] = args.store
    suite.report['connectionSourceHashes'] = hashes
    display = session = lease = None
    owned = False
    document = '/tmp/md-gui-' + suite.report['id'] + '.txt'
    probe_script = document + '.probe'
    run = [suite.runner, '--store', args.store, '--user', args.user, '--home', args.home, '--']

    def wait(condition, **selection):
        # EVENT_WAIT: production observer publication; expiry fails this workflow.
        result = client.call('wait_for_state', {'condition': condition, 'timeoutMillis': 30000, **selection})
        assert result.get('matched'), result
        return result

    def launches():
        import json
        return [json.loads(line) for line in suite.command(suite.image_command('launches', args.store), quiet=True).splitlines()]

    def until(read, predicate, label):
        # BOUNDED_STATE_WAIT: file commit/pixel readiness has no pushed MCP endpoint.
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            value = read()
            if predicate(value):
                return value
        raise AssertionError(label)

    def close_case():
        nonlocal owned, session
        if owned:
            for launch in launches():
                suite.command(suite.image_command('stop', args.store, launch['launchId']))
            until(launches, lambda values: not values, 'guest launch cleanup')
            owned = False
        if session:
            if any(s['sessionId'] == session for s in client.call('graphics.list')['sessions']):
                try:
                    client.call('graphics.stop', {'sessionId': session})
                except suite.transport.ToolError:
                    if any(s['sessionId'] == session for s in client.call('graphics.list')['sessions']):
                        raise
            wait('graphics_session_absent', sessionId=session)
            session = None

    try:
        before = client.call('get_state')
        assert before['readiness']['interactive'] and not before['readiness']['deviceLocked'], before['readiness']
        assert not launches(), 'Store is in use; GUI fixture requires exclusive test ownership'
        suite.command(run + ['/bin/sh', '-ec', 'command -v pidof; command -v timeout'])
        lease = client.call('device.keep_awake', {'durationMillis': 1800000,
                            **({'leaseId': args.lease} if args.lease else {})})['leaseId']
        with tempfile.TemporaryDirectory() as temporary:
            wrapper = Path(temporary) / 'shroot'
            wrapper.write_text('#!/system/bin/sh\nexec '
                               + shlex.join([suite.runner, *suite.entry_options]) + ' "$@"\n')
            suite.transport.upload(client, wrapper, suite.base + '/shroot')
        suite.command(['chmod', '700', suite.base + '/shroot'])
        keyboard = suite.base + '/xkb'
        suite.command(suite.image_command('export-tree', args.store, '/usr/share/X11/xkb', keyboard))
        display = client.call('create_display', {'type': 'virtual', 'width': 1200, 'height': 800, 'densityDpi': 160})
        suite.report['display'] = display
        for entry_name in args.entry or ['mousepad-x11', 'mousepad-wayland', 'xfce', 'weston']:
            check = {'entry': entry_name, 'passed': False}
            suite.report['cases'].append(check)
            log = suite.base + '/' + entry_name + '.log'
            receipt = log + '.exit'
            try:
                assert not launches(), 'Unexpected guest owner'
                entry = configparser.ConfigParser(interpolation=None)
                entry.read_string(suite.command(run + ['/bin/cat',
                    '/usr/local/share/applications/magicdesk-' + entry_name + '.desktop'], quiet=True))
                values = entry['Desktop Entry']
                protocol = values['X-MagicDesk-Graphics']
                desktop = values.get('X-MagicDesk-GraphicsMode') == 'desktop'
                words = shlex.split(values['Exec'])
                if not desktop:
                    assert words[-1] == '%F', words
                    words[-1] = document
                    suite.command(run + ['/bin/sh', '-c', 'printf "seed\\n" > "$1"', 'sh', document])
                check['command'] = words
                guest_command = shlex.join(words)
                ready_file = document + '.' + entry_name + '.ready'
                if desktop:
                    programs = {'xfce': ['xfce4-session', 'xfwm4', 'xfce4-panel', 'xfdesktop'],
                                'weston': ['weston'], 'plasma-x11': ['kwin_x11', 'plasmashell'],
                                'plasma-wayland': ['kwin_wayland', 'plasmashell']}[entry_name]
                    # Observe processes within their launch, not a peer launch's proc view.
                    probe = ('for program do printf "Waiting for %s\\n" "$program" >&2; '
                             'until pidof "$program"; do '
                             'kill -0 "$MD_DESKTOP_PID" || exit 1; sleep 0.1; done; done')
                    if entry_name.startswith('plasma-'):
                        # A rendered splash is not a ready desktop.
                        probe += ('; while pidof ksplashqml >/dev/null; do '
                                  'kill -0 "$MD_DESKTOP_PID" || exit 1; sleep 0.1; done')
                    suite.command(run + ['/bin/sh', '-ec', 'printf "%s\\n" "$2" > "$1"',
                                         'sh', probe_script, probe], quiet=True)
                    guest_command += (' & MD_DESKTOP_PID=$!; export MD_DESKTOP_PID; '
                        + shlex.join(['timeout', '30', '/bin/sh', '-e', probe_script, *programs])
                        + ' > ' + shlex.quote(ready_file + '.tmp')
                        + ' && mv ' + shlex.quote(ready_file + '.tmp') + ' ' + shlex.quote(ready_file)
                        + ' || { ps -eo pid,comm,args >&2; exit 1; }; wait "$MD_DESKTOP_PID"')
                recipe = subprocess.check_output(['java', '-cp', str(classes),
                    'io.github.mekhontsev.magicdesk.GraphicalRecipe', 'routed', protocol, args.store,
                    args.home, guest_command, args.user], text=True, timeout=20)
                command = ('{ env PATH=' + shlex.quote(suite.base + ':/system/bin') + ' timeout -k 15 180 /system/bin/sh -c '
                           + shlex.quote(recipe) + '; r=$?; printf "%s\\n" "$r" > ' + shlex.quote(receipt)
                           + '; } > ' + shlex.quote(log) + ' 2>&1')
                # The generated recipe can exceed the desktop Exec size limit.
                launcher = args.output / (suite.report['id'] + '-' + entry_name + '.sh')
                launcher.write_text('#!/system/bin/sh\n' + command + '\n')
                remote_launcher = suite.base + '/' + entry_name + '.sh'
                suite.transport.upload(client, launcher, remote_launcher)
                session = client.call('graphics.start', {'protocol': protocol, 'backend': 'shell',
                    'connection': 'routed', 'name': 'Recipe ' + entry_name + ' ' + suite.report['id'][:8],
                    'keyboardDirectory': keyboard, 'wholeDesktop': desktop})['sessionId']
                ready = wait('graphics_ready', sessionId=session)['session']
                assert ready['executorUid'] == 2000 and ready['serverUid'] not in (0, 2000), ready
                owned = True
                client.call('graphics.execute', {'sessionId': session,
                    'command': shlex.join(['/system/bin/sh', remote_launcher])})
                if desktop and protocol == 'x11':
                    window = 0
                else:
                    mapped = wait('graphics_window_present', sessionId=session)['session']
                    window = next(w['windowId'] for w in mapped['windows'] if w['mapped'] and not w['parentWindowId'])
                client.call('graphics.open_window', {'sessionId': session, 'windowId': window,
                    'placement': 'display', 'displayId': display['id'], 'uniqueId': display['uniqueId']})
                host = wait('graphics_host_attached', sessionId=session, windowId=window)['matchingHosts'][0]
                check['host'] = host
                assert not host['managed'], host
                wait('task_focused', taskId=host['taskId'], displayId=display['id'])
                wait('app_ready', taskId=host['taskId'], displayId=display['id'])
                if desktop:
                    check['componentPids'] = until(lambda: suite.command(run + ['/bin/sh', '-c',
                        'test ! -f "$1" || cat "$1"', 'sh', ready_file], quiet=True), bool, 'desktop components')
                def capture():
                    snapshot = next(s for s in client.call('graphics.list')['sessions'] if s['sessionId'] == session)
                    if not any(h.get('content') and h['content']['width'] > 0 for h in snapshot['hosts']):
                        return None
                    result = client.call_result('capture_screenshot', {'taskId': host['taskId']})
                    data = base64.b64decode(next(c['data'] for c in result['content'] if c['type'] == 'image'))
                    metrics = subprocess.run(['magick', 'png:-', '-crop', '1200x720+0+40', '-format',
                                              '%k %[standard-deviation]', 'info:'], input=data,
                                             capture_output=True, check=True, timeout=20).stdout.split()
                    return (data, snapshot, metrics) if int(metrics[0]) >= 100 and float(metrics[1]) > 0.01 else None

                png, snapshot, metrics = until(capture, bool, 'rendered client pixels')
                if not desktop:
                    for keys in (['CTRL_LEFT', 'A'], ['G'], ['U'], ['I'], ['ENTER'], ['CTRL_LEFT', 'S']):
                        client.call('input.key_chord', {'displayId': display['id'], 'keys': keys})
                    check['savedText'] = until(lambda: suite.command(run + ['/bin/cat', document], quiet=True),
                                              lambda value: value == 'gui\n', 'editor input/save')
                    png, snapshot, metrics = until(capture, bool, 'edited client pixels')
                path = args.output / (suite.report['id'] + '-' + entry_name + '.png')
                path.write_bytes(png)
                check.update(capture=str(path), snapshot=snapshot, pixelMetrics=[v.decode() for v in metrics])
                if desktop:
                    client.call('graphics.detach_viewer', {'sessionId': session, 'taskId': host['taskId']})
                    wait('task_absent', taskId=host['taskId'])
                    client.call('graphics.open_window', {'sessionId': session, 'windowId': window,
                        'placement': 'display', 'displayId': display['id'], 'uniqueId': display['uniqueId']})
                    host = wait('graphics_host_attached', sessionId=session, windowId=window)['matchingHosts'][0]
                    wait('app_ready', taskId=host['taskId'], displayId=display['id'])
                    png, snapshot, metrics = until(capture, bool, 'retained desktop pixels')
                    retained = path.with_stem(path.stem + '-retained')
                    retained.write_bytes(png)
                    check['retainedCapture'] = str(retained)
                check['passed'] = True
            except Exception as error:
                check['error'] = repr(error)
                print(check['error'], flush=True)
            finally:
                close_case()
                check['log'] = suite.command(['/system/bin/sh', '-c', 'test ! -f "$1" || cat "$1"', 'sh', log], quiet=True)
                if 'Authorization required, but no authorization protocol specified' in check['log']:
                    check.update(passed=False, error='A client or activated service lost X11 authorization')
                if "assertion 'POLKIT_IS_SUBJECT (subject)' failed" in check['log']:
                    check.update(passed=False, error='PolicyKit agent cannot identify its session')
                if 'guest-runtime: supervisor failure' in check['log']:
                    check.update(passed=False, error='Shroot supervisor failure')
            print(('PASS ' if check['passed'] else 'FAIL ') + entry_name, flush=True)
        suite.command(run + ['/bin/rm', '-f', document, probe_script,
            *(document + '.' + entry + suffix for entry in ('xfce', 'weston', 'plasma-x11', 'plasma-wayland')
              for suffix in ('.ready', '.ready.tmp'))])
        after = client.call('get_state')
        assert before['homeLease'] == after['homeLease'] and before['workspaces'] == after['workspaces']
        suite.report['passed'] = all(c['passed'] for c in suite.report['cases'])
    finally:
        try:
            close_case()
            if display:
                client.call('remove_display', {'displayId': display['id'], 'uniqueId': display['uniqueId']})
                wait('display_absent', displayId=display['id'])
            if lease and not args.lease:
                client.call('device.release_awake', {'leaseId': lease})
        finally:
            suite.finish()
    return 0 if suite.report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
