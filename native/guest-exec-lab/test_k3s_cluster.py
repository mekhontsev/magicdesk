#!/usr/bin/env python3
"""Stock k3s cross-distribution cluster using two existing recipe inputs."""
import argparse
from pathlib import Path

from k3s_fixture import add_runtime_arguments, run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path, nargs='?', help='Staged runtime; omit to test the installed APK')
    parser.add_argument('environment', metavar='server_environment', help='Installed server recipe')
    parser.add_argument('agent_environment', help='Installed agent recipe, mounted read-only')
    parser.add_argument('k3s', type=Path, help='Verified upstream ARM64 k3s binary')
    add_runtime_arguments(parser)
    parser.add_argument('--agent-store', help='Existing agent store accessible to the selected executor')
    args = parser.parse_args()
    args.nodes = 2
    run(args)


if __name__ == '__main__':
    main()
