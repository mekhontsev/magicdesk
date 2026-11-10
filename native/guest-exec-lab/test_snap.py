#!/usr/bin/env python3
"""Stock snapd under systemd, using a disposable Debian-like Shroot store."""
import argparse
from pathlib import Path
import re
import shlex
import subprocess

from benchmark_cpu import observe_suite_cpu
from fixture_executor import FixtureExecutor
from init_fixture import prepare_init
from test_oci_services import Suite, add_machine_entry_argument


PREPARE = prepare_init() + r'''
# Snap needs shared mount propagation before its image mounts exist. systemd
# inside a container leaves this to the launcher; unshare defaults to private.
mount --make-rshared /
mkdir -p /run/systemd/system
mkdir -p /run/systemd/system/snapd.service.d
cat > /run/systemd/system/snapd.service.d/90-test-log.conf <<EOF
[Service]
StandardOutput=append:$1/daemon.log
StandardError=inherit
EOF
cat > /run/systemd/system/md-snap.target <<EOF
[Unit]
DefaultDependencies=no
Requires=md-snap.service
After=md-snap.service
EOF
cat > /run/systemd/system/md-snap.service <<EOF
[Unit]
DefaultDependencies=no
Conflicts=shutdown.target
Before=shutdown.target
[Service]
Type=simple
ExecStart=/bin/sh $1/driver.sh $1
StandardOutput=append:$1/snap.log
StandardError=inherit
EOF
cat > "$1/driver.sh" <<'EOF'
set -eux
trap 'code=$?; journalctl --no-pager -u snapd.service -n 100 > "$1/journal.log" || :; systemctl exit "$code"' EXIT
systemctl start dbus.socket snapd.mounts.target snapd.socket
systemctl start snapd.service
snap version
snap debug sandbox-features > "$1/sandbox-features.log"
cat "$1/sandbox-features.log"
if command -v aa-status >/dev/null; then
    aa-status > "$1/apparmor.log" 2>&1 || printf 'aa-status exit=%s\n' "$?" >> "$1/apparmor.log"
fi
snap install @SNAP@
@COLD_LAUNCH@
@RUN@
snap refresh @SNAP@
snap remove --purge @SNAP@
test ! -e /snap/@SNAP@/current
if grep -q ' /snap/@SNAP@/' /proc/self/mountinfo; then exit 1; fi
printf 'PASS snapd install, run, refresh and remove\n' > "$1/result"
EOF
exec /usr/lib/systemd/systemd --system --unit=md-snap.target --show-status=no --crash-shell=no --crash-reboot=no
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--store', required=True)
    parser.add_argument('--build', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=Path('build/fuse-results'))
    parser.add_argument('--trace', action='store_true')
    parser.add_argument('--debug', action='store_true', help='Enable snapd diagnostics without syscall tracing')
    parser.add_argument('--statistics', action='store_true', help='Collect Shroot counters for this launch')
    parser.add_argument('--profile', action='store_true',
                        help='Sample the host process tree with simpleperf; invalidates timing comparisons')
    parser.add_argument('--trace-app', action='store_true', help='Trace application mappings, not install hooks')
    parser.add_argument('--namespace-only', action='store_true',
                        help='Verify one cold confined-shell launch without running or removing the app')
    parser.add_argument('--cold-launch-checks', type=int, choices=range(0, 11), default=0,
                        help='Time repeated stock confined-shell launches, discarding their saved mount namespace each time')
    parser.add_argument('--trace-cold', action='store_true',
                        help='Trace only cold namespace launches, not installation hooks; invalidates timing comparisons')
    parser.add_argument('--timeout', type=int, default=600)
    parser.add_argument('--user-session', action='store_true',
                        help='Start the stock user manager and use its D-Bus session, including Snap scope placement')
    parser.add_argument('--snap', choices=['hello-world', 'firefox', 'chromium'], default='hello-world')
    parser.add_argument('--snap-file', help='Guest path to an official cached snap; signature checks remain enabled')
    parser.add_argument('--reinstall', action='store_true',
                        help='Remove an existing test snap before the install phase')
    parser.add_argument('--app-launches', type=int, choices=range(1, 11), default=1,
                        help='Run Firefox repeatedly with a fresh profile and verify every rendered image')
    add_machine_entry_argument(parser)
    FixtureExecutor.add_argument(parser)
    args = parser.parse_args()
    if args.snap == 'chromium' and not args.namespace_only:
        parser.error('--snap chromium requires --namespace-only; GUI coverage uses the desktop fixture')
    if args.namespace_only:
        args.cold_launch_checks = max(1, args.cold_launch_checks)
    suite = Suite(args.output, args.build, machine_entry=args.machine_entry, executor=args.executor)
    suite.report['store'] = args.store
    suite.report['snap'] = args.snap
    suite.report['diagnostics'] = dict(debug=args.debug, statistics=args.statistics,
                                      profile=args.profile, traceCold=args.trace_cold)
    target = '/opt/md-snap-' + suite.report['id']
    prefix = suite.guest(args.store, '--user', '0:0', '--network', 'routed')
    if args.statistics and not args.machine_entry:
        prefix += ['--statistics']
    created = False
    try:
        suite.command(prefix + ['--', '/bin/mkdir', target])
        created = True
        program = 'snap run hello-world'
        if args.snap == 'firefox':
            program = r'''
