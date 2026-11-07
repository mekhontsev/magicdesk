#!/usr/bin/env python3
"""Unix IPC identities and stock D-Bus under the selected Android executor."""
import argparse
from pathlib import Path
import shlex
from fixture_executor import FixtureExecutor
from test_oci_services import Suite, add_machine_entry_argument


def custody_cases(native, guest):
    for receive in range(5):
        for protected in range(2):
            for is_guest, prefix in ((False, native), (True, guest)):
                name = f'ancillary custody receive={receive} protected={protected} guest={is_guest}'
                yield name, [*prefix, str(receive), str(protected)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('store')
    parser.add_argument('--gdbus', action='store_true')
    parser.add_argument('--activation', type=Path)
    parser.add_argument('--installed', action='store_true', help="Use the installed APK's guest bundle")
    parser.add_argument('--custody', action='store_true', help='Compare terminal send/receive adapter custody with native shell')
    FixtureExecutor.add_argument(parser)
    add_machine_entry_argument(parser)
    args = parser.parse_args()
    suite = Suite(args.build, None if args.installed else args.build,
                  executor=args.executor, machine_entry=args.machine_entry)
    base, report, command = suite.base, suite.report, suite.command
    report.update(store=args.store, installed=args.installed)

    try:
        command(['mkdir', base + '/fixtures'])
        for name in ('ipc_credentials_guest', 'ipc_credentials_launch'):
            filename = 'libmagicdesk_guest_' + name + '.so'
            destination = base + ('/fixtures/' if name == 'ipc_credentials_guest' else '/') + filename
            suite.upload(args.build / filename, destination)
            suite.chmod('700', destination)
        run = suite.guest(args.store, '--user', '0:0', '--deadline-seconds', '30')
        command(run + ['--', '/bin/mkdir', '-p', '/fixture'])
        if args.custody:
            failures = []
            fixture = '/fixture/libmagicdesk_guest_ipc_credentials_guest.so'
            native = [base + '/fixtures/libmagicdesk_guest_ipc_credentials_guest.so', '--custody-native']
            guest = run + ['--bind-ro', base + '/fixtures', '/fixture', '--', fixture, '--custody']
            for name, argv in custody_cases(native, guest):
                try:
                    command(argv)
                except AssertionError:
                    failures.append(name)
                    print('FAIL', name, flush=True)
            report['passed'] = not failures
            assert not failures, failures
            return
        command(run + ['--bind-ro', base + '/fixtures', '/fixture', '--', '/fixture/libmagicdesk_guest_ipc_credentials_guest.so'])
        if args.activation:
            suite.upload(args.activation, base + '/fixtures/ipc-dbus')
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
        try:
            command(['rm', '-rf', '--', base], quiet=True)
        finally:
            suite.finish()


if __name__ == '__main__':
    main()
