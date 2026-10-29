#!/usr/bin/env python3
"""Exercise the private stock-KWin adaptation through production graphics routing."""
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
parser.add_argument('--directory', required=True)
parser.add_argument('--name', choices=('steamos', 'nixos-recipe', 'debian-turnip'), default='steamos')
parser.add_argument('--case', choices=('qpa', 'egl', 'vulkan'), default='qpa')
parser.add_argument('--gdb', help='Guest GDB path for a diagnostic launch')
parser.add_argument('--cleanup', action='store_true', help='Remove this retained module work directory after the test')
args = parser.parse_args()
work = args.directory
assert Path(work).parent == Path('/data/local/tmp') and Path(work).name.startswith('md-oci-services-')
out = Path('build/kwin-binary-module') / args.name
suite = Suite(out)
client = suite.client
state = suite.report
state['case'] = args.case
state['debugger'] = args.gdb
target = '/tmp/md-kwin-module'
owned = False
resources_stopped = False

def shell(command):
    result = client.call('console.execute', dict(sessionId=state['consoleId'], command=command))
    assert result['exitCode'] == 0, result
    return result['output']

def wait(condition, **extra):
    result = client.call('wait_for_state', dict(condition=condition, timeoutMillis=45000,
                                              sessionId=state['sessionId'], **extra))
    assert result['matched'], result
    return result

try:
    suite.command(['test', '-f', work + '/adapt-result.json'])
    owned = True
    info = suite.installed_environment(args.name)
    assert not info['launches'], 'Keep the desktop experiment isolated'
    state['environment'] = info
    state['runner'] = suite.runner
    state['homeLeaseBefore'] = client.call('get_state')['homeLease']
    guest_home = '/home/steamos' if args.name == 'steamos' else ('/root' if args.name == 'nixos-recipe' else '/home/mdtester')
    guest_user = '0:0' if args.name == 'nixos-recipe' else '1000:1000'
    state['guestUser'] = guest_user
    if args.gdb:
        suite.command([suite.runner, '--store', info['store'], '--user', guest_user,
                       '--', '/bin/sh', '-c', 'test -x ' + shlex.quote(args.gdb)])
    path = '/run/current-system/sw/bin:/usr/local/bin:/usr/bin:/bin'
    for name in ('kwin-module-session.sh', 'kwin-module-client.sh'):
        suite.transport.upload(client, Path(__file__).with_name('fixtures') / name,
                               work + '/' + name, overwrite=True)
    suite.command(['chmod', '-R', 'a+rX', work])
    suite.command(['rm', '-f', work + '/client.ready', work + '/control-path', work + '/exit-status'])
    classes = out / 'classes'
    state['sourceHashes'] = compile_recipe(classes)
    recipe = subprocess.check_output(['java', '-cp', str(classes),
        'io.github.mekhontsev.magicdesk.GraphicalRecipe', 'routed', 'wayland',
        info['store'], guest_home, '/bin/sh ' + target + '/kwin-module-session.sh', guest_user], text=True)
    wrapper = out / 'shroot'
    # The installed shroot wrapper already forwards audio and gamepad connections.
    wrapper.write_text('#!/system/bin/sh\nexec ' +
        shlex.join([suite.runner, '--network', 'routed', '--env', 'MAGICDESK_GUEST_LABEL=' + state['id'],
                    '--env', 'MD_KWIN_CASE=' + args.case,
                    '--env', 'MD_KWIN_GDB=' + (args.gdb or ''),
                    '--env', 'PATH=' + path,
                    '--bind', work, target]) + ' "$@"\n')
    suite.transport.upload(client, wrapper, suite.base + '/shroot')
    suite.command(['chmod', '755', suite.base + '/shroot'])
    state['display'] = client.call('create_display', dict(type='virtual', width=960, height=640, densityDpi=160))
    awake = client.call('get_state')['automationAwake']
    assert not awake.get('held'), 'Release the other fixture lease before this display test'
    state['awakeLease'] = client.call('device.keep_awake',
        dict(displayId=state['display']['id'], durationMillis=180000))['leaseId']
    state['sessionId'] = client.call('graphics.start', dict(protocol='wayland', backend='shell',
        connection='routed', wholeDesktop=True, name='Stock KWin module probe',
        keyboardDirectory='guest:' + info['store']))['sessionId']
    wait('graphics_ready')
    launcher = out / 'launch-module.sh'
    launcher.write_text('#!/system/bin/sh\nenv PATH=' + shlex.quote(suite.base + ':/system/bin') +
        ' /system/bin/sh -c ' + shlex.quote(recipe) + ' >' + shlex.quote(work + '/desktop.log') + ' 2>&1\n'
        'status=$?\nprintf "%s\\n" "$status" > ' + shlex.quote(work + '/exit-status') + '\nexit "$status"\n')
    suite.transport.upload(client, launcher, suite.base + '/launch.sh')
    client.call('graphics.execute', dict(sessionId=state['sessionId'],
        command=shlex.join(['/system/bin/sh', suite.base + '/launch.sh'])))
    (out / 'desktop-state.json').write_text(json.dumps(state, indent=2))
    observation = wait('graphics_window_present')
    window = next(w['windowId'] for w in observation['session']['windows'] if w['mapped'] and not w['parentWindowId'])
    client.call('graphics.open_window', dict(sessionId=state['sessionId'], windowId=window,
        placement='display', displayId=state['display']['id'], uniqueId=state['display']['uniqueId']))
    wait('graphics_host_attached', windowId=window)
    deadline = time.monotonic() + 45
    while not shell('test ! -f ' + shlex.quote(work + '/client.ready') + ' || printf ready'):
        assert time.monotonic() < deadline, 'Nested clients did not become ready'
        # STATE_POLL: this fixture marker has no MCP event; expiry is a failed test.
        threading.Event().wait(0.2)
    deadline = time.monotonic() + 30
    while True:
        pixels = client.call('sample_pixels', dict(displayId=state['display']['id'],
            points=[dict(x=x, y=y) for x in range(60, 960, 120) for y in range(40, 640, 80)]))
        state['pixels'] = pixels
        if len({sample['argb'] for sample in pixels['samples']}) >= 3:
            break
        assert time.monotonic() < deadline, 'Compositor mapped but did not present varied pixels'
        # STATE_POLL: actual display pixels; a blank frame never qualifies as success.
        threading.Event().wait(0.2)
    state['pixels'] = pixels
    capture = client.call_result('capture_screenshot', dict(displayId=state['display']['id']))
    (out / 'module-desktop.png').write_bytes(base64.b64decode(next(
        item['data'] for item in capture['content'] if item['type'] == 'image')))
    state['graphics'] = client.call('graphics.list')
    state['clientsReady'] = True
    control = shell('cat ' + shlex.quote(work + '/control-path')).strip()
    assert control.startswith('/tmp/magicdesk-runtime.') and control.endswith('/module-control')
    deadline = time.monotonic() + 30
    frames = []
    while args.case != 'qpa' and len(frames) < 3:
        pixels = client.call('sample_pixels', dict(displayId=state['display']['id'],
            points=[dict(x=x, y=y) for x in range(60, 960, 120) for y in range(40, 640, 80)]))
        colored = sum(max(p['red'], p['green'], p['blue']) - min(p['red'], p['green'], p['blue']) > 60
                      for p in pixels['samples'])
        values = [p['argb'] for p in pixels['samples']]
        # vkcube's textured gray faces need temporal evidence, not the EGL
        # triangle's saturated-color predicate. Require multiple changing pixels.
        changed = sum(a != b for a, b in zip(values, frames[-1])) if frames else len(set(values))
        qualified = colored >= 3 if args.case == 'egl' else changed >= 3
        if qualified and (not frames or values != frames[-1]):
            frames.append(values)
        assert time.monotonic() < deadline, 'GPU client did not present changing pixels'
        if len(frames) < 3:
            threading.Event().wait(0.2)
    state['animationFrames'] = frames
    support = shell('cat ' + shlex.quote(work + '/support.txt'))
    assert 'Compositing Type: OpenGL' in support and 'MESA_TURNIP' in support
    maps = shell('cat ' + shlex.quote(work + '/compositor.maps'))
    for library in ('libkwin.so.6', 'libmd-kwin-module.so', 'plugins/platforms/libmd-kwin-qpa.so'):
        assert work + '/patched/' + library in maps, library
    kwin_paths = {line.split(maxsplit=5)[-1] for line in maps.splitlines() if '/libkwin.so.' in line}
    assert kwin_paths == {work + '/patched/libkwin.so.6'}, kwin_paths
    capture = client.call_result('capture_screenshot', dict(displayId=state['display']['id']))
    (out / ('module-' + args.case + '.png')).write_bytes(base64.b64decode(next(
        item['data'] for item in capture['content'] if item['type'] == 'image')))
    suite.command([suite.runner, '--store', info['store'], '--user', guest_user, '--',
                   '/bin/sh', '-c', 'printf "quit\\n" > ' + shlex.quote(control)])
    deadline = time.monotonic() + 30
    while any(item['label'] == state['id'] for item in client.call('guest.launches', dict(name=args.name))['launches']):
        assert time.monotonic() < deadline, 'KWin did not exit cleanly'
        threading.Event().wait(0.2)
    while not shell('test ! -f ' + shlex.quote(work + '/exit-status') + ' || printf present'):
        assert time.monotonic() < deadline, 'No process completion status'
        threading.Event().wait(0.1)
    state['exitStatus'] = int(shell('cat ' + shlex.quote(work + '/exit-status')))
    assert state['exitStatus'] == 0, state['exitStatus']
    state['cleanExit'] = True
    state['passed'] = True
