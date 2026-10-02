#!/usr/bin/env python3
"""Distribution package/user/session workflows in private ARM64 OCI instances."""
import argparse
from pathlib import Path
from test_oci_services import Suite, python_runtime


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--build', type=Path)
    parser.add_argument('distribution', choices=('ubuntu', 'centos', 'arch'))
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--layout', type=Path)
    group.add_argument('--instance')
    parser.add_argument('--install', action='store_true')
    parser.add_argument('--arch-without-landlock', action='store_true',
                        help='Explicit pacman filesystem-sandbox opt-out on a kernel without Landlock')
    args = parser.parse_args()
    if args.arch_without_landlock and args.distribution != 'arch':
        parser.error('--arch-without-landlock requires arch')
    suite = Suite(args.output, args.build)
    try:
        store = suite.prepare(args.distribution, args.layout) if args.layout else args.instance
        suite.report['instance'] = store
        if args.install:
            # No systemd-resolved runs in this command-only fixture.
            setup = 'rm -f /etc/resolv.conf\nprintf "nameserver 1.1.1.1\\n" > /etc/resolv.conf\n'
            suite.command(suite.run(store, '--', '/bin/sh', '-ec', setup))
            pacman = 'pacman --disable-sandbox-filesystem' if args.arch_without_landlock else 'pacman'
            suite.report['pacmanFilesystemSandbox'] = not args.arch_without_landlock
            commands = {
                'ubuntu': [
                    'apt-get update',
                    'apt-get install -y --no-install-recommends ca-certificates dbus python3 jq mousepad qemu-user binutils-x86-64-linux-gnu',
                    'apt-get install -y --reinstall jq',
                    'apt-get purge -y jq && apt-get install -y jq', 'dpkg --audit'],
                'centos': [
                    'dnf -y --setopt=install_weak_deps=False --setopt=max_parallel_downloads=10 install python3 dbus-daemon shadow-utils jq zenity',
                    'dnf -y reinstall jq', 'rpm -q python3 dbus-daemon jq'],
                # This fixture runs userspace, not the image's boot kernel/firmware.
                'arch': ['pacman-key --init', 'pacman-key --populate archlinuxarm',
                         pacman + ' -Syu --noconfirm --ignore linux-aarch64,linux-firmware',
                         pacman + ' -S --noconfirm dbus python jq mousepad',
                         pacman + ' -S --noconfirm jq', 'pacman -Q dbus python jq']}
            for command in commands[args.distribution]:
                if args.distribution == 'arch':
                    # The launch owns descendants, including GnuPG's detached agent.
                    command = "trap 'gpgconf --homedir /etc/pacman.d/gnupg --kill all' EXIT\n" + command
                suite.command(suite.run(store, '--env', 'DEBIAN_FRONTEND=noninteractive', '--', '/bin/sh', '-ec', command), timeout=600)
        suite.command(suite.run(store, '--', '/bin/sh', '-ec',
            'cat /etc/os-release\ntest "$(id -u)" = 0\n'
            'groupadd md-fixture-group\nuseradd -m -g md-fixture-group -s /bin/sh md-fixture\n'
            'getent passwd md-fixture\ngetent group md-fixture-group\n'))
        suite.command(suite.run(store, '--user', 'md-fixture', '--', '/bin/sh', '-ec',
            'test "$(id -un)" = md-fixture\nprintf owned >/home/md-fixture/value\n'
            'test "$(stat -c %U /home/md-fixture/value)" = md-fixture\n'
            'if touch /root/md-fixture-denied; then exit 90; fi\n'))
        suite.command(suite.run(store, '--', '/bin/sh', '-ec',
            'test "$(cat /home/md-fixture/value)" = owned\n'
            'dbus-run-session -- dbus-send --session --print-reply '
            '--dest=org.freedesktop.DBus / org.freedesktop.DBus.ListNames\n'
            'userdel -r md-fixture\ngroupdel md-fixture-group\njq -n 42\n'))
        suite.report['cases'].append({'name': 'accounts-permissions-dbus', 'passed': True})
        # A real multi-process service with SQLite and POSIX SHM, not --version.
        python_runtime(suite, store)
        suite.report['cases'].append({'name': 'python-service', 'passed': True})
        suite.report['passed'] = True
    except Exception as error:
        suite.report['failure'] = repr(error)
        raise
    finally:
        suite.finish()


if __name__ == '__main__':
    main()
