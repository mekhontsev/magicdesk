#!/usr/bin/env python3
"""Boot an installed distribution's default userspace under actual shell UID.

The observer supplements, but does not replace, the distribution's boot units.
Uses a disposable store. Does not request a host reboot or shutdown.
"""
import argparse
import ctypes
import json
import os
import re
from pathlib import Path
import select
import shlex
import stat
import subprocess
import sys
import time

from init_fixture import OPENRC_PREPARE, RUNIT_PREPARE

SYSTEMD = r'''
mkdir -p /run/systemd/system/default.target.wants
date +%s > "$1/started"
if test "$2" = diagnostic; then
    mkdir -p /run/systemd/system/systemd-journald.service.d
    printf '[Service]\nStandardOutput=append:%s/journald.log\nStandardError=inherit\n' "$1" > /run/systemd/system/systemd-journald.service.d/diagnostics.conf
    mkdir -p /run/systemd/system.conf.d
    printf '[Manager]\nDefaultEnvironment=SNAPD_DEBUG=1 MD_NETWORKD_TRACE=1\n' > /run/systemd/system.conf.d/md-diagnostic.conf
fi
cat > /run/systemd/system/md-boot-observer.service <<EOF
[Unit]
DefaultDependencies=no
After=basic.target
[Service]
Type=simple
ExecStart=/bin/sh $1/observe.sh $1
StandardOutput=append:$1/boot.log
StandardError=inherit
EOF
ln -s ../md-boot-observer.service /run/systemd/system/default.target.wants/md-boot-observer.service
cat > "$1/observe.sh" <<'EOF'
set -ux
# EVENT_WAIT: systemd's startup job completion, bounded by the launch deadline.
systemctl is-system-running --wait > "$1/state"
code=$?
cat "$1/state"
systemctl --failed --no-pager
systemctl list-jobs --no-pager
systemctl status --no-pager
journalctl -b --since="@$(cat "$1/started")" -p warning --no-pager > "$1/journal.log"
systemctl --failed --plain --no-legend | while read -r unit rest; do
    journalctl -b --since="@$(cat "$1/started")" -u "$unit" --no-pager >> "$1/journal.log"
done
systemctl show -p ActiveState -p SubState systemd-journald.service dbus.service systemd-logind.service
# Check real service IO, not merely an ActiveState reported by the manager.
if test "$code" = 0; then
    # A separate shell retains errexit even though its status is tested by if.
    if /bin/sh -es -- "$1" > "$1/services.log" 2>&1 <<'CHECK'
        systemctl is-active systemd-journald.service dbus.service
        busctl --system call org.freedesktop.DBus /org/freedesktop/DBus org.freedesktop.DBus ListNames
        marker="md-boot-${1##*/}"
        printf '%s\n' "$marker" | systemd-cat -t md-boot-test
        # EVENT_WAIT: journal sync acknowledgement commits preceding log data.
        # The outer launch deadline fails a stalled journal, never implies success.
        journalctl --sync
        journalctl -t md-boot-test --no-pager -o cat | grep -Fx "$marker"
        logger --id=$$ -t md-boot-logger "$marker"
        journalctl --sync
        journalctl _PID=$$ SYSLOG_IDENTIFIER=md-boot-logger --no-pager -o cat | grep -Fx "$marker"
        systemctl restart systemd-logind.service
        systemctl is-active systemd-logind.service
        test "$(systemctl is-system-running)" = running
CHECK
    then
        echo 'PASS boot service IO and restart'
    else
        code=1
    fi
fi
printf '%s\n' "$code" > "$1/result"
journalctl -b --since="@$(cat "$1/started")" -p warning --no-pager > "$1/journal.log"
systemctl exit "$code"
EOF
exec /usr/lib/systemd/systemd --system --show-status=yes --crash-shell=no --crash-reboot=no
'''

OPENRC = OPENRC_PREPARE + r'''
printf '#!/bin/sh\nexec /usr/bin/python3 %s/observer.py --openrc-observer %s\n' "$1" "$1" > /etc/local.d/observer.start
chmod 755 /etc/local.d/observer.start
exec /sbin/openrc-init
'''

