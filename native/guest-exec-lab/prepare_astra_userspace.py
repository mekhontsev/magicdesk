#!/usr/bin/env python3
"""Prepare a minimal official Astra ARM64 userspace fixture, not an installed OS.

Repository trust is HTTPS plus Release -> Packages -> archive SHA256, not a
pinned signing key. No maintainer scripts run and no host files are extracted.
"""
import argparse
import email.parser
import gzip
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile
import urllib.request
from rootfs_oci_fixture import write_layout


BASE = 'https://dl.astralinux.ru/astra/stable/4.7_arm/repository-main/'
SUITE = '4.7_arm'
PACKAGES = ('base-files', 'libc6', 'libc-bin', 'libgcc1', 'libtinfo6', 'libacl1',
            'libattr1', 'libselinux1', 'libpcre3', 'libgmp10', 'libpcre2-8-0',
            'liblzma5', 'libbz2-1.0', 'zlib1g', 'bash', 'dash', 'coreutils',
            'sed', 'grep', 'findutils', 'diffutils', 'gzip', 'tar', 'xz-utils')


def records(text):
    return [email.parser.Parser().parsestr(block) for block in text.split('\n\n') if block.strip()]


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--cache', type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True)

    def download(relative):
        if relative.startswith('/') or '..' in relative.split('/') or ':' in relative:
            raise ValueError('repository-relative path required')
        with urllib.request.urlopen(BASE + relative, timeout=90) as response:
            return response.read()

    release = download(f'dists/{SUITE}/Release')
    release_record = records(release.decode())[0]
    index_name = 'main/binary-arm64/Packages.gz'
    expected = next(line.split() for line in release_record['SHA256'].splitlines()
                    if line.strip() and line.split()[2] == index_name)
    compressed = download(f'dists/{SUITE}/{index_name}')
    assert len(compressed) == int(expected[1]) and digest(compressed) == expected[0]
    index = records(gzip.decompress(compressed).decode())
    report = {'repository': BASE, 'suite': SUITE, 'architecture': 'arm64',
              'trust': 'HTTPS; unsigned Release -> Packages -> deb SHA256 chain',
              'releaseSha256': digest(release), 'indexSha256': digest(compressed),
              'packages': [], 'installedDistribution': False}
    archives = []
    for name in PACKAGES:
        record = next(p for p in index if p['Package'] == name
                      and p['Architecture'] in ('arm64', 'all'))
        package = args.output / Path(record['Filename']).name
        cached = args.cache / package.name if args.cache else None
        data = cached.read_bytes() if cached and cached.exists() else download(record['Filename'])
        assert len(data) == int(record['Size']) and digest(data) == record['SHA256'], name
        package.write_bytes(data)
        payload = subprocess.check_output(['dpkg-deb', '--fsys-tarfile', str(package)])
        archive = args.output / (name + '.tar.gz')
        with gzip.open(archive, 'wb') as layer:
            layer.write(payload)
        archives.append(archive)
        report['packages'].append({'name': name, 'version': record['Version'],
                                  'file': record['Filename'], 'sha256': digest(data)})
        print(name, record['Version'], flush=True)
    archive = args.output / 'fixture.tar.gz'
    archives.append(archive)
    with tarfile.open(archive, 'w:gz') as layer:
        for name, mode in (('tmp', 0o1777), ('proc', 0o755), ('dev', 0o755),
                           ('root', 0o700), ('etc', 0o755)):
            member = tarfile.TarInfo(name)
            member.type, member.mode = tarfile.DIRTYPE, mode
            layer.addfile(member)
        member = tarfile.TarInfo('bin/sh')
        member.type, member.linkname, member.mode = tarfile.SYMTYPE, 'dash', 0o777
        layer.addfile(member)
        for name, text in {'etc/passwd': 'root:x:0:0:root:/root:/bin/bash\n',
                           'etc/group': 'root:x:0:\n'}.items():
            data = text.encode()
            member = tarfile.TarInfo(name)
            member.mode, member.size = 0o644, len(data)
            layer.addfile(member, io.BytesIO(data))
    report['archives'] = {p.name: digest(p.read_bytes()) for p in archives}
    (args.output / 'manifest.json').write_text(json.dumps(report, indent=2) + '\n')
    write_layout(archives, args.output / 'layered-layout')


if __name__ == '__main__':
    main()
