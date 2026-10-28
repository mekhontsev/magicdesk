#!/usr/bin/env python3
"""Run the reviewed LTP core selection once, unprivileged, in disposable directories."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import pwd
import re
import signal
import subprocess
import tarfile
import tempfile

TESTS = ('dup/dup01', 'dup2/dup201', 'openat/openat01', 'pipe/pipe01',
         'epoll_create1/epoll_create1_01', 'sigprocmask/sigprocmask01', 'getuid/getuid01')


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('user')
    parser.add_argument('--archive', type=Path)
    parser.add_argument('report', type=Path)
    args = parser.parse_args()
    account = pwd.getpwnam(args.user)
    assert account.pw_uid != 0, 'LTP core must run as an ordinary guest account'
    report = dict(passed=False, uid=account.pw_uid, gid=account.pw_gid,
                  osRelease=Path('/etc/os-release').read_text(), tests=[])
    try:
        if args.archive:
            report['archiveSha256'] = digest(args.archive)
            if not args.source.exists():
                args.source.parent.mkdir(parents=True, exist_ok=True)
                with tarfile.open(args.archive) as archive:
                    members = archive.getmembers()
                    assert all(Path(m.name).parts[0] == args.source.name for m in members)
                    archive.extractall(args.source.parent, members=members, filter='data')
            report['buildCommands'] = []
            commands = ([['./configure']] if not (args.source / 'include/config.h').is_file() else [])
            commands += [['make', '-j4', 'include-all', 'lib-all']]
            commands += [['make', '-j4', '-C', 'testcases/kernel/syscalls/' + str(Path(t).parent)]
                         for t in TESTS]
            for command in commands:
                report['buildCommands'].append(command)
                subprocess.run(command, cwd=args.source, check=True, timeout=300)
        report['librarySha256'] = digest(args.source / 'lib/libltp.a')
        for selected in TESTS:
            binary = args.source / 'testcases/kernel/syscalls' / selected
            record = dict(name=binary.name, binarySha256=digest(binary),
                          sourceSha256=digest(binary.with_suffix('.c')), passed=False)
            report['tests'].append(record)
            with tempfile.TemporaryDirectory(prefix='md-ltp-', dir='/var/tmp') as temporary:
                os.chown(temporary, account.pw_uid, account.pw_gid)
                env = dict(PATH=os.environ.get('PATH', os.defpath), HOME=temporary,
                           TMPDIR=temporary, LC_ALL='C')
                command = [str(binary), '-i', '1']
                print('RUN', command, flush=True)
                with subprocess.Popen(command, cwd=temporary, env=env,
                        user=account.pw_uid, group=account.pw_gid, extra_groups=[],
                        start_new_session=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                        text=True) as child:
                    try:
                        # EVENT_WAIT: exact test completion; timeout kills only this test group.
                        output, _ = child.communicate(timeout=45)
                    except subprocess.TimeoutExpired:
                        os.killpg(child.pid, signal.SIGKILL)
                        output, _ = child.communicate(timeout=15)
                        record['timeout'] = True
                    record.update(exitCode=child.returncode, output=output)
                print(output, end='', flush=True)
                record['statuses'] = re.findall(r'\bT(?:PASS|FAIL|BROK|WARN|CONF)\b', output)
                record['passed'] = (record['exitCode'] == 0 and 'TPASS' in record['statuses']
                                    and not any(s != 'TPASS' for s in record['statuses']))
        report['passed'] = all(item['passed'] for item in report['tests'])
    finally:
        args.report.write_text(json.dumps(report, indent=2) + '\n')
    assert report['passed'], 'Inspect individual LTP statuses; TCONF is not a pass'


if __name__ == '__main__':
    main()
