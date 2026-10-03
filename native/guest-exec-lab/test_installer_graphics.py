#!/usr/bin/env python3
"""Launch installer-owned catalog entries on an isolated display, without Desktop."""
import argparse
import base64
import importlib.util
import json
from pathlib import Path
import subprocess
import time
import tomllib


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--names', nargs='+', required=True)
    parser.add_argument('--entry', help='Optional desktop-file basename filter')
    parser.add_argument('--lease', help='Retain an existing phone awake lease')
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    spec = importlib.util.spec_from_file_location('transport', repo / 'scripts/mcp-client.py')
    transport = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(transport)
    config = tomllib.loads((Path.home() / '.codex/config.toml').read_text())['mcp_servers']['magicdesk']
    client = transport.Client(config['url'], config['http_headers']['Authorization'].removeprefix('Bearer '), timeout=180)
    report = {'checks': [], 'passed': False}
    display = lease = None
    sessions = []
    destination = repo / 'build' / ('installer-graphics-' + '-'.join(args.names) + '.json')

    def stop(session):
        if any(s['sessionId'] == session for s in client.call('graphics.list')['sessions']):
            try:
                client.call('graphics.stop', {'sessionId': session})
            except transport.ToolError:
                if any(s['sessionId'] == session for s in client.call('graphics.list')['sessions']):
                    raise
        wait('graphics_session_absent', sessionId=session)

    def wait(condition, **selection):
        # EVENT_WAIT: production observer state; timeout fails the selected launch.
        result = client.call('wait_for_state', {'condition': condition, 'timeoutMillis': 30000, **selection})
        assert result.get('matched'), result
        return result

    def until(read, predicate, label):
        # BOUNDED_STATE_WAIT: catalog publication/pixel capture has no pushed endpoint.
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            value = read()
            if predicate(value):
                return value
        raise AssertionError(label)

    try:
        state = client.call('get_state')
        assert state['shell']['uid'] == 2000 and not state['workspaces'] and not state['graphics'], state
        report['app'] = state['app']
        display = client.call('create_display', {'type': 'virtual', 'width': 1200, 'height': 800, 'densityDpi': 160})
        lease = client.call('device.keep_awake', {'durationMillis': 1800000,
                            **({'leaseId': args.lease} if args.lease else {'displayId': display['id']})})['leaseId']
        entries = []
        for name in args.names:
            catalog = client.call('list_desktop_entries', {'source': 'guest', 'query': '(' + name + ')', 'limit': 256})
            assert not catalog['truncated'], catalog
            selected = [e for e in catalog['entries'] if Path(e['desktopPath']).name.startswith('magicdesk-')
                        and (not args.entry or args.entry in Path(e['desktopPath']).name)]
            assert selected, catalog
            entries.extend(selected)
        for entry in entries:
            check = {'entry': entry, 'passed': False}
            session = None
            try:
                previous = {s['sessionId'] for s in client.call('graphics.list')['sessions']}
                client.call('launch_desktop_entry', {'source': 'guest', 'desktopPath': entry['desktopPath'],
                            'placement': 'display', 'displayId': display['id'], 'instance': 'new'})
                found = until(lambda: client.call('graphics.list')['sessions'],
                              lambda values: any(v['sessionId'] not in previous for v in values), 'session publication')
                session = next(s['sessionId'] for s in found if s['sessionId'] not in previous)
                sessions.append(session)
                wait('graphics_ready', sessionId=session)
                if entry['graphics'] == {'protocol': 'x11', 'mode': 'desktop'}:
                    window = 0
                else:
                    window = next(w['windowId'] for w in wait('graphics_window_present', sessionId=session)['session']['windows']
                                  if w['mapped'] and not w['parentWindowId'])
                hosts = wait('graphics_host_attached', sessionId=session, windowId=window)['matchingHosts']
                assert len(hosts) == 1 and not hosts[0]['managed'], hosts
                wait('app_ready', taskId=hosts[0]['taskId'], displayId=display['id'])

                def rendered():
                    snapshot = next(s for s in client.call('graphics.list')['sessions'] if s['sessionId'] == session)
                    if not any(h.get('content') and h['content']['width'] > 0 for h in snapshot['hosts']):
                        return None
                    capture = client.call_result('capture_screenshot', {'taskId': hosts[0]['taskId']})
                    data = base64.b64decode(next(c['data'] for c in capture['content'] if c['type'] == 'image'))
                    metrics = subprocess.run(['magick', 'png:-', '-crop', '1200x720+0+40',
                                              '-format', '%k ', '-write', 'info:', '-colorspace', 'HSL',
                                              '-channel', 'G', '-separate', '+channel', '-threshold', '2%',
                                              '-format', '%[fx:mean]', 'info:'],
                                             input=data, capture_output=True, check=True, timeout=20).stdout.split()
                    # Installer fixtures have colored icons/backgrounds; Android's
                    # grayscale starting placeholder must not count as client pixels.
                    return (data, snapshot) if int(metrics[0]) >= 32 and float(metrics[1]) > 0.0001 else None

                png, snapshot = until(rendered, bool, 'client pixels')
                filename = 'installer-' + entry['name'].replace('/', '_') + '.png'
                (repo / 'build' / filename).write_bytes(png)
                check.update({'passed': True, 'snapshot': snapshot, 'capture': filename})
            except Exception as error:
                check['error'] = str(error)
                check['graphics'] = client.call('graphics.list')
            finally:
                if session:
                    stop(session)
                    sessions.remove(session)
            report['checks'].append(check)
            destination.write_text(json.dumps(report, indent=2) + '\n')
            print(('PASS ' if check['passed'] else 'FAIL ') + entry['name'], flush=True)
        final = client.call('get_state')
        assert not final['workspaces'] and not final['homeLease'] and not final['graphics'], final
        report['passed'] = all(c['passed'] for c in report['checks'])
    finally:
        for session in sessions:
            stop(session)
        if lease and not args.lease:
            client.call('device.release_awake', {'leaseId': lease})
        if display:
            client.call('remove_display', {'displayId': display['id'], 'uniqueId': display['uniqueId']})
        destination.write_text(json.dumps(report, indent=2) + '\n')
        print(destination, flush=True)
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
