#!/usr/bin/env python3
"""Boot the installed init and run a non-root session in its namespace, without a viewer."""
import argparse
from pathlib import Path
import shlex
import tempfile

import _bootstrap
from fixture_executor import FixtureExecutor
from magicdesk_executor import init_helper
from test_oci_services import Suite


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--store', required=True)
    parser.add_argument('--user', required=True)
    parser.add_argument('--installed', action='store_true', help='Use the helper installed by the recipe')
    parser.add_argument('--network', choices=('none', 'bridge', 'routed'), default='bridge')
    parser.add_argument('--pty', action='store_true', help='Exercise terminal input/output rather than a headless command')
    parser.add_argument('--build', type=Path)
    parser.add_argument('--output', type=Path, default=Path('build/service-session-test'))
    FixtureExecutor.add_argument(parser)
    args = parser.parse_args()
    suite = Suite(args.output, build=args.build, executor=args.executor)
    try:
        prefix = suite.guest(args.store, '--user', '0:0', '--network', args.network)
        uid, gid = suite.stdout(prefix + ['--', '/bin/sh', '-ec',
                'id -u "$1"; id -g "$1"', 'ids', args.user]).split()
        assert uid.isdigit() and uid != '0' and gid.isdigit()
        payload = suite.base + '/payload'
        helpers = suite.base + '/helpers'
        commands = suite.base + '/commands'
        suite.command(['mkdir', '-m', '755', payload, helpers, commands])
        if not args.installed:
            with tempfile.TemporaryDirectory() as directory:
                helper = Path(directory) / 'magicdesk-init'
                helper.write_text(init_helper())
                suite.upload(helper, payload + '/magicdesk-init')
            suite.chmod('755', payload + '/magicdesk-init')
        suite.chmod('711', suite.base)
        suite.command(['touch', payload + '/session-result', helpers + '/marker', commands + '/marker'])
        suite.chmod('666', payload + '/session-result')
        suite.command(prefix + ['--', '/bin/sh', '-ec', 'mkdir -p /tmp/magicdesk-helpers'])
        probe = '''set -eu
test "$(id -u):$(id -g)" = "$1"
test "$PWD" = /
test "$HOME" = "$(getent passwd "$(id -u)" | cut -d: -f6)"
test "$XDG_RUNTIME_DIR" = "/run/user/$(id -u)"
test "$(stat -c %a:%u:%g "$XDG_RUNTIME_DIR")" = "700:$1"
touch "$XDG_RUNTIME_DIR/session-probe"
test "$2" = "a'b" && test "$3" = ""
test "$MAGICDESK_GUEST_FILES_TOKEN" = "a'b"
test -e /tmp/magicdesk-helpers/marker
test -e /run/magicdesk/marker
if [ -d /run/systemd/system ]; then
    test "$(cat /proc/1/comm)" = systemd
    systemctl show --property=ActiveState --value basic.target | grep -qx active
    manager=systemd
elif [ "$(cat /proc/1/comm)" = runit ]; then
    # A second runsv, started by the distribution's own runsvdir, proves that
    # the session did not replace the stock service stage.
    timeout 30 sh -c 'until ps -eo args | grep -q "^runsv [^/]"; do sleep 0.2; done'
    manager=runit
else
    test -d /run/openrc && rc-status >/dev/null
    manager=openrc
fi
printf 'PASS %s %s\\n' "$manager" "$1" > /mnt/session-result
'''
        helper = ['/bin/sh', '-c', 'exec magicdesk-init "$@"', 'sh'] if args.installed else ['/mnt/magicdesk-init']
        command = helper + ['--session', uid + ':' + gid, '/', '/bin/sh', '-c', probe,
                            'probe', uid + ':' + gid, "a'b", '']
        if args.pty:
            command = ['/usr/bin/script', '-q', '-e', '-c', shlex.join(command), '/dev/null']
        result = suite.observe(prefix + ['--bind', payload, '/mnt', '--bind-ro', helpers, '/tmp/magicdesk-helpers',
                               '--bind-ro-create', commands, '/run/magicdesk',
                               '--env', "MAGICDESK_GUEST_FILES_TOKEN=a'b",
                               '--', *command], timeout=180)
        receipt = suite.command(['cat', payload + '/session-result']).strip()
        assert receipt in {f'PASS {m} {uid}:{gid}' for m in ('systemd', 'openrc', 'runit')}, (receipt, result)
        assert result['exitCode'] == 0 or (receipt.startswith(('PASS openrc ', 'PASS runit ')) and result['exitCode'] == 130), result
        suite.report.update(passed=True, session=receipt)
    finally:
        try:
            suite.command(['rm', '-rf', suite.base])
        finally:
            suite.finish()


if __name__ == '__main__':
    main()
