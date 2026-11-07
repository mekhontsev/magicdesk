#!/usr/bin/env python3
"""Long exec vectors, proc images and mkinitcpio-style file batches under shell UID."""
import argparse
from pathlib import Path
from test_oci_services import Suite


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--store', required=True)
    parser.add_argument('--build', type=Path)
    parser.add_argument('--fixtures', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=Path('build/argument-results'))
    args = parser.parse_args()
    suite = Suite(args.output, args.build)
    suite.report['store'] = args.store
    target='/tmp/md-arguments-' + suite.report['id']
    base=[suite.runner, '--store', args.store, '--user', '0:0']
    created=False
    try:
        source = args.fixtures / 'md-arguments-fixture'
        suite.upload(source, suite.base + '/md-arguments-fixture')
        suite.chmod('700', suite.base + '/md-arguments-fixture')
        suite.command(base + ['--', '/bin/mkdir', target])
        created=True
        prefix = base + ['--bind', suite.base, target, '--']
        suite.command(prefix + [target + '/md-arguments-fixture'], timeout=120)
        suite.command(prefix + ['/bin/true'] + ['argument'] * 4096)
        suite.command(suite.image_command('exec', args.store, '--user', 'root', '--',
                                         '/bin/true', *(['argument'] * 4096)))
        suite.command(prefix + ['/bin/bash', '-ec', r'''
work=$(mktemp -d /tmp/md-arguments-files.XXXXXX)
trap 'rm -rf "$work"' EXIT
mkdir "$work/source" "$work/early"
cd "$work/source"
files=()
for ((i=0;i<4096;i++)); do
    name="file-$i.zst"
    printf 'file %d\n' "$i" > "$name"
    files+=("$name")
done
cp -flt "$work/early" --parents "${files[@]}"
rm -f "${files[@]}"
test "$(find "$work/early" -type f | wc -l)" = 4096
test "$(find . -type f | wc -l)" = 0
test "$(cat "$work/early/file-4095.zst")" = 'file 4095'
printf 'PASS mkinitcpio-style cp/rm: 4096 operands, contents retained\n'
'''], timeout=180)
        suite.report['passed'] = True
    finally:
        try:
            if created:
                suite.command(base + ['--', '/bin/rmdir', target])
        finally:
            suite.finish()


if __name__ == '__main__':
    main()
