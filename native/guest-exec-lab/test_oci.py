#!/usr/bin/env python3
"""Offline image boundary checks. No Desktop, network or kernel adaptation."""
import argparse
import base64
import ctypes
import errno
import gzip
import hashlib
import io
import json
import os
import pathlib
import sqlite3
import subprocess
import tarfile
import tempfile
import unittest

ROOT = '0' * 32
OCI = 'application/vnd.oci.image.'
BINARY = None


def encoded(value):
    return json.dumps(value, separators=(',', ':')).encode()


def tar(entries):
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode='w') as archive:
        for item in entries:
            name, kind, value = item[:3]
            entry = tarfile.TarInfo(name)
            entry.mode = 0o755 if kind == 'dir' else 0o644
            if len(item) > 3:
                entry.mode = item[3]
            if len(item) > 4:
                entry.uid, entry.gid = item[4:6]
            entry.mtime = 123456
            if len(item) > 6:
                entry.pax_headers = {'LIBARCHIVE.xattr.' + name: base64.b64encode(value).decode()
                                     for name, value in item[6].items()}
            if len(item) > 7:
                entry.pax_headers.update(item[7])
            if kind == 'file':
                entry.size = len(value)
                archive.addfile(entry, io.BytesIO(value))
            else:
                entry.type = {'dir': tarfile.DIRTYPE, 'sym': tarfile.SYMTYPE,
                              'hard': tarfile.LNKTYPE, 'device': tarfile.CHRTYPE}[kind]
                entry.linkname = value
                archive.addfile(entry)
    return stream.getvalue()


class Layout:
    def __init__(self, path, layers, compression='gzip', config=None):
        self.path = path
        (path / 'blobs/sha256').mkdir(parents=True)
        self.layers = []
        diffs = []
        for entries in layers:
            raw = tar(entries)
            diffs.append('sha256:' + hashlib.sha256(raw).hexdigest())
            if compression == 'gzip':
                body = gzip.compress(raw)
            elif compression == 'zstd':
                body = subprocess.run(['zstd', '-q', '-c'], input=raw, capture_output=True, check=True).stdout
            else:
                body = raw
            self.layers.append(self.blob(body, OCI + 'layer.v1.tar' + ('+' + compression if compression else '')))
        self.config = {'architecture': 'arm64', 'os': 'linux',
                       'rootfs': {'type': 'layers', 'diff_ids': diffs},
                       'config': {'Env': ['PATH=/usr/bin:/bin'], 'Cmd': ['/bin/sh']}}
        if config:
            self.config.update(config)
        self.publish()

    def blob(self, body, media):
        digest = hashlib.sha256(body).hexdigest()
        (self.path / 'blobs/sha256' / digest).write_bytes(body)
        return {'mediaType': media, 'digest': 'sha256:' + digest, 'size': len(body)}

    def publish(self):
        self.manifest = {'schemaVersion': 2, 'mediaType': OCI + 'manifest.v1+json',
                         'config': self.blob(encoded(self.config), OCI + 'config.v1+json'),
                         'layers': self.layers}
        self.descriptor = self.blob(encoded(self.manifest), OCI + 'manifest.v1+json')
        self.descriptor['annotations'] = {'org.opencontainers.image.ref.name': 'fixture'}
        (self.path / 'oci-layout').write_bytes(encoded({'imageLayoutVersion': '1.0.0'}))
        self.index([self.descriptor])

    def index(self, descriptors):
        (self.path / 'index.json').write_bytes(encoded({'schemaVersion': 2, 'manifests': descriptors}))