finally:
    try:
        try:
            if 'sessionId' in state and not state.get('passed'):
                capture = client.call_result('capture_screenshot', dict(displayId=state['display']['id']))
                (out / ('module-' + args.case + '-failed.png')).write_bytes(base64.b64decode(next(
                    item['data'] for item in capture['content'] if item['type'] == 'image')))
        except Exception as error:
            state['failureCaptureError'] = str(error)
        finally:
            if 'environment' in state:
                stop_owned_resources(client, shell, state)
                resources_stopped = True
        for name in ('desktop.log', 'support.txt', 'compositor.maps', 'client.log', 'client.env'):
            if shell('test ! -f ' + shlex.quote(work + '/' + name) + ' || printf present'):
                suite.transport.download(client, work + '/' + name, out / name, overwrite=True)
        state['homeLeaseAfter'] = client.call('get_state')['homeLease']
        assert state['homeLeaseAfter'] == state['homeLeaseBefore']
        suite.command(['rm', '-rf', suite.base])
    finally:
        try:
            if args.cleanup and owned and resources_stopped:
                suite.command(['rm', '-rf', work])
                if 'environment' in state:
                    suite.command([suite.runner, '--store', state['environment']['store'], '--user', '0:0',
                                   '--', '/bin/sh', '-c', 'PATH=' + shlex.quote(path) + ' rmdir ' + shlex.quote(target)])
        finally:
            (out / 'desktop-state.json').write_text(json.dumps(state, indent=2))
            (out / ('desktop-' + args.case + '-state.json')).write_text(json.dumps(state, indent=2))
            suite.finish()
