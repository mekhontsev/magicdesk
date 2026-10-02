#!/usr/bin/env python3
"""Install explicit test-owned Linux Mesa drivers, never Android/APK libraries."""
import argparse
import hashlib
import shlex
from pathlib import Path
from test_oci_services import Suite


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--store', required=True)
    parser.add_argument('--runtime', required=True)
    parser.add_argument('--zink', type=Path, required=True)
    parser.add_argument('--turnip', type=Path, required=True)
    parser.add_argument('--x11-helper', type=Path, required=True)
    args = parser.parse_args()
    suite = Suite(args.output)
    suite.runner = args.runtime + '/libmagicdesk_guest_run.so'
    try:
        suite.command([suite.runner, '--store', args.store, '--', '/bin/sh', '-ec',
                       'command -v blender; mkdir -p /opt/md-gpu/lib /home/shell /usr/local/bin'])
        assets = [(args.zink, None), (args.turnip, '/opt/md-gpu/lib/libvulkan_freedreno.so'),
                  (Path(__file__).parent / 'fixtures/turnip.json', '/opt/md-gpu/turnip.json'),
                  (args.x11_helper, '/usr/local/bin/md-x11-desktop')]
        suite.report['drivers'] = []
        for index, (source, destination) in enumerate(assets):
            remote = suite.base + '/asset-' + str(index)
            digest = hashlib.sha256(source.read_bytes()).hexdigest()
            suite.transport.upload(suite.client, source, remote)
            actual = suite.command(['sha256sum', remote], quiet=True).split()[0]
            assert actual == digest, source
            inner = 'tar -xzf - -C /opt/md-gpu' if destination is None else 'cat > ' + shlex.quote(destination)
            command = 'set -o pipefail\ncat ' + shlex.quote(remote) + ' | ' + shlex.join([
                suite.runner, '--store', args.store, '--', '/bin/sh', '-ec', inner])
            suite.command(['/system/bin/sh', '-c', command])
            suite.report['drivers'].append({'source': str(source), 'sha256': digest, 'destination': destination})
        suite.command([suite.runner, '--store', args.store, '--', '/bin/sh', '-ec',
                       'chmod 755 /usr/local/bin/md-x11-desktop; blender --version'])
        suite.report['passed'] = True
    finally:
        suite.finish()


if __name__ == '__main__':
    main()
