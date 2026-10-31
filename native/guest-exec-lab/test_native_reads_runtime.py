#!/usr/bin/env python3
"""Namespace reads under UID 2000, with an unmodified external FD owner."""
import argparse
from pathlib import Path
import shlex
from test_oci_services import Suite
from benchmark_cpu import observe_suite_cpu


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('store')
    parser.add_argument('--lsof', action='store_true', help='Measure the installed guest lsof without DNS')
    parser.add_argument('--metadata', action='store_true', help='Measure ordinary inode-tree metadata and links')
    parser.add_argument('--profile', action='store_true', help='Enable candidate cost counters; adds clock sampling')
    parser.add_argument('--compare-installed', action='store_true', help='Compare the same store/arguments with the installed runtime')
    args = parser.parse_args()
    suite = Suite(args.build, args.build)
    target = '/tmp/md-native-reads-' + suite.report['id']
    base = [suite.runner, '--store', args.store, '--user', '0:0', '--deadline-seconds', '120']
    try:
        suite.transport.upload(suite.client, Path(__file__).with_name('test_native_reads_guest.py'), suite.base + '/probe.py')
        suite.command(['/system/bin/sh', '-c',
                       'printf "native data" > "$1/target"; ln -s target "$1/link"', 'fixture', suite.base])
        suite.command(base + ['--', '/bin/mkdir', target])
        suite.command(base + ['--', '/bin/mkdir', target + '-alias'])
        command = shlex.join(base + ['--bind-ro', suite.base, target, '--', '/usr/bin/python3', target + '/probe.py'])
        suite.command(['/system/bin/sh', '-c', 'exec 3< "$1/target"\n' + command + ' "$$" ' + shlex.quote(target)
                       + ' ' + shlex.quote(target + '-alias'),
                       'fixture', suite.base])
        workloads = []
        if args.metadata:
            workloads.append(('metadata', '''import os, time
names = sorted(os.listdir('/usr/bin'))[:128]
paths = ['/usr/bin/' + name for name in names]
links = [path for path in paths if os.path.islink(path)]
for _ in range(3):
    start = time.monotonic()
    size = 0
    for _ in range(30):
        for path in paths:
            size += os.lstat(path).st_size
        for path in links:
            size += len(os.readlink(path))
    print('metadata_seconds=%.6f paths=%d links=%d checksum=%d' % (time.monotonic()-start, len(paths), len(links), size), flush=True)
'''))
        if args.lsof:
            code = '''import subprocess, time
for _ in range(3):
    start = time.monotonic()
    result = subprocess.run(['/usr/bin/lsof', '-nP', '-F', 'upnR', '-i', 'TCP@127.0.0.1:9'], capture_output=True)
    assert result.returncode == 1, result
    print('lsof_seconds=%.6f' % (time.monotonic() - start), flush=True)
'''
            workloads.append(('lsof', code))
        for name, code in workloads:
            candidates = [('installed', ['shroot', *base[1:]])] if args.compare_installed else []
            candidates.append(('candidate', base + (['--statistics'] if args.profile else [])))
            for candidate, runner in candidates:
                print(candidate.upper() + ' ' + name, flush=True)
                with observe_suite_cpu(suite) as cpu:
                    output = suite.command(runner + ['--', '/usr/bin/python3', '-c', code])
                suite.report.setdefault('measurements', []).append(
                    dict(workload=name, candidate=candidate, cpu=cpu, output=output))
        suite.report['passed'] = True
    finally:
        try:
            suite.command(base + ['--', '/bin/rmdir', target, target + '-alias'])
        finally:
            suite.finish()


if __name__ == '__main__':
    main()
