#!/usr/bin/env python3
"""Stock Arch pacman, without a sandbox opt-out or a package/configuration patch.

Use a disposable prepared Arch Linux ARM instance with initialized package keys
and DNS. Synchronization updates its package databases, not installed packages.
An optional package check installs, reinstalls and verifies tree, removing it
only when this fixture installed it. Failure remains failure; the report
retains full output.
"""
import argparse
import hashlib
from pathlib import Path
from test_oci_services import Suite


def observe(suite, argv, timeout=60):
    return suite.observe(argv, timeout=timeout)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('store')
    parser.add_argument('--install-package', action='store_true')
    args = parser.parse_args()
    suite = Suite(args.build, args.build)
    suite.report['instance'] = args.store
    suite.report['pacmanFilesystemSandbox'] = True
    base = [suite.runner, '--store', args.store, '--user', '0:0',
            '--deadline-seconds', '180', '--']
    guest_dir = '/tmp/md-arch-' + suite.report['id']
    guest_created = False
    try:
        suite.command(base + ['/bin/sh', '-ec',
            '. /etc/os-release; test "$ID" = archarm\n'
            'cat /etc/os-release\n'
            'pacman --version\n'])
        config = suite.command(base + ['/usr/bin/pacman-conf'], quiet=True)
        forbidden = {'DisableSandbox', 'DisableSandboxFilesystem', 'DisableSandboxSyscalls'}
        directives = {line.partition('=')[0].strip() for line in config.splitlines()}
        assert not directives.intersection(forbidden), 'pacman sandbox opt-out in configuration'
        assert suite.command(base + ['/usr/bin/pacman-conf', 'DownloadUser'], quiet=True).strip() == 'alpm'
        suite.command(base + ['/usr/bin/runuser', '-u', 'alpm', '--', '/usr/bin/id'])
        suite.report['cases'].append({'name': 'stock-alpm-identity', 'passed': True})

        source = args.build / 'libmagicdesk_guest_landlock_guest.so'
        with source.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        native_fixture = suite.base + '/landlock-contract'
        suite.upload(source, native_fixture)
        assert suite.command(['sha256sum', native_fixture], quiet=True).split()[0] == digest
        suite.report['fixtureSha256'] = digest
        suite.chmod('755', native_fixture)
        suite.report['nativeLandlockProbe'] = observe(suite, [native_fixture, '--probe'])
        suite.command(base + ['/bin/mkdir', guest_dir])
        guest_created = True
        # Copy once into the inode store; Landlock tests do not depend on bind mounts.
        suite.command([suite.runner, '--store', args.store, '--user', '0:0',
                       '--bind-ro', suite.base, guest_dir, '--', '/bin/cp',
                       guest_dir + '/landlock-contract', '/tmp/md-landlock-' + suite.report['id']])
        guest_fixture = '/tmp/md-landlock-' + suite.report['id']
        suite.command(base + ['/bin/mv', guest_fixture, guest_dir + '/contract'])
        guest_fixture = guest_dir + '/contract'
        suite.command(base + [guest_fixture, '--setup-only', guest_dir])
        contract = observe(suite, base + [guest_fixture, guest_dir])
        suite.report['cases'].append({'name': 'landlock-filesystem-contract',
                                      'passed': contract['exitCode'] == 0, 'result': contract})
        sync = observe(suite, base + ['/usr/bin/pacman', '-Syy', '--noconfirm'], timeout=200)
        suite.report['cases'].append({'name': 'stock-pacman-sync',
                                      'passed': sync['exitCode'] == 0, 'result': sync})
        if args.install_package and sync['exitCode'] == 0:
            installed = observe(suite, base + ['/usr/bin/pacman', '-Q', 'tree'])['exitCode'] == 0
            try:
                suite.command(base + ['/usr/bin/pacman', '-S', '--needed', '--noconfirm', 'tree'], timeout=200)
                assert 'tree v' in suite.command(base + ['/usr/bin/tree', '--version'])
                suite.report['cases'].append({'name': 'stock-pacman-install-run', 'passed': True})
                suite.command(base + ['/usr/bin/pacman', '-S', '--noconfirm', 'tree'], timeout=200)
                suite.command(base + ['/usr/bin/pacman', '-Qkk', 'tree'])
                assert 'tree v' in suite.command(base + ['/usr/bin/tree', '--version'])
                suite.report['cases'].append({'name': 'stock-pacman-reinstall-verify', 'passed': True})
            finally:
                if not installed:
                    present = observe(suite, base + ['/usr/bin/pacman', '-Q', 'tree'])['exitCode'] == 0
                    if present:
                        suite.command(base + ['/usr/bin/pacman', '-R', '--noconfirm', 'tree'])
                        suite.command(base + ['/usr/bin/pacman', '-Q', 'tree'], expected=1)
                        suite.command(base + ['/bin/sh', '-ec', 'test ! -e /usr/bin/tree'])
                        suite.report['cases'].append({'name': 'stock-pacman-remove', 'passed': True})
        suite.report['passed'] = all(case['passed'] for case in suite.report['cases'])
    except Exception as error:
        suite.report['failure'] = repr(error)
        raise
    finally:
        try:
            if guest_created:
                suite.command(base + ['/bin/rm', '-rf', guest_dir,
                                      '/tmp/md-landlock-' + suite.report['id']])
        finally:
            suite.finish()
    return 0 if suite.report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
