#!/usr/bin/env python3
"""Installed Linux clients using their recipe identity and graphics profile."""
import argparse
import base64
from pathlib import Path
import re
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
    parser.add_argument('--client', choices=['steam', 'glx', 'vulkan', 'gles'], required=True)
    parser.add_argument('--trace', action='store_true')
    parser.add_argument('--diagnostics', action='store_true', help='Enable verbose native diagnostics')
    parser.add_argument('--require-shm', action='store_true', help='Reject a profile disabling MIT-SHM')
    parser.add_argument('--installed', action='store_true', help='Use the installed APK runtime and shell launcher')
    parser.add_argument('--renderer', choices=['turnip', 'lavapipe'], default='turnip',
                        help='Require this installed graphics profile, never inject driver overrides')
    parser.add_argument('--timeout', type=int, default=180, help='Owned launch deadline, including client downloads')
    parser.add_argument('--window-title', help='Exact expected client window, rather than an updater or splash')
    parser.add_argument('--user', default='steamos')
    parser.add_argument('--home', default='/home/steamos')
    add_machine_entry_argument(parser)
    args = parser.parse_args()
    if not 1 <= args.timeout <= 1800:
        parser.error('--timeout must be between 1 and 1800 seconds')
    if args.machine_entry is not None and args.installed:
        parser.error('--machine-entry/--base require the native build')
    suite = Suite(args.build, None if args.installed else args.build, machine_entry=args.machine_entry)
    client = suite.client
    protocol = 'x11' if args.client in ('steam', 'glx') else 'wayland'
    session = display = display_lease = None
    launch_attempted = False
    owned_executable = suite.runner
    log, receipt, pidfile = [suite.base + '/' + n for n in ('client.log', 'exit', 'pid')]
    suite.report.update(store=args.store, probe='steamos-' + args.client,
                        protocol=protocol, renderer=args.renderer, sandboxFlagsDisabled=False)
    print('Fixture:', suite.base, flush=True)

    def wait(condition, **selection):
        # EVENT_WAIT: production catalog/host event; timeout fails observation.
        deadline = time.monotonic() + (args.timeout if condition == 'graphics_window_present' else 60)
        while True:
            remaining = deadline - time.monotonic()
            assert remaining > 0, 'Deadline waiting for ' + condition
            value = client.call('wait_for_state', dict(condition=condition,
                timeoutMillis=max(1, min(60000, int(remaining * 1000))), **selection))
            if value.get('matched'):
                return value
            ended = suite.command(['/system/bin/sh', '-c',
                'test ! -f "$1" || cat "$1"', 'fixture', receipt], quiet=True).strip()
            assert not ended, 'Client exited before window: ' + ended

    try:
        before = client.call('get_state')
        assert before['readiness']['interactive'] and not before['readiness']['deviceLocked']
        if args.installed:
            # Resolve the immutable native executable through the same APK
            # preparation owner as the launcher, for exact cleanup identity.
            runtime = suite.command(['/system/bin/sh', '-ec',
                'CLASSPATH="$MAGICDESK_COMMAND_APK" /system/bin/app_process / '
                'io.github.mekhontsev.magicdesk.GuestRuntimeMain "$MAGICDESK_GUEST_LIBRARIES" '
                '"$MAGICDESK_RUNTIME/guest-runtime"'], quiet=True).strip()
            owned_executable = runtime + '/libmagicdesk_guest_run.so'
        classes = args.build / 'recipe-classes'
        suite.report['connectionSourceHashes'] = compile_recipe(classes)
        suite.command(suite.image_command('export-tree', args.store, '/usr/share/X11/xkb', suite.base + '/xkb'))
        with tempfile.TemporaryDirectory() as temporary:
            wrapper = Path(temporary) / 'shroot'
            wrapper.write_text('#!/system/bin/sh\nprintf "%s\\n" "$$" > ' + shlex.quote(pidfile)
                               + '\nexec ' + shlex.quote(suite.runner)
                               + (' ' + shlex.join(suite.entry_options) if suite.entry_options else '')
                               + (' --diagnostics' if args.diagnostics else '')
                               + ' --deadline-seconds ' + str(args.timeout) + ' "$@"\n')
            suite.upload(wrapper, suite.base + '/shroot')
        suite.upload(args.build / 'md-await-exit', suite.base + '/md-await-exit')
        suite.chmod('700', suite.base + '/shroot', suite.base + '/md-await-exit')
        display = client.call('create_display', dict(type='virtual', width=1280, height=800, densityDpi=160))
        display_lease = client.call('device.keep_awake',
            dict(displayId=display['id'], durationMillis=1800000))['leaseId']
        session = client.call('graphics.start', dict(protocol=protocol, backend='shell', connection='routed',
            name='SteamOS ARM64 ' + args.client, keyboardDirectory=suite.base + '/xkb'))['sessionId']
        suite.report['sessionId'] = session
        state = wait('graphics_ready', sessionId=session)['session']
        assert state['executorUid'] == 2000 and state['serverUid'] not in (0, 2000)
        programs = {
            'steam': 'exec /usr/bin/steam',
            'glx': 'glxinfo -B; exec glxgears -info',
            'vulkan': 'vulkaninfo --summary; '
                      'if command -v vkcube-wayland >/dev/null; then '
                      'exec vkcube-wayland --width 640 --height 480 --c 1800; '
                      'else exec vkcube --wsi wayland --width 640 --height 480 --c 1800; fi',
            'gles': 'eglinfo -B -p wayland; exec es2gears_wayland -info',
        }
        program = programs[args.client]
        if args.require_shm:
            program = 'case ",${MESA_VK_WSI_DEBUG-}," in *,noshm,*) exit 98;; esac; ' + program
        if args.trace:
            program = 'exec strace -f -tt -s 160 -o /tmp/md-steamos.trace /bin/sh -ec ' + shlex.quote(program)
        recipe = subprocess.check_output(['java', '-cp', str(args.build / 'recipe-classes'),
            'io.github.mekhontsev.magicdesk.GraphicalRecipe', 'routed', protocol, args.store,
            args.home, program, args.user], text=True, timeout=20)
        launch = ('{ env PATH=' + shlex.quote(suite.base + ':/system/bin')
                  + ' /system/bin/sh -c ' + shlex.quote(recipe)
                  + '; r=$?; printf "%s\\n" "$r" > ' + shlex.quote(receipt)
                  + '; } > ' + shlex.quote(log) + ' 2>&1')
        suite.report['launch'] = launch
        launch_attempted = True
        client.call('graphics.execute', dict(sessionId=session, command=launch))
        selection = dict(windowTitle=args.window_title) if args.window_title else {}
        mapped = wait('graphics_window_present', sessionId=session, **selection)['session']
        window = next(w for w in mapped['windows'] if w['mapped'] and not w['parentWindowId']
                      and (not args.window_title or w['title'] == args.window_title))
        suite.report['window'] = window
        client.call('graphics.open_window', dict(sessionId=session, windowId=window['windowId'],
                    placement='display', displayId=display['id'], uniqueId=display['uniqueId']))
        host = wait('graphics_host_attached', sessionId=session, windowId=window['windowId'])['matchingHosts'][0]
        suite.report['host'] = host
        wait('app_ready', taskId=host['taskId'], displayId=display['id'])
        previous = None
        for frame in range(2):
            # Bounded pixel observation: attachment precedes the first rendered
            # frame. Each capture is a fresh compositor snapshot; no settle delay.
            deadline = time.monotonic() + 60
            while True:
                result = client.call_result('capture_screenshot', dict(taskId=host['taskId']))
                png = base64.b64decode(next(c['data'] for c in result['content'] if c['type'] == 'image'))
                rgb = subprocess.check_output(['magick', 'png:-', '-depth', '8', 'rgb:-'], input=png)
                pixels = list(zip(rgb[::3], rgb[1::3], rgb[2::3]))
                populated = args.client != 'steam' or sum(max(pixel) > 8 for pixel in pixels) > len(pixels) // 5
                if populated and len(set(pixels)) > 100:
                    break
                assert time.monotonic() < deadline, 'Window remained blank or at its initial loading indicator'
            path = args.build / ('steamos-' + args.client + '-' + suite.report['id'] + '-' + str(frame) + '.png')
            path.write_bytes(png)
            suite.report.setdefault('captures', []).append(str(path))
            print('Capture:', path, flush=True)
            if previous is not None and args.client != 'steam':
                assert previous != rgb, 'Graphics did not change between captures'
            previous = rgb
        suite.report['mappedAndCaptured'] = True
        if args.client != 'steam':
            output = suite.command(['cat', log], quiet=True)
            if args.client == 'vulkan':
                renderer = ('DRIVER_ID_MESA_TURNIP' if args.renderer == 'turnip'
                            else 'DRIVER_ID_MESA_LLVMPIPE') in output
            else:
                renderers = re.findall(r'(?im)^.*(?:GL_RENDERER|OpenGL.*renderer)\s*[:=]\s*(.+)$', output)
                renderer = any((re.search(r'(?i)\b(?:Adreno|Turnip)\b', value)
                                and not re.search(r'(?i)llvmpipe|softpipe', value))
                               if args.renderer == 'turnip' else
                               re.search(r'(?i)zink.*llvmpipe', value) for value in renderers)
            assert renderer and 'supervisor failure' not in output, 'No confirmed ' + args.renderer + ' renderer'
        after = client.call('get_state')
        assert before['homeLease'] == after['homeLease'] and before['workspaces'] == after['workspaces']
        suite.report['passed'] = args.client != 'steam' or bool(args.window_title)
        if args.client == 'steam':
            suite.report['coverage'] = 'Requested window rendered; no account login or games tested'
    except Exception as error:
        suite.report['failure'] = repr(error)
        raise
    finally:
        errors = []
        try:
            suite.command(['/system/bin/sh', '-ec', '''
if test -f "$1"; then
    pid=$(cat "$1")
    if test "$(readlink "/proc/$pid/exe")" = "$2"; then
        kill -TERM "$pid"
    fi
    timeout 200 "$3" "$4" || test -s "$4"
fi
''', 'fixture', pidfile, owned_executable, suite.base + '/md-await-exit', receipt], timeout=210)
            if launch_attempted:
                local_log = args.build / ('steamos-' + args.client + '-' + suite.report['id'] + '.log')
                suite.download(log, local_log, overwrite=True)
                suite.report['logFile'] = str(local_log)
                suite.report['logTail'] = '\n'.join(local_log.read_text(errors='replace').splitlines()[-200:])
                suite.record_entry(local_log.read_text(errors='replace'))
                suite.report['exit'] = suite.command(['cat', receipt], quiet=True).strip()
            if args.trace and launch_attempted:
                suite.report['trace'] = suite.command([suite.runner, '--store', args.store,
                    '--user', '0:0', '--deadline-seconds', '20', '--', '/usr/bin/tail', '-400', '/tmp/md-steamos.trace'], quiet=True)
        except Exception as error:
            errors.append(str(error))
        for action in ([lambda: client.call('graphics.stop', dict(sessionId=session)),
                        lambda: wait('graphics_session_absent', sessionId=session)] if session else []) + (
                       [lambda: client.call('device.release_awake', dict(leaseId=display_lease))]
                       if display_lease else []) + (
                       [lambda: client.call('remove_display', dict(displayId=display['id'], uniqueId=display['uniqueId'])),
                        lambda: wait('display_absent', displayId=display['id'])] if display else []):
            try:
                action()
            except Exception as error:
                errors.append(str(error))
        suite.report['cleanupErrors'] = errors
        suite.report['passed'] &= not errors
        suite.finish()
        if errors:
            raise RuntimeError(errors)


if __name__ == '__main__':
    main()
