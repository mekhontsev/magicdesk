#!/usr/bin/env python3
"""Stock Debian account tools and package configuration, with actual UID 2000."""
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
    parser.add_argument('store', help='Disposable prepared Debian store with passwd and D-Bus packages')
    parser.add_argument('--installed', action='store_true')
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
    base = '/data/local/tmp/md-accounts-' + tag
    name = 'mdacct' + tag[:8]
    console = client.call('console.open', {'directory': '/data/local/tmp'})['sessionId']
    report = {'id': tag, 'directory': base, 'store': args.store, 'app': state['app'],
              'device': state['device'], 'installed': args.installed, 'checks': [], 'passed': False}

    def command(argv, expected=0):
        text = shlex.join(map(str, argv))
        result = client.call('console.execute', {'sessionId': console, 'command': text})
        report['checks'].append({'command': text, **result})
        print(result['output'], end='', flush=True)
        assert result['exitCode'] == expected, result
        return result['output']

    try:
        command(['mkdir', base, base + '/fixtures'])
        for target in ('bootstrap', 'supervisor', 'run', 'guest_interfaces'):
            if args.installed and target != 'guest_interfaces':
                continue
            filename = 'libmagicdesk_guest_' + target + '.so'
            destination = base + ('/fixtures/' if target == 'guest_interfaces' else '/') + filename
            transport.upload(client, args.build / filename, destination)
            command(['chmod', '700', destination])
        runner = (command(['/system/bin/sh', '-c', 'command -v magicdesk-guest']).strip()
                  if args.installed else base + '/libmagicdesk_guest_run.so')
        root = [runner, '--store', args.store, '--user', '0:0', '--deadline-seconds', '60']
        command(root + ['--', '/bin/mkdir', '-p', '/fixture'])
        fixture = 'libmagicdesk_guest_guest_interfaces.so'
        native = command([base + '/fixtures/' + fixture, 'native'])
        controls = next(line for line in native.splitlines() if line.startswith('controls '))
        for user in ('0:0', '65534:65534', '2000:2000'):
            result = command([runner, '--store', args.store, '--user', user, '--deadline-seconds', '30',
                              '--bind-ro', base + '/fixtures', '/fixture', '--', '/fixture/' + fixture, 'guest'])
            assert controls in result and 'PASS guest audit unavailable' in result
        group = name + 'g'
        extra = name + 'x'
        renamed = name + 'r'
        script = f'''set -eu
groupadd {group}
groupadd {extra}
groupmod -n {renamed} {extra}
useradd -m -g {group} -s /bin/sh {name}
usermod -a -G {renamed} {name}
getent passwd {name}
getent group {group}
getent group {renamed}
test "$(stat -c %u /home/{name})" = "$(id -u {name})"
printf 'PASS stock account creation and modification\n'
'''
        try:
            command(root + ['--', '/bin/sh', '-ec', script])
            command([runner, '--store', args.store, '--user', name, '--deadline-seconds', '30', '--',
                     '/bin/sh', '-ec', f'''test "$(id -un)" = {name}
test "$(id -gn)" = {group}
id -Gn | tr ' ' '\n' | grep -qx {renamed}
printf user-owned > /home/{name}/marker
test "$(stat -c %u /home/{name}/marker)" = "$(id -u)"
printf 'PASS named launch and supplementary group resolution\n'
'''])
            before = command(root + ['--', '/usr/bin/sha256sum', '/etc/group', '/etc/gshadow'])
            command([runner, '--store', args.store, '--user', '65534:65534', '--deadline-seconds', '30',
                     '--', '/usr/sbin/groupadd', name + 'denied'], expected=10)
            after = command(root + ['--', '/usr/bin/sha256sum', '/etc/group', '/etc/gshadow'])
            assert before == after, 'denied groupadd changed group files'
        finally:
            # Only fixture-owned accounts and their home are removed; unrelated records remain.
            command(root + ['--', '/bin/sh', '-ec', f'''
if getent passwd {name} >/dev/null; then userdel -r {name}; fi
for group in {group} {extra} {renamed}; do
    if getent group "$group" >/dev/null; then groupdel "$group"; fi
done
'''])
        command(root + ['--', '/usr/bin/dpkg', '--configure', '--pending'])
        status = command(root + ['--', '/usr/bin/dpkg-query', '-W', '-f=${binary:Package} ${db:Status-Status}\n',
                                 'dbus-system-bus-common', 'dbus'])
        assert sorted(status.splitlines()) == ['dbus installed', 'dbus-system-bus-common installed']
        command(root + ['--', '/usr/bin/getent', 'passwd', 'messagebus'])
        command(root + ['--', '/usr/bin/getent', 'group', 'messagebus'])
        assert not command(root + ['--', '/usr/bin/dpkg', '--audit']).strip()
        assert command([base + '/fixtures/' + fixture, 'native']) == native
        report['passed'] = True
    except Exception as error:
        report['failure'] = str(error)
        raise
    finally:
        client.call('console.close', {'sessionId': console})
        output = args.build / ('guest-accounts-' + tag + '.json')
        output.write_text(json.dumps(report, indent=2) + '\n')
        print('Report:', output, flush=True)


if __name__ == '__main__':
    main()
