#!/usr/bin/env python3
"""Check rootfs fixture conversion without executing archive contents."""
import gzip
import hashlib
import io
import json
import lzma
from pathlib import Path
import tarfile
import tempfile
from rootfs_oci_fixture import write_layout


def layer(layout):
    index = json.loads((layout / 'index.json').read_text())
    manifest = json.loads((layout / 'blobs/sha256' / index['manifests'][0]['digest'][7:]).read_bytes())
    item = manifest['layers'][0]
    data = (layout / 'blobs/sha256' / item['digest'][7:]).read_bytes()
    assert len(data) == item['size']
    assert hashlib.sha256(data).hexdigest() == item['digest'][7:]
    raw = gzip.decompress(data)
    config = json.loads((layout / 'blobs/sha256' / manifest['config']['digest'][7:]).read_bytes())
    assert config['rootfs']['diff_ids'] == ['sha256:' + hashlib.sha256(raw).hexdigest()]
    return raw


def main():
    archive = io.BytesIO()
    with tarfile.open(fileobj=archive, mode='w') as stream:
        entry = tarfile.TarInfo('etc/value'); entry.size = 4; entry.mode = 0o644
        stream.addfile(entry, io.BytesIO(b'test'))
        entry = tarfile.TarInfo('etc/alias'); entry.type = tarfile.LNKTYPE; entry.linkname = 'etc/value'
        stream.addfile(entry)
        for name in ('./dev/null', 'etc/device', '/dev/absolute', 'dev/../escape'):
            entry = tarfile.TarInfo(name); entry.type = tarfile.CHRTYPE
            entry.devmajor = 1; entry.devminor = 3; stream.addfile(entry)
    raw = archive.getvalue()
    with tempfile.TemporaryDirectory(prefix='md-rootfs-fixture-') as directory:
        root = Path(directory)
        for extension, encode in (('gz', gzip.compress), ('xz', lzma.compress)):
            source = root / ('root.tar.' + extension); source.write_bytes(encode(raw))
            layout = root / ('unchanged-' + extension)
            write_layout([source], layout)
            assert layer(layout) == raw
        with tarfile.open(fileobj=io.BytesIO(layer(layout))) as stream:
            names = stream.getnames()
            assert all(name in names for name in ('./dev/null', 'etc/device', '/dev/absolute', 'dev/../escape'))
            assert stream.getmember('etc/alias').islnk()
            assert stream.extractfile('etc/alias').read() == b'test'
        assert source.read_bytes() == lzma.compress(raw)
    print('PASS unchanged gzip/XZ tar content, OCI digests, hardlinks and unfiltered device/path entries')


if __name__ == '__main__':
    main()
