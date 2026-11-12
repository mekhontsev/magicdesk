#!/usr/bin/env python3
"""Mixed native/control epoll through the production shell runtime."""
import argparse
import re
from pathlib import Path
from test_oci_services import Suite, add_machine_entry_argument

MODES = ('exclusive', 'exclusive-filtered', 'mixed', 'filtered', 'shared', 'shared-filtered', 'concurrent-admission', 'admission-file-waiters', 'cold-admission', 'native-ready', 'native-capacity', 'close-filtered', 'retire-filtered',
         'poll-sandbox', 'poll-sandbox-protected', 'wait-fence')

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
        suite.upload(args.build / 'libmagicdesk_guest_readiness_guest.so', suite.base + '/readiness')
        suite.chmod('700', suite.base + '/readiness')
        suite.command(prefix + ['--', '/bin/mkdir', target])
        created = True
        for repetition in range(args.repetitions):
            for mode in args.mode or MODES:
                command = [target + '/readiness', '--' + mode]
                trace = f'trace-{repetition}-{mode}.log'
                if args.trace:
                    command = ['/usr/bin/strace', '-f', '-qq', '-o', target + '/' + trace] + command
                try:
                    output = suite.command(prefix + ['--statistics', '--bind', suite.base, target, '--deadline-seconds', '20',
                                                     '--'] + command, timeout=35, expected=0)
                finally:
                    if args.trace:
                        path = suite.base + '/' + trace
                        found = suite.command(['/system/bin/sh', '-c',
                                               '[ ! -f "$1" ] || printf %s "$1"', 'fixture', path], quiet=True)
                        if found:
                            artifact = args.build / ('epoll-' + suite.report['id'] + '-' + trace)
                            suite.download(path, artifact)
                            suite.report.setdefault('traces', []).append(str(artifact))
                assert ('PASS poll sandbox' if mode.startswith('poll-sandbox') else 'PASS mixed epoll') in output
                if mode in ('mixed', 'filtered', 'shared', 'shared-filtered') and args.machine_entry is not False and not args.trace:
                    stops = re.search(r'MD_EXECUTION_STOP reason=wait calls=(\d+)', output)
                    assert not stops or int(stops[1]) < 128, output
                    completed = re.search(r'MD_EXECUTION taskEntries=\d+ completed=(\d+)', output)
                    assert completed and int(completed[1]) >= 1024, output
        suite.report['passed'] = True
    finally:
        try:
            if created:
                suite.command(prefix + ['--', '/bin/rmdir', target])
        finally:
            try:
                suite.command(['rm', '-rf', '--', suite.base], quiet=True)
            finally:
                suite.finish()


if __name__ == '__main__':
    main()
