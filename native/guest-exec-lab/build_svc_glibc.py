#!/usr/bin/env python3
"""Reuse existing glibc libraries; compile only the small comparison workload."""
import argparse
import tarfile
import tempfile
from pathlib import Path
from test_oci_services import Suite


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stock', type=Path, help='stock toolchain sysroot')
    parser.add_argument('output', type=Path)
    parser.add_argument('--environment', default='ubuntu-fuse')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    lab = Path(__file__).resolve().parent
    suite = Suite(args.output / 'receipts')
    try:
        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary) / 'inputs.tar'
            with tarfile.open(archive, 'w') as out:
                out.add(args.stock / 'usr/include', arcname='include')
                directory = args.stock / 'usr/lib/aarch64-linux-gnu'
                for name in ('libc.a', 'crt1.o', 'crti.o', 'crtn.o', 'Scrt1.o', 'libc.so.6',
                             'libc_nonshared.a', 'ld-linux-aarch64.so.1', 'libgcc_s.so.1'):
                    out.add(directory / name, arcname='stock/' + name)
                out.add(lab / 'test_cooperative_libc.c', arcname='probe.c')
                out.add(lab / 'build-svc-glibc-probes.sh', arcname='build.sh')
            suite.upload(archive, suite.base + '/inputs.tar')
        suite.command(['tar', '-oxf', suite.base + '/inputs.tar', '-C', suite.base])
        suite.command(['shroot', 'exec', args.environment, '--user', 'root',
                       '--bind', suite.base, '/tmp', '--', '/bin/sh', '/tmp/build.sh'], timeout=120)
        for kind in ('stock', 'stock-dynamic'):
            suite.download(suite.base + '/probe-' + kind,
                                     args.output / ('probe-' + kind), overwrite=True)
        suite.command(['rm', '-rf', suite.base])
        suite.report['passed'] = True
    finally:
        suite.finish()


if __name__ == '__main__':
    main()
