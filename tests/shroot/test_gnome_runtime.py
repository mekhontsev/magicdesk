#!/usr/bin/env python3
"""Stock Fedora GNOME devkit in private PID/run views, with GUI input checks."""
import _bootstrap
import argparse
import base64
import json
from pathlib import Path
import shlex
import subprocess
import time

from test_oci_services import Suite

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('build', type=Path)
parser.add_argument('store')
parser.add_argument('--trace', action='store_true')
args = parser.parse_args()
suite = Suite(args.build, args.build)
client = suite.client
display = session = display_lease = None
log = suite.base + '/gnome.log'
receipt = log + '.exit'
suite.report.update(store=args.store, probe='gnome-devkit')
run = [suite.runner, '--store', args.store, '--user', '0:0', '--']

def wait(condition, **selection):
    # EVENT_WAIT: native/host lifecycle event, deadline is failure, not settling.
    value = client.call('wait_for_state', dict(condition=condition, timeoutMillis=60000 if condition=='graphics_window_present' else 30000, **selection))
    suite.report.setdefault('observations', []).append(value)
    assert value.get('matched'), value
    return value

def capture(task, phase):
    value = client.call_result('capture_screenshot', {'taskId': task})
    image = next(c for c in value['content'] if c['type'] == 'image')
    png = base64.b64decode(image['data'])
    path = args.build / ('gnome-' + suite.report['id'] + '-' + phase + '.png')
    path.write_bytes(png)
    suite.report.setdefault('captures', []).append(str(path))
    print('Capture:', path, flush=True)
    return png