attempt=0
while test "$attempt" -lt @LAUNCHES@; do
profile=/root/snap/firefox/common/md-@ID@-$attempt
mkdir -p "$profile/profile"
snap run firefox --headless --no-remote --profile "$profile/profile" \
    --screenshot "$profile/render.png" --window-size 320,240 \
    'data:text/html,<style>html{background:rgb(51,153,77)}</style>'
test -s "$profile/render.png"
cp "$profile/render.png" "$1/render-$attempt.png"
rm -rf "$profile"
attempt=$((attempt + 1))
done
'''.replace('@ID@', suite.report['id']).replace('@LAUNCHES@', str(args.app_launches))
        prepare = PREPARE.replace('@SNAP@', args.snap).replace('@RUN@', program)
        if args.user_session:
            prepare = prepare.replace('snap version\n', '''systemctl start user@0.service
export XDG_RUNTIME_DIR=/run/user/0
export DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/0/bus
systemctl --user is-active default.target
systemd-run --user --wait --pipe /usr/bin/true
snap version
''')
            suite.report['userSession'] = True
        if args.snap_file:
            prepare = prepare.replace('snap install ' + args.snap,
                'if ! snap list ' + args.snap + ' >/dev/null 2>&1; then\n'
                'snap install ' + shlex.quote(args.snap_file) + '\nfi')
            suite.report['snapFile'] = args.snap_file
        if args.reinstall:
            prepare = prepare.replace('snap version\n', 'snap version\n'
                'if snap list ' + args.snap + ' >/dev/null 2>&1; then\n'
                'snap remove --purge ' + args.snap + '\nfi\n')
        cold_launch = ''
        if args.cold_launch_checks:
            cold_launch = r'''/usr/bin/python3 - "$1" @SNAP@ @COUNT@ <<'PY'
import json, os, pathlib, subprocess, sys, time
directory, name, count = pathlib.Path(sys.argv[1]), sys.argv[2], int(sys.argv[3])
results = []
for attempt in range(count):
    subprocess.run(['/usr/lib/snapd/snap-discard-ns', name], check=True)
    with (directory / ('cold-launch-%d.log' % attempt)).open('w') as log:
        start = time.monotonic()
        command = ['snap', 'run', '--shell', name, '-c', 'true']
        if @TRACE@:
            command = ['strace', '-f', '-ttt', '-T', '-s', '256', '-o',
                       str(directory / ('cold-trace-%d.log' % attempt)), '-e',
                       'trace=%file,mount,unshare,setns,pivot_root', *command]
        result = subprocess.run(command,
                                stdout=log, stderr=subprocess.STDOUT,
                                env=dict(os.environ, SNAPD_DEBUG='1') if @DEBUG@ else os.environ)
        elapsed = time.monotonic() - start
    markers = {}
    for line in (directory / ('cold-launch-%d.log' % attempt)).read_text().splitlines():
        _, separator, payload = line.partition('-- snap startup ')
        if separator:
            marker = json.loads(payload)
            markers[marker['stage']] = float(marker['time'])
    start = markers.get('snap-confine mount namespace start')
    end = markers.get('snap-confine mount namespace finish')
    results.append(dict(attempt=attempt, seconds=elapsed, exitCode=result.returncode,
                        namespaceSeconds=end-start if start is not None and end is not None else None))
    (directory / 'cold-launch.json').write_text(json.dumps(results, indent=2) + '\n')
    print('COLD_LAUNCH', json.dumps(results[-1]), flush=True)
    result.check_returncode()
