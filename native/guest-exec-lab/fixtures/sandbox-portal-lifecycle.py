#!/usr/bin/env python3
"""Exercise document grants, clean portal exit and D-Bus reactivation as a user."""
import argparse
import json
import os
from pathlib import Path
import re
import select
import signal
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--app', default='org.chromium.Chromium')
    parser.add_argument('--backend', choices=('flatpak', 'snap'), default='flatpak')
    parser.add_argument('--exit-mode', choices=('all', 'graceful', 'auto-unmount'), default='all',
                        help='Select focused coverage; all includes graceful exit and daemon death')
    parser.add_argument('--systemd', action='store_true',
                        help='Also stop/restart the entire user service, including its FUSE helper')
    args = parser.parse_args()
    assert os.geteuid() != 0, 'Use the ordinary recipe desktop user'
    document = Path(os.environ['XDG_RUNTIME_DIR']) / 'doc'
    report = dict(passed=False, uid=os.getuid(), app=args.app, backend=args.backend,
                  exitMode=args.exit_mode, rounds=[])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    try:
        signals = ((signal.SIGTERM, signal.SIGTERM) if args.exit_mode != 'auto-unmount' else ())
        if args.exit_mode != 'graceful':
            signals += (signal.SIGKILL,)
        for sig in signals:
            subprocess.run(['sh', str(Path(__file__).with_name('sandbox-document-portal.sh')), args.app, args.backend],
                           check=True, timeout=90)
            owner = subprocess.check_output([
                'gdbus', 'call', '--session', '--dest', 'org.freedesktop.DBus',
                '--object-path', '/org/freedesktop/DBus',
                '--method', 'org.freedesktop.DBus.GetConnectionUnixProcessID',
                'org.freedesktop.portal.Documents'], text=True, timeout=15)
            match = re.fullmatch(r'\(uint32 (\d+),\)\s*', owner)
            assert match, owner
            pid = int(match[1])
            descriptor = os.pidfd_open(pid)
            helpers = []
            try:
                executable = os.readlink('/proc/' + str(pid) + '/exe')
                # Nix's packaged launcher execs its wrapped binary in the same PID.
                assert Path(executable).name in ('xdg-document-portal',
                                                 '.xdg-document-portal-wrapped'), executable
                if sig == signal.SIGKILL:
                    for child in Path('/proc').iterdir():
                        if not child.name.isdigit():
                            continue
                        try:
                            status = dict(line.split(':', 1) for line in (child / 'status').read_text().splitlines()
                                          if ':' in line)
                            if (int(status['PPid']) == pid
                                    and status['Name'].strip() in ('fusermount', 'fusermount3')):
                                helpers.append(os.pidfd_open(int(child.name)))
                        except FileNotFoundError:
                            continue
                    assert helpers, 'No auto-unmount helper owned by the document portal'
                poll = select.poll()
                poll.register(descriptor, select.POLLIN)
                signal.pidfd_send_signal(descriptor, sig)
                # EVENT_WAIT: the exact portal process exits after its FUSE
                # teardown; expiration fails, never forces a passing cleanup.
                assert poll.poll(15000), 'Document portal failed to exit'
                for helper in helpers:
                    poll = select.poll()
                    poll.register(helper, select.POLLIN)
                    # EVENT_WAIT: socket EOF makes the surviving helper verify
                    # and detach its mount; waiting for the daemon alone races it.
                    assert poll.poll(15000), 'Portal auto-unmount helper failed to exit'
                assert not any(line.split()[4] == str(document)
                               for line in Path('/proc/self/mountinfo').read_text().splitlines()), \
                    'Document portal left its FUSE mount attached'
                report['rounds'].append(dict(pid=pid, executable=executable, signal=sig.name,
                                            helpers=len(helpers), mountRemoved=True))
            finally:
                os.close(descriptor)
                for helper in helpers:
                    os.close(helper)
        if args.systemd:
            unit = 'xdg-document-portal.service'
            for action in ('restart', 'stop'):
                subprocess.run(['sh', str(Path(__file__).with_name('sandbox-document-portal.sh')),
                                args.app, args.backend], check=True, timeout=90)
                subprocess.run(['systemctl', '--user', action, unit], check=True, timeout=30)
                if action == 'stop':
                    assert not any(line.split()[4] == str(document)
                                   for line in Path('/proc/self/mountinfo').read_text().splitlines()), \
                        'Stopped portal service left its FUSE mount attached'
                # Activation and actual grants must work after whole-group teardown,
                # not merely after a successful systemd job submission.
                subprocess.run(['sh', str(Path(__file__).with_name('sandbox-document-portal.sh')),
                                args.app, args.backend], check=True, timeout=90)
                subprocess.run(['systemctl', '--user', 'is-active', unit], check=True, timeout=15)
                report['rounds'].append(dict(serviceAction=action, reactivated=True))
        report['passed'] = True
    except Exception as error:
        report['failure'] = repr(error)
        raise
    finally:
        args.output.write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main()
