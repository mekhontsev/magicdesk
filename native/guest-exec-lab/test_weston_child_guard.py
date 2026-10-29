#!/usr/bin/env python3
"""Public-API Weston guard proof; all installed binaries remain unchanged."""
import argparse
from pathlib import Path
from test_oci_services import Suite

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--name', required=True)
args = parser.parse_args()
out = Path('build/weston-child-guard') / args.name
suite = Suite(out)
target = '/tmp/md-weston-child'
info = None
try:
    info = suite.installed_environment(args.name)
    assert not info['launches'], 'Keep this experiment isolated'
    suite.report['environment'] = info
    for name in ('weston-child-guard.c', 'weston-child-probe.c', 'weston-child-build.py'):
        suite.transport.upload(suite.client, Path(__file__).with_name('fixtures') / name, suite.base + '/' + name)
    suite.command(['chmod', '-R', 'a+rX', suite.base])
    suite.command(['chmod', '777', suite.base])
    suite.command([suite.runner, '--store', info['store'], '--', '/bin/mkdir', '-p', target])
    suite.command([suite.runner, '--store', info['store'], '--bind', suite.base, target,
                   '--', '/usr/bin/python3', target + '/weston-child-build.py'])
    suite.report['passed'] = True
finally:
    try:
        for name in ('result.json', 'libmd-weston-child.so', 'probe'):
            if suite.command(['/system/bin/sh', '-c', 'test ! -f "$1" || printf present', 'sh', suite.base + '/' + name], quiet=True):
                suite.transport.download(suite.client, suite.base + '/' + name, out / name, overwrite=True)
        suite.command(['rm', '-rf', suite.base])
        if info:
            suite.command([suite.runner, '--store', info['store'], '--', '/bin/rmdir', target])
    finally:
        suite.finish()
