#!/usr/bin/env python3
"""Run prebuilt reviewed LTP tests in an installed distribution's own userspace."""
import argparse
from pathlib import Path
from boot_fixture import run_fixture


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--session', type=Path, required=True)
    parser.add_argument('--source', required=True)
    parser.add_argument('--archive', type=Path, help='Build the selected tests with this guest compiler first')
    parser.add_argument('--user', default='mdtester')
    args = parser.parse_args()
    options = ['--archive', args.archive] if args.archive else []
    run_fixture(args.session, 'ltp', [args.source, args.user, *options],
                [args.archive] if args.archive else [])


if __name__ == '__main__':
    main()
