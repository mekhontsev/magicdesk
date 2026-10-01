#!/usr/bin/env python3
"""Unix IPC identities and stock session D-Bus under guest root, actual shell."""
import argparse
import importlib.util
import json
from pathlib import Path
import shlex
import tomllib
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('store')
    parser.add_argument('--gdbus', action='store_true')
    parser.add_argument('--activation', type=Path)
    parser.add_argument('--installed', action='store_true', help="Use the installed APK's guest bundle")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    spec = importlib.util.spec_from_file_location('transport', repo / 'scripts/mcp-client.py')
    transport = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(transport)
    config = tomllib.loads((Path.home() / '.codex/config.toml').read_text())['mcp_servers']['magicdesk']
    client = transport.Client(config['url'], config['http_headers']['Authorization'].removeprefix('Bearer '))
    state = client.call('get_state')
    assert state['shell']['uid'] == 2000
    tag = uuid.uuid4().hex
    base = '/data/local/tmp/md-ipc-credentials-' + tag
    session = client.call('console.open', {'directory': '/data/local/tmp'})['sessionId']
    report = {'id': tag, 'directory': base, 'app': state['app'], 'store': args.store,
              'installed': args.installed, 'checks': [], 'passed': False}

    def command(argv):
        text = shlex.join(map(str, argv))
        result = client.call('console.execute', {'sessionId': session, 'command': text})
        report['checks'].append({'command': text, **result})
        print(result['output'], end='', flush=True)
        assert result['exitCode'] == 0, result
        return result

    try:
        command(['mkdir', base, base + '/fixtures'])
        for name in ('bootstrap', 'supervisor', 'run', 'ipc_credentials_guest', 'ipc_credentials_launch'):
            if args.installed and name in ('bootstrap', 'supervisor', 'run'):
                continue
            filename = 'libmagicdesk_guest_' + name + '.so'
            destination = base + ('/fixtures/' if name == 'ipc_credentials_guest' else '/') + filename
            transport.upload(client, args.build / filename, destination)
            command(['chmod', '700', destination])
        runner = (command(['/system/bin/sh', '-c', 'command -v magicdesk-guest'])['output'].strip()
                  if args.installed else base + '/libmagicdesk_guest_run.so')
        run = [runner, '--store', args.store, '--user', '0:0', '--deadline-seconds', '30']
        command(run + ['--', '/bin/mkdir', '-p', '/fixture'])
        command(run + ['--bind-ro', base + '/fixtures', '/fixture', '--', '/fixture/libmagicdesk_guest_ipc_credentials_guest.so'])
        if args.activation:
            transport.upload(client, args.activation, base + '/fixtures/ipc-dbus')
            command(['chmod', '700', base + '/fixtures/ipc-dbus'])
            service = '/usr/share/dbus-1/services/io.github.magicdesk.CredentialsFixture.service'
            content = '[D-BUS Service]\nName=io.github.magicdesk.CredentialsFixture\nExec=/fixture/ipc-dbus service\n'
            command(run + ['--', '/bin/sh', '-c', 'printf %s ' + shlex.quote(content) + ' >' + shlex.quote(service)])
            try:
                command(run + ['--bind-ro', base + '/fixtures', '/fixture', '--', '/usr/bin/dbus-run-session', '--', '/fixture/ipc-dbus'])
            finally:
                command(run + ['--', '/bin/rm', '-f', service])
        command(run + ['--', '/usr/bin/dbus-run-session', '--', '/usr/bin/dbus-send', '--session', '--print-reply',
                       '--dest=org.freedesktop.DBus', '/', 'org.freedesktop.DBus.ListNames'])
        if args.gdbus:
            command(run + ['--', '/usr/bin/dbus-run-session', '--', '/usr/bin/gdbus', 'call', '--session',
                           '--dest', 'org.freedesktop.DBus', '--object-path', '/org/freedesktop/DBus',
                           '--method', 'org.freedesktop.DBus.ListNames'])
        command([base + '/libmagicdesk_guest_ipc_credentials_launch.so', run[0], args.store, base + '/fixtures'])
        report['passed'] = True
    finally:
        client.call('console.close', {'sessionId': session})
        output = args.build / ('ipc-credentials-' + tag + '.json')
        output.write_text(json.dumps(report, indent=2) + '\n')
        print('Report:', output)


if __name__ == '__main__':
    main()
