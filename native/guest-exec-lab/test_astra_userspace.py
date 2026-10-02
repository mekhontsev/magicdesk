#!/usr/bin/env python3
"""Direct Astra ARM64 userspace smoke, without QEMU or package configuration."""
import argparse
from pathlib import Path
from test_oci_services import Suite


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--build', type=Path)
    parser.add_argument('--layout', required=True, type=Path)
    args = parser.parse_args()
    suite = Suite(args.output, args.build)
    try:
        store = suite.prepare('astra-arm64', args.layout)
        suite.command(suite.run(store, '--env', 'LC_ALL=C', '--', '/bin/bash', '-ec', '''
cat /etc/os-release
test "$(id -u)" = 0
test "$(getconf GNU_LIBC_VERSION)" = 'glibc 2.28'
test "$(getconf LONG_BIT)" = 64
mkdir -p /tmp/md-astra/{source,restored}
printf 'astra userspace\n' >/tmp/md-astra/source/value
ln /tmp/md-astra/source/value /tmp/md-astra/source/hard
ln -s value /tmp/md-astra/source/symbolic
(printf 'child\n' >/tmp/md-astra/child) &
wait
test "$(cat /tmp/md-astra/child)" = child
test "$(cat /tmp/md-astra/source/symbolic)" = 'astra userspace'
test "$(stat -c %h /tmp/md-astra/source/value)" = 2
tar -C /tmp/md-astra/source -czf /tmp/md-astra/archive.tgz .
tar -C /tmp/md-astra/restored -xzf /tmp/md-astra/archive.tgz
diff -r /tmp/md-astra/source /tmp/md-astra/restored
test "$(stat -c %h /tmp/md-astra/restored/value)" = 2
test "$(find /tmp/md-astra/source -type f | wc -l)" = 2
test "$(printf 'one\ntwo\nthree\n' | sed -n '2p' | grep two)" = two
xz -k /tmp/md-astra/archive.tgz
xz -dc /tmp/md-astra/archive.tgz.xz | sha256sum >/tmp/md-astra/hash
test "$(cut -d ' ' -f 1 /tmp/md-astra/hash)" = "$(sha256sum /tmp/md-astra/archive.tgz | cut -d ' ' -f 1)"
printf 'PASS Astra ARM64 glibc, shell, child process, links, archives and pipelines\n'
'''))
        suite.report['passed'] = True
    except Exception as error:
        suite.report['failure'] = repr(error)
        raise
    finally:
        suite.finish()


if __name__ == '__main__':
    main()
