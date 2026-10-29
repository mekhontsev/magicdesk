#!/usr/bin/env python3
"""Compile a small fragment and adapt a private stock SteamOS KWin ELF copy."""
import argparse
from pathlib import Path
import shlex

from test_oci_services import Suite

FIXTURES = ('kwin-binary-allocator.cpp', 'kwin-binary-probe.cpp',
            'kwin-binary-adapt.py', 'kwin-binary-entry.S')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--name', default='steamos')
    parser.add_argument('--directory', help='Reuse only this experiment\'s retained work directory')
    parser.add_argument('--keep', action='store_true', help='Retain device artifacts for investigation')
    args = parser.parse_args()
    if args.directory:
        path = Path(args.directory)
        if path.parent != Path('/data/local/tmp') or not path.name.startswith('md-oci-services-'):
            parser.error('--directory must name this experiment\'s md-oci-services directory')
    output = Path('build/kwin-binary-adaptation')
    suite = Suite(output)
    work = args.directory or suite.base
    work_owned = not args.directory
    try:
        if args.directory:
            suite.command(['test', '-f', work + '/kwin-binary-adapt.py'], quiet=True)
            work_owned = True
        suite.report['workDirectory'] = work
        info = suite.installed_environment(args.name)
        suite.report['environment'] = info
        for name in FIXTURES:
            suite.transport.upload(suite.client, Path(__file__).with_name('fixtures') / name,
                                   work + '/' + name, overwrite=True)
        suite.command(['chmod', '777', work])
        suite.command(['chmod', '644', *[work + '/' + name for name in FIXTURES]])
        suite.command([suite.runner, '--store', info['store'], '--user', '1000:1000',
                       '--', '/bin/mkdir', '-p', '/tmp/md-kwin-binary'])
        guest = [suite.runner, '--store', info['store'], '--user', '1000:1000',
                 '--bind', work, '/tmp/md-kwin-binary']
        command = '\n'.join([
            'set -eu',
            '. /etc/profile.d/magicdesk-graphics.sh',
            'cd /tmp/md-kwin-binary',
            'if [ ! -x venv/bin/python ]; then python3 -m venv venv; fi',
            'venv/bin/python -m pip --disable-pip-version-check --no-cache-dir install --only-binary=:all: lief==0.17.1',
            'venv/bin/python kwin-binary-adapt.py',
        ])
        try:
            suite.command([*guest, '--', '/bin/sh', '-c', command], timeout=240)
            suite.report['passed'] = True
        finally:
            for name in ('result.json', 'probe', 'patched/libkwin.so.6', 'patched/libmd-kwin-fragment.so'):
                exists = suite.command(['/system/bin/sh', '-c', 'test ! -f ' + shlex.quote(work + '/' + name)
                                        + ' || printf present'], quiet=True)
                if exists:
                    destination = output / name
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    suite.transport.download(suite.client, work + '/' + name, destination, overwrite=True)
    finally:
        try:
            if work_owned and not args.keep:
                suite.command(['rm', '-rf', work], quiet=True)
            if work != suite.base:
                suite.command(['rmdir', suite.base], quiet=True)
        finally:
            suite.finish()


if __name__ == '__main__':
    main()
