#!/usr/bin/env python3
"""Stock Weston popup/focus regression, with and without the public-API guard."""
import argparse
import base64
import json
from pathlib import Path
import shlex
import subprocess
import threading
import time
from graphical_recipe import compile_recipe
from installed_desktop import stop_owned_resources
from test_oci_services import Suite

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--name', required=True)
parser.add_argument('--stock', action='store_true', help='Require the original popup failure')
args = parser.parse_args()
out = Path('build/weston-child-guard') / args.name / ('stock' if args.stock else 'guarded')
suite = Suite(out)
state, client = suite.report, suite.client
target = '/tmp/md-weston-popup'

def shell(command):
    result = client.call('console.execute', dict(sessionId=suite.console, command=command))
    assert result['exitCode'] == 0, result
    return result['output']

def wait(condition, **extra):
    result = client.call('wait_for_state', dict(condition=condition, timeoutMillis=30000, **extra))
    assert result['matched'], result
    return result

try:
    info = suite.installed_environment(args.name)
    assert not info['launches'], 'Keep the compositor probe isolated'
    state.update(environment=info, runner=suite.runner, stock=args.stock)
    state['homeLeaseBefore'] = client.call('get_state')['homeLease']
    for name in ('weston-child-guard.c', 'weston-popup-probe.c'):
        suite.transport.upload(client, Path(__file__).with_name('fixtures') / name, suite.base + '/' + name)
    prefix = [suite.runner, '--store', info['store'], '--user', '0:0']
    suite.command([*prefix, '--', '/bin/mkdir', '-p', target])
    prefix += ['--bind', suite.base, target]
    suite.command([*prefix, '--', '/bin/sh', '-ec', f'''
cd {target}
cc -O2 -fPIC -shared weston-child-guard.c -o guard.so -ldl
wayland-scanner client-header /usr/share/wayland-protocols/stable/xdg-shell/xdg-shell.xml xdg-shell-client-protocol.h
wayland-scanner private-code /usr/share/wayland-protocols/stable/xdg-shell/xdg-shell.xml xdg-shell-protocol.c
cc -O2 -Wall -Wextra -Werror weston-popup-probe.c xdg-shell-protocol.c -o popup -lwayland-client
'''])
    for name, text in {
        'client.sh': f'''#!/bin/sh
set -eu
mkfifo "$XDG_RUNTIME_DIR/weston-control"
printf '%s\\n' "$XDG_RUNTIME_DIR/weston-control" > {target}/control-path
exec 3<>"$XDG_RUNTIME_DIR/weston-control"
exec env LD_PRELOAD= MD_POPUP_READY={target}/ready {target}/popup <&3 >{target}/client.log 2>&1
''',
        'weston.ini': f'''[core]
idle-time=0
[shell]
locking=false
panel-position=none
[autolaunch]
path={target}/client.sh
watch=true
''',
        'session.sh': f'''#!/bin/sh
set -eu
. /etc/profile.d/magicdesk-graphics.sh
export LD_PRELOAD={' ' if args.stock else target + '/guard.so'}
exec /usr/bin/weston --backend=wayland --renderer=gl --width=960 --height=640 --socket=popup-probe --config={target}/weston.ini
''',
    }.items():
        local = out / name
        local.write_text(text)
        suite.transport.upload(client, local, suite.base + '/' + name)
    suite.command(['chmod', '755', suite.base + '/client.sh'])
    suite.command(['chmod', '-R', 'a+rX', suite.base])
    suite.command(['chmod', '777', suite.base])
    classes = out / 'classes'
    compile_recipe(classes)
    recipe = subprocess.check_output(['java', '-cp', str(classes),
        'io.github.mekhontsev.magicdesk.GraphicalRecipe', 'routed', 'wayland',
        info['store'], '/root', '/bin/sh ' + target + '/session.sh', '0:0'], text=True)
    wrapper = out / 'shroot'
    wrapper.write_text('#!/system/bin/sh\nexec ' + shlex.join([suite.runner,
        '--env', 'MAGICDESK_GUEST_LABEL=' + state['id'], '--bind', suite.base, target]) + ' "$@"\n')
    suite.transport.upload(client, wrapper, suite.base + '/shroot')
    suite.command(['chmod', '755', suite.base + '/shroot'])
    state['display'] = client.call('create_display', dict(type='virtual', width=960, height=640, densityDpi=160))
    assert not client.call('get_state')['automationAwake'].get('held')
    state['awakeLease'] = client.call('device.keep_awake', dict(displayId=state['display']['id'], durationMillis=180000))['leaseId']
    state['sessionId'] = client.call('graphics.start', dict(protocol='wayland', backend='shell',
        connection='routed', wholeDesktop=True, name='Stock Weston popup probe',
        keyboardDirectory='guest:' + info['store']))['sessionId']
    wait('graphics_ready', sessionId=state['sessionId'])
    launch = out / 'launch.sh'
    launch.write_text('#!/system/bin/sh\nenv PATH=' + shlex.quote(suite.base + ':/system/bin') +
        ' /system/bin/sh -c ' + shlex.quote(recipe) + ' >' + shlex.quote(suite.base + '/desktop.log') + ' 2>&1\n'
        'status=$?\nprintf "%s\\n" "$status" > ' + shlex.quote(suite.base + '/exit-status') + '\nexit "$status"\n')
    suite.transport.upload(client, launch, suite.base + '/launch.sh')
    client.call('graphics.execute', dict(sessionId=state['sessionId'], command=shlex.join(['/system/bin/sh', suite.base + '/launch.sh'])))
    deadline = time.monotonic() + 45
    def exists(name):
        return bool(shell('test ! -f ' + shlex.quote(suite.base + '/' + name) + ' || printf present'))
    while not exists('exit-status') and not exists('ready'):
        assert time.monotonic() < deadline, 'Neither popup readiness nor process completion'
        # STATE_POLL: fixture markers have no MCP event; expiry fails the test.
        threading.Event().wait(0.1)
    if not args.stock:
        assert exists('ready'), shell('cat ' + shlex.quote(suite.base + '/desktop.log'))
        observation = wait('graphics_window_present', sessionId=state['sessionId'])
        window = next(w['windowId'] for w in observation['session']['windows'] if w['mapped'] and not w['parentWindowId'])
        client.call('graphics.open_window', dict(sessionId=state['sessionId'], windowId=window,
            placement='display', displayId=state['display']['id'], uniqueId=state['display']['uniqueId']))
        wait('graphics_host_attached', sessionId=state['sessionId'], windowId=window)
        pixel_deadline = time.monotonic() + 20
        while True:
            pixels = client.call('sample_pixels', dict(displayId=state['display']['id'],
                points=[dict(x=x, y=y) for x in range(60, 960, 120) for y in range(40, 640, 80)]))
            if sum(max(p['red'], p['green'], p['blue']) - min(p['red'], p['green'], p['blue']) > 60
                   for p in pixels['samples']) >= 3:
                state['pixels'] = pixels
                break
            assert time.monotonic() < pixel_deadline, 'Popup windows were not presented'
            threading.Event().wait(0.1)
        capture = client.call_result('capture_screenshot', dict(displayId=state['display']['id']))
        (out / 'popup.png').write_bytes(base64.b64decode(next(c['data'] for c in capture['content'] if c['type'] == 'image')))
        control = shell('cat ' + shlex.quote(suite.base + '/control-path')).strip()
        assert control.startswith('/tmp/magicdesk-runtime.') and control.endswith('/weston-control')
        suite.command([*prefix, '--', '/bin/sh', '-c', 'printf quit > ' + shlex.quote(control)])
    while not exists('exit-status'):
        assert time.monotonic() < deadline, 'Compositor did not exit'
        threading.Event().wait(0.1)
    state['exitStatus'] = int(shell('cat ' + shlex.quote(suite.base + '/exit-status')))
    assert state['exitStatus'] == (139 if args.stock else 0), state['exitStatus']
    state['passed'] = True
finally:
    try:
        if 'environment' in state:
            stop_owned_resources(client, shell, state)
        for name in ('desktop.log', 'client.log'):
            if shell('test ! -f ' + shlex.quote(suite.base + '/' + name) + ' || printf present'):
                suite.transport.download(client, suite.base + '/' + name, out / name, overwrite=True)
        suite.command(['rm', '-rf', suite.base])
        if 'environment' in state:
            suite.command([suite.runner, '--store', info['store'], '--user', '0:0', '--', '/bin/rmdir', target])
        state['homeLeaseAfter'] = client.call('get_state')['homeLease']
        assert state['homeLeaseAfter'] == state['homeLeaseBefore']
    finally:
        suite.finish()
