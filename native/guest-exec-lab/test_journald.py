#!/usr/bin/env python3
"""Stock journald service policy and readiness under actual shell UID.

Uses private runtime units and API filesystems. Does not reboot the host.
Probe-only success is not a successful journald or distribution boot.
"""
import argparse
import json
from pathlib import Path
import shlex

from init_fixture import prepare_init, TRACE_SYSCALLS
from test_oci_services import Suite


SETUP = r'''
mkdir -p /run/systemd/system/systemd-journald.service.d
cat > /run/systemd/system/systemd-journald.service.d/probe.conf <<EOF
[Service]
StandardOutput=append:$1/journald.log
StandardError=inherit
EOF
if test "$2" = probe; then
cat >> /run/systemd/system/systemd-journald.service.d/probe.conf <<EOF
ExecStart=
ExecStart=$1/readiness --filter /usr/lib/systemd/systemd-journald
EOF
fi
cat > /run/systemd/system/md-journal.target <<EOF
[Unit]
DefaultDependencies=no
Requires=md-journal-driver.service
After=md-journal-driver.service
EOF
cat > /run/systemd/system/md-journal-driver.service <<EOF
[Unit]
DefaultDependencies=no
[Service]
Type=simple
ExecStart=/bin/sh $1/driver.sh $1 $2
StandardOutput=append:$1/driver.log
StandardError=inherit
EOF
cat > "$1/driver.sh" <<'EOF'
set -ux
systemctl start systemd-journald.service
started=$?
systemctl show -p ActiveState -p SubState -p Result systemd-journald.service
cat "$1/journald.log"
code=1
if test "$2" = probe; then
    if grep -q 'PASS readiness selector admission' "$1/journald.log"; then code=0; fi
elif test "$started" = 0; then
    code=0
fi
printf '%s\n' "$code" > "$1/result"
systemctl exit "$code"
EOF
exec /usr/lib/systemd/systemd --system --unit=md-journal.target --show-status=no --crash-shell=no --crash-reboot=no
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--store', required=True)
    parser.add_argument('--build', type=Path, required=True)
    parser.add_argument('--probe-only', action='store_true')
    parser.add_argument('--trace', action='store_true')
    parser.add_argument('--diagnostics', action='store_true')
    parser.add_argument('--output', type=Path, default=Path('build/journald-results'))
    args = parser.parse_args()
    suite = Suite(args.output, args.build)
    target = '/md-journal-' + suite.report['id']
    label = 'journald-' + suite.report['id']
    prefix = [suite.runner, '--store', args.store, '--user', '0:0', '--network', 'routed']
    if args.diagnostics:
        prefix.append('--diagnostics')
    created = False
    suite.report['probeOnly'] = args.probe_only
    try:
        suite.upload(args.build / 'libmagicdesk_guest_readiness_guest.so',
                               suite.base + '/readiness')
        suite.chmod('700', suite.base + '/readiness')
        suite.command(prefix + ['--', '/bin/mkdir', target])
        created = True
        boot = ['/usr/bin/unshare', '--mount', '--pid', '--fork', '--mount-proc', '--uts',
                '--ipc', '--cgroup', '/bin/sh', '-ec', prepare_init() + SETUP, 'journal-test', target,
                'probe' if args.probe_only else 'journal']
        if args.trace:
            boot = ['/usr/bin/strace', '-f', '-s', '256', '-e', 'trace=' + TRACE_SYSCALLS,
                    '-o', target + '/trace.log'] + boot
        # EVENT_WAIT: PID1 returns after the service job; the deadline cancels
        # a stalled launch, never substitutes for service readiness.
        command = prefix + ['--env', 'MAGICDESK_GUEST_LABEL=' + label,
                            '--bind', suite.base, target, '--deadline-seconds', '90', '--',
                            '/usr/bin/script', '-q', '-e', '-c', shlex.join(boot), '/dev/null']
        suite.command(['/system/bin/sh', '-c', 'log=$1; shift; exec "$@" >"$log" 2>&1',
                       'journal-boot', suite.base + '/boot.log', *command], timeout=110)
        assert suite.command(['cat', suite.base + '/result']).strip() == '0'
        suite.report['passed'] = True
    finally:
        try:
            for name in ('driver.log', 'journald.log', 'result', 'boot.log'):
                suite.command(['/system/bin/sh', '-c', 'test ! -f "$1" || tail -100 "$1"',
                               'journal-log', suite.base + '/' + name])
            suite.download(suite.base + '/boot.log',
                                     args.output / (suite.report['id'] + '.boot.log'))
            if args.trace:
                suite.download(suite.base + '/trace.log',
                                         args.output / (suite.report['id'] + '.trace'))
        finally:
            try:
                remaining = [json.loads(line) for line in
                             suite.command(suite.image_command('launches', args.store)).splitlines()
                             if line.strip()]
                suite.report['launchesAfter'] = remaining
                assert not any(item['label'] == label for item in remaining), remaining
                if created:
                    suite.command(prefix + ['--', '/bin/rmdir', target])
            finally:
                suite.finish()


if __name__ == '__main__':
    main()
