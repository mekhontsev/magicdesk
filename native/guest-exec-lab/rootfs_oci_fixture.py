#!/usr/bin/env python3
"""Wrap an independently authenticated rootfs archive as an OCI layer.

Fixture preparation only: no host extraction or package execution.
XZ is transcoded to OCI gzip without changing the decompressed tar stream.
The production importer remains responsible for rejecting unsupported entries.
"""
import argparse
import gzip
import hashlib
import json
import lzma
from pathlib import Path
import shutil
import tempfile


def write_layout(archives, destination):
    destination.mkdir()
    blobs = destination / 'blobs/sha256'
    blobs.mkdir(parents=True)
    media = 'application/vnd.oci.image.'
    layers, differences = [], []
    for archive in archives:
        with tempfile.TemporaryDirectory(prefix='md-oci-layer-') as temporary:
            compressed = archive
            if archive.suffix == '.xz':
                compressed = Path(temporary) / 'layer.tar.gz'
                with lzma.open(archive, 'rb') as source, compressed.open('wb') as output:
                    with gzip.GzipFile(filename='', mode='wb', fileobj=output, compresslevel=1, mtime=0) as target:
                        shutil.copyfileobj(source, target, 1024 * 1024)
            with compressed.open('rb') as stream:
                digest = hashlib.file_digest(stream, 'sha256').hexdigest()
            with gzip.open(compressed, 'rb') as stream:
                diff = hashlib.file_digest(stream, 'sha256').hexdigest()
            size = compressed.stat().st_size
            shutil.copyfile(compressed, blobs / digest)
        layers.append({'mediaType': media + 'layer.v1.tar+gzip',
                       'digest': 'sha256:' + digest, 'size': size})
        differences.append('sha256:' + diff)

    def blob(value, kind):
        data = json.dumps(value, separators=(',', ':')).encode()
        key = hashlib.sha256(data).hexdigest()
        (blobs / key).write_bytes(data)
        return {'mediaType': media + kind, 'digest': 'sha256:' + key, 'size': len(data)}

    config = blob({'architecture': 'arm64', 'os': 'linux',
                   'rootfs': {'type': 'layers', 'diff_ids': differences},
                   'config': {'Env': ['PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'],
                              'Cmd': ['/bin/sh']}}, 'config.v1+json')
    manifest = blob({'schemaVersion': 2, 'mediaType': media + 'manifest.v1+json',
                     'config': config,
                     'layers': layers},
                    'manifest.v1+json')
    (destination / 'index.json').write_text(json.dumps({'schemaVersion': 2, 'manifests': [manifest]}) + '\n')
    (destination / 'oci-layout').write_text('{"imageLayoutVersion":"1.0.0"}\n')
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('destination', type=Path)
    args = parser.parse_args()
    manifest = write_layout([args.archive], args.destination)
    print(json.dumps({'archive': str(args.archive), 'manifest': manifest}))


if __name__ == '__main__':
    main()
