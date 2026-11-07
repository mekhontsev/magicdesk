#!/usr/bin/env python3
"""Stock Debian account tools under the selected Android executor."""
import argparse
from pathlib import Path
from fixture_executor import FixtureExecutor
from test_oci_services import Suite


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('store', help='Disposable prepared Debian store with passwd and D-Bus packages')
    parser.add_argument('--installed', action='store_true')
    FixtureExecutor.add_argument(parser)
    args = parser.parse_args()
    suite = Suite(args.build, None if args.installed else args.build, executor=args.executor)
    base, report, command = suite.base, suite.report, suite.command
    name = 'mdacct' + report['id'][:8]
    report.update(store=args.store, installed=args.installed)

    try:
        command(['mkdir', base + '/fixtures'])
        filename = 'libmagicdesk_guest_guest_interfaces.so'
        destination = base + '/fixtures/' + filename
        suite.upload(args.build / filename, destination)
        suite.chmod('700', destination)
        runner = suite.runner
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
        try:
            command(['rm', '-rf', '--', base], quiet=True)
        finally:
            suite.finish()


if __name__ == '__main__':
    main()
