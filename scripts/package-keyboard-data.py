"""Rebuild the portable XKB runtime asset from an installed xkeyboard-config tree."""
import argparse
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo


def pack(root, notice, output):
    if not (root / 'rules/evdev').is_file():
        raise ValueError('Missing generated evdev rules; use an installed data tree')
    root = root.resolve()
    entries = {path.relative_to(root).as_posix(): path for path in root.rglob('*')
               if path.is_file() and path.resolve().is_relative_to(root)}
    entries['COPYING'] = notice
    with ZipFile(output, 'w') as archive:
        for name, path in sorted(entries.items()):
            entry = ZipInfo(name)
            entry.create_system = 3
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, path.read_bytes(), ZIP_DEFLATED, 9)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('root', 'notice', 'output'):
        parser.add_argument(name, type=Path)
    args = parser.parse_args()
    pack(args.root, args.notice, args.output)
