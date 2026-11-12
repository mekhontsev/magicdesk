#!/usr/bin/env python3
"""Deterministic signals at every executed SVC-only gate instruction."""
import argparse
from pathlib import Path
from test_oci_services import Suite


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    args = parser.parse_args()
    suite = Suite(Path('build/svc-direct-signals'), None)
    try:
        binary = suite.base + '/signals'
        suite.upload(args.build / 'libmagicdesk_guest_test_svc_direct_signal.so', binary)
        suite.chmod('700', binary)
        output = suite.command([binary], timeout=250)
        assert 'PASS SVC arbitrary boundary:' in output
        assert output.count('SVC_SIGNAL_PATH ') == 8
        output = suite.command([binary, '--mmap-guard'], timeout=60)
        assert 'PASS mmap argument guard:' in output
        for control in ('--unsafe-context', '--unsafe-checkpoint'):
            output = suite.command([binary, control], timeout=20)
            assert 'PASS negative signal control:' in output
        output = suite.command([binary, '--queued'], timeout=30)
        assert output.count('PASS queued RT:') == 8
        output = suite.command([binary, '--queued-unsafe'], timeout=20)
        assert 'PASS negative queued RT:' in output
        suite.report['passed'] = True
        suite.command(['rm', '-rf', suite.base])
    finally:
        suite.finish()


if __name__ == '__main__':
    main()
