#!/usr/bin/env python3
"""Clone ABI contracts under the selected executor and in an installed userspace."""
import argparse
from pathlib import Path

from test_oci_services import Suite


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build', type=Path, required=True)
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument('--name')
    target.add_argument('--store')
    args = parser.parse_args()
    suite = Suite(args.build, args.build)
    store = None
    mount = '/tmp/md-clone-' + suite.report['id']
    created = False
    try:
        info = suite.installed_environment(args.name) if args.name else dict(store=args.store)
        suite.report['environment'] = info
        store = info['store']
        binary = 'libmagicdesk_guest_clone3_guest.so'
        suite.upload(args.build / binary, suite.base + '/' + binary)
        suite.chmod('755', suite.base + '/' + binary)
        suite.command([suite.base + '/' + binary, '--native'])
        suite.command(suite.guest(store, '--user', '0:0', '--', '/bin/mkdir', mount))
        created = True
        for mode in (0, 1):
            result = suite.command(suite.guest(store, '--user', '0:0',
                '--env', 'SHROOT_MACHINE_ENTRY=' + str(mode),
                '--bind-ro', suite.base, mount, '--deadline-seconds', '90', '--',
                mount + '/' + binary), timeout=120)
            assert 'PASS clone3' in result
        suite.report['passed'] = True
    finally:
        try:
            if created:
                suite.command(suite.guest(store, '--user', '0:0', '--', '/bin/rmdir', mount), quiet=True)
        finally:
            try:
                suite.command(['rm', '-rf', '--', suite.base], quiet=True)
            finally:
                suite.finish()


if __name__ == '__main__':
    main()
