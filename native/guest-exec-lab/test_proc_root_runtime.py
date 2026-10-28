#!/usr/bin/env python3
"""Foreign process roots in a stock Python userspace under the shell runtime."""
import argparse
from pathlib import Path
from test_oci_services import Suite


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('store')
    parser.add_argument('--entry', choices=('lazy', 'base'), default='lazy')
    args = parser.parse_args()
    suite = Suite(args.build, args.build)
    target = '/tmp/md-proc-root-' + suite.report['id']
    base = [suite.runner, '--store', args.store, '--user', '0:0',
            '--env', 'SHROOT_MACHINE_ENTRY=' + ('0' if args.entry == 'base' else '1')]
    suite.report['entryMode'] = args.entry
    try:
        suite.transport.upload(suite.client, Path(__file__).with_name('test_proc_root_guest.py'), suite.base + '/probe.py')
        suite.transport.upload(suite.client, Path(__file__).with_name('test_fd_selector_guest.py'), suite.base + '/selectors.py')
        suite.command(base + ['--', '/bin/mkdir', target])
        suite.command(base + ['--bind-ro', suite.base, target, '--deadline-seconds', '60', '--',
                              '/usr/bin/python3', target + '/selectors.py'])
        for wrapper in ([], ['/usr/bin/unshare', '--pid', '--fork', '--mount-proc']):
            output = suite.command(base + ['--bind-ro', suite.base, target, '--deadline-seconds', '90',
                                           '--'] + wrapper + ['/usr/bin/python3', '-X', 'faulthandler', target + '/probe.py'])
            assert 'PASS foreign proc root: namespace, chroot, suffix' in output, output
        suite.report['passed'] = True
    finally:
        try:
            suite.command(base + ['--', '/bin/rmdir', target])
        finally:
            suite.finish()


if __name__ == '__main__':
    main()