RUNIT = RUNIT_PREPARE + r'''
# Keep the distribution's stages and default services. Only the observer and
# disposable service definitions live in private configuration mounts.
mkdir -p /run/md-runit-services/observer /run/md-runit-services/lifecycle
cat > /run/md-runit-services/lifecycle/run <<'EOF'
#!/bin/sh
cp requested started
exec sleep 86400
EOF
cat > /run/md-runit-services/lifecycle/check <<'EOF'
#!/bin/sh
cmp -s requested started
EOF
printf '1\n' > /run/md-runit-services/lifecycle/requested
cat > /run/md-runit-services/observer/run <<EOF
#!/bin/sh
/bin/sh $1/observe-runit.sh $1
exec sleep 86400
EOF
chmod 755 /run/md-runit-services/*/run
chmod 755 /run/md-runit-services/lifecycle/check
ln -s /run/md-runit-services/lifecycle /etc/runit/runsvdir/default/md-lifecycle
ln -s /run/md-runit-services/observer /etc/runit/runsvdir/default/md-observer
cat > "$1/observe-runit.sh" <<'EOF'
set -eu
exec > "$1/boot.log" 2>&1
trap 'code=$?; printf "%s\n" "$code" > "$1/result"; if test "$code" = 0; then echo "PASS runit stock PID1 boot and service lifecycle"; else echo "FAIL runit stock PID1 boot and service lifecycle"; fi > /dev/console' EXIT
test "$(basename "$(readlink /proc/1/exe)")" = runit
service=/run/md-runit-services/lifecycle
# EVENT_WAIT: sv waits for the supervised service's requested state; its
# deadline is failure, not a fixed initialization delay.
sv -w 10 start "$service"
before=$(sv status "$service" | sed -n 's/.*(pid \([0-9]*\)).*/\1/p')
test -n "$before"
# runit's timestamp acknowledgement has one-second precision. Its check hook
# additionally requires the next instance to publish the requested generation.
printf '2\n' > "$service/requested"
sv -w 10 restart "$service"
after=$(sv status "$service" | sed -n 's/.*(pid \([0-9]*\)).*/\1/p')
test -n "$after"
test "$before" != "$after"
test ! -e "/proc/$before"
sv -w 10 stop "$service"
test ! -e "/proc/$after"
# Record stock service failures separately from the lifecycle probe.
sv status /etc/runit/runsvdir/default/* > "$1/stock-services.log" || :
EOF
exec runit
'''


def observe_openrc(target):
    # Install the watch before returning from local.start. openrc-init creates
    # its command FIFO only after sysinit, boot and the default runlevel return.
    libc = ctypes.CDLL(None, use_errno=True)
    watch = libc.inotify_init1(os.O_CLOEXEC)
    if watch < 0 or libc.inotify_add_watch(watch, b'/run/openrc', 0x100 | 0x80) < 0:
        raise OSError(ctypes.get_errno(), 'boot inotify admission')
    if os.fork():
        os.close(watch)
        return
    try:
        with open(target + '/boot.log', 'w', buffering=1) as log:
            os.dup2(log.fileno(), 1)
            os.dup2(log.fileno(), 2)
            fifo = Path('/run/openrc/init.ctl')
            poll = select.poll()
            poll.register(watch, select.POLLIN)
            deadline = time.monotonic() + 60
            # EVENT_WAIT: PID1 creates its command FIFO after boot. Deadline
            # fails the observer; it is not a readiness/settling delay.
            while not fifo.exists():
                remaining = deadline - time.monotonic()
                assert remaining > 0 and poll.poll(int(remaining * 1000)), 'OpenRC did not finish its boot runlevels'
                os.read(watch, 4096)
            os.close(watch)
            assert stat.S_ISFIFO(fifo.stat().st_mode)
            assert os.readlink('/proc/1/exe') == '/sbin/openrc-init'

            def run(*argv):
                result = subprocess.check_output(argv, stderr=subprocess.STDOUT, text=True)
                print(result, end='', flush=True)
                return result

            assert run('rc-status', '--runlevel').strip() == 'default'
            for service in ('syslog', 'crond'):
                run('rc-service', service, 'status')
            # Stop dependants before their provider; stopping syslog also stops
            # crond through the stock OpenRC dependency graph.
            for service in ('crond', 'syslog'):
                run('rc-service', service, 'status')
                before = Path('/run/' + ('syslogd' if service == 'syslog' else service) + '.pid')
                previous = int(before.read_text())
                run('rc-service', service, 'restart')
                current = int(before.read_text())
                assert previous != current
                assert not Path('/proc/' + str(previous)).exists()
                run('rc-service', service, 'status')
                run('rc-service', service, 'stop')
                assert not Path('/proc/' + str(current)).exists()
            run('rc-status', '--all')
            assert not list(Path('/run/openrc/failed').glob('*'))
            Path(target + '/result').write_text('0\n')
            with open('/dev/console', 'w') as console:
                console.write('PASS OpenRC stock PID1 boot and service lifecycle\n')
    except BaseException:
        import traceback
        traceback.print_exc()
        Path(target + '/result').write_text('1\n')
        with open('/dev/console', 'w') as console:
            console.write('FAIL OpenRC stock PID1 boot and service lifecycle\n')
    finally:
        os._exit(0)


