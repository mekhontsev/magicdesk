#!/usr/bin/env python3
"""Cold named Debian/Alpine workflows through installed MCP, CLI and Start recipes."""
import argparse
import base64
import importlib.util
import json
from pathlib import Path
import shlex
import subprocess
import time
import tomllib
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dns', default='system')
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--lease', help='Reuse and renew an existing wake lease; do not release it')
    parser.add_argument('--tag', default=uuid.uuid4().hex[:12])
    parser.add_argument('--stage', choices=['prepare', 'accounts', 'files', 'graphics', 'backup', 'all'], default='all')
    args = parser.parse_args()
    assert args.tag.isalnum() and len(args.tag) <= 32
    repo = Path(__file__).resolve().parents[2]
    if args.stage in ('graphics', 'backup', 'all'):
        sources = ['LinuxGraphicalEnvironment', 'GuestGraphicalConnection', 'GuestLaunchPlan',
                   'GuestEnvironment', 'GraphicalProtocol', 'ShellCommandLine']
        subprocess.run(['javac', '-d', str(repo / 'build/guest-workflow-recipes'),
                        str(repo / 'native/guest-exec-lab/GraphicalRecipe.java'),
                        *(str(repo / ('app/src/main/java/io/github/mekhontsev/magicdesk/' + name + '.java')) for name in sources)], check=True)
    spec = importlib.util.spec_from_file_location('transport', repo / 'scripts/mcp-client.py')
    transport = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(transport)
    config = tomllib.loads((Path.home() / '.codex/config.toml').read_text())['mcp_servers']['magicdesk']
    client = transport.Client(config['url'], config['http_headers']['Authorization'].removeprefix('Bearer '),
                              timeout=600, request_timeout=600)
    if args.apk:
        transport.update(client, args.apk, 'guest-workflows-' + uuid.uuid4().hex)
    state = client.call('get_state')
    assert state['shell']['uid'] == 2000 and not state['workspaces']
    report = {'tag': args.tag, 'stage': args.stage, 'app': state['app'], 'checks': [], 'passed': False}
    report_path = repo / 'build' / ('environment-workflow-' + args.tag + '-' + args.stage + '.json')
    root = '/data/local/tmp/md-workflow-' + args.tag
    names = {distro: 'check-' + distro + '-' + args.tag for distro in ('debian', 'alpine')}
    console, display, lease = None, None, None
    sessions = []
    operations = set()
    clipboard = None

    def save():
        report_path.write_text(json.dumps(report, indent=2) + '\n')

    def command(*argv):
        result = client.call('console.execute', {'sessionId': console, 'command': shlex.join(argv)})
        assert result['exitCode'] == 0, result
        return result['output']

    def finish(operation, success=True, bound=900):
        operations.add(operation['operationId'])
        report['activeOperation'] = operation
        save()
        deadline = time.monotonic() + bound
        while operation['state'] == 'running':
            assert time.monotonic() < deadline, operation
            # EVENT_WAIT: exact command output/exit revision; observation timeout is not completion.
            operation = client.call('guest.status', {'operationId': operation['operationId'],
                'afterRevision': operation['revision'], 'timeoutMillis': 30000})
            report['activeOperation'] = operation
            save()
        report['checks'].append(operation)
        operations.discard(operation['operationId'])
        save()
        print(operation['operationId'], operation['state'], operation['output'][-1800:], flush=True)
        assert (operation['state'] == 'completed') == success, operation
        return operation

    def guest(*arguments, success=True):
        return finish(client.call('guest.start', {'arguments': list(arguments)}), success)

    def shell(name, script):
        return guest('exec', name, '--', '/bin/sh', '-c', script)['output']

    def wait(condition, **selection):
        result = client.call('wait_for_state', {'condition': condition, 'timeoutMillis': 30000, **selection})
        assert result.get('matched'), result
        return result

    def keys(*values):
        client.call('input.key_chord', {'displayId': display['id'], 'keys': list(values)})

    def launch(name, protocol):
        entry = next(e for e in client.call('guest.list')['environments'] if e['name'] == name)
        recipe = subprocess.check_output(['java', '-cp', str(repo / 'build/guest-workflow-recipes'),
            'io.github.mekhontsev.magicdesk.GraphicalRecipe', 'managed', protocol, entry['store'],
            'export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe; exec mousepad /tmp/workflow.txt'],
            text=True, timeout=20)
        # Desktop Entry argv encoding, then its outer string-value escaping.
        words = ['"' + word.replace('\\', '\\\\').replace('"', '\\"').replace('$', '\\$')
                 .replace('`', '\\`').replace('%', '%%') + '"' for word in ['sh', '-c', recipe]]
        values = {'Type': 'Application', 'Name': name + '-' + protocol, 'Exec': ' '.join(words),
                  'Terminal': 'false', 'X-MagicDesk-ExecBackend': 'shell', 'X-MagicDesk-ExecSyntax': 'argv',
                  'X-MagicDesk-Graphics': protocol, 'X-MagicDesk-GraphicsMode': 'application',
                  'X-MagicDesk-GraphicsConnection': 'routed', 'X-MagicDesk-KeyboardDirectory': 'guest:' + entry['store'],
                  'X-MagicDesk-FileEnvironment': 'GUEST:' + str(len(entry['store'])) + ':' + entry['store'] + ':'}
        desktop = '[Desktop Entry]\n' + ''.join(k + '=' + v.replace('\\', '\\\\').replace('\n', '\\n') + '\n' for k, v in values.items())
        path = root + '/' + name + '-' + protocol + '.desktop'
        command('/system/bin/sh', '-c', 'printf %s ' + shlex.quote(desktop) + ' > ' + shlex.quote(path))
        previous = {s['sessionId'] for s in client.call('graphics.list')['sessions']}
        client.call('launch_desktop_entry', {'desktopPath': path, 'placement': 'display', 'displayId': display['id'], 'instance': 'new'})
        # BOUNDED_STATE_WAIT: recipe launch publishes a session asynchronously; no catalog event endpoint.
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            found = [s for s in client.call('graphics.list')['sessions'] if s['sessionId'] not in previous and s['name'] == values['Name']]
            if found:
                break
        else:
            raise AssertionError('No session published for ' + path)
        assert len(found) == 1, found
        session = found[0]['sessionId']
        sessions.append(session)
        wait('graphics_ready', sessionId=session)
        mapped = wait('graphics_window_present', sessionId=session)['session']
        windows = [w for w in mapped['windows'] if w['mapped'] and not w['parentWindowId']]
        assert len(windows) == 1, windows
        window = windows[0]['windowId']
        hosts = wait('graphics_host_attached', sessionId=session, windowId=window)['matchingHosts']
        assert len(hosts) == 1 and not hosts[0]['managed'], hosts
        wait('task_focused', taskId=hosts[0]['taskId'], displayId=display['id'])
        wait('app_ready', taskId=hosts[0]['taskId'], displayId=display['id'])
        return session, window, hosts[0]['taskId']

    def edit(name, protocol, launched):
        nonlocal clipboard
        session, window, task = launched
        text = name + '-' + protocol + '\n'
        if clipboard is None:
            clipboard = client.call('clipboard.read_text')
            assert clipboard.get('access') in ('available', 'empty') and not clipboard.get('truncated'), clipboard
            assert not clipboard.get('itemCount') or clipboard.get('mimeTypes') == ['text/plain'], 'Cannot preserve non-text clipboard'
        client.call('clipboard.write_text', {'text': text})
        keys('CTRL_LEFT', 'A')
        keys('CTRL_LEFT', 'V')
        keys('CTRL_LEFT', 'MOVE_HOME')
        keys('G')
        text = 'g' + text
        keys('CTRL_LEFT', 'S')
        keys('CTRL_LEFT', 'A')
        keys('CTRL_LEFT', 'C')
        copied = client.call('clipboard.read_text', {'expectedText': text, 'timeoutMillis': 10000})
        assert copied.get('matched'), copied
        # An independent process opens the document, so a rendered edit alone cannot pass.
        output = shell(name, 'cat /tmp/workflow.txt')
        assert output == text, repr(output)
        image = client.call_result('capture_screenshot', {'taskId': task})
        png = base64.b64decode(next(c['data'] for c in image['content'] if c['type'] == 'image'))
        image_path = repo / 'build' / (name + '-' + protocol + '.png')
        image_path.write_bytes(png)
        report['checks'].append({'environment': name, 'protocol': protocol, 'session': session,
                                 'task': task, 'text': text, 'screenshot': str(image_path)})
        client.call('graphics.close_window', {'sessionId': session, 'windowId': window})
        wait('graphics_session_absent', sessionId=session)
        sessions.remove(session)

    try:
        lease = client.call('device.keep_awake', {'durationMillis': 1800000, **({'leaseId': args.lease} if args.lease else {})})['leaseId']
        console = client.call('console.open', {'directory': '/data/local/tmp'})['sessionId']
        command('mkdir', '-p', root)
        if args.stage in ('prepare', 'all'):
            for distro, name in names.items():
                image = 'debian:trixie-slim' if distro == 'debian' else 'alpine:3.23'
                guest('install', image, '--name', name, '--dns', args.dns)
                shell(name, 'set -eu; test "$HOME" = /root; test "$USER" = root; test -x "$SHELL"; '
                           'printf "seed\\n" > /tmp/workflow.txt; cat /etc/resolv.conf')
                login = command('magicdesk-guest', 'login', name, '--', '/bin/sh', '-c',
                                'test "$PWD" = "$HOME" && printf "%s:%s:%s" "$USER" "$HOME" "$SHELL"')
                assert login.startswith('root:/root:'), login
                packages = 'apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends mousepad thunar dbus-x11 xkb-data fonts-dejavu-core ca-certificates python3-gi' if distro == 'debian' else 'apk add mousepad thunar dbus xkeyboard-config font-dejavu ca-certificates py3-gobject3'
                shell(name, 'set -eu; ' + packages)
            # Same store-independent command at the same pathname, two supervised trees.
            held = [client.call('guest.start', {'arguments': ['exec', name, '--', '/bin/sh', '-c',
                    'printf ready; exec tail -f /dev/null']}) for name in names.values()]
            operations.update(operation['operationId'] for operation in held)
            for operation in held:
                deadline = time.monotonic() + 30
                while 'ready' not in operation['output']:
                    assert time.monotonic() < deadline, operation
                    # EVENT_WAIT: command output revision; missing readiness fails the fixture.
                    operation = client.call('guest.status', {'operationId': operation['operationId'], 'afterRevision': operation['revision'], 'timeoutMillis': 10000})
                    assert operation['state'] == 'running', operation
                client.call('guest.cancel', {'operationId': operation['operationId']})
                finish(operation, success=False, bound=30)
        if args.stage in ('accounts', 'all'):
            for name in names.values():
                shell(name, "set -eu; if ! grep -q '^mdworkflow:' /etc/passwd; then "
                            "printf 'mdworkflow:x:1234:1234:Workflow:/home/mdworkflow:/bin/sh\\n' >> /etc/passwd; "
                            "printf 'mdworkflow:x:1234:\\n' >> /etc/group; fi; "
                            "mkdir -p /home/mdworkflow; chown 1234:1234 /home/mdworkflow")
                actual = command('magicdesk-guest', 'login', name, '--user', 'mdworkflow', '--', '/bin/sh', '-c',
                                 'set -eu; test "$(id -u)" = 1234; test "$PWD" = "$HOME"; '
                                 'printf "%s:%s:%s" "$USER" "$HOME" "$SHELL"')
                assert actual == 'mdworkflow:/home/mdworkflow:/bin/sh', actual
                report['checks'].append({'account': name, 'login': actual})
        if args.stage in ('files', 'all'):
            for name in names.values():
                entry = next(e for e in client.call('guest.list')['environments'] if e['name'] == name)
                receipt = command('am', 'instrument', '--no-restart', '-w', '-e', 'store', entry['store'],
                                  'io.github.mekhontsev.magicdesk/.GuestFilesInstrumentation')
                assert 'guest_files=PASS' in receipt and 'INSTRUMENTATION_CODE: -1' in receipt, receipt
                report['checks'].append({'files': name, 'receipt': receipt})
        if args.stage in ('graphics', 'all'):
            if display is None:
                display = client.call('create_display', {'type': 'virtual', 'width': 1000, 'height': 700, 'densityDpi': 160})
            for protocol in ('x11', 'wayland'):
                first = launch(names['debian'], protocol)
                second = launch(names['alpine'], protocol)
                live = client.call('graphics.list')['sessions']
                assert {first[0], second[0]} <= {s['sessionId'] for s in live if s['ready']}
                edit(names['alpine'], protocol, second)
                client.call('launch_desktop_entry', {'desktopPath': root + '/' + names['debian'] + '-' + protocol + '.desktop',
                    'placement': 'display', 'displayId': display['id'], 'instance': 'reuse'})
                wait('task_focused', taskId=first[2], displayId=display['id'])
                edit(names['debian'], protocol, first)
        if args.stage in ('backup', 'all'):
            for name in names.values():
                expected = shell(name, 'cat /tmp/workflow.txt')
                original = next(e for e in client.call('guest.list')['environments'] if e['name'] == name)
                archive = root + '/' + name + '.tar.zst'
                guest('backup', name, archive)
                guest('remove', name)
                guest('restore', archive, '--name', name)
                restored = next(e for e in client.call('guest.list')['environments'] if e['name'] == name)
                assert restored['id'] != original['id'] and restored['store'] != original['store']
                assert shell(name, 'cat /tmp/workflow.txt') == expected
            if display is None:
                display = client.call('create_display', {'type': 'virtual', 'width': 1000, 'height': 700, 'densityDpi': 160})
            for distro, name in names.items():
                protocol = 'x11' if distro == 'debian' else 'wayland'
                edit(name, protocol, launch(name, protocol))
        final = client.call('get_state')
        assert final['homeLease'] == state['homeLease'] and final['workspaces'] == state['workspaces']
        report['passed'] = True
    except Exception as error:
        report['failure'] = str(error)
        raise
    finally:
        for operation in operations.copy():
            try:
                client.call('guest.cancel', {'operationId': operation})
                finish(client.call('guest.status', {'operationId': operation}), success=False, bound=30)
            except Exception as error:
                report.setdefault('cleanupErrors', []).append(str(error))
        for session in sessions:
            try:
                client.call('graphics.stop', {'sessionId': session})
                wait('graphics_session_absent', sessionId=session)
            except Exception as error:
                report.setdefault('cleanupErrors', []).append(str(error))
        if display:
            client.call('remove_display', {'displayId': display['id'], 'uniqueId': display['uniqueId']})
        if console:
            client.call('console.close', {'sessionId': console})
        if clipboard is not None:
            if clipboard.get('text'):
                client.call('clipboard.write_text', {'text': clipboard['text'], 'sensitive': clipboard.get('sensitive', False)})
            else:
                client.call('clipboard.clear')
        if lease and not args.lease:
            client.call('device.release_awake', {'leaseId': lease})
        save()
        print(report_path, flush=True)


if __name__ == '__main__':
    main()
