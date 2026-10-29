#!/usr/bin/env python3
"""Mixed native/control epoll through the production shell runtime."""
import argparse
from pathlib import Path
from test_oci_services import Suite, add_machine_entry_argument

MODES = ('mixed', 'filtered', 'shared', 'shared-filtered', 'concurrent-admission', 'cold-admission', 'native-ready', 'native-capacity', 'close-filtered', 'retire-filtered',
         'poll-sandbox', 'poll-sandbox-protected')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build', type=Path, required=True)
    parser.add_argument('--store', required=True)
    parser.add_argument('--repetitions', type=int, default=1)
    parser.add_argument('--trace', action='store_true', help='Exercise guest strace across readiness and signals')
    add_machine_entry_argument(parser)
    parser.add_argument('--diagnostics', action='store_true')
    parser.add_argument('--mode', choices=MODES,
                        action='append', help='Select focused cases; default runs all modes')
    args = parser.parse_args()
    if args.repetitions < 1:
        parser.error('--repetitions must be positive')
    suite = Suite(args.build, args.build, machine_entry=args.machine_entry)
    target = '/md-epoll-' + suite.report['id']
    prefix = suite.guest(args.store, '--user', '0:0')
    if args.diagnostics:
        prefix += ['--diagnostics']
    created = False
    try:
        suite.transport.upload(suite.client, args.build / 'libmagicdesk_guest_readiness_guest.so', suite.base + '/readiness')
        suite.command(['chmod', '700', suite.base + '/readiness'])
        suite.command(prefix + ['--', '/bin/mkdir', target])
        created = True
        for _ in range(args.repetitions):
            for mode in args.mode or MODES:
                denied = mode == 'retire-filtered'
                command = [target + '/readiness', '--' + mode]
                if args.trace:
                    command = ['/usr/bin/strace', '-f', '-qq', '-o', target + '/trace-' + mode + '.log'] + command
                output = suite.command(prefix + ['--bind', suite.base, target, '--deadline-seconds', '20',
                                                 '--'] + command, timeout=35,
                                       expected=125 if denied else 0)
                if denied:
                    assert 'Shroot: cannot retire temporary FD ' in output
                    assert 'Operation not permitted' in output
                    assert 'launch deadline expired' not in output
                else:
                    assert ('PASS poll sandbox' if mode.startswith('poll-sandbox') else 'PASS mixed epoll') in output
        suite.report['passed'] = True
    finally:
        try:
            if created:
                suite.command(prefix + ['--', '/bin/rmdir', target])
        finally:
            suite.finish()


if __name__ == '__main__':
    main()
