#!/usr/bin/env python3
"""Deliver filesystem FDs without requiring an unrelated syscall in the guest filter."""
import argparse
from pathlib import Path
from test_oci_services import Suite


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('store')
    parser.add_argument('--diagnostics', action='store_true')
    args = parser.parse_args()
    suite = Suite(args.build, args.build)
    target = '/tmp/md-filtered-fd-' + suite.report['id']
    prefix = [suite.runner, '--store', args.store, '--user', '0:0']
    try:
        suite.upload(Path(__file__).with_suffix('.c'), suite.base + '/test.c')
        suite.chmod('777', suite.base)
        suite.command(prefix + ['--', '/bin/mkdir', target])
        command = prefix + ['--bind', suite.base, target, '--deadline-seconds', '60']
        if args.diagnostics:
            command += ['--diagnostics', '--env', 'MD_FILTER_TRAP=1']
        suite.command(command + ['--', '/usr/bin/cc', '-O2', '-Wall', '-Wextra', '-Werror',
                                 target + '/test.c', '-o', target + '/test'])
        for mode in ('0', '1'):
            selected = command + ['--env', 'SHROOT_MACHINE_ENTRY=' + mode]
            suite.command(selected + ['--', target + '/test', target + '/created-' + mode])
            suite.command(selected + ['--', '/bin/sh', '-ec',
                'if command -v file >/dev/null; then file /bin/sh /etc/os-release; '
                'else echo "SKIP file utility: not installed"; fi'])
        suite.report['passed'] = True
    finally:
        try:
            suite.command(prefix + ['--', '/bin/rmdir', target])
        finally:
            suite.finish()


if __name__ == '__main__':
    main()