PY'''.replace('@SNAP@', args.snap).replace('@COUNT@', str(args.cold_launch_checks)).replace('@TRACE@', str(args.trace_cold)).replace('@DEBUG@', str(args.debug))
        prepare = prepare.replace('@COLD_LAUNCH@', cold_launch)
        if args.namespace_only:
            start = prepare.index(program)
            end = prepare.index("\nEOF\nexec /usr/lib/systemd/systemd", start)
            prepare = prepare[:start] + 'printf "PASS cold snap namespace\\n" > "$1/result"' + prepare[end:]
        if args.debug:
            prepare = prepare.replace('StandardOutput=append:$1/daemon.log',
                                      'Environment=SNAPD_DEBUG=1\nStandardOutput=append:$1/daemon.log')
            prepare = prepare.replace('\nsnap run ' + args.snap, '\nSNAPD_DEBUG=1 snap run ' + args.snap)
            if args.snap == 'firefox':
                prepare = prepare.replace('\nSNAPD_DEBUG=1 snap run firefox',
                                          '\nMOZ_LOG=SharedMemory:5 SNAPD_DEBUG=1 snap run firefox')
        if args.trace:
            # Hooks are snapd children. -D preserves LISTEN_PID/MAINPID;
            # systemd needs %% to pass strace's literal syscall-class prefix.
            prepare = prepare.replace('StandardOutput=append:$1/daemon.log',
                'Environment=SNAPD_DEBUG=1\nExecStart=\n'
                'ExecStart=/usr/bin/strace -D -f -ttt -T -s 256 -o $1/daemon-trace.log '
                '-e trace=%%file,fstat,fstatfs,getdents64,unshare,setns,mount,pivot_root,chroot,execve /usr/lib/snapd/snapd\n'
                'StandardOutput=append:$1/daemon.log')
            prepare = prepare.replace('snap run ' + args.snap,
                'SNAPD_DEBUG=1 strace -f -ttt -T -s 200 -o "$1/trace.log" '
                '-e trace=openat,readlinkat,fcntl,close,unshare,mount,pivot_root,chroot,execve '
                'snap run ' + args.snap)
        if args.trace_app:
            if args.snap != 'firefox':
                parser.error('--trace-app requires --snap firefox')
            prepare = prepare.replace('snap run firefox --headless',
                'strace -f -ttt -T -e trace=mmap,mprotect,munmap '
                '-o "$1/app-$attempt.trace" snap run firefox --headless')
        boot = ['/usr/bin/unshare', '--mount', '--pid', '--fork', '--mount-proc',
                '--uts', '--ipc', '--cgroup', '/bin/sh', '-ec', prepare, 'snap-test', target]
        command = prefix + ['--bind', suite.base, target, '--deadline-seconds', str(args.timeout), '--',
                            '/usr/bin/script', '-q', '-e', '-c', shlex.join(boot), '/dev/null']
        if args.profile:
            command = ['/system/bin/simpleperf', 'record', '-e', 'cpu-clock:u', '-f', '500',
                       '--call-graph', 'dwarf', '-o', suite.base + '/perf.data', '--', *command]
        with observe_suite_cpu(suite) as cpu:
            suite.report['cpu'] = cpu
            suite.command(command, timeout=args.timeout+20)
        result = suite.command(['/system/bin/cat', suite.base + '/result'])
        assert ('PASS cold snap namespace' if args.namespace_only else 'PASS snapd') in result
        if args.snap == 'firefox' and not args.namespace_only:
            suite.report['screenshots'] = []
            for attempt in range(args.app_launches):
                screenshot = args.output / (suite.report['id'] + '-firefox-%d.png' % attempt)
                suite.download(suite.base + '/render-%d.png' % attempt,
                                         screenshot, overwrite=True)
                pixel = subprocess.check_output(['magick', str(screenshot), '-crop', '1x1+160+120',
                                                 '-depth', '8', 'rgb:-'])
                assert pixel == bytes([51, 153, 77]), 'Firefox screenshot did not render the requested page'
                suite.report['screenshots'].append(str(screenshot))
            application_log = args.output / (suite.report['id'] + '-snap.log')
            suite.download(suite.base + '/snap.log', application_log,
                                     overwrite=True)
            suite.report['applicationLog'] = str(application_log)
            failures = [line for line in application_log.read_text(errors='replace').splitlines()
                        if re.search(r"XPCOMGlueLoad error|Couldn't load XPCOM|"
                                     r'WARNING: process \d+ exited with status [1-9]|'
                                     r'exited on signal|Sandbox: seccomp sandbox violation', line)]
            assert not failures, 'Firefox child failure despite rendered image: ' + '\n'.join(failures)
        suite.report['sandboxFeatures'] = suite.command(
            ['/system/bin/cat', suite.base + '/sandbox-features.log'])
        # Execution alone cannot certify mandatory access control, even when
        # the daemon advertises it. Keep that claim separate from this receipt.
        suite.report['strictConfinementVerified'] = False
        suite.report['case'] = 'namespace' if args.namespace_only else 'application'
        suite.report['passed'] = True
    finally:
        try:
            for name in ('snap.log', 'daemon.log', 'journal.log', 'trace.log', 'daemon-trace.log',
                         'sandbox-features.log', 'apparmor.log', 'cold-launch.json', 'result',
                         *(('perf.data',) if args.profile else ()),
                         *('app-%d.trace' % i for i in range(args.app_launches) if args.trace_app),
                         *('cold-trace-%d.log' % i for i in range(args.cold_launch_checks) if args.trace_cold),
                         *('cold-launch-%d.log' % i for i in range(args.cold_launch_checks))):
                remote = suite.base + '/' + name
                exists = suite.command(['/system/bin/sh', '-c', 'test ! -f "$1" || printf yes',
                                        'snap-log', remote], quiet=True)
                if exists == 'yes':
                    local = args.output / (suite.report['id'] + '-' + name)
                    suite.download(remote, local, overwrite=True)
                    suite.report.setdefault('artifacts', {})[name] = str(local)
                    if name == 'perf.data':
                        suite.report['profile'] = str(local)
        finally:
            try:
                if created:
                    suite.command(prefix + ['--', '/bin/rmdir', target])
            finally:
                suite.finish()


if __name__ == '__main__':
    main()
