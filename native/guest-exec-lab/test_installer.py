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
    parser.add_argument('--gpu', choices=('keep', 'auto', 'software', 'turnip'), default='keep')
    parser.add_argument('--jobs', type=int, choices=range(1, 9), default=2)
    parser.add_argument('--dns', default='auto')
    parser.add_argument('--locale', default='en_US.UTF-8', help='Explicit guest locale to verify')
    parser.add_argument('--timezone', default='UTC', help='Explicit IANA timezone to verify')
    parser.add_argument('--package', action='append', default=[])
    parser.add_argument('--timeout', type=int, default=7200,
                        help='Installer command failure bound, including a source GPU build')
    parser.add_argument('--name', required=True, help='Dedicated fixture name; retained for graphics checks')
    parser.add_argument('--resume', action='store_true')
    parser.add_argument('--library', help='Dedicated shell-owned library, isolated from existing image caches')
    parser.add_argument('--ipv4', action='store_true', help='Set XBPS FORCE_IPV4 for networks without working IPv6')
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    spec = importlib.util.spec_from_file_location('transport', repo / 'scripts/mcp-client.py')
    transport = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(transport)
    config = tomllib.loads((Path.home() / '.codex/config.toml').read_text())['mcp_servers']['magicdesk']
    client = transport.Client(config['url'], config['http_headers']['Authorization'].removeprefix('Bearer '),
                              timeout=args.timeout, request_timeout=args.timeout)
    state = client.call('get_state')
    assert state['shell']['uid'] == 2000
    report = {'app': state['app'], 'distro': args.distro, 'gui': args.gui, 'name': args.name, 'library': args.library,
              'ipv4': args.ipv4, 'gpu': args.gpu, 'jobs': args.jobs,
              'dns': args.dns, 'packages': args.package, 'locale': args.locale, 'timezone': args.timezone,
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

        if args.library:
            command(['export', 'MAGICDESK_GUEST_HOME=' + args.library])
        if args.ipv4:
            command(['export', 'FORCE_IPV4=1'])
        source = repo / 'scripts/install_linux.sh'
        digest = hashlib.sha256(source.read_bytes()).hexdigest()
        script = '/data/local/tmp/magicdesk/installer-test-' + args.name + '-' + digest + '.sh'
        report['upload'] = transport.upload(client, source, script, overwrite=True)
        invocation = ['sh', script, '--yes', '--name', args.name, '--distro', args.distro,
                      '--gui', args.gui, '--locale', args.locale, '--timezone', args.timezone,
                      '--create-user', 'mdtester', '--fonts', 'basic', '--package', 'jq',
                      '--gpu', args.gpu, '--jobs', str(args.jobs)]
        for package in args.package:
            invocation += ['--package', package]
        invocation += ['--resume'] if args.resume else ['--dns', args.dns]
        report['installerLog'] = script + '.log'
        print('Installer output: ' + report['installerLog'], flush=True)
        command(['sh', '-c', 'log=$1; shift; "$@" >"$log" 2>&1; code=$?; cat "$log"; exit "$code"',
                 'installer-log', report['installerLog'], *invocation])
        inspect = command(['shroot', 'inspect', args.name])
        report['environment'] = json.loads(inspect)
        checks = ('set -eu\ncat /etc/os-release\n'
                  'test "$(cat /etc/locale.conf)" = ' + shlex.quote('LANG=' + args.locale) + '\n'
                  'test "$(readlink /etc/localtime)" = ' + shlex.quote('/usr/share/zoneinfo/' + args.timezone) + '\n'
                  'test "$(id -u mdtester)" -ge 1000\n'
                  'jq -n 42\n'
                  'if command -v mousepad; then command -v thunar; command -v xfce4-terminal; '
                  'dbus-run-session -- dbus-send --session --print-reply '
                  '--dest=org.freedesktop.DBus / org.freedesktop.DBus.ListNames; fi\n')
        if args.gui not in ('none', 'keep'):
            checks += ('bash -n /usr/local/bin/magicdesk-audio-session\n'
                       'sh -n /usr/local/bin/magicdesk-desktop-session\n'
                       'for program in pipewire pipewire-pulse wireplumber pw-link; do command -v "$program"; done\n'
                       'for library in libudev.so.1 libSDL2-2.0.so.0; do\n'
                       '  found=no\n'
                       '  for path in /usr/lib/"$library" /usr/lib64/"$library" /usr/lib/*-linux-gnu/"$library"; do\n'
                       '    [ ! -e "$path" ] || found=yes\n'
                       '  done\n'
                       '  [ "$found" = yes ] || { echo "Missing library: $library" >&2; exit 1; }\n'
                       'done\n')
        command(['shroot', 'exec', args.name, '--user', 'root', '--', '/bin/sh', '-c', checks])
        login_locale_check = ('set -eu; test "$LANG" = ' + shlex.quote(args.locale) + '; '
                              'test "${LC_ALL:-$LANG}" = ' + shlex.quote(args.locale) + '; ')
        command(['shroot', 'login', args.name, '--user', 'mdtester', '--', '/bin/sh', '-lc',
                 login_locale_check +
                 'locale; test "$HOME" = /home/mdtester; '
                 'printf retained > "$HOME/installer-sentinel"'])
        resume = ['sh', script, '--yes', '--name', args.name, '--resume']
        command(resume)
        command(['shroot', 'login', args.name, '--user', 'mdtester', '--', '/bin/sh', '-lc',
                 login_locale_check +
                 'test "$(cat "$HOME/installer-sentinel")" = retained'])
        report['passed'] = True
    finally:
        client.call('console.close', {'sessionId': console})
        destination = repo / 'build' / ('installer-' + args.name + '.json')
        destination.write_text(json.dumps(report, indent=2) + '\n')
        print(destination, flush=True)


if __name__ == '__main__':
    main()
