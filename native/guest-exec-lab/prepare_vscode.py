#!/usr/bin/env python3
"""Install verified official ARM64 VS Code and C/C++ into a disposable Ubuntu store."""
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
    args = p.parse_args()
    suite = Suite(args.build, args.build)
    try:
        suite.report['instance'] = args.store
        for local, digest, target in [(args.code, args.code_sha256, 'code.tar.gz'),
                                       (args.extension, args.extension_sha256, 'cpp.vsix')]:
            with local.open('rb') as f:
                assert hashlib.file_digest(f, 'sha256').hexdigest() == digest
            remote = suite.base + '/' + target
            suite.transport.upload(suite.client, local, remote)
            assert suite.command(['sha256sum', remote], quiet=True).split()[0] == digest
            suite.command(['/system/bin/sh', '-c', 'cat ' + shlex.quote(remote) + ' | '
                + shlex.join(suite.run(args.store, '--', '/bin/sh', '-ec', 'cat > /tmp/' + target))], timeout=240)
        suite.command(suite.run(args.store, '--env', 'DEBIAN_FRONTEND=noninteractive', '--', '/bin/sh', '-ec', '''
apt-get update
apt-get install -y --no-install-recommends libnss3 libasound2t64 libgbm1 libgl1 libegl1 libxss1 libgtk-3-0t64 libx11-xcb1 libsecret-1-0 libxkbfile1 dbus-x11 ca-certificates
id mdcode >/dev/null 2>&1 || useradd -m -u 2001 -s /bin/bash mdcode
mkdir -p /opt/md-vscode
tar -xzf /tmp/code.tar.gz --strip-components=1 -C /opt/md-vscode
test -x /opt/md-vscode/code
chown root:root /opt/md-vscode/chrome-sandbox
chmod 4755 /opt/md-vscode/chrome-sandbox
mkdir -p /home/mdcode/extensions
chown mdcode:mdcode /home/mdcode/extensions
'''), timeout=300)
        suite.command([suite.runner, '--store', args.store, '--user', 'mdcode', '--home', '/home/mdcode', '--',
            '/usr/bin/env', 'ELECTRON_RUN_AS_NODE=1', '/opt/md-vscode/code',
            '/opt/md-vscode/resources/app/out/cli.js', '--install-extension', '/tmp/cpp.vsix',
            '--extensions-dir', '/home/mdcode/extensions', '--user-data-dir', '/home/mdcode/code-data'], timeout=180)
        suite.report['passed'] = True
    finally:
        suite.finish()


if __name__ == '__main__':
    main()
