#!/usr/bin/env python3
"""Run machinectl boot or command-only nspawn in an installed systemd environment.

Use an existing machine or a disposable image extracted from a supplied rootfs
with systemd, dbus, iproute2 and Python. No unit overrides or sandbox switches
are installed by this test.
"""
import argparse
from pathlib import Path
from boot_fixture import run_fixture


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--session', type=Path, required=True)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--machine')
    source.add_argument('--archive', type=Path)
    parser.add_argument('--command', action='store_true', help='test command mode using an existing machine image')
    args = parser.parse_args()
    if args.command:
        if not args.machine:
            parser.error('--command requires --machine')
        run_fixture(args.session, 'nspawn-command', ['--machine', args.machine])
        return
    if args.archive:
        run_fixture(args.session, 'nspawn', ['--archive', args.archive], [args.archive])
    else:
        run_fixture(args.session, 'nspawn', ['--machine', args.machine])


if __name__ == '__main__':
    main()
