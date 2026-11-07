#!/usr/bin/env python3
"""Optional Turnip discovery: profile gating and real shell-UID device semantics.

Uses a prepared test environment with working Turnip; restores its selection.
"""
import argparse
from pathlib import Path
from test_oci_services import Suite
from kernel_profile import KernelProfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('store')
    KernelProfile.add_argument(parser)
    args = parser.parse_args()
    suite = Suite(args.build / 'render-results', args.build, kernel_profile=args.kernel_profile)
    target = '/tmp/md-render-' + suite.report['id']
    prefix = [suite.runner, '--store', args.store, '--user', '0:0']
    saved = False
    try:
        suite.command(prefix + ['--', '/bin/sh', '-ec',
            'test -r /etc/magicdesk/mesa-prefix; mkdir -p /etc/shroot; '
            'test ! -e /etc/shroot/render-node.test-save; '
            'if test -e /etc/shroot/render-node; then '
            'mv /etc/shroot/render-node /etc/shroot/render-node.test-save; fi'])
        saved = True
        suite.command(prefix + ['--', '/bin/sh', '-ec',
            'test ! -e /sys/class/drm; printf "turnip\\n" > /etc/shroot/render-node'])
        if suite.kernel_profile.options:
            output = suite.command(prefix + ['--', '/bin/sh', '-ec',
                'test ! -e /sys/class/drm; printf "PASS optional render node absent\\n"'])
            assert 'render-node unavailable:' in output and 'PASS optional render node absent' in output
        suite.command(prefix + ['--', '/bin/sh', '-ec',
            'printf "turnip-completion-v1\\n" > /etc/shroot/render-node'])
        filename = 'libmagicdesk_guest_test_render_guest.so'
        suite.upload(args.build / filename, suite.base + '/' + filename)
        suite.chmod('755', suite.base, suite.base + '/' + filename)
        suite.command(prefix + ['--', '/bin/mkdir', '-p', target])
        suite.command(prefix + ['--bind', suite.base, target, '--', '/bin/sh', '-ec',
            'for node in /sys/class/drm/renderD*; do test -e "$node"; '
            + target + '/' + filename + ' /dev/dri/${node##*/}; done'])
        suite.report['passed'] = True
    finally:
        if saved:
            suite.command(prefix + ['--', '/bin/sh', '-ec',
                'rm -f /etc/shroot/render-node; '
                'if test -e /etc/shroot/render-node.test-save; then '
                'mv /etc/shroot/render-node.test-save /etc/shroot/render-node; fi'])
        suite.finish()


if __name__ == '__main__':
    main()
