#!/usr/bin/env python3
"""Guest seccomp and signal contracts, identical for every Android executor."""
import argparse
from pathlib import Path

from fixture_executor import FixtureExecutor
from test_oci_services import Suite


POLICY = ('plain', 'blocked', 'ignored', 'allow', 'errno', 'trap', 'blocked-trap',
          'ignored-trap', 'kill', 'protected-creation', 'modern-mount',
          'modern-mount-filtered', 'modern-mount-denied', 'cold-identity',
          'cold-identity-denied', 'oom-sharing', 'pid-init-signals',
          'optional-native-calls', 'unavailable-calls', 'accept-calls', 'socket-service-filter',
          'vfork-shared-socket', 'namespace-service-filter', 'relative-path-filter',
          'proc', 'proc-limits', 'dumpability')
SYNC = ('tsync-cold', 'tsync-cold-fork', 'tsync-notification', 'tsync-notification-native',
        'tsync-continue', 'tsync-continue-native', 'tsync-continue-identity',
        'tsync-continue-fcntl', 'tsync-continue-open', 'tsync-continue-stat', 'tsync-continue-pid', 'socket-tsync',
        'signal-wait-tsync', 'signal-epoll-tsync')
NOTIFICATIONS = ('listener', 'listener-continue', 'listener-error', 'listener-close',
                 'listener-stat', 'listener-reject', 'listener-cold', 'listener-cold-continue',
                 'listener-interrupt', 'listener-restart', 'listener-cold-interrupt',
                 'listener-cold-restart', 'listener-addfd-send', 'listener-addfd',
                 'listener-addfd-fixed', 'listener-addfd-fixed-send',
                 'listener-addfd-protected-send', 'listener-addfd-protected-fixed',
                 'listener-addfd-protected', 'listener-exec', 'listener-target-death')
SIGNALS = ('signal-entry', 'signal-entry-filtered', 'signal-views', 'signal-wait',
           'signal-wait-filtered', 'signal-wait-threads', 'signal-wait-masks',
           'signal-wait-masks-filtered', 'trace-cookie-0', 'trace-cookie-55',
           'trace-cookie-65535', 'signal-descriptors', 'signal-descriptors-filtered',
           'signal-mask-edges', 'signal-mask-queries', 'signal-descriptor-interrupt', 'signal-descriptor-restart',
           'signal-wait-pressure', 'signal-wait-pressure-filtered', 'signal-epoll',
           'signal-epoll-filtered', 'signal-epoll-pressure', 'signal-epoll-pressure-filtered',
           'signal-epoll-domains', 'signal-epoll-fault', 'signal-epoll-fault-filtered',
           'socket-signals', 'socket-child-signals')
GROUPS = dict(policy=POLICY, sync=SYNC, notifications=NOTIFICATIONS, signals=SIGNALS)


def cases(group):
    selected = GROUPS if group == 'all' else {group: GROUPS[group]}
    for names in selected.values():
        for name in names:
            yield name, 'guest_policy', [name]
    if group in ('all', 'signals'):
        for mode in (*range(15), *range(17, 38), 68, 69, 70):
            yield 'signal-shadow-' + str(mode), 'signal_shadow', ['guest', str(mode)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build', type=Path, required=True)
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument('--name')
    target.add_argument('--store')
    parser.add_argument('--output', type=Path, default=Path('build/guest-policy'))
    parser.add_argument('--group', choices=('all', *GROUPS), default='all')
    parser.add_argument('--case', action='append', choices=[name for name, _, _ in cases('all')])
    parser.add_argument('--entry', choices=('both', 'lazy', 'base'), default='both')
    parser.add_argument('--diagnostics', action='store_true')
    FixtureExecutor.add_argument(parser)
    args = parser.parse_args()
    selected = [item for item in cases(args.group) if not args.case or item[0] in args.case]
    assert selected, 'No selected policy cases'
    suite = Suite(args.output, args.build, executor=args.executor)
    store = None
    mount = '/tmp/md-policy-' + suite.report['id']
    try:
        store = args.store or suite.installed_environment(args.name)['store']
        for fixture in sorted({item[1] for item in selected}):
            binary = 'libmagicdesk_guest_' + fixture + '.so'
            suite.upload(args.build / binary, suite.base + '/' + binary)
            suite.chmod('755', suite.base + '/' + binary)
        suite.command(suite.guest(store, '--user', '0:0', '--', '/bin/mkdir', mount), quiet=True)
        for entry in ((0, 1) if args.entry == 'both' else (int(args.entry == 'lazy'),)):
            for name, fixture, options in selected:
                record = dict(name=name, entry=entry, passed=False)
                suite.report['cases'].append(record)
                try:
                    output = suite.command(suite.guest(store, '--user', '0:0',
                        *(['--diagnostics'] if args.diagnostics else []),
                        '--env', 'SHROOT_MACHINE_ENTRY=' + str(entry),
                        '--bind-ro', suite.base, mount, '--deadline-seconds', '60', '--',
                        mount + '/libmagicdesk_guest_' + fixture + '.so', *options), timeout=75, quiet=True)
                    assert 'PASS' in output and 'FAIL' not in output, output
                    record['passed'] = True
                except Exception as error:
                    record['error'] = str(error)
                print(('PASS' if record['passed'] else 'FAIL') + f' {name} entry={entry}', flush=True)
        suite.report['passed'] = all(record['passed'] for record in suite.report['cases'])
    finally:
        try:
            if store:
                suite.command(suite.guest(store, '--user', '0:0', '--', '/bin/rmdir', mount), quiet=True)
            suite.command(['rm', '-rf', suite.base], quiet=True)
        finally:
            suite.finish()
    assert suite.report['passed'], 'Failed policy cases: ' + str(suite.output)


if __name__ == '__main__':
    main()
