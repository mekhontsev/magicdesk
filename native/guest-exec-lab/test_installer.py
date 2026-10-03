#!/usr/bin/env python3
"""Run the public installer and verify settings inside a named test environment."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shlex
import tomllib


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--distro', required=True)
    parser.add_argument('--gui', default='apps')
    parser.add_argument('--name', required=True, help='Dedicated fixture name; retained for graphics checks')
    parser.add_argument('--resume', action='store_true')
    parser.add_argument('--arch-without-landlock', action='store_true')
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    spec = importlib.util.spec_from_file_location('transport', repo / 'scripts/mcp-client.py')
    transport = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(transport)
    config = tomllib.loads((Path.home() / '.codex/config.toml').read_text())['mcp_servers']['magicdesk']
    client = transport.Client(config['url'], config['http_headers']['Authorization'].removeprefix('Bearer '),
                              timeout=3600, request_timeout=3600)
    state = client.call('get_state')
    assert state['shell']['uid'] == 2000
    report = {'app': state['app'], 'distro': args.distro, 'gui': args.gui, 'name': args.name,
              'checks': [], 'passed': False}
    console = client.call('console.open', {'directory': '/data/local/tmp/magicdesk'})['sessionId']
    try:
        def command(argv):
            # EVENT_WAIT: command exit; transport deadline fails without replaying a running install.
            result = client.call('console.execute', {'sessionId': console, 'command': shlex.join(argv)})
            report['checks'].append({'argv': argv, **result})
            print(result['output'], flush=True)
            assert result['exitCode'] == 0, result
            return result['output']

        source = repo / 'scripts/install_linux.sh'
        digest = hashlib.sha256(source.read_bytes()).hexdigest()
        script = '/data/local/tmp/magicdesk/installer-test-' + args.name + '-' + digest + '.sh'
        report['upload'] = transport.upload(client, source, script, overwrite=True)
        invocation = ['sh', script, '--yes', '--name', args.name, '--distro', args.distro,
                      '--gui', args.gui, '--locale', 'ru_RU.UTF-8', '--timezone', 'Asia/Krasnoyarsk',
                      '--create-user', 'mdtester', '--package', 'jq']
        invocation += ['--resume'] if args.resume else ['--dns', '1.1.1.1,1.0.0.1']
        if args.arch_without_landlock:
            invocation += ['--arch-sandbox', 'disable-filesystem']
        command(invocation)
        inspect = command(['magicdesk-guest', 'inspect', args.name])
        report['environment'] = json.loads(inspect)
        checks = ('set -eu\ncat /etc/os-release\n'
                  'test "$(cat /etc/locale.conf)" = LANG=ru_RU.UTF-8\n'
                  'test "$(readlink /etc/localtime)" = /usr/share/zoneinfo/Asia/Krasnoyarsk\n'
                  'test "$(id -u mdtester)" -ge 1000\n'
                  'jq -n 42\n'
                  'if command -v mousepad; then command -v thunar; command -v xfce4-terminal; '
                  'dbus-run-session -- dbus-send --session --print-reply '
                  '--dest=org.freedesktop.DBus / org.freedesktop.DBus.ListNames; fi\n')
        command(['magicdesk-guest', 'exec', args.name, '--user', 'root', '--', '/bin/sh', '-c', checks])
        command(['magicdesk-guest', 'login', args.name, '--user', 'mdtester', '--', '/bin/sh', '-lc',
                 'set -eu; test "$LANG" = ru_RU.UTF-8; test "${LC_ALL:-$LANG}" = ru_RU.UTF-8; '
                 'locale; test "$HOME" = /home/mdtester; '
                 'printf retained > "$HOME/installer-sentinel"'])
        resume = ['sh', script, '--yes', '--name', args.name, '--resume']
        if args.arch_without_landlock:
            resume += ['--arch-sandbox', 'disable-filesystem']
        command(resume)
        command(['magicdesk-guest', 'login', args.name, '--user', 'mdtester', '--', '/bin/sh', '-lc',
                 'set -eu; test "$LANG" = ru_RU.UTF-8; test "${LC_ALL:-$LANG}" = ru_RU.UTF-8; '
                 'test "$(cat "$HOME/installer-sentinel")" = retained'])
        report['passed'] = True
    finally:
        client.call('console.close', {'sessionId': console})
        destination = repo / 'build' / ('installer-' + args.name + '.json')
        destination.write_text(json.dumps(report, indent=2) + '\n')
        print(destination, flush=True)


if __name__ == '__main__':
    main()
