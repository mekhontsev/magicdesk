#!/usr/bin/env python3
"""Run one unchanged guest command Shell -> User -> Shell without a rootfs copy."""
import argparse
from pathlib import Path
from borrowed_store import BorrowedStore
from test_oci_services import Suite


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--library', type=Path, default=Path('/data/local/tmp/magicdesk/linux'))
    parser.add_argument('--name', required=True)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument('--command', help='Guest shell command, identical for all three runs')
    action.add_argument('--script', type=Path, help='Local guest shell script, identical for all three runs')
    parser.add_argument('--timeout', type=int, default=180)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    shell = Suite(args.output / 'shell', executor='shell')
    try:
        store = shell.command(['/system/bin/sh', '-ec',
            'MAGICDESK_GUEST_HOME="$1" shroot path "$2"', 'resolve', str(args.library), args.name], quiet=True).strip()
        shell.command(shell.image_command('prepare', store), quiet=True)
        if shell.command(shell.image_command('launches', store), quiet=True).strip():
            raise RuntimeError('Stop the installed environment before borrowing it')
        options = ('--', '/bin/sh', '-ec', args.command if args.command is not None else args.script.read_text())
        shell.command(shell.run(store, *options), timeout=args.timeout)
        app = Suite(args.output / 'app', executor='app')
        try:
            with BorrowedStore(app, args.library, store) as borrowed:
                app.command(app.run(borrowed.store, *options), timeout=args.timeout)
            app.command(['rmdir', app.base], quiet=True)
            app.report['passed'] = True
        finally:
            app.finish()
        shell.command(shell.run(store, *options), timeout=args.timeout)
        shell.command(['rmdir', shell.base], quiet=True)
        shell.report['passed'] = True
    finally:
        shell.finish()


if __name__ == '__main__':
    main()
