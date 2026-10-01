#!/usr/bin/env python3
"""Focused service contracts through the actual shell executor, without Desktop."""
import argparse
import uuid
from pathlib import Path
from test_oci_services import Suite


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('store', nargs='?')
    parser.add_argument('other_store', nargs='?')
    parser.add_argument('--layout', type=Path)
    parser.add_argument('--installed', action='store_true')
    args = parser.parse_args()
    if bool(args.layout) == bool(args.store or args.other_store) or (args.store and not args.other_store):
        parser.error('choose --layout or two prepared stores')
    suite = Suite(args.build, None if args.installed else args.build)
    try:
        if args.layout:
            args.store = suite.prepare('contracts', args.layout)
            args.other_store = suite.base + '/contracts/other'
            suite.command(suite.image_command('create', suite.base + '/contracts/image', args.other_store))
        suite.command(['mkdir', suite.base + '/fixtures'])
        fixture = 'libmagicdesk_guest_service_runtime.so'
        for name in (fixture, 'libmagicdesk_guest_test_credentials.so'):
            destination = suite.base + '/fixtures/' + name
            suite.transport.upload(suite.client, args.build / name, destination)
            suite.command(['chmod', '700', destination])
        suite.command([suite.base + '/fixtures/libmagicdesk_guest_test_credentials.so', suite.base + '/unit-store'])

        def run(store, *argv):
            root = [suite.runner, '--store', store, '--user', '0:0', '--deadline-seconds', '90']
            suite.command(root + ['--', '/bin/mkdir', '-p', '/fixture'])
            return suite.command(root + ['--bind-ro', suite.base + '/fixtures', '/fixture', '--',
                                         '/fixture/' + fixture, *argv])

        run(args.store, 'all')
        for hostname in ('first.example', 'second.example'):
            suite.command([suite.runner, '--store', args.store, '--user', '0:0', '--hostname', hostname,
                           '--bind-ro', suite.base + '/fixtures', '/fixture', '--',
                           '/fixture/' + fixture, 'hostname', hostname])
        key = str(1 + (uuid.uuid4().int % 0x7ffffffe))
        run(args.store, 'publish', key)
        run(args.other_store, 'absent', key)
        run(args.store, 'consume', key)
        suite.report['passed'] = True
    finally:
        suite.finish()


if __name__ == '__main__':
    main()
