#!/usr/bin/env python3
"""Build the stock-Go stress fixture as UID 2000, outside Android app seccomp."""
import argparse
import hashlib
from pathlib import Path
from test_oci_services import Suite


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--source', type=Path, default=Path(__file__).with_name('test_svc_go.go'))
    parser.add_argument('--cgo-environment', help='existing test environment supplying an unmodified Linux C toolchain')
    args = parser.parse_args()
    expected = '94f3e30b8e374bc285e7dadc11e0865726b9bc6e85b841ccceaabc0214c6b7c8'
    assert hashlib.sha256(args.archive.read_bytes()).hexdigest() == expected, 'requires official Go 1.27.2 linux-arm64'
    suite = Suite(Path('build/svc-go-build'))
    try:
        suite.transport.upload(suite.client, args.archive, suite.base + '/go.tar.gz')
        suite.transport.upload(suite.client, args.source, suite.base + '/probe.go')
        suite.command(['tar', '-xzf', suite.base + '/go.tar.gz', '-C', suite.base], quiet=True)
        suite.command(['mkdir', suite.base + '/tmp', suite.base + '/cache'])
        if args.cgo_environment:
            suite.transport.upload(suite.client, Path(__file__).with_name('test_svc_cgo.go'), suite.base + '/cgo.go')
            suite.command(['shroot', 'exec', args.cgo_environment, '--user', 'root',
                           '--bind', suite.base, '/tmp', '--', '/usr/bin/env',
                           'CGO_ENABLED=1', 'GOTOOLCHAIN=local', 'GOENV=off', 'CC=/usr/bin/cc',
                           'GOROOT=/tmp/go', 'GOCACHE=/tmp/cache', 'GOTMPDIR=/tmp/tmp',
                           '/tmp/go/bin/go', 'build', '-p', '4', '-ldflags=-linkmode=external -extldflags=-static',
                           '-o', '/tmp/probe', '/tmp/probe.go', '/tmp/cgo.go'], timeout=240)
        else:
            suite.command(['env', 'CGO_ENABLED=0', 'GOTOOLCHAIN=local', 'GOENV=off',
                       'GOROOT=' + suite.base + '/go', 'GOCACHE=' + suite.base + '/cache',
                       'GOTMPDIR=' + suite.base + '/tmp', 'CC=/system/bin/true', 'CXX=/system/bin/true',
                       suite.base + '/go/bin/go', 'build', '-p', '4', '-o', suite.base + '/probe',
                       suite.base + '/probe.go'], timeout=240)
        suite.transport.download(suite.client, suite.base + '/probe', args.output, overwrite=True)
        suite.command(['rm', '-rf', suite.base])
        suite.report['passed'] = True
    finally:
        suite.finish()


if __name__ == '__main__':
    main()
