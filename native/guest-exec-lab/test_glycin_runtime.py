#!/usr/bin/env python3
"""Distribution Glycin with Bubblewrap and its original seccomp policy, UID 2000."""
import argparse
from pathlib import Path

from test_oci_services import Suite, Server


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('store')
    parser.add_argument('--trace', action='store_true')
    parser.add_argument('--library', default='libglycin-1.so.0')
    parser.add_argument('--format', choices=('png', 'svg'), default='png')
    parser.add_argument('--fixture-deb', type=Path, action='append', default=[],
                        help='Extract unmodified distribution binaries into this dedicated test store')
    args = parser.parse_args()
    suite = Suite(args.build, args.build)
    suite.report['store'] = args.store
    try:
        source = Path(__file__).with_name('test_glycin_loader.py')
        suite.upload(source, suite.base + '/loader.py')
        target = '/tmp/md-glycin-' + suite.report['id']
        base = [suite.runner, '--store', args.store, '--user', '2000:2000']
        if args.trace:
            base += ['--diagnostics']
        suite.command(base + ['--', '/bin/mkdir', target])
        prefix = base + ['--bind-ro', suite.base, target, '--deadline-seconds', '60', '--']
        suite.report['fixturePackages'] = []
        for index, package in enumerate(args.fixture_deb):
            name = 'fixture-' + str(index) + '.deb'
            suite.upload(package, suite.base + '/' + name)
            setup = [suite.runner, '--store', args.store, '--user', '0:0', '--bind-ro', suite.base, target,
                     '--deadline-seconds', '60', '--', '/usr/bin/dpkg-deb']
            suite.report['fixturePackages'].append(suite.command(setup + ['-f', target + '/' + name]))
            suite.command(setup + ['-x', target + '/' + name, '/'])
        suite.report['format'] = args.format
        suite.report['packages'] = suite.command(prefix + ['/bin/sh', '-ec', '''
cat /etc/os-release
if command -v dpkg-query >/dev/null; then
    dpkg-query -W '*glycin*' bubblewrap
elif command -v rpm >/dev/null; then
    rpm -qa '*glycin*' bubblewrap
elif command -v pacman >/dev/null; then
    pacman -Q glycin bubblewrap
else
    exit 1
fi
'''])
        trace = ['/usr/bin/strace', '-f', '-s', '160'] if args.trace else []
        command = prefix + trace + ['/usr/bin/env', 'RUST_LOG=glycin=debug', 'HOME=/tmp',
                                    'GLYCIN_LIBRARY=' + args.library,
                                    'python3', target + '/loader.py', '--format', args.format]
        process = Server(suite, command)
        try:
            process.ready('PASS Glycin BWRAP decoded 2x1 RGB pixels', timeout=80)
            process.completed()
            result = ''.join(process.log)
        finally:
            process.close()
        assert 'PASS Glycin BWRAP decoded 2x1 RGB pixels' in result
        assert 'running without sandbox' not in result
        suite.report['passed'] = True
    finally:
        try:
            if 'target' in locals():
                suite.command(base + ['--', '/bin/rmdir', target])
        finally:
            suite.finish()
    return 0 if suite.report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
