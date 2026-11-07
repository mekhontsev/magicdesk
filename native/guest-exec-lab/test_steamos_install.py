#!/usr/bin/env python3
"""Install the official SteamOS image through the complete user-facing recipe.

Retains the named test environment for runtime and graphical checks. Uses the
installed APK's CLI; native-build testing is a separate test_steamos_runtime run.
"""
import argparse
import hashlib
from pathlib import Path
from test_oci_services import Suite


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--name', default='steamos-recipe-check')
    parser.add_argument('--downloads', default='/data/local/tmp/magicdesk/downloads')
    parser.add_argument('--gui', choices=['plasma', 'keep'], default='plasma')
    parser.add_argument('--gpu', choices=['software', 'keep', 'auto', 'turnip'], default='software')
    parser.add_argument('--resume', action='store_true')
    args = parser.parse_args()
    suite = Suite(Path('build/steamos-install-results'))
    installer = Path(__file__).resolve().parents[2] / 'scripts/install_linux.sh'
    suite.report['installerSha256'] = hashlib.sha256(installer.read_bytes()).hexdigest()
    suite.report['environment'] = args.name
    try:
        suite.upload(installer, suite.base + '/install_linux.sh')
        options = ['--distro', 'steamos', '--name', args.name, '--downloads', args.downloads,
                   '--gui', args.gui, '--gpu', args.gpu, '--fonts', 'keep', '--yes']
        if args.resume:
            options.append('--resume')
        else:
            options.extend(['--dns', '1.1.1.1'])
        suite.command(['/system/bin/sh', suite.base + '/install_linux.sh', *options], timeout=3600)
        suite.report['store'] = suite.command(['shroot', 'path', args.name]).strip()
        suite.command(['shroot', 'exec', args.name, '--', '/bin/sh', '-ec',
                       'test "$(id -un)" = steamos; test -x "$HOME/.local/share/Steam/steamrtarm64/steam"; '
                       'test -x /usr/local/bin/magicdesk-steam-game; '
                       'for dir in "$HOME/.config" "$HOME/.cache"; do '
                       'if test -e "$dir"; then test -w "$dir" && test -x "$dir" || exit 1; fi; done; '
                       'cat /etc/os-release; id'])
        suite.report['passed'] = True
    finally:
        suite.finish()


if __name__ == '__main__':
    main()
