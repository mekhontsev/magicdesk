#!/usr/bin/env python3
"""Retained init fixture with an optional recipe desktop in an installed environment.

Default-target boot is independently checked by test_os_boot.py. This focused
session hosts the existing browser, portal, container and VS Code fixtures.
Use stop after inspection; state and logs are kept under build/installed-NAME.
"""
import argparse
import base64
import errno
import importlib.util
import json
from pathlib import Path
import re
import shlex
import subprocess
import threading
import time
import tomllib
import urllib.error
import urllib.request
import uuid

from graphical_recipe import compile_recipe
from init_fixture import installed_boot
from fixture_executor import FixtureExecutor
from test_oci_services import Suite, port


def controller_command(state, command, *, root=False, wait=False, ident=None):
    ident = ident or uuid.uuid4().hex
    request = urllib.request.Request('http://127.0.0.1:' + str(state['rootPort' if root else 'userPort']),
        data=json.dumps(dict(id=ident, command=command, wait=wait)).encode(),
        headers={'Authorization': 'Bearer ' + state['token']})
    # EVENT_WAIT: exact accepted command; never replay an uncertain POST.
    with urllib.request.urlopen(request, timeout=1900 if wait else 10) as response:
        result = json.load(response)
    assert result['id'] == ident, result
    return result


def wait_controller(state, timeout=90, *, user=False):
    deadline = time.monotonic() + timeout
    port_key, uid = ('userPort', state['userUid']) if user else ('rootPort', 0)
    request = urllib.request.Request('http://127.0.0.1:' + str(state[port_key]),
                                     headers={'Authorization': 'Bearer ' + state['token']})
    while True:
        remaining = deadline - time.monotonic()
        assert remaining > 0, port_key + ' controller did not become ready'
        try:
            with urllib.request.urlopen(request, timeout=min(remaining, 5)) as response:
                result = json.load(response)
            assert result['uid'] == uid, result
            return
        except urllib.error.URLError as error:
            if not isinstance(error.reason, OSError) or error.reason.errno not in (errno.ECONNREFUSED, errno.ECONNRESET):
                raise
        except ConnectionResetError:
            pass
        # PROTOCOL_BACKOFF: an unbound control socket (including a reserved
        # forwarding frontend) is not ready. The deadline
        # fails startup; success requires an authenticated response from this run.
        threading.Event().wait(min(0.1, remaining))


def attach_window(client, state, statefile):
    def wait(condition, **kwargs):
        # EVENT_WAIT: exact graphics state; timeout retains the failed fixture
        # for diagnosis and does not claim that its window became ready.
        value = client.call('wait_for_state', dict(condition=condition, timeoutMillis=60000,
                                                 sessionId=state['sessionId'], **kwargs))
        assert value['matched'], value
        return value

    if state.get('offscreen'):
        ready = wait('graphics_output_ready')
        state['windowId'] = ready['session']['offscreen']['outputs'][0]['windowId']
        statefile.write_text(json.dumps(state, indent=2))
        return
    ready = wait('graphics_window_present')
    window = next(w['windowId'] for w in ready['session']['windows'] if w['mapped'] and not w['parentWindowId'])
    display = state['display']
    attached = any(host.get('attached') and host.get('windowId') == window
                   and host.get('displayId') == display['id']
                   for host in ready['session'].get('hosts', []))
    if not attached:
        client.call('graphics.open_window', dict(sessionId=state['sessionId'], windowId=window,
                    placement='display', displayId=display['id'], uniqueId=display['uniqueId']))
    wait('graphics_host_attached', windowId=window)
    state['windowId'] = window
    statefile.write_text(json.dumps(state, indent=2))


def keep_display_awake(client, state, statefile, display=None):
    if state.get('headless') or state.get('offscreen'):
        return
    previous = state.get('awakeDisplayId', state['display']['id'])
    selected = previous if display is None else display
    if state.get('awakeLease'):
        current = client.call('get_state')['automationAwake']
        if not current.get('held'):
            del state['awakeLease']
            statefile.write_text(json.dumps(state, indent=2))
        elif current.get('leaseId') != state['awakeLease']:
            raise RuntimeError('another awake lease is held')
    if selected != previous and state.get('awakeLease'):
        client.call('device.release_awake', dict(leaseId=state['awakeLease']))
        del state['awakeLease']
        statefile.write_text(json.dumps(state, indent=2))
    request = dict(displayId=selected, durationMillis=1800000)
    if state.get('awakeLease'):
        request['leaseId'] = state['awakeLease']
    lease = client.call('device.keep_awake', request)
    assert lease['held'] and lease['displayId'] == request['displayId'], lease
    state['awakeLease'] = lease['leaseId']
    state['awakeDisplayId'] = selected
    statefile.write_text(json.dumps(state, indent=2))


