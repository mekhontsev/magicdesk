#!/usr/bin/env python3
"""Stock k3s acceptance within one installed distribution."""
import argparse
from pathlib import Path

from k3s_fixture import add_runtime_arguments, run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path, nargs='?', help='Staged runtime; omit to test the installed APK')
    parser.add_argument('environment', help='Installed recipe with Python 3 and util-linux')
    parser.add_argument('k3s', type=Path, help='Verified upstream ARM64 k3s binary')
    parser.add_argument('--nodes', type=int, choices=(1, 2), default=1)
    add_runtime_arguments(parser)
    args = parser.parse_args()
    args.agent_environment = None
    run(args)


if __name__ == '__main__':
    main()
