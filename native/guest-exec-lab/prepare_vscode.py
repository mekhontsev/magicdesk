#!/usr/bin/env python3
"""Install verified official ARM64 VS Code and C/C++ into a test distribution."""
import argparse
import hashlib
from pathlib import Path
import shlex
from test_oci_services import Suite


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('build', type=Path)
    p.add_argument('--store', required=True)
    p.add_argument('--code', type=Path, required=True)
    p.add_argument('--code-sha256', required=True)
    p.add_argument('--extension', type=Path, required=True)
    p.add_argument('--extension-sha256', required=True)
    p.add_argument('--installed', action='store_true', help='Use the installed APK runtime')
    p.add_argument('--user', default='mdcode')
    args = p.parse_args()
    suite = Suite(args.build, None if args.installed else args.build)
    try:
        suite.report['instance'] = args.store
        for local, digest, target in [(args.code, args.code_sha256, 'code.tar.gz'),
                                       (args.extension, args.extension_sha256, 'cpp.vsix')]:
            with local.open('rb') as f:
                assert hashlib.file_digest(f, 'sha256').hexdigest() == digest
            remote = suite.base + '/' + target
            suite.upload(local, remote)
            assert suite.command(['sha256sum', remote], quiet=True).split()[0] == digest
            suite.command(['/system/bin/sh', '-c', 'cat ' + shlex.quote(remote) + ' | '
                + shlex.join(suite.run(args.store, '--', '/bin/sh', '-ec', 'cat > /tmp/' + target))], timeout=240)
        suite.command(suite.run(args.store, '--env', 'DEBIAN_FRONTEND=noninteractive', '--', '/bin/sh', '-ec', '''
. /etc/os-release
case "$ID" in
    debian|ubuntu)
        apt-get update
        apt-get install -y --no-install-recommends gcc gdb libnss3 libasound2t64 libgbm1 libgl1 libegl1 libxss1 libgtk-3-0t64 libx11-xcb1 libsecret-1-0 libxkbfile1 dbus-x11 ca-certificates ;;
    arch|archarm|steamos|manjaro-arm|armtix|artix)
        pacman -S --needed --noconfirm gcc gdb nss alsa-lib libglvnd libxss gtk3 libsecret libxkbfile dbus ca-certificates ;;
    fedora)
        dnf install -y gcc gdb nss alsa-lib mesa-libgbm libglvnd libXScrnSaver gtk3 libsecret libxkbfile dbus-x11 ca-certificates ;;
    void)
        xbps-install -y gcc gdb nss alsa-lib libglvnd libXScrnSaver gtk+3 libsecret libxkbfile dbus ca-certificates ;;
    *) echo "Unsupported VS Code fixture package family: $ID" >&2; exit 1 ;;
esac
id "$1" >/dev/null 2>&1 || useradd -m -s /bin/bash "$1"
mkdir -p /opt/md-vscode
tar --no-same-owner -xzf /tmp/code.tar.gz --strip-components=1 -C /opt/md-vscode
test -x /opt/md-vscode/code
chown root:root /opt/md-vscode/chrome-sandbox
chmod 4755 /opt/md-vscode/chrome-sandbox
ln -sfn /opt/md-vscode/bin/code /usr/local/bin/code
''', 'vscode-prepare', args.user), timeout=300)
        home = suite.command(suite.run(args.store, '--', '/bin/sh', '-ec',
                             'getent passwd "$1" | cut -d: -f6', 'fixture', args.user), quiet=True).strip()
        assert home.startswith('/home/')
        suite.command([suite.runner, '--store', args.store, '--user', args.user, '--home', home, '--',
            '/usr/bin/env', 'ELECTRON_RUN_AS_NODE=1', '/opt/md-vscode/code',
            '/opt/md-vscode/resources/app/out/cli.js', '--install-extension', '/tmp/cpp.vsix',
            '--extensions-dir', home + '/.vscode/extensions', '--user-data-dir', home + '/code-data'], timeout=180)
        suite.report['passed'] = True
    finally:
        suite.finish()


if __name__ == '__main__':
    main()
