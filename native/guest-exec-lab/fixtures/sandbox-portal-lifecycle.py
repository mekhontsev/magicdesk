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
    args = parser.parse_args()
    assert os.geteuid() != 0, 'Use the ordinary recipe desktop user'
    document = Path(os.environ['XDG_RUNTIME_DIR']) / 'doc'
    report = dict(passed=False, uid=os.getuid(), app=args.app, backend=args.backend, rounds=[])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    try:
        for _ in range(2):
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
            try:
                executable = os.readlink('/proc/' + str(pid) + '/exe')
                # Nix's packaged launcher execs its wrapped binary in the same PID.
                assert Path(executable).name in ('xdg-document-portal',
                                                 '.xdg-document-portal-wrapped'), executable
                poll = select.poll()
                poll.register(descriptor, select.POLLIN)
                signal.pidfd_send_signal(descriptor, signal.SIGTERM)
                # EVENT_WAIT: the exact portal process exits after its FUSE
                # teardown; expiration fails, never forces a passing cleanup.
                assert poll.poll(15000), 'Document portal failed to exit'
                assert not any(line.split()[4] == str(document)
                               for line in Path('/proc/self/mountinfo').read_text().splitlines()), \
                    'Document portal left its FUSE mount attached'
                report['rounds'].append(dict(pid=pid, executable=executable, mountRemoved=True))
            finally:
                os.close(descriptor)
        report['passed'] = True
    except Exception as error:
        report['failure'] = repr(error)
        raise
    finally:
        args.output.write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main()