try:
    state = client.call('get_state')
    assert state['readiness']['interactive'] and not state['readiness']['deviceLocked']
    suite.command([suite.image, 'export-tree', args.store, '/usr/share/X11/xkb', suite.base + '/xkb'])
    display = client.call('create_display', {'type': 'virtual', 'width': 1200, 'height': 800, 'densityDpi': 160})
    display_lease = client.call('device.keep_awake', {'displayId': display['id'], 'durationMillis': 300000})['leaseId']
    session = client.call('graphics.start', {'protocol': 'wayland', 'backend': 'shell',
        'connection': 'routed', 'name': 'Clean Fedora GNOME', 'wholeDesktop': True,
        'keyboardDirectory': suite.base + '/xkb'})['sessionId']
    suite.report['sessionId'] = session
    wait('graphics_ready', sessionId=session)
    init = '''set -eu
mount -t tmpfs -o mode=755 tmpfs /run
mkdir -p /run/dbus /run/user/1000
chown developer:developer /run/user/1000
chmod 700 /run/user/1000
mkfifo /run/dbus/ready
dbus-daemon --system --nofork --nopidfile --print-address=3 3>/run/dbus/ready &
bus=$!
trap 'kill "$bus" || :; wait "$bus" || :' EXIT
read -r address </run/dbus/ready
rm /run/dbus/ready
setpriv --reuid=developer --regid=developer --init-groups env \
    HOME=/home/developer XDG_RUNTIME_DIR=/run/user/1000 \
    XDG_SESSION_TYPE=wayland XDG_CURRENT_DESKTOP=GNOME \
    LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
    dbus-run-session -- /bin/sh -ec '
        pipewire &
        pw=$!
        wireplumber &
        wp=$!
        trap "kill $wp $pw || :; wait $wp $pw || :" EXIT
        (gdbus wait --session org.gnome.TextEditor && echo MD_EDITOR_BUS_READY) &
        gnome-shell --devkit
    '
'''
    if args.trace:
        init = 'export PIPEWIRE_DEBUG=3\n' + init.replace('        gnome-shell --devkit',
            '        strace -f -tt -s 128 -o /tmp/md-gnome.trace gnome-shell --devkit')
    recipe = (shlex.join([suite.runner, '--store', args.store, '--user', '0:0',
        '--admit-elf', '/usr/libexec/dbus-1/dbus-daemon-launch-helper',
        '--env', 'WAYLAND_DISPLAY=/tmp/magicdesk-wayland/wayland-0',
        '--socket-path', '/tmp/magicdesk-wayland/wayland-0'])
        + ' "${MAGICDESK_GRAPHICS_ENDPOINT:?}" -- '
        + shlex.join(['/usr/bin/unshare', '--pid', '--fork', '--mount-proc', '--propagation', 'private',
                      '/bin/sh', '-ec', init]))
    launch = ('{ timeout -k 10 180 env PATH=' + shlex.quote(suite.base + ':/system/bin')
        + ' /system/bin/sh -c ' + shlex.quote(recipe)
        + '; r=$?; printf "%s\\n" "$r" > ' + shlex.quote(receipt)
        + '; } > ' + shlex.quote(log) + ' 2>&1')
    suite.report['launch'] = launch
    client.call('graphics.execute', {'sessionId': session, 'command': launch})
    mapped = wait('graphics_window_present', sessionId=session)['session']
    window = next(w for w in mapped['windows'] if w['mapped'] and not w['parentWindowId'])
    client.call('graphics.open_window', {'sessionId': session, 'windowId': window['windowId'],
        'placement': 'display', 'displayId': display['id'], 'uniqueId': display['uniqueId']})
    host = wait('graphics_host_attached', sessionId=session, windowId=window['windowId'])['matchingHosts'][0]
    suite.report['host'] = host
    wait('app_ready', taskId=host['taskId'], displayId=display['id'])
    wait('task_focused', taskId=host['taskId'], displayId=display['id'])
    # BOUNDED_STATE_WAIT: fresh capture completion paces client-pixel observation.
    deadline = time.monotonic() + 40
    while True:
        png = capture(host['taskId'], 'initial')
        snapshot = client.call('graphics.inspect_window', {'sessionId': session, 'windowId': window['windowId']})
        suite.report['graphics'] = snapshot
        content = next(h for h in snapshot['hosts'] if h['taskId'] == host['taskId']).get('content')
        metrics = subprocess.run(['magick', 'png:-',
            '-format', '%k ', '-write', 'info:', '-colorspace', 'HSL',
            '-channel', 'G', '-separate', '+channel', '-threshold', '2%',
            '-format', '%[fx:mean]', 'info:'], input=png, capture_output=True,
            check=True, timeout=20).stdout.split()
        suite.report['pixels'] = [int(metrics[0]), float(metrics[1])]
        selected = next(w for w in snapshot['catalog'] if w['windowId'] == window['windowId'])
        ready = suite.command(['cat', log], quiet=True)
        if ('GNOME Shell started at' in ready
                and content and content['width'] > 0 and selected['width'] == content['width']
                and selected['height'] == content['height']
                and int(metrics[0]) >= 32 and float(metrics[1]) > 0.5):
            break
        assert time.monotonic() < deadline, ('No GNOME client pixels', metrics)
    initial = args.build / ('gnome-' + suite.report['id'] + '-initial.png')
    client.call('click_pointer', {'displayId': display['id'], 'x': 500, 'y': 400})
    client.call('input.key_chord', {'displayId': display['id'], 'keys': ['ALT_LEFT', 'F2']})
    deadline = time.monotonic() + 20
    while True:
        png = capture(host['taskId'], 'run-dialog')
        delta = subprocess.run(['magick', str(initial), 'png:-', '-compose', 'difference', '-composite',
            '-colorspace', 'gray', '-threshold', '10%', '-format', '%[fx:mean]', 'info:'],
            input=png, capture_output=True, check=True, timeout=20).stdout
        suite.report['inputPixelChange'] = float(delta)
        if float(delta) > 0.05:
            break
        assert time.monotonic() < deadline, ('GNOME did not redraw after Alt-F2', delta)
    dialog = args.build / ('gnome-' + suite.report['id'] + '-run-dialog.png')
    for key in 'gnome-text-editor':
        client.call('input.key_chord', {'displayId': display['id'],
                                       'keys': ['MINUS' if key == '-' else key.upper()]})
    client.call('input.key_chord', {'displayId': display['id'], 'keys': ['ENTER']})
    # BOUNDED_STATE_WAIT: client captures pace observation of the launched editor.
    deadline = time.monotonic() + 30
    while True:
        png = capture(host['taskId'], 'editor')
        delta = subprocess.run(['magick', str(dialog), 'png:-', '-compose', 'difference', '-composite',
            '-colorspace', 'gray', '-threshold', '10%', '-format', '%[fx:mean]', 'info:'],
            input=png, capture_output=True, check=True, timeout=20).stdout
        suite.report['editorPixelChange'] = float(delta)
        ready = suite.command(['cat', log], quiet=True)
        center = subprocess.run(['magick', 'png:-', '-crop', '600x300+300+250',
            '-alpha', 'off', '-colorspace', 'gray', '-format', '%[fx:mean]', 'info:'],
            input=png, capture_output=True, check=True, timeout=20).stdout
        suite.report['editorCenterBrightness'] = float(center)
        if float(delta) > 0.3 and float(center) > 0.5 and 'MD_EDITOR_BUS_READY' in ready:
            break
        assert time.monotonic() < deadline, ('GNOME editor did not appear', delta)
    suite.report['passed'] = True
    suite.report['log'] = suite.command(['cat', log], quiet=True)
    print(json.dumps(host), flush=True)
finally:
    try:
        suite.report['log'] = suite.command(['/system/bin/sh', '-c',
            'test ! -f ' + shlex.quote(log) + ' || cat ' + shlex.quote(log)], quiet=True)
        if args.trace:
            suite.report['gnomeTrace'] = suite.command(run + ['/usr/bin/tail', '-400', '/tmp/md-gnome.trace'], quiet=True)
    finally:
        try:
            if session:
                client.call('graphics.stop', {'sessionId': session})
        finally:
            try:
                if display_lease:
                    client.call('device.release_awake', {'leaseId': display_lease})
            finally:
                try:
                    if display:
                        client.call('remove_display', {'displayId': display['id'], 'uniqueId': display['uniqueId']})
                finally:
                    suite.finish()
