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
    parser.add_argument('--diagnostics', action='store_true', help='Record nspawn debug messages in command mode')
    parser.add_argument('--trace', action='store_true', help='Trace command-mode syscalls; requires guest strace')
    args = parser.parse_args()
    if (args.diagnostics or args.trace) and not args.command:
        parser.error('--diagnostics and --trace require --command')
    if args.command:
        if not args.machine:
            parser.error('--command requires --machine')
        options = ['--machine', args.machine]
        if args.diagnostics:
            options.append('--diagnostics')
        if args.trace:
            options.append('--trace')
        run_fixture(args.session, 'nspawn-command', options)
        return
    if args.archive:
        run_fixture(args.session, 'nspawn', ['--archive', args.archive], [args.archive])
    else:
        run_fixture(args.session, 'nspawn', ['--machine', args.machine])


if __name__ == '__main__':
    main()
