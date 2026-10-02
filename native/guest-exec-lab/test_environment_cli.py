#!/usr/bin/env python3
"""Installed named-environment CLI, actual UID 2000, no Termux execution or Desktop."""
import argparse
import importlib.util
import json
from pathlib import Path
import shlex
import tomllib
import threading
import http.server
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--images', nargs='+', default=['alpine:3.23', 'debian:trixie-slim'])
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    spec = importlib.util.spec_from_file_location('transport', repo / 'scripts/mcp-client.py')
    transport = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(transport)
    config = tomllib.loads((Path.home() / '.codex/config.toml').read_text())['mcp_servers']['magicdesk']
    client = transport.Client(config['url'], config['http_headers']['Authorization'].removeprefix('Bearer '),
                              timeout=600, request_timeout=600)
    tag = uuid.uuid4().hex
    directory = '/data/local/tmp/md-environment-cli-' + tag
    report = {'id': tag, 'directory': directory, 'checks': [], 'passed': False}
    console = None
    lease = None
    try:
        if args.apk:
            report['update'] = transport.update(client, args.apk, 'guest-manager-' + tag)
        state = client.call('get_state')
        assert state['shell']['uid'] == 2000
        assert not state['workspaces'] and not state['graphics']
        report['app'], report['device'] = state['app'], state['device']
        lease = client.call('device.keep_awake', {'durationMillis': 1800000})['leaseId']
        report['leaseId'] = lease
        console = client.call('console.open', {'directory': '/data/local/tmp'})['sessionId']

        def command(argv, success=True):
            text = shlex.join(['timeout', '480', *map(str, argv)])
            result = client.call('console.execute', {'sessionId': console, 'command': text})
            report['checks'].append({'command': text, **result})
            print(result['output'], end='', flush=True)
            assert (result['exitCode'] == 0) == success, result
            return result['output']

        def guest(*argv, success=True):
            return command(['env', 'MAGICDESK_GUEST_HOME=' + directory + '/library',
                            'magicdesk-guest', *argv], success=success)

        command(['mkdir', '-p', directory + '/rw', directory + '/ro'])
        assert command(['id', '-u']).strip() == '2000'
        guest('--help')
        names = []
        for index, image in enumerate(args.images):
            client.call('device.keep_awake', {'durationMillis': 1800000, 'leaseId': lease})
            name = 'linux-' + str(index)
            guest('install', image, '--name', name)
            names.append(name)
            output = guest('exec', name, '--', '/bin/sh', '-c',
                           'set -eu\ntest "$(id -u)" = 0\nmkdir -p /mnt /media\n'
                           'printf private >/tmp/manager-value\ncat /etc/os-release\n')
            assert 'ID=' in output
            guest('exec', name, '--bind', directory + '/rw', '/mnt', '--bind-ro', directory + '/ro', '/media',
                  '--', '/bin/sh', '-c', 'set -eu\nprintf attached >/mnt/value\n'
                  'if printf forbidden >/media/value; then exit 90; fi\nprintf "PASS binds\\n"')
            assert command(['cat', directory + '/rw/value']) == 'attached'
            guest('login', name, '--', '/bin/sh', '-c', 'test "$(cat /tmp/manager-value)" = private')
            guest('run', name, '--', '/bin/sh', '-c', 'test "$(cat /tmp/manager-value)" = private')
            inspected = json.loads(guest('inspect', name))
            assert inspected['kind'] == 'instance' and inspected['guestUsers'] == 1
        guest('install', args.images[0], '--name', 'independent')
        names.append('independent')
        guest('exec', 'independent', '--', '/bin/sh', '-c', 'test ! -e /tmp/manager-value')
        guest('install', args.images[0], '--name', 'independent', success=False)
        assert len(guest('list').strip().splitlines()) == len(names)
        ready, release = threading.Event(), threading.Event()
        class Hold(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                ready.set()
                # EVENT_WAIT: fixture release after maintenance checks; timeout fails this rendezvous.
                if not release.wait(90):
                    self.send_error(504)
                    return
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b'released\n')
            def log_message(self, *unused):
                pass
        server = http.server.HTTPServer(('127.0.0.1', 0), Hold)
        server.timeout = 90
        serving = threading.Thread(target=server.handle_request)
        holding = client.call('console.open', {'directory': '/data/local/tmp'})['sessionId']
        held_result = []
        def hold():
            try:
                text = shlex.join(['timeout', '120', 'env', 'MAGICDESK_GUEST_HOME=' + directory + '/library',
                                  'magicdesk-guest', 'exec', names[0], '--', 'wget', '-qO-',
                                  'http://127.0.0.1:' + str(server.server_port) + '/hold'])
                held_result.append(client.call('console.execute', {'sessionId': holding, 'command': text}))
            except Exception as error:
                held_result.append(error)
        worker = threading.Thread(target=hold)
        serving.start()
        worker.start()
        try:
            # EVENT_WAIT: real guest HTTP request proves the store is retained; timeout is a failure.
            assert ready.wait(90), held_result
            guest('remove', names[0], success=False)
            guest('backup', names[0], directory + '/busy.tar.zst', success=False)
        finally:
            release.set()
            worker.join(150)
            serving.join(100)
            server.server_close()
            client.call('console.close', {'sessionId': holding})
        assert not worker.is_alive() and not serving.is_alive()
        assert held_result and isinstance(held_result[0], dict) and held_result[0]['exitCode'] == 0, held_result
        report['busyStore'] = held_result[0]
        guest('backup', names[0], directory + '/backup.tar.zst')
        guest('restore', directory + '/backup.tar.zst', '--name', 'restored')
        restored = json.loads(guest('inspect', 'restored'))
        assert not restored['sources']
        for name in names:
            guest('remove', name)
        guest('prune')
        guest('exec', 'restored', '--', '/bin/sh', '-c', 'test "$(cat /tmp/manager-value)" = private')
        guest('remove', 'restored')
        guest('prune')
        assert not guest('list').strip()
        report['passed'] = True
    finally:
        if console:
            client.call('console.close', {'sessionId': console})
        if lease:
            client.call('device.release_awake', {'leaseId': lease})
        path = repo / 'build' / ('environment-cli-' + tag + '.json')
        path.write_text(json.dumps(report, indent=2) + '\n')
        print(str(path), flush=True)


if __name__ == '__main__':
    main()