def recipe_launch(shell, tools, recipe, log):
    # The nested non-interactive script does not inherit console-local variables.
    assert shell.startswith('/') and '\n' not in shell, 'Missing executor shell'
    # Android APK paths contain '='; env can mistake the executable for another
    # assignment. Shell assignment words have an unambiguous command boundary.
    return ('MAGICDESK_TOOLS=' + shlex.quote(tools)
            + ' MAGICDESK_ANDROID_SHELL=' + shlex.quote(shell) + ' '
            + shlex.join([shell, '-c', recipe])
            + ' >' + shlex.quote(log) + ' 2>&1')


def stop_owned_resources(client, shell, state, timeout=30):
    store = state['environment']['store']
    image = ([state['image']] if state.get('image') else
             [state['directory'] + '/libmagicdesk_guest_image.so'] if state.get('nativeBuild')
             else [state['runner'], 'image'])

    def launches():
        return [item for line in shell(shlex.join([*image, 'launches', store])).splitlines()
                if (item := json.loads(line))['label'] == state['id']]

    for launch in launches():
        shell(shlex.join([*image, 'stop', store, launch['launchId']]))
    deadline = time.monotonic() + timeout
    while launches():
        remaining = deadline - time.monotonic()
        assert remaining > 0, 'Owned guest launch did not stop'
        # STATE_POLL: image stop only sends SIGTERM. Require registry disappearance
        # before collecting logs; the deadline fails cleanup rather than claiming exit.
        threading.Event().wait(min(0.1, remaining))
    session = state.get('sessionId')
    if session:
        if any(item['sessionId'] == session for item in client.call('graphics.list')['sessions']):
            client.call('graphics.stop', dict(sessionId=session))
        # EVENT_WAIT: exact retained session disappears, otherwise cleanup fails.
        assert client.call('wait_for_state', dict(condition='graphics_session_absent',
            sessionId=session, timeoutMillis=30000))['matched']
    display = state.get('display')
    if display:
        if any(item['id'] == display['id'] and item['uniqueId'] == display['uniqueId']
               for item in client.call('list_displays')['displays']):
            client.call('remove_display', dict(displayId=display['id'], uniqueId=display['uniqueId']))
        # EVENT_WAIT: fixture display disappears, otherwise cleanup fails.
        assert client.call('wait_for_state', dict(condition='display_absent',
            displayId=display['id'], timeoutMillis=30000))['matched']
    if state.get('awakeDisplayId') == 0 and state.get('awakeLease'):
        lease = client.call('get_state')['automationAwake']
        if lease.get('leaseId') == state['awakeLease']:
            client.call('device.release_awake', dict(leaseId=state['awakeLease']))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('start', 'attach', 'run', 'shell', 'capture', 'status', 'stop', 'upload', 'download'))
    parser.add_argument('--name', required=True)
    parser.add_argument('--store', help='Existing store accessible to the selected executor; no import or copy')
    parser.add_argument('--output', type=Path, help='Retained fixture artifacts and session identity')
    FixtureExecutor.add_argument(parser)
    parser.add_argument('--user', default='mdtester')
    parser.add_argument('--root', action='store_true')
    parser.add_argument('--wait', action='store_true', help='Wait for run completion and retain its log')
    parser.add_argument('--timeout', type=int, default=300, help='Shell command failure bound in seconds')
    parser.add_argument('--init', choices=('systemd', 'openrc', 'runit'), default='systemd')
    parser.add_argument('--headless', action='store_true', help='Boot control/init only, without user session, display or GPU')
    parser.add_argument('--offscreen', action='store_true', help='Retain GUI output without an Android display; caller owns the awake lease')
    parser.add_argument('--network-profile', choices=('routed', 'bridge'), default='routed',
                        help='Headless network profile; bridge publishes only the control port')
    parser.add_argument('--desktop', choices=('weston', 'plasma-wayland'), default='weston')
    parser.add_argument('--build', type=Path, help='Stage a native build; otherwise use the installed APK')
    parser.add_argument('--entry', choices=('lazy', 'base'), default='lazy')
    parser.add_argument('--diagnostics', action='store_true', help='Trace the staged runtime; not for performance measurements')
    parser.add_argument('--statistics', action='store_true', help='Record native operation costs and entry counters at shutdown')
    parser.add_argument('--awake-display', choices=('phone', 'target'),
                        help='Start/attach: retain the unlocked phone or only the virtual target (default)')
    parser.add_argument('value', nargs='?')
    args = parser.parse_args()
    if args.headless and args.offscreen:
        parser.error('--offscreen requires a graphical session')
    if args.awake_display and args.action not in ('start', 'attach'):
        parser.error('--awake-display requires start or attach')
    if args.network_profile != 'routed' and not (args.action == 'start' and args.headless):
        parser.error('--network-profile requires headless start')
    if not re.fullmatch(r'[a-zA-Z0-9_-]+', args.name) or not re.fullmatch(r'[a-z_][a-z0-9_-]*', args.user):
        parser.error('Use a simple environment and guest account name')
    out = args.output or Path('build/installed-' + args.name)
    out.mkdir(parents=True, exist_ok=True)
    statefile = out / 'session.json'
    target = '/opt/md-installed-fixture'

    if args.action == 'start':
        if statefile.exists() and not json.loads(statefile.read_text()).get('closed'):
            parser.error('Stop the previous exact fixture before starting another')
        suite = Suite(out, args.build, executor=args.executor)
        client = suite.client
        before = client.call('get_state')
        if not args.headless and before['workspaces']:
            suite.finish()
            parser.error('Stop managed Desktop before starting an isolated desktop fixture')
        state = suite.report
        state.update(name=args.name, user=args.user, init=args.init, desktop=args.desktop,
                     networkProfile=args.network_profile,
                     headless=args.headless, offscreen=args.offscreen, entryMode=args.entry,
                     diagnostics=args.diagnostics, statistics=args.statistics, token=uuid.uuid4().hex,
                     rootPort=port(), userPort=port(), target=target)
        state['executor'] = suite.executor.evidence
        state['image'] = suite.image
        selected_shell = suite.executor.execute('printf "%s" "$MAGICDESK_ANDROID_SHELL"')
        assert selected_shell['exitCode'] == 0, selected_shell
        state['shell'] = selected_shell['output'].strip()
        state['homeLeaseBefore'] = before['homeLease']
        try:
            info = suite.installed_environment(args.name, args.store)
            assert not info['launches'], 'Environment must be idle before boot'
            store = info['store']
            entry = 'SHROOT_MACHINE_ENTRY=' + ('0' if args.entry == 'base' else '1')
            prefix = [suite.runner, '--store', store, '--user', '0:0', '--env', entry]
            system = suite.stdout(prefix + ['--env', 'LC_ALL=C', '--', '/bin/sh', '-ec',
                'if [ -e /nix/var/nix/profiles/system ]; then '
                '/nix/var/nix/profiles/system/sw/bin/readlink -f /nix/var/nix/profiles/system; fi']).strip()
            assert not system or (system.startswith('/nix/store/') and '\n' not in system), system
        except Exception as error:
            suite.report['preparationFailure'] = repr(error)
            suite.finish()
            raise
        state['environment'] = info
        binary = system + '/sw/bin' if system else '/usr/bin'
        guest_path = (binary + ':' if system else '') + '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'
        prefix += ['--env', 'PATH=' + guest_path]
        state['system'] = system or None
        state['runner'] = suite.runner
        state['prefix'] = prefix
        # Retain the exact owner even if preparation fails before guest launch.
        statefile.write_text(json.dumps(state, indent=2))
        state['commandConsoleId'] = (client.call('console.open', dict(backend='app'))['sessionId']
                                     if suite.executor.mode == 'app' else suite.console)
        statefile.write_text(json.dumps(state, indent=2))
        suite.command(prefix + ['--', binary + '/mkdir' if system else '/bin/mkdir', '-p', target])
        boot, driver = installed_boot(target, state['rootPort'], state['token'], args.init,
                                      None if args.headless else args.user, system or None,
                                      '0.0.0.0' if args.network_profile == 'bridge' else '127.0.0.1')
        if args.headless:
            files = {'boot.sh': boot, 'session-controller.py':
                     (Path(__file__).with_name('fixtures') / 'session-controller.py').read_text()}
            for name, content in files.items():
                path = out / name
                path.write_text(content)
                suite.upload(path, suite.base + '/' + name)
            suite.chmod('755', suite.base)
            suite.chmod('644', suite.base + '/boot.sh', suite.base + '/session-controller.py')
            command = shlex.join([binary + '/unshare', '--mount', '--pid', '--fork', '--mount-proc',
                                  '--uts', '--ipc', '--cgroup', '/bin/sh', target + '/boot.sh'])
            network = ['--network', args.network_profile]
            if args.network_profile == 'bridge':
                network += ['--publish', '127.0.0.1:%d:%d/tcp' % (state['rootPort'], state['rootPort'])]
            launch = shlex.join([*prefix, *(['--diagnostics'] if args.diagnostics else []),
                                *(['--statistics'] if args.statistics else []),
                                *network, '--env', 'MAGICDESK_GUEST_LABEL=' + state['id'],
                                '--bind', suite.base, target, '--', binary + '/script', '-q', '-e', '-c', command, '/dev/null'])
            state['launch'] = launch
            statefile.write_text(json.dumps(state, indent=2))
            result = client.call('console.execute', dict(sessionId=state['commandConsoleId'], command=
                launch + ' >' + shlex.quote(suite.base + '/boot.log') + ' 2>&1 </dev/null & echo $!'))
            assert result['exitCode'] == 0, result
            state['supervisorPid'] = int(result['output'].strip())
            statefile.write_text(json.dumps(state, indent=2))
            wait_controller(state)
            state['ready'] = True
            suite.kernel_profile.bind_session(lambda argv: suite.command(argv, quiet=True, raw=True), state)
            statefile.write_text(json.dumps(state, indent=2))
            print(statefile)
            return
        account = suite.stdout(prefix + ['--', binary + '/getent', 'passwd', args.user]).strip().split(':')
        assert len(account) == 7 and account[0] == args.user, 'Expected the selected guest account'
        state['userUid'] = int(account[2])
        assert state['userUid'] > 0, 'Desktop fixtures require an ordinary guest account'
        home = account[5]
        assert home.startswith('/home/')
        state['home'] = home
        user = shlex.quote(args.user)
        control = binary + '/python3 ' + target + '/session-controller.py ' + target
        if args.desktop == 'plasma-wayland':
            control = 'kde-inhibit --screenSaver ' + control
        autostart = ('#!/bin/sh\nset -eu\nenv > ' + target + '/desktop.env\n'
                     'magicdesk-desktop-session --ready\n'
                     'exec ' + control + ' ')
        autostart += str(state['userPort']) + ' ' + state['token'] + '\n'
        bus = ('export DBUS_SESSION_BUS_ADDRESS=unix:path=$XDG_RUNTIME_DIR/bus\n'
               if args.init == 'systemd' else 'unset DBUS_SESSION_BUS_ADDRESS\n')
        desktop = (f'weston --socket=wayland-1 --config={target}/weston.ini --width=1200 --height=800 --idle-time=0'
                   if args.desktop == 'weston' else 'plasma-wayland')
        configuration = '' if args.desktop == 'weston' else f'''export XDG_CONFIG_HOME={target}/desktop-config
mkdir -p "$XDG_CONFIG_HOME/autostart"
cp {target}/controller.desktop "$XDG_CONFIG_HOME/autostart/"
'''
        session = f'''#!/bin/bash
set -eux
export PATH={shlex.quote(guest_path)}
export HOME={shlex.quote(home)} USER={user} LOGNAME={user}
export XDG_RUNTIME_DIR=/run/user/$(id -u)
export MAGICDESK_AUDIO_PULSE="$(cat {target}/audio-endpoint)"
{bus.rstrip()}
export WAYLAND_DISPLAY=/tmp/magicdesk-wayland/wayland-0
{configuration}exec magicdesk-desktop-session {desktop}
'''
        files = {'boot.sh': boot, 'driver.sh': driver, 'session.sh': session, 'autostart.sh': autostart,
                 'controller.desktop': '[Desktop Entry]\nType=Application\nName=Shroot validation controller\n'
                     + 'Exec=/bin/sh ' + target + '/autostart.sh\n',
                 'weston.ini': '[core]\nxwayland=true\n[autolaunch]\npath=' + target + '/autostart.sh\n'}
        fixtures = Path(__file__).with_name('fixtures')
        for name in ('session-controller.py', 'sandbox-browser-gui.py', 'vscode-gui.py', 'vscode-debug.js',
                     'flatpak-chromium-smoke.sh', 'flatpak-chromium-zink.sh', 'sandbox-document-portal.sh',
                     'sandbox-portal-lifecycle.py'):
            files[name] = (fixtures / name).read_text()
        for name, content in files.items():
            path = out / name
            path.write_text(content)
            suite.upload(path, suite.base + '/' + name)
        suite.chmod('777', suite.base)
        suite.chmod('755', suite.base + '/autostart.sh')
        suite.command(['chmod', '-R', 'a+rX', suite.base])
        classes = out / 'classes'
        state['sourceHashes'] = compile_recipe(classes)
        boot_command = shlex.join([binary + '/unshare', '--mount', '--pid', '--fork', '--mount-proc',
                                  '--uts', '--ipc', '--cgroup', '/bin/sh', target + '/boot.sh'])
        command = shlex.join([binary + '/script', '-q', '-e', '-c', boot_command, '/dev/null'])
        recipe = subprocess.check_output(['java', '-cp', str(classes),
            'io.github.mekhontsev.magicdesk.GraphicalRecipe', 'routed', 'wayland', store, '/root', command, '0:0'], text=True)
        # The fixture calls the exact native runner. Reuse production connection
        # forwarding for both installed APK and staged runtime helpers.
        connections = subprocess.check_output(['java', '-cp', str(classes),
            'io.github.mekhontsev.magicdesk.GraphicalRecipe', 'connections'], text=True)
        wrapper = out / 'shroot'
        wrapper.write_text('#!/system/bin/sh\nmd_run() {\n' + connections + 'exec '
            + shlex.join([suite.runner, *(['--diagnostics'] if args.diagnostics else []),
                *(['--statistics'] if args.statistics else []),
                '--network', 'routed', '--env', entry, '--env', 'PATH=' + guest_path,
                '--env', 'MAGICDESK_GUEST_LABEL=' + state['id'], '--bind', suite.base, target])
            + ' "$@"\n}\nmd_run "$@"\n')
        suite.upload(wrapper, suite.base + '/shroot')
        suite.chmod('755', suite.base + '/shroot')
        before = client.call('get_state')
        # Independent protocol sessions may coexist. This fixture owns only
        # its exact session and display; managed Desktop must remain absent.
        assert not before['workspaces']
        state['homeLeaseBefore'] = before['homeLease']
        if not args.offscreen:
            state['display'] = client.call('create_display', dict(type='virtual', width=1200, height=800, densityDpi=160))
        statefile.write_text(json.dumps(state, indent=2))
        keep_display_awake(client, state, statefile, 0 if args.awake_display == 'phone' else None)
        state['sessionId'] = client.call('graphics.start', dict(protocol='wayland', backend=suite.executor.mode,
            connection='routed', wholeDesktop=True, name=args.name + ' ' + args.init, keyboardDirectory='guest:' + store,
            **({'offscreen': dict(width=1280, height=800)} if args.offscreen else {})))['sessionId']
        statefile.write_text(json.dumps(state, indent=2))

        def wait(condition, **kwargs):
            # EVENT_WAIT: exact graphics state; timeout fails startup.
            value = client.call('wait_for_state', dict(condition=condition, timeoutMillis=60000, **kwargs))
            assert value['matched'], value
            return value

        wait('graphics_ready', sessionId=state['sessionId'])
        launch = recipe_launch(state['shell'], suite.base, recipe, suite.base + '/boot.log')
        # The fixture carries the exact production session payload, not a
        # desktop Exec entry. Keep that payload in a file, outside Exec's limit.
        launcher = out / 'launch.sh'
        launcher.write_text('#!/system/bin/sh\n' + launch + '\n')
        suite.upload(launcher, suite.base + '/launch.sh')
        state['launch'] = launch
        statefile.write_text(json.dumps(state, indent=2))
        client.call('graphics.execute', dict(sessionId=state['sessionId'],
                    command=shlex.join(['/system/bin/sh', suite.base + '/launch.sh'])))
        attach_window(client, state, statefile)
        wait_controller(state)
        wait_controller(state, user=True)
        state['ready'] = True
        suite.kernel_profile.bind_session(lambda argv: suite.command(argv, quiet=True, raw=True), state)
        statefile.write_text(json.dumps(state, indent=2))
        print(statefile)
        return

    state = json.loads(statefile.read_text())
    spec = importlib.util.spec_from_file_location('transport', Path(__file__).resolve().parents[2] / 'scripts/mcp-client.py')
    transport = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(transport)
    config = tomllib.loads((Path.home() / '.codex/config.toml').read_text())['mcp_servers']['magicdesk']
    client = transport.Client(config['url'], config['http_headers']['Authorization'].removeprefix('Bearer '),
                              timeout=args.timeout+30, request_timeout=args.timeout)

    def shell(command):
        result = client.call('console.execute', dict(sessionId=state.get('commandConsoleId', state['consoleId']), command=command))
        assert result['exitCode'] == 0, result
        return result['output']

    def transfer(direction, source, destination, **options):
        if state.get('executor', {}).get('mode', 'shell') == 'shell':
            return getattr(transport, direction)(client, source, destination, **options)
        owner = FixtureExecutor(client, transport, out, 'app', 'transfer')
        try:
            return getattr(owner, direction)(source, destination, **options)
        finally:
            owner.close()

    if args.action == 'attach':
        assert not state.get('closed') and not state.get('headless'), 'No retained graphical fixture'
        selected = (0 if args.awake_display == 'phone' else state['display']['id']) if args.awake_display else None
        keep_display_awake(client, state, statefile, selected)
        attach_window(client, state, statefile)
        wait_controller(state)
        wait_controller(state, user=True)
        state['ready'] = True
        statefile.write_text(json.dumps(state, indent=2))
    elif args.action == 'run':
        assert args.root or not state.get('headless'), 'Headless fixture exposes only --root commands'
        keep_display_awake(client, state, statefile)
        ident = uuid.uuid4().hex
        print('Command log: ' + state['directory'] + '/' + ident + '.log', flush=True)
        response = controller_command(state, args.value, root=args.root, wait=args.wait, ident=ident)
        print(json.dumps(response))
        if args.wait:
            log=out / (ident + '.log')
            transfer('download', state['directory'] + '/' + ident + '.log', log)
            print(log.read_text(errors='replace'))
            raise SystemExit(response['exitCode'])
    elif args.action == 'shell':
        print(shell(args.value.replace('@BASE@', state['directory'])))
    elif args.action in ('upload', 'download'):
        path = Path(args.value)
        if args.action == 'upload':
            transfer('upload', path, state['directory'] + '/' + path.name, overwrite=True)
            shell(shlex.join(['chmod', '644', state['directory'] + '/' + path.name]))
        else:
            assert not path.is_absolute() and '..' not in path.parts
            destination = out / path
            destination.parent.mkdir(parents=True, exist_ok=True)
            transfer('download', state['directory'] + '/' + path.as_posix(), destination, overwrite=True)
    elif args.action == 'capture':
        assert not state.get('headless'), 'Headless fixture has no display'
        keep_display_awake(client, state, statefile)
        selection = (dict(graphicsSessionId=state['sessionId']) if state.get('offscreen')
                     else dict(displayId=state['display']['id']))
        result = client.call_result('capture_screenshot', selection)
        path = out / (args.value or 'desktop.png')
        path.write_bytes(base64.b64decode(next(i['data'] for i in result['content'] if i['type'] == 'image')))
        print(path)
    elif args.action == 'status':
        print(shell('tail -60 ' + shlex.quote(state['directory'] + '/boot.log')))
        print(json.dumps(client.call('graphics.list'), indent=2))
    elif args.action == 'stop':
        stop_owned_resources(client, shell, state)
        for name in ('boot.log', 'control.log', 'desktop.log', 'driver.log', 'desktop.env'):
            if shell('test ! -f ' + shlex.quote(state['directory'] + '/' + name) + ' || echo present').strip():
                transfer('download', state['directory'] + '/' + name, out / name, overwrite=True)
        after = client.call('get_state')
        client.call('console.close', dict(sessionId=state['consoleId']))
        if state.get('commandConsoleId') not in (None, state['consoleId']):
            client.call('console.close', dict(sessionId=state['commandConsoleId']))
        state['closed'] = True
        state['homeLeaseAfter'] = after['homeLease']
        statefile.write_text(json.dumps(state, indent=2))
        assert after['homeLease'] == state['homeLeaseBefore']
        if not state.get('headless'):
            assert not after['workspaces']


if __name__ == '__main__':
    main()
