#!/usr/bin/env python3
"""Installed catalog and exact launch ownership, without Desktop or a Termux executor."""
import argparse
import base64
import importlib.util
import json
import subprocess
from pathlib import Path
import shlex
import time
import tomllib


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lease')
    parser.add_argument('--names', nargs='+', default=['check-alpine-cold01', 'check-debian-cold01'])
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    spec = importlib.util.spec_from_file_location('transport', repo / 'scripts/mcp-client.py')
    transport = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(transport)
    config = tomllib.loads((Path.home() / '.codex/config.toml').read_text())['mcp_servers']['magicdesk']
    client = transport.Client(config['url'], config['http_headers']['Authorization'].removeprefix('Bearer '), timeout=180)
    report = {'checks': [], 'passed': False}
    sessions, operations = [], []
    display = console = terminal = lease = None

    def check(label, data):
        report['checks'].append({'check': label, 'data': data})
        print('PASS ' + label, flush=True)

    def wait(condition, **selection):
        result = client.call('wait_for_state', {'condition': condition, 'timeoutMillis': 30000, **selection})
        assert result.get('matched'), result
        return result

    def finish(operation):
        deadline = time.monotonic() + 60
        while operation['state'] == 'running' and time.monotonic() < deadline:
            operation = client.call('guest.status', {'operationId': operation['operationId'],
                                    'afterRevision': operation['revision'], 'timeoutMillis': 10000})
        assert operation['state'] != 'running', operation
        return operation

    def command(*arguments):
        result = client.call('console.execute', {'sessionId': console, 'command': shlex.join(arguments)})
        assert result['exitCode'] == 0, result
        return result['output']

    def until(read, predicate, label):
        # BOUNDED_STATE_WAIT: the native catalog has no pushed discovery endpoint.
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            result = read()
            if predicate(result): return result
        raise AssertionError(label)

    try:
        state = client.call('get_state')
        assert state['shell']['uid'] == 2000 and not state['workspaces'] and not state['graphics'], state
        report['app'] = state['app']
        lease = client.call('device.keep_awake', {'durationMillis': 1800000, **({'leaseId': args.lease} if args.lease else {})})['leaseId']
        console = client.call('console.open', {'directory': '/data/local/tmp'})['sessionId']
        name = args.names[0]
        for index in range(2):
            operation = client.call('guest.start', {'arguments': ['exec', name, '--', 'python3', '-u', '-c',
                       'import signal; print("OWNED",flush=True); signal.pause()']})
            operations.append(operation['operationId'])
            deadline = time.monotonic() + 30
            while 'OWNED' not in operation['output'] and operation['state'] == 'running' and time.monotonic() < deadline:
                operation = client.call('guest.status', {'operationId': operation['operationId'],
                                       'afterRevision': operation['revision'], 'timeoutMillis': 10000})
            assert 'OWNED' in operation['output'], operation
        live = client.call('guest.launches', {'name': name})['launches']
        assert len(live) == 2 and len({e['launchId'] for e in live}) == 2, live
        client.call('guest.stop', {'name': name, 'launchId': live[0]['launchId']})
        remaining = until(lambda: client.call('guest.launches', {'name': name})['launches'], lambda value: len(value) == 1, 'exact stop')
        assert remaining[0]['launchId'] == live[1]['launchId'], remaining
        command('magicdesk-guest', 'stop', name, remaining[0]['launchId'])
        for operation in operations: finish(client.call('guest.status', {'operationId': operation}))
        operations.clear()
        assert not client.call('guest.launches', {'name': name})['launches']
        check('MCP and CLI stop exact launches without touching their peer', live)

        display = client.call('create_display', {'type': 'virtual', 'width': 1000, 'height': 700, 'densityDpi': 160})
        terminal = client.call('terminal.open', {'backend': 'shell', 'command': 'magicdesk-guest login ' + shlex.quote(name),
                               'placement': 'display', 'displayId': display['id']})['terminalId']
        live = until(lambda: client.call('guest.launches', {'name': name})['launches'], bool, 'terminal ownership')
        assert len(live) == 1
        client.call('guest.stop', {'name': name, 'launchId': live[0]['launchId']})
        until(lambda: client.call('guest.launches', {'name': name})['launches'], lambda value: not value, 'terminal stop')
        client.call('terminal.close', {'terminalId': terminal})
        terminal = None
        check('interactive terminal shares native ownership', live)

        for name in args.names:
            descriptor = '[Desktop Entry]\nType=Application\nName=Catalog Wayland\nExec=mousepad\nIcon=org.xfce.mousepad\nX-MagicDesk-Graphics=wayland\n'
            command('magicdesk-guest', 'exec', name, '--', '/bin/sh', '-c',
                    'mkdir -p "$HOME/.local/share/applications"; printf %s ' + shlex.quote(descriptor)
                    + ' > "$HOME/.local/share/applications/md-catalog-test.desktop"')
        entries = client.call('list_desktop_entries', {'source': 'guest', 'limit': 256})['entries']
        selected = [e for e in entries if e['name'].startswith(('Mousepad (', 'Catalog Wayland ('))
                    and any(e['name'].endswith('(' + n + ')') for n in args.names)]
        assert len(selected) == 2 * len(args.names), selected
        assert len({e['desktopPath'] for e in selected}) == len(selected)
        for entry in selected:
            previous = {s['sessionId'] for s in client.call('graphics.list')['sessions']}
            client.call('launch_desktop_entry', {'source': 'guest', 'desktopPath': entry['desktopPath'],
                        'placement': 'display', 'displayId': display['id'], 'instance': 'new'})
            found = until(lambda: client.call('graphics.list')['sessions'],
                          lambda values: any(v['sessionId'] not in previous for v in values), 'graphics publication')
            session = next(s['sessionId'] for s in found if s['sessionId'] not in previous)
            sessions.append(session)
            wait('graphics_ready', sessionId=session)
            window = next(w['windowId'] for w in wait('graphics_window_present', sessionId=session)['session']['windows']
                          if w['mapped'] and not w['parentWindowId'])
            hosts = wait('graphics_host_attached', sessionId=session, windowId=window)['matchingHosts']
            assert len(hosts) == 1 and not hosts[0]['managed'], hosts
            wait('app_ready', taskId=hosts[0]['taskId'], displayId=display['id'])
            def rendered():
                current = next(s for s in client.call('graphics.list')['sessions'] if s['sessionId'] == session)
                if not any(h.get('content') and h['content']['width'] > 0 for h in current['hosts']): return None
                image = client.call_result('capture_screenshot', {'taskId': hosts[0]['taskId']})
                data = base64.b64decode(next(c['data'] for c in image['content'] if c['type'] == 'image'))
                # Exclude Android's small loading label and require actual content variation.
                colors = subprocess.run(['magick', 'png:-', '-crop', '1000x660+0+40', '-format', '%k', 'info:'],
                                        input=data, capture_output=True, check=True, timeout=20).stdout
                if int(colors) < 32: return None
                return data
            png = until(rendered, bool, 'first client frame')
            (repo / 'build' / ('catalog-' + str(len(report['checks'])) + '.png')).write_bytes(png)
            environment = next(n for n in args.names if entry['name'].endswith('(' + n + ')'))
            live = client.call('guest.launches', {'name': environment})['launches']
            assert len(live) == 1 and live[0]['executorUid'] == 2000, live
            assert live[0]['label'] == entry['name'], live
            client.call('guest.stop', {'name': environment, 'launchId': live[0]['launchId']})
            wait('graphics_session_absent', sessionId=session)
            sessions.remove(session)
            check(entry['name'] + ' ' + entry['graphics']['protocol'], {'entry': entry, 'launch': live, 'host': hosts})
        for name in args.names:
            command('magicdesk-guest', 'exec', name, '--', '/bin/sh', '-c', 'rm "$HOME/.local/share/applications/md-catalog-test.desktop"')
        fresh = client.call('list_desktop_entries', {'source': 'guest', 'query': 'Catalog Wayland'})
        assert fresh['total'] == 0, fresh
        state = client.call('get_state')
        assert not state['workspaces'] and not state['homeLease'], state
        check('explicit refresh removes deleted recipes and Desktop remains inactive', fresh)
        report['passed'] = True
    finally:
        for operation in operations: client.call('guest.cancel', {'operationId': operation})
        for session in sessions: client.call('graphics.stop', {'sessionId': session})
        if terminal: client.call('terminal.close', {'terminalId': terminal})
        if display: client.call('remove_display', {'displayId': display['id'], 'uniqueId': display['uniqueId']})
        if console: client.call('console.close', {'sessionId': console})
        if lease and not args.lease: client.call('device.release_awake', {'leaseId': lease})
        (repo / 'build/environment-catalog.json').write_text(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
