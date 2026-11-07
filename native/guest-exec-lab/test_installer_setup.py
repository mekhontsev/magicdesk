#!/usr/bin/env python3
"""Execute install_linux.sh's guest setup in a disposable prepared store.

This validates the actual package/keyring/settings payload, not image download,
Android catalog registration, rendered GUI workflows or init-system boot.
"""
import argparse
import hashlib
import ipaddress
from pathlib import Path
import subprocess
import tempfile

from test_oci_services import Suite


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('store')
    parser.add_argument('--gui', choices=['none', 'apps', 'xfce', 'weston', 'plasma', 'gnome', 'keep'], default='none')
    parser.add_argument('--fonts', choices=['keep', 'basic', 'cjk'], default='keep')
    parser.add_argument('--gpu', choices=['software'], help='Also apply the actual software graphics recipe')
    parser.add_argument('--extra-packages', default='', help='Space-separated recipe package names')
    parser.add_argument('--dns', action='append', type=ipaddress.ip_address, default=[],
                        help='Explicit test resolver; repeat for multiple servers, omit to retain DNS')
    parser.add_argument('--output', type=Path, default=Path('build/installer-setup-results'))
    args = parser.parse_args()
    installer = Path(__file__).resolve().parents[2] / 'scripts/install_linux.sh'
    # EVENT_WAIT: script emission must finish; timeout is failure, not readiness.
    payload = subprocess.check_output(['sh', str(installer), '--print-system-setup'], timeout=15)
    suite = Suite(args.output, args.build)
    suite.report['store'] = args.store
    suite.report['recipeSha256'] = hashlib.sha256(payload).hexdigest()
    suite.report['recipeArguments'] = [args.gui, 'both', 'keep', 'keep', '', args.fonts, 'keep', args.extra_packages]
    suite.report['dns'] = [str(address) for address in args.dns]
    prefix = [suite.runner, '--store', args.store, '--user', '0:0', '--network', 'routed']
    target = '/md-recipe-' + suite.report['id']
    created = False
    try:
        if args.dns:
            contents = ''.join('nameserver ' + str(address) + '\n' for address in args.dns)
            suite.command(suite.image_command('resolver', args.store, contents, '--replace'))
        with tempfile.TemporaryDirectory(prefix='md-installer-setup-') as temporary:
            script = Path(temporary) / 'setup.sh'
            script.write_bytes(payload)
            suite.upload(script, suite.base + '/setup.sh')
        suite.command(prefix + ['--', '/bin/mkdir', target])
        created = True
        suite.command(prefix + ['--bind-ro', suite.base, target,
                                '--env', 'DEBIAN_FRONTEND=noninteractive',
                                '--env', 'SYSTEMD_IN_CHROOT=1',
                                '--deadline-seconds', '1200', '--', '/bin/sh', target + '/setup.sh',
                                *suite.report['recipeArguments']], timeout=1230)
        if args.gpu:
            graphics = subprocess.check_output(['sh', str(installer), '--print-graphics-setup'], timeout=15)
            fingerprint = hashlib.sha256(graphics).hexdigest()
            with tempfile.TemporaryDirectory(prefix='md-graphics-setup-') as temporary:
                script = Path(temporary) / 'graphics.sh'
                script.write_bytes(graphics)
                suite.upload(script, suite.base + '/graphics.sh')
            suite.report['graphicsRecipeSha256'] = fingerprint
            suite.command(prefix + ['--bind-ro', suite.base, target, '--env', 'SYSTEMD_IN_CHROOT=1',
                                    '--deadline-seconds', '300', '--', '/bin/sh', target + '/graphics.sh',
                                    args.gpu, '1', fingerprint], timeout=330)
        suite.report['passed'] = True
    finally:
        try:
            if created:
                suite.command(prefix + ['--', '/bin/rmdir', target])
        finally:
            suite.finish()


if __name__ == '__main__':
    main()