def check_runit_boot(log):
    plain = re.sub(r'\x1b\[[0-9;]*[A-Za-z]', '', log)
    assert not re.search(r'\[\s*fail\s*\]', plain), plain


def check_systemd_boot(log):
    plain = re.sub(r'\x1b\[[0-9;]*[A-Za-z]', '', log)
    assert not re.search(r'generator.*(?:failed|exit(?:ed)? (?:with )?(?:code|status))',
                         plain, re.IGNORECASE), plain
    failures = re.findall(r"\S+\.(?:service|mount|socket|scope|target): Failed with result '[^']+'", plain)
    assert not failures, '\n'.join(failures)


def main():
    from test_oci_services import Server, Suite
    from init_fixture import PREPARE, TRACE_SYSCALLS

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--store', required=True)
    parser.add_argument('--build', type=Path)
    parser.add_argument('--entry', choices=('lazy', 'base'), default='lazy')
    parser.add_argument('--diagnostic-console', action='store_true')
    parser.add_argument('--trace', action='store_true', help='Trace filesystem, sandbox and readiness setup; requires strace')
    parser.add_argument('--rtkit', action='store_true', help='Verify packaged RTKit unit, D-Bus and restart; requires RTKit')
    parser.add_argument('--restart-service', action='append', default=[],
                        help='Require a stock systemd unit and three successful restart cycles')
    parser.add_argument('--service-io', action='append', choices=('avahi', 'cups', 'firewalld'), default=[],
                        help='Verify a packaged service protocol and clean stop/start across three cycles')
    parser.add_argument('--init', choices=('systemd', 'openrc', 'runit'), default='systemd')
    parser.add_argument('--network', choices=('host', 'none', 'bridge', 'routed'), default='routed')
    parser.add_argument('--dns', action='append', default=[], help='Per-launch resolver IPv4 or IPv6 address')
    parser.add_argument('--binfmt', action='store_true', help='Require the stock binfmt mount/service and registration across restart')
    parser.add_argument('--networkd-ipv6', action='store_true', help='Require stock networkd to configure IPv6 from a router advertisement')
    parser.add_argument('--drop-capability', action='append', default=[],
                        help='Explicit container capability policy, applied by stock setpriv before PID1')
    parser.add_argument('--output', type=Path, default=Path('build/os-boot-results'))
    args = parser.parse_args()
    if any(not re.fullmatch('[a-z][a-z0-9_]*', cap) for cap in args.drop_capability):
        parser.error('capability names must use setpriv lowercase spelling without CAP_')
    if args.init != 'systemd' and args.trace:
        parser.error('--trace is currently a systemd diagnostic')
    if args.rtkit and args.init != 'systemd':
        parser.error('--rtkit requires systemd')
    if (args.restart_service or args.service_io) and args.init != 'systemd':
        parser.error('--restart-service requires systemd')
    if any(not re.fullmatch(r'[A-Za-z0-9_.@:-]+', unit) for unit in args.restart_service):
        parser.error('invalid service unit name')
    if args.binfmt and args.init != 'systemd':
        parser.error('--binfmt requires systemd')
    if args.networkd_ipv6 and args.init != 'systemd':
        parser.error('--networkd-ipv6 requires systemd')
    suite = Suite(args.output, args.build)
    suite.report['store'] = args.store
    suite.report['bootPolicy'] = {'init': args.init, 'dropCapabilities': args.drop_capability,
                                'rtkit': args.rtkit, 'network': args.network, 'dns': args.dns,
                                'entryMode': args.entry, 'restartServices': args.restart_service, 'serviceIO': args.service_io}
    target = '/md-boot-' + suite.report['id']
    prefix = [suite.runner, '--store', args.store, '--user', '0:0', '--network', args.network,
              '--env', 'SHROOT_MACHINE_ENTRY=' + ('0' if args.entry == 'base' else '1')]
    for address in args.dns:
        prefix += ['--dns', address]
    label = 'os-boot-' + suite.report['id']
    created = False
    try:
        suite.command(prefix + ['--', '/bin/mkdir', target])
        created = True
        if args.init == 'openrc':
            suite.transport.upload(suite.client, Path(__file__), suite.base + '/observer.py')
            suite.transport.upload(suite.client, Path(__file__).with_name('init_fixture.py'),
                                   suite.base + '/init_fixture.py')
        program = {'systemd': SYSTEMD, 'openrc': OPENRC, 'runit': RUNIT}[args.init]
        if args.service_io:
            suite.transport.upload(suite.client, Path(__file__).with_name('boot_service_io.py'),
                                   suite.base + '/boot_service_io.py')
        probes = {'avahi-daemon.service': 'avahi', 'snap.cups.cupsd.service': 'cups', 'firewalld.service': 'firewalld'}
        for unit, probe in probes.items():
            if probe in args.service_io and unit not in args.restart_service:
                args.restart_service.append(unit)
        for unit in args.restart_service:
            probe = probes.get(unit)
            check_io = f'python3 "$1/boot_service_io.py" {probe}' if probe in args.service_io else ':'
            checks = f'''
        for cycle in 1 2 3; do
            if ! {check_io} || ! systemctl is-active '{unit}' || ! systemctl stop '{unit}' \
                    || test "$(systemctl show -p Result --value '{unit}')" != success \
                    || ! systemctl start '{unit}' || ! systemctl is-active '{unit}'; then
                systemctl status '{unit}' --no-pager || :
                journalctl -b --since="@$(cat "$1/started")" -u '{unit}' --no-pager || :
                exit 1
            fi
        done
        if ! {check_io}; then
            systemctl status '{unit}' --no-pager || :
            journalctl -b --since="@$(cat "$1/started")" -u '{unit}' --no-pager || :
            exit 1
        fi
        echo 'PASS repeated service restart: {unit}'
'''
            program = program.replace('        systemctl restart systemd-logind.service',
                                      checks + '        systemctl restart systemd-logind.service')
        if args.networkd_ipv6:
            suite.transport.upload(suite.client, Path(__file__).with_name('networkd_ipv6_fixture.py'),
                                   suite.base + '/networkd_ipv6_fixture.py')
            program = program.replace('        systemctl restart systemd-logind.service',
                                      '        python3 "$1/networkd_ipv6_fixture.py"\n'
                                      '        systemctl restart systemd-logind.service')
        if args.binfmt:
            # Runtime configuration only; systemd itself orders and starts the
            # stock mount and service. No manual mount can hide a boot failure.
            program = '''mkdir -p /run/binfmt.d
printf ':md-boot:E::mdboot::/bin/true:P\\n' > /run/binfmt.d/md-boot.conf
''' + program
            checks = '''
        systemctl is-active proc-sys-fs-binfmt_misc.mount systemd-binfmt.service
        grep -Fx enabled /proc/sys/fs/binfmt_misc/md-boot
        printf 'not an executable\\n' > /tmp/fixture.mdboot
        chmod 755 /tmp/fixture.mdboot
        /tmp/fixture.mdboot
        systemctl stop systemd-binfmt.service
        test ! -e /proc/sys/fs/binfmt_misc/md-boot
        systemctl start systemd-binfmt.service
        /tmp/fixture.mdboot
        echo 'PASS binfmt boot registration, execution and service lifecycle'
'''
            program = program.replace('        systemctl restart systemd-logind.service',
                                      checks + '        systemctl restart systemd-logind.service')
        if args.rtkit:
            checks = '''
        systemctl start rtkit-daemon.service
        systemctl is-active rtkit-daemon.service
        busctl --system get-property org.freedesktop.RealtimeKit1 /org/freedesktop/RealtimeKit1 org.freedesktop.RealtimeKit1 MaxRealtimePriority
        systemctl restart rtkit-daemon.service
        busctl --system call org.freedesktop.RealtimeKit1 /org/freedesktop/RealtimeKit1 org.freedesktop.DBus.Peer Ping
        systemctl stop rtkit-daemon.service
'''
            program = program.replace('        systemctl restart systemd-logind.service',
                                      checks + '        systemctl restart systemd-logind.service')
        boot = ['/usr/bin/unshare', '--mount', '--pid', '--fork', '--mount-proc',
                '--uts', '--ipc', '--cgroup', '/bin/sh', '-ec', PREPARE + program, 'boot-test', target,
                'diagnostic' if args.diagnostic_console else 'normal']
        if args.drop_capability:
            boot = ['/usr/bin/setpriv', '--bounding-set=' + ','.join('-' + cap for cap in args.drop_capability),
                    '--', *boot]
        if args.trace:
            boot = ['/usr/bin/strace', '-f', '-s', '256', '-e',
                    'trace=' + TRACE_SYSCALLS,
                    '-o', target + '/boot.trace'] + boot
        # EVENT_WAIT: PTY and PID1 completion, never an elapsed-time boot verdict.
        command = prefix + ['--env', 'MAGICDESK_GUEST_LABEL=' + label,
                            '--bind', suite.base, target, '--deadline-seconds', '180', '--',
                            '/usr/bin/script', '-q', '-e', '-c', shlex.join(boot), '/dev/null']
        if args.init == 'systemd':
            boot_log = suite.command(command, timeout=205)
            check_systemd_boot(boot_log)
        else:
            server = Server(suite, command)
            try:
                manager = 'OpenRC' if args.init == 'openrc' else 'runit'
                # Either verdict ends observation; only the result file below
                # can establish success after exact launch cleanup.
                server.ready(manager + ' stock PID1 boot and service lifecycle', timeout=120)
            finally:
                try:
                    # The registry pins the exact supervisor with pidfd. Closing
                    # the console alone need not terminate its detached init tree.
                    def launches():
                        records = suite.command(suite.image_command('launches', args.store))
                        return [json.loads(line) for line in records.splitlines() if line.strip()]

                    active = launches()
                    owned = [item for item in active if item['label'] == label]
                    assert len(owned) <= 1, owned
                    for launch in owned:
                        suite.command(suite.image_command('stop', args.store, launch['launchId']))
                    # EVENT_WAIT: the cancelled launch returns and closes its
                    # inherited output. The bound is failure, not readiness.
                    server.process.join(30)
                    assert not server.process.is_alive(), 'init cancellation did not complete'
                    assert server.error is None, server.error
                    remaining = launches()
                    assert not any(item['label'] == label for item in remaining), remaining
                finally:
                    server.close()
            boot_log = ''.join(server.log)
            suite.report['bootLog'] = boot_log
            if args.init == 'runit':
                check_runit_boot(boot_log)
        result = suite.command(['cat', suite.base + '/result'])
        assert result.strip() == '0', result
        suite.report['passed'] = True
    finally:
        try:
            for name in ('boot.log', 'services.log', 'stock-services.log', 'journal.log', 'journald.log', 'state', 'result'):
                suite.command(['/system/bin/sh', '-c', 'test ! -f "$1" || tail -200 "$1"',
                               'boot-log', suite.base + '/' + name])
            if args.trace:
                suite.transport.download(suite.client, suite.base + '/boot.trace',
                                         args.output / (suite.report['id'] + '.trace'))
        finally:
            try:
                if created:
                    suite.command(prefix + ['--', '/bin/rmdir', target])
            finally:
                suite.finish()


if __name__ == '__main__':
    if '--openrc-observer' in sys.argv:
        observe_openrc(sys.argv[-1])
    else:
        main()