class Images(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='md-oci-')
        self.root = pathlib.Path(self.tmp.name)
        self.destination = self.root / 'image'

    def tearDown(self):
        self.tmp.cleanup()

    def layout(self, layers=None, **kwargs):
        return Layout(self.root / 'layout', layers or [[('hello', 'file', b'world')]], **kwargs)

    def run_import(self, layout, success=True, args=(), preserve=False):
        result = subprocess.run([BINARY, 'import', str(layout.path), str(self.destination),
                                 '--preserve-ownership' if preserve else '--map-current-user', *args],
                                capture_output=True, text=True, timeout=60)
        self.assertEqual(result.returncode == 0, success, result.stdout + result.stderr)
        self.assertFalse(list(self.root.glob('.md-image-*')))
        if not success:
            self.assertFalse(self.destination.exists(), result.stderr)
        return result

    def object(self, path):
        with sqlite3.connect(self.destination / 'namespace.db') as db:
            parent = ROOT
            for name in path.split('/'):
                if not name:
                    continue
                row = db.execute('SELECT object FROM names WHERE parent=? AND name=?', (parent, name.encode())).fetchone()
                if not row:
                    return None
                parent = row[0]
            return self.destination / 'objects' / parent

    def test_gzip(self):
        self.run_import(self.layout())
        self.assertEqual(self.object('hello').read_bytes(), b'world')

    def test_preserved_owners_and_snapshot(self):
        layout = self.layout([[('d', 'dir', '', 0o2750, 1000, 2000),
                               ('d/a', 'file', b'value', 0o4755, 123, 456),
                               ('d/link', 'sym', 'a', 0o777, 789, 987),
                               ('d/alias', 'hard', 'd/a')]],
                             config={'config': {'User': '123:456', 'Cmd': ['/d/a']}})
        self.run_import(layout, preserve=True)
        expected = {'d': (1000, 2000, 0o2750), 'd/a': (123, 456, 0o4755),
                    'd/link': (789, 987, 0o777)}
        for path, metadata in expected.items():
            backing = self.object(path)
            with sqlite3.connect(self.destination / 'namespace.db') as db:
                actual = db.execute('SELECT uid,gid,mode FROM objects WHERE object=?',
                                    (backing.name,)).fetchone()
            self.assertEqual(actual, metadata)
            self.assertEqual(backing.lstat().st_uid, os.getuid())
            self.assertFalse(backing.lstat().st_mode & 0o6000)
        self.assertEqual(self.object('d/a'), self.object('d/alias'))
        instance = self.root / 'instance'
        subprocess.run([BINARY, 'create', str(self.destination), str(instance)], check=True,
                       capture_output=True, timeout=60)
        with sqlite3.connect(instance / 'namespace.db') as db:
            self.assertEqual(db.execute('SELECT uid,gid,mode FROM objects WHERE object=?',
                                        (self.object('d/a').name,)).fetchone(), expected['d/a'])
        info = json.loads(subprocess.check_output([BINARY, 'inspect', str(instance)]))
        self.assertEqual(str(info['guestUsers']), '1')
        self.assertEqual(info['config']['config']['User'], '123:456')

    def test_ownership_policy_is_explicit(self):
        layout = self.layout()
        for flags in ([], ['--map-current-user', '--preserve-ownership']):
            result = subprocess.run([BINARY, 'import', str(layout.path), str(self.destination), *flags],
                                    capture_output=True, timeout=60)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(self.destination.exists())

    def test_capability_inode_metadata(self):
        value = bytes.fromhex('0100000200040000000000000000000000000000')
        layout = self.layout([[('program', 'file', b'ELF fixture', 0o755, 0, 0,
                               {'security.capability': value}), ('alias', 'hard', 'program')]])
        self.run_import(layout, preserve=True)
        backing = self.object('program')
        self.assertEqual(backing, self.object('alias'))
        libc = ctypes.CDLL(None, use_errno=True)
        libc.getxattr.restype = ctypes.c_ssize_t
        self.assertEqual(libc.getxattr(os.fsencode(backing), b'security.capability', None, 0), -1)
        self.assertEqual(ctypes.get_errno(), errno.ENODATA)
        with sqlite3.connect(self.destination / 'namespace.db') as db:
            self.assertEqual(db.execute('SELECT value FROM file_capabilities WHERE object=?',
                                        (backing.name,)).fetchone(), (value,))
        instance = self.root / 'instance'
        subprocess.run([BINARY, 'create', str(self.destination), str(instance)], check=True, capture_output=True)
        with sqlite3.connect(instance / 'namespace.db') as db:
            self.assertEqual(db.execute('SELECT value FROM file_capabilities WHERE object=?',
                                        (backing.name,)).fetchone(), (value,))

    def test_malformed_capability(self):
        self.run_import(self.layout([[('bad', 'file', b'x', 0o755, 0, 0,
                                       {'security.capability': b'broken'})]]), success=False)

    def test_capability_dual_tar_encoding(self):
        value = bytes.fromhex('0100000200040000000000000000000000000000')
        layout = self.layout([[('program', 'file', b'code', 0o755, 0, 0,
                              {'security.capability': value},
                              {'SCHILY.xattr.security.capability': value.decode('ascii')})]])
        self.run_import(layout, preserve=True)
        with sqlite3.connect(self.destination / 'namespace.db') as db:
            self.assertEqual(db.execute('SELECT value FROM file_capabilities').fetchone(), (value,))

    def test_acl_import_snapshot_and_replacement(self):
        acl = 'user::rwx,user:fixture:r-x:1234,group::---,mask::r-x,other::---'
        layout = self.layout([
            [('folder', 'dir', '', 0o750, 0, 0, {},
              {'SCHILY.acl.access': acl, 'SCHILY.acl.default': acl}),
             ('program', 'file', b'old', 0o750, 0, 0, {}, {'SCHILY.acl.access': acl}),
             ('alias', 'hard', 'program')], [('program', 'file', b'new')]])
        self.run_import(layout, preserve=True)
        with sqlite3.connect(self.destination / 'namespace.db') as db:
            self.assertEqual(db.execute('SELECT acl_mask FROM objects WHERE object=?',
                                        (self.object('folder').name,)).fetchone(), (3,))
            self.assertEqual(db.execute('SELECT acl_mask FROM objects WHERE object=?',
                                        (self.object('alias').name,)).fetchone(), (1,))
            self.assertEqual(db.execute('SELECT acl_mask FROM objects WHERE object=?',
                                        (self.object('program').name,)).fetchone(), (0,))
            expected = db.execute('SELECT object,type,value FROM inode_acls ORDER BY object,type').fetchall()
        instance = self.root / 'instance'
        subprocess.run([BINARY, 'create', self.destination, instance], check=True, capture_output=True)
        with sqlite3.connect(instance / 'namespace.db') as db:
            self.assertEqual(db.execute('SELECT object,type,value FROM inode_acls ORDER BY object,type').fetchall(), expected)

    def test_acl_requires_ownership_policy(self):
        self.run_import(self.layout([[('folder', 'dir', '', 0o750, 0, 0, {},
            {'SCHILY.acl.default': 'user::rwx,group::r-x,other::---'})]]), success=False)

    def test_unknown_attribute(self):
        self.run_import(self.layout([[('bad', 'file', b'x', 0o755, 0, 0,
                                       {'security.unknown': b'value'})]]), success=False)

    def test_tar(self):
        self.run_import(self.layout(compression=''))
        self.assertEqual(self.object('hello').read_bytes(), b'world')

    def test_zstd(self):
        self.run_import(self.layout(compression='zstd'))
        self.assertEqual(self.object('hello').read_bytes(), b'world')

    def test_whiteouts_apply_before_current_layer(self):
        layout = self.layout([
            [('old', 'file', b'old'), ('d/old', 'file', b'old'), ('same', 'file', b'lower')],
            [('same', 'file', b'upper'), ('d/new', 'file', b'new'), ('.wh.old', 'file', b''),
             ('d/.wh..wh..opq', 'file', b''), ('.wh.same', 'file', b'')]])
        self.run_import(layout)
        self.assertIsNone(self.object('old'))
        self.assertIsNone(self.object('d/old'))
        self.assertEqual(self.object('d/new').read_bytes(), b'new')
        self.assertEqual(self.object('same').read_bytes(), b'upper')

    def test_hardlinks_forward(self):
        layout = self.layout([[('a', 'hard', 'b'), ('b', 'hard', 'c'), ('c', 'file', b'linked'),
                               ('s', 'sym', '/c')]])
        self.run_import(layout)
        self.assertEqual(self.object('a'), self.object('c'))
        self.assertEqual(self.object('b'), self.object('c'))
        self.assertEqual(self.object('s').readlink(), pathlib.Path('/c'))

    def test_hardlink_cycle(self):
        self.run_import(self.layout([[('a', 'hard', 'b'), ('b', 'hard', 'a')]]), False)

    def test_replacement_does_not_modify_lower_alias(self):
        layout = self.layout([[('a', 'file', b'lower'), ('alias', 'hard', 'a')],
                              [('a', 'file', b'upper')]])
        self.run_import(layout)
        self.assertEqual(self.object('a').read_bytes(), b'upper')
        self.assertEqual(self.object('alias').read_bytes(), b'lower')
        self.assertNotEqual(self.object('a'), self.object('alias'))

    def test_upper_hardlink_to_lower_object(self):
        self.run_import(self.layout([[('a', 'file', b'lower')], [('alias', 'hard', 'a')]]))
        self.assertEqual(self.object('a'), self.object('alias'))

    def test_whiteout_preserves_other_hardlink(self):
        self.run_import(self.layout([[('a', 'file', b'value'), ('alias', 'hard', 'a')],
                                     [('.wh.a', 'file', b'')]]))
        self.assertIsNone(self.object('a'))
        self.assertEqual(self.object('alias').read_bytes(), b'value')

    def test_layer_type_replacements(self):
        self.run_import(self.layout([
            [('dir/old', 'file', b'old'), ('file', 'file', b'old'), ('sym', 'sym', 'dir')],
            [('dir', 'file', b'new'), ('file', 'dir', ''), ('file/new', 'file', b'child'),
             ('sym', 'file', b'regular')]]))
        self.assertIsNone(self.object('dir/old'))
        self.assertEqual(self.object('dir').read_bytes(), b'new')
        self.assertEqual(self.object('file/new').read_bytes(), b'child')
        self.assertEqual(self.object('sym').read_bytes(), b'regular')

    def test_opaque_root_and_later_recreation(self):
        self.run_import(self.layout([
            [('a/old', 'file', b'old'), ('b', 'file', b'old')],
            [('a/current', 'file', b'current'), ('.wh..wh..opq', 'file', b'')],
            [('b', 'file', b'recreated')]]))
        self.assertIsNone(self.object('a/old'))
        self.assertEqual(self.object('a/current').read_bytes(), b'current')
        self.assertEqual(self.object('b').read_bytes(), b'recreated')

    def test_replacement_drops_capability_not_alias_metadata(self):
        capability = bytes.fromhex('0100000200040000000000000000000000000000')
        self.run_import(self.layout([
            [('app', 'file', b'old', 0o755, 0, 0, {'security.capability': capability}),
             ('alias', 'hard', 'app')], [('app', 'file', b'new')]]), preserve=True)
        with sqlite3.connect(self.destination / 'namespace.db') as db:
            self.assertIsNone(db.execute('SELECT value FROM file_capabilities WHERE object=?',
                                         (self.object('app').name,)).fetchone())
            self.assertEqual(db.execute('SELECT value FROM file_capabilities WHERE object=?',
                                        (self.object('alias').name,)).fetchone(), (capability,))

    def test_duplicate_paths(self):
        self.run_import(self.layout([[('a', 'file', b'1'), ('./a', 'file', b'2')]]), False)

    def test_duplicate_whiteouts(self):
        self.run_import(self.layout([[('.wh.a', 'file', b''), ('.wh.a', 'file', b'')]]), False)

    def test_restrictive_permissions_do_not_block_layers(self):
        layout = self.layout([[('private', 'dir', '', 0), ('private/old', 'file', b'old', 0)],
                              [('private/new', 'file', b'new'), ('private', 'dir', '', 0)]])
        self.run_import(layout)
        self.assertEqual(self.object('private/new').read_bytes(), b'new')
        self.assertEqual(self.object('private').stat().st_mode & 0o777, 0)
        self.object('private').chmod(0o700)
        self.object('private/old').chmod(0o600)

    def test_failed_snapshot_cleans_restrictive_private_tree(self):
        self.run_import(self.layout([[('private', 'dir', '', 0)]]))
        (self.destination / 'image-config.json').unlink()
        instance = self.root / 'instance'
        result = subprocess.run([BINARY, 'create', str(self.destination), str(instance)],
                                capture_output=True, text=True, timeout=60)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(instance.exists())
        self.assertFalse(list(self.root.glob('.md-image-*')))
        self.object('private').chmod(0o700)

    def test_parent_escape(self):
        self.run_import(self.layout([[('../escaped', 'file', b'bad')]]), False)
        self.assertFalse((self.root / 'escaped').exists())

    def test_absolute_entry(self):
        self.run_import(self.layout([[('/absolute', 'file', b'bad')]]), False)

    def test_devices_rejected(self):
        self.run_import(self.layout([[('device', 'device', '')]]), False)

    def test_whiteout_data_rejected(self):
        self.run_import(self.layout([[('.wh.old', 'file', b'bad')]]), False)

    def test_layer_digest(self):
        layout = self.layout()
        blob = layout.path / 'blobs/sha256' / layout.layers[0]['digest'][7:]
        data = bytearray(blob.read_bytes()); data[-1] ^= 1; blob.write_bytes(data)
        self.run_import(layout, False)

    def test_diff_id(self):
        layout = self.layout()
        layout.config['rootfs']['diff_ids'][0] = 'sha256:' + '0' * 64
        layout.publish()
        self.run_import(layout, False)

    def test_wrong_architecture(self):
        self.run_import(self.layout(config={'architecture': 'amd64'}), False)

    def test_wrong_os(self):
        self.run_import(self.layout(config={'os': 'windows'}), False)

    def test_blob_symlink(self):
        layout = self.layout()
        blob = layout.path / 'blobs/sha256' / layout.layers[0]['digest'][7:]
        moved = self.root / 'blob'; blob.rename(moved); blob.symlink_to(moved)
        self.run_import(layout, False)

    def test_duplicate_json_keys(self):
        layout = self.layout()
        index = layout.path / 'index.json'
        index.write_text(index.read_text().replace('"schemaVersion":2', '"schemaVersion":2,"schemaVersion":2'))
        self.run_import(layout, False)

    def test_embedded_nul(self):
        layout = self.layout()
        index = layout.path / 'index.json'
        index.write_bytes(index.read_bytes() + b'\0extra')
        self.run_import(layout, False)

    def test_ambiguous_index(self):
        layout = self.layout()
        layout.index([layout.descriptor, layout.descriptor])
        self.run_import(layout, False)

    def test_reference(self):
        layout = self.layout()
        other = dict(layout.descriptor, annotations={'org.opencontainers.image.ref.name': 'other'})
        layout.index([layout.descriptor, other])
        self.run_import(layout, args=('--reference', 'fixture'))

    def test_atomic_failure_later_layer(self):
        layout = self.layout([[('ok', 'file', b'first')], [('bad', 'hard', 'missing')]])
        self.run_import(layout, False)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('build', type=pathlib.Path)
    args, rest = parser.parse_known_args()
    BINARY = str((args.build / 'libmagicdesk_guest_image.so').resolve())
    unittest.main(argv=[__file__, *rest])
