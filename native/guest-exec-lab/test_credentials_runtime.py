#!/usr/bin/env python3
"""Virtual guest credentials under the explicitly selected Android executor."""
import argparse
from pathlib import Path
from fixture_executor import FixtureExecutor
from test_oci_services import Suite, add_machine_entry_argument


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('layout', type=Path, help='Prepared local OCI layout')
    parser.add_argument('--packages', choices=['debian', 'alpine'])
    parser.add_argument('--fixture', type=Path, action='append', default=[],
                        help='Additional glibc/musl build of test_credentials_guest.c')
    FixtureExecutor.add_argument(parser)
    add_machine_entry_argument(parser)
    args = parser.parse_args()
    suite = Suite(args.build, args.build, executor=args.executor, machine_entry=args.machine_entry)
    base, report, command = suite.base, suite.report, suite.command
    report['uploads'] = []

    try:
        command(['mkdir', base + '/fixtures'])
        for name in ('test_credentials', 'credentials_guest'):
            filename = 'libmagicdesk_guest_' + name + '.so'
            destination = base + ('/fixtures/' if name == 'credentials_guest' else '/') + filename
            report['uploads'].append(suite.upload(args.build / filename, destination))
            command(['chmod', '700', destination])
        command([base + '/libmagicdesk_guest_test_credentials.so', base + '/contract'])
        image, run = suite.image, [suite.runner, *suite.entry_options]
        store = suite.prepare('credentials', args.layout)
        command([*run, '--store', store, '--user', '0:0', '--', '/bin/mkdir', '/fixture'])
        command([*run, '--store', store, '--user', '0:0',
                 '--bind-ro', base + '/fixtures', '/fixture',
                 '--deadline-seconds', '60', '--', '/fixture/libmagicdesk_guest_credentials_guest.so'])
        for index, fixture in enumerate(args.fixture):
            name = 'credentials-libc-' + str(index)
            report['uploads'].append(suite.upload(fixture, base + '/fixtures/' + name))
            command(['chmod', '700', base + '/fixtures/' + name])
            command([*run, '--store', store, '--user', '0:0',
                     '--bind-ro', base + '/fixtures', '/fixture',
                     '--deadline-seconds', '60', '--', '/fixture/' + name])
        output = command([*run, '--store', store, '--user', '0:0',
                          '--deadline-seconds', '60', '--', '/bin/sh', '-c',
                          'set -eu\numask 022\nid\ntest "$(id -u)" = 0\ntest "$(id -g)" = 0\n'
                          'mkdir -p /tmp/credentials\nprintf value >/tmp/credentials/file\n'
                          'chown 1234:2345 /tmp/credentials/file\nchmod 640 /tmp/credentials/file\n'
                          'test "$(stat -c %u:%g:%a /tmp/credentials/file)" = 1234:2345:640\n'
                          'ln /tmp/credentials/file /tmp/credentials/alias\n'
                          'chmod 600 /tmp/credentials/alias\n'
                          'test "$(stat -c %a /tmp/credentials/file)" = 600\n'
                          'printf "PASS virtual root\\n"'])
        assert 'PASS virtual root' in output
        command([*run, '--store', store, '--user', '1000:1000', '--', '/bin/sh', '-c',
                 'set -eu\ntest "$(id -u)" = 1000\n'
                 'if cat /tmp/credentials/file; then exit 91; fi\n'
                 'if chmod 777 /tmp/credentials/file; then exit 92; fi\n'
                 'test "$(stat -c %u:%g:%a /tmp/credentials/file)" = 1234:2345:600\n'])
        assert command(['id', '-u']).strip() == str(suite.executor.uid)
        output = command([image, 'run', store, '--', '/bin/sh', '-c',
                          'test "$(id -u)" = 0 && echo "PASS image root"'])
        assert 'PASS image root' in output
        output = command([image, 'run', store, '--user', 'nobody', '--', '/usr/bin/id', '-u'])
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
            output = command([image, 'run', store, '--', '/bin/sh', '-c', setup], timeout=600)
            assert 'PASS packages' in output
        report['passed'] = True
    except Exception as error:
        report['failure'] = str(error)
        raise
    finally:
        try:
            command(['rm', '-rf', base], quiet=True)
        finally:
            suite.finish()


if __name__ == '__main__':
    main()
