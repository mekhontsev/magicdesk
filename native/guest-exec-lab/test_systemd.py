#!/usr/bin/env python3
"""Stock systemd PID 1, service/notify/socket/timer activation in the selected executor.

Requires a disposable Debian-like Shroot store with systemd, util-linux and bash.
No distro units, host devices, reboot or shutdown targets are started.
"""
import argparse
from pathlib import Path
import shlex
import socket

from init_fixture import prepare_init, TRACE_SYSCALLS
from test_oci_services import Suite, add_machine_entry_argument
from kernel_profile import KernelProfile


PREPARE = prepare_init() + r'''
export SYSTEMD_LOG_LEVEL=debug
mkdir -p /run/systemd/system /run/md-user /run/md-user-units
chmod 700 /run/md-user
cat > /run/systemd/system/md-test.target <<EOF
[Unit]
DefaultDependencies=no
Requires=md-driver.service
After=md-driver.service
EOF
cat > /run/systemd/system/md-driver.service <<EOF
[Unit]
DefaultDependencies=no
[Service]
Type=simple
ExecStart=/bin/bash $1/driver.sh $1 $2
StandardOutput=append:$1/services.log
StandardError=inherit
EOF
cat > /run/systemd/system/md-oneshot.service <<EOF
[Unit]
DefaultDependencies=no
[Service]
Type=oneshot
RemainAfterExit=yes
MemoryDenyWriteExecute=yes
NoNewPrivileges=yes
SystemCallFilter=@system-service
ExecStart=/bin/sh -c 'echo oneshot > $1/oneshot'
StandardOutput=append:$1/oneshot.log
StandardError=inherit
EOF
printf '%s\n' "$3" >> /run/systemd/system/md-oneshot.service
cat > /run/systemd/system/md-notify.service <<EOF
[Unit]
DefaultDependencies=no
[Service]
Type=notify
NotifyAccess=all
TimeoutStartSec=10
TimeoutStopSec=5
ExecStart=/bin/sh -ec 'systemd-notify --ready; exec sleep infinity'
StandardOutput=append:$1/notify.log
StandardError=inherit
EOF
cat > /run/systemd/system/md-user.service <<EOF
[Unit]
DefaultDependencies=no
[Service]
Type=notify
TimeoutStartSec=15
TimeoutStopSec=5
Environment=XDG_RUNTIME_DIR=/run/md-user
Environment=SYSTEMD_UNIT_PATH=/run/md-user-units:
ExecStart=/usr/lib/systemd/systemd --user --unit=md-user.target
StandardOutput=append:$1/user.log
StandardError=inherit
EOF
cat > /run/systemd/system/md-fdstore.service <<EOF
[Unit]
DefaultDependencies=no
[Service]
Type=notify
NotifyAccess=main
FileDescriptorStoreMax=4
TimeoutStartSec=10
TimeoutStopSec=5
ExecStart=$1/notify-guest $1
StandardOutput=append:$1/fdstore.log
StandardError=inherit
EOF
cat > /run/md-user-units/md-user.target <<EOF
[Unit]
DefaultDependencies=no
Requires=md-user-oneshot.service
After=md-user-oneshot.service
EOF
cat > /run/md-user-units/md-user-oneshot.service <<EOF
[Unit]
DefaultDependencies=no
[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/bin/sh -c 'echo user > $1/user-result'
StandardOutput=append:$1/user-service.log
StandardError=inherit
EOF
cat > /run/systemd/system/md-echo.socket <<EOF
[Unit]
DefaultDependencies=no
[Socket]
ListenStream=127.0.0.1:$2
Accept=yes
EOF
cat > /run/systemd/system/md-echo@.service <<EOF
[Unit]
DefaultDependencies=no
[Service]
ExecStart=/bin/cat
StandardInput=socket
StandardOutput=socket
StandardError=null
EOF
cat > /run/systemd/system/md-timer.timer <<EOF
[Unit]
DefaultDependencies=no
[Timer]
OnActiveSec=100ms
AccuracySec=1ms
EOF
cat > /run/systemd/system/md-timer.service <<EOF
[Unit]
DefaultDependencies=no
[Service]
Type=oneshot
ExecStart=/bin/sh -c 'printf "timer-activated\\n" > /run/md-timer-result'
EOF
cat > "$1/driver.sh" <<'EOF'
set -eux
trap 'code=$?; if test "$code" != 0; then systemctl status --no-pager md-oneshot.service || :; systemctl exit "$code"; fi' EXIT
test "$(systemctl show -p SystemState --value)" = running
systemctl show -p Version -p SystemState
systemctl start md-notify.service
test "$(systemctl show -p ActiveState --value md-notify.service)" = active
systemctl start md-fdstore.service
test "$(systemctl show -p NFileDescriptorStore --value md-fdstore.service)" = 2
systemctl restart md-fdstore.service
grep -q 'PASS descriptor store restart' "$1/fdstore-restored"
systemctl start md-oneshot.service
test "$(cat "$1/oneshot")" = oneshot
test "$(systemctl show -p ActiveState --value md-oneshot.service)" = active
systemctl start md-user.service
export XDG_RUNTIME_DIR=/run/md-user
systemctl --user start md-user.target
test "$(cat "$1/user-result")" = user
systemctl --user show -p Version -p SystemState
systemctl --user stop md-user-oneshot.service
systemctl --user exit
systemctl start md-echo.socket
exec 3<>/dev/tcp/127.0.0.1/"$2"
printf 'socket-activated\n' >&3
IFS= read -r -t 10 response <&3
test "$response" = socket-activated
exec 3>&-
mkfifo /run/md-timer-result
exec 4<>/run/md-timer-result
systemctl start md-timer.timer
# EVENT_WAIT: the timer-activated service writes its result into the FIFO.
# The read deadline fails missing activation; timer duration is not readiness.
IFS= read -r -t 10 response <&4
test "$response" = timer-activated
systemctl stop md-timer.timer md-timer.service
exec 4>&-
rm /run/md-timer-result
systemctl stop md-echo.socket md-oneshot.service md-notify.service md-user.service md-fdstore.service
printf 'PASS systemd PID1, notify, oneshot, user manager, socket and timer activation\n' > "$1/result"
systemctl exit 0
EOF
exec /usr/lib/systemd/systemd --system --unit=md-test.target --show-status=no --crash-shell=no --crash-reboot=no
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--store', required=True)
    parser.add_argument('--build', type=Path)
    parser.add_argument('--output', type=Path, default=Path('build/systemd-results'))
    parser.add_argument('--trace', action='store_true', help='Requires strace in the guest')
    parser.add_argument('--property', action='append', default=[],
                        help='Additional service property for the hardened oneshot')
    add_machine_entry_argument(parser)
    KernelProfile.add_argument(parser)
    args = parser.parse_args()
    suite = Suite(args.output, args.build, machine_entry=args.machine_entry, kernel_profile=args.kernel_profile)
    suite.report['store'] = args.store
    suite.report['serviceProperties'] = args.property
    # INIT_PREPARE replaces /run, /tmp and /var/tmp with private mounts.
    target = '/opt/md-systemd-' + suite.report['id']
    prefix = suite.guest(args.store, '--user', '0:0', '--network', 'routed')
    created = False
    try:
        build = args.build or Path('app/build/shroot')
        suite.upload(build / 'libmagicdesk_guest_notify_guest.so',
                               suite.base + '/notify-guest')
        suite.chmod('700', suite.base + '/notify-guest')
        with socket.socket() as reservation:
            reservation.bind(('127.0.0.1', 0))
            port = str(reservation.getsockname()[1])
        suite.command(prefix + ['--', '/bin/mkdir', target])
        created = True
        command = prefix + ['--bind', suite.base, target, '--deadline-seconds', '90', '--']
        boot = []
        if args.trace:
            boot += ['/usr/bin/strace', '-f', '-s', '256', '-e',
                        'trace=' + TRACE_SYSCALLS,
                        '-o', target + '/trace.log']
        # EVENT_WAIT: manager/service completion; the launch deadline kills a
        # failed fixture instead of treating elapsed time as successful boot.
        boot += ['/usr/bin/unshare', '--mount', '--pid', '--fork', '--mount-proc',
                    '--uts', '--ipc', '--cgroup', '/bin/sh', '-ec', PREPARE, 'systemd-test', target, port,
                    '\n'.join(args.property)]
        command += ['/usr/bin/script', '-q', '-e', '-c', shlex.join(boot), '/dev/null']
        suite.command(command, timeout=110)
        result = suite.command(['/system/bin/cat', suite.base + '/result'])
        assert 'PASS systemd PID1' in result
        suite.report['passed'] = True
    finally:
        try:
            for name in ('services.log', 'oneshot.log', 'notify.log', 'fdstore.log', 'fdstore-restored', 'user.log', 'user-service.log', 'result'):
                suite.command(['/system/bin/sh', '-c', 'test ! -f "$1" || tail -100 "$1"',
                               'systemd-log', suite.base + '/' + name])
            if args.trace:
                suite.download(suite.base + '/trace.log',
                                         args.output / (suite.report['id'] + '.trace'))
        finally:
            try:
                if created:
                    suite.command(prefix + ['--', '/bin/rmdir', target])
            finally:
                suite.finish()


if __name__ == '__main__':
    main()
