#!/usr/bin/env python3
"""Virtual guest credentials under the already-authorized UID 2000 executor."""
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
    parser.add_argument('layout', help='Prepared OCI layout on the device')
    parser.add_argument('--packages', choices=['debian', 'alpine'])
    parser.add_argument('--fixture', type=Path, action='append', default=[],
                        help='Additional glibc/musl build of test_credentials_guest.c')
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
    base = '/data/local/tmp/md-credentials-' + tag
    console = client.call('console.open', {'directory': '/data/local/tmp'})['sessionId']
    report = {'id': tag, 'directory': base, 'app': state['app'], 'device': state['device'],
              'uid': 2000, 'checks': [], 'uploads': [], 'passed': False}

    def command(argv, timeout=180):
        text = shlex.join(['timeout', str(timeout), *map(str, argv)])
        result = client.call('console.execute', {'sessionId': console, 'command': text})
        report['checks'].append({'command': text, **result})
        print(result['output'], end='', flush=True)
        assert result['exitCode'] == 0, result
        return result['output']

    try:
        command(['mkdir', base])
        command(['mkdir', base + '/fixtures'])
        for name in ('bootstrap', 'supervisor', 'run', 'service', 'image', 'test_credentials', 'credentials_guest'):
            filename = 'libmagicdesk_guest_' + name + '.so'
            destination = base + ('/fixtures/' if name == 'credentials_guest' else '/') + filename
            report['uploads'].append(transport.upload(client, args.build / filename, destination))
            command(['chmod', '700', destination])
        command([base + '/libmagicdesk_guest_test_credentials.so', base + '/contract'])
        image = base + '/libmagicdesk_guest_image.so'
        run = base + '/libmagicdesk_guest_run.so'
        command([image, 'import', args.layout, base + '/image', '--preserve-ownership'])
        command([image, 'create', base + '/image', base + '/instance'])
        command([run, '--store', base + '/instance', '--user', '0:0', '--', '/bin/mkdir', '/fixture'])
        command([run, '--store', base + '/instance', '--user', '0:0',
                 '--bind-ro', base + '/fixtures', '/fixture',
                 '--deadline-seconds', '60', '--', '/fixture/libmagicdesk_guest_credentials_guest.so'])
        for index, fixture in enumerate(args.fixture):
            name = 'credentials-libc-' + str(index)
            report['uploads'].append(transport.upload(client, fixture, base + '/fixtures/' + name))
            command(['chmod', '700', base + '/fixtures/' + name])
            command([run, '--store', base + '/instance', '--user', '0:0',
                     '--bind-ro', base + '/fixtures', '/fixture',
                     '--deadline-seconds', '60', '--', '/fixture/' + name])
        output = command([run, '--store', base + '/instance', '--user', '0:0',
                          '--deadline-seconds', '60', '--', '/bin/sh', '-c',
                          'set -eu\nid\ntest "$(id -u)" = 0\ntest "$(id -g)" = 0\n'
                          'mkdir -p /tmp/credentials\nprintf value >/tmp/credentials/file\n'
                          'chown 1234:2345 /tmp/credentials/file\nchmod 640 /tmp/credentials/file\n'
                          'test "$(stat -c %u:%g:%a /tmp/credentials/file)" = 1234:2345:640\n'
                          'ln /tmp/credentials/file /tmp/credentials/alias\n'
                          'chmod 600 /tmp/credentials/alias\n'
                          'test "$(stat -c %a /tmp/credentials/file)" = 600\n'
                          'printf "PASS virtual root\\n"'])
        assert 'PASS virtual root' in output
        command([run, '--store', base + '/instance', '--user', '1000:1000', '--', '/bin/sh', '-c',
                 'set -eu\ntest "$(id -u)" = 1000\n'
                 'if cat /tmp/credentials/file; then exit 91; fi\n'
                 'if chmod 777 /tmp/credentials/file; then exit 92; fi\n'
                 'test "$(stat -c %u:%g:%a /tmp/credentials/file)" = 1234:2345:600\n'])
        assert command(['id', '-u']).strip() == '2000'
        output = command([image, 'run', base + '/instance', '--', '/bin/sh', '-c',
                          'test "$(id -u)" = 0 && echo "PASS image root"'])
        assert 'PASS image root' in output
        output = command([image, 'run', base + '/instance', '--user', 'nobody', '--', '/usr/bin/id', '-u'])
        assert output.strip() == '65534'
        if args.packages:
            setup = 'set -eu\nprintf "nameserver 1.1.1.1\\n" >/etc/resolv.conf\n'
            if args.packages == 'debian':
                setup += ('export DEBIAN_FRONTEND=noninteractive\napt-get update\n'
                          'apt-get install -y hello ca-certificates curl jq dbus\nhello\n'
                          'getent passwd messagebus\ngetent group messagebus\n'
                          'dbus-run-session -- dbus-send --session --print-reply '
                          '--dest=org.freedesktop.DBus / org.freedesktop.DBus.ListNames\n'
                          'apt-get install -y --reinstall hello\napt-get purge -y hello\n'
                          'apt-get install -y hello\ndpkg --audit\nhello\n')
            else:
                setup += ('apk update\napk add curl jq dbus\njq -n 42\n'
                          'apk fix jq\napk del jq\napk add jq\njq -n 42\n')
            setup += 'printf "PASS packages\\n"'
            output = command([image, 'run', base + '/instance', '--', '/bin/sh', '-c', setup], 600)
            assert 'PASS packages' in output
        report['passed'] = True
    except Exception as error:
        report['failure'] = str(error)
        raise
    finally:
        try:
            client.call('console.close', {'sessionId': console})
        finally:
            output = args.build / ('credentials-runtime-' + tag + '.json')
            output.write_text(json.dumps(report, indent=2) + '\n')
            print('Report:', output, flush=True)


if __name__ == '__main__':
    main()
