#!/usr/bin/env python3
"""Wrap an independently authenticated rootfs tar.gz as an unchanged OCI layer.

Fixture preparation only: no extraction, path filtering or package execution.
The production importer remains responsible for rejecting unsupported entries.
"""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import shutil


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('destination', type=Path)
    args = parser.parse_args()
    args.destination.mkdir()
    blobs = args.destination / 'blobs/sha256'
    blobs.mkdir(parents=True)
    with args.archive.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    with gzip.open(args.archive, 'rb') as stream:
        diff = hashlib.file_digest(stream, 'sha256').hexdigest()
    shutil.copyfile(args.archive, blobs / digest)
    media = 'application/vnd.oci.image.'

    def blob(value, kind):
        data = json.dumps(value, separators=(',', ':')).encode()
        key = hashlib.sha256(data).hexdigest()
        (blobs / key).write_bytes(data)
        return {'mediaType': media + kind, 'digest': 'sha256:' + key, 'size': len(data)}

    config = blob({'architecture': 'arm64', 'os': 'linux',
                   'rootfs': {'type': 'layers', 'diff_ids': ['sha256:' + diff]},
                   'config': {'Env': ['PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'],
                              'Cmd': ['/bin/sh']}}, 'config.v1+json')
    manifest = blob({'schemaVersion': 2, 'mediaType': media + 'manifest.v1+json',
                     'config': config,
                     'layers': [{'mediaType': media + 'layer.v1.tar+gzip',
                                 'digest': 'sha256:' + digest, 'size': args.archive.stat().st_size}]},
                    'manifest.v1+json')
    (args.destination / 'index.json').write_text(json.dumps({'schemaVersion': 2, 'manifests': [manifest]}) + '\n')
    (args.destination / 'oci-layout').write_text('{"imageLayoutVersion":"1.0.0"}\n')
    print(json.dumps({'archive': str(args.archive), 'sha256': digest, 'diffId': diff, 'manifest': manifest}))


if __name__ == '__main__':
    main()
