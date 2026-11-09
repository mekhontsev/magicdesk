#!/usr/bin/env python3
"""Offline image boundary checks. No Desktop, network or kernel adaptation."""
import argparse
import base64
import ctypes
from contextlib import closing, contextmanager
import errno
import fcntl
import gzip
import hashlib
import io
import json
import os
import select
import signal
import pathlib
import shutil
import sqlite3
import stat
import subprocess
import sys
import tarfile
import tempfile
import threading
import unittest

ROOT = '0' * 32
OCI = 'application/vnd.oci.image.'
BINARY = None


@contextmanager
def database(path, **options):
    # A connection context commits/rolls back but does not close SQLite. WAL
    # checks need deterministic connection and native-lock retirement.
    with closing(sqlite3.connect(path, **options)) as connection:
        with connection:
            yield connection


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
                              'hard': tarfile.LNKTYPE, 'device': tarfile.CHRTYPE,
                              'block': tarfile.BLKTYPE, 'fifo': tarfile.FIFOTYPE}[kind]
                entry.linkname = value
                if kind in ('device', 'block'):
                    entry.devmajor, entry.devminor = (1, 3) if kind == 'device' else (8, 0)
                archive.addfile(entry)
    return stream.getvalue()


def frozen_backup_v1():
    # Fixed wire-format input, independent of the current exporter.
    header = b'{"format":1,"guestUsers":1,"imageConfig":{"config":{"Cmd":["/bin/sh"]}}}'
    return tar([
        ('magicdesk-backup.json', 'file', header),
        ('rootfs/.', 'dir', '', 0o755, 0, 0, {'magicdesk.backup.mode': b'\xed\x01'}),
        ('rootfs/file', 'file', b'contents', 0o640, 0, 0, {'magicdesk.backup.mode': b'\xa0\x01'}),
        ('rootfs/alias', 'hard', 'rootfs/file'),
        ('rootfs/native', 'file', b'native', 0o6750, 42, 43,
         {'magicdesk.backup.mode': b'\xe8\x0d'})])


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
        result = self.command('import', layout.path, self.destination,
                              '--preserve-ownership' if preserve else '--map-current-user', *args,
                              success=success)
        self.assertFalse(list(self.root.glob('.md-image-*')))
        if not success:
            self.assertFalse(self.destination.exists(), result.stderr)
        return result

    def object(self, path):
        with database(self.destination / 'namespace.db') as db:
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

    def test_format_and_revision_are_independent_of_import_identity(self):
        version = int(self.command('format').stdout)
        self.assertEqual(version, 17)
        self.assertEqual(self.command('import-version').stdout.strip(), '1')
        self.run_import(self.layout())
        with database(self.destination / 'namespace.db') as db:
            self.assertEqual(version, db.execute('PRAGMA user_version').fetchone()[0])
            self.assertEqual(0x53485254, db.execute('PRAGMA application_id').fetchone()[0])
            revision = db.execute("SELECT value FROM properties WHERE key='schema-revision'").fetchone()[0]
        result = json.loads(self.command('inspect', self.destination).stdout)
        self.assertEqual(result['format'], version)
        self.assertEqual(result['schemaRevision'], revision)

    def test_unsupported_format_is_never_updated_or_deleted(self):
        self.run_import(self.layout())
        for version in (14, 15, 16, 18):
            with self.subTest(version=version):
                with database(self.destination / 'namespace.db') as db:
                    db.execute('PRAGMA user_version=' + str(version))
                    db.execute('PRAGMA journal_mode=DELETE')
                for command in ('inspect', 'prepare'):
                    result = self.command(command, self.destination, success=False)
                    self.assertIn('errno=' + str(errno.EPROTONOSUPPORT), result.stderr)
                with database(self.destination / 'namespace.db') as db:
                    self.assertEqual(version, db.execute('PRAGMA user_version').fetchone()[0])
                    self.assertEqual('delete', db.execute('PRAGMA journal_mode').fetchone()[0])
                self.assertEqual(self.object('hello').read_bytes(), b'world')

    def test_wrong_application_and_unknown_revision_fail_before_updates(self):
        self.run_import(self.layout())
        for sql in ('PRAGMA application_id=0',
                    "UPDATE properties SET value=2147483647 WHERE key='schema-revision'"):
            with self.subTest(sql=sql):
                with database(self.destination / 'namespace.db') as db:
                    db.execute(sql)
                for command in ('inspect', 'prepare'):
                    result = self.command(command, self.destination, success=False)
                    self.assertIn('errno=' + str(errno.EPROTONOSUPPORT), result.stderr)
                with database(self.destination / 'namespace.db') as db:
                    db.execute('PRAGMA application_id=1397248596')

    def test_journal_policy_and_indexes_do_not_change_store_identity(self):
        self.run_import(self.layout())
        path = self.destination / 'namespace.db'
        identity = self.destination.stat().st_ino, self.object('hello').stat().st_ino
        with database(path) as db:
            db.execute('DROP INDEX objects_parent')
        self.command('inspect', self.destination)
        with database(path) as db:
            self.assertFalse(db.execute("SELECT 1 FROM sqlite_schema WHERE name='objects_parent'").fetchone())
            db.execute('PRAGMA journal_mode=DELETE')
        self.command('inspect', self.destination, success=False)
        fd = os.open(self.destination, os.O_RDONLY | os.O_DIRECTORY)
        try:
            fcntl.flock(fd, fcntl.LOCK_SH | fcntl.LOCK_NB)
            result = self.command('prepare', self.destination, success=False)
            self.assertIn('errno=' + str(errno.EBUSY), result.stderr)
        finally:
            os.close(fd)
        self.command('prepare', self.destination)
        self.command('prepare', self.destination)
        self.assertEqual(identity, (self.destination.stat().st_ino, self.object('hello').stat().st_ino))
        with database(self.destination / 'namespace.db') as db:
            self.assertEqual(17, db.execute('PRAGMA user_version').fetchone()[0])
            self.assertEqual('wal', db.execute('PRAGMA journal_mode').fetchone()[0])
            self.assertTrue(db.execute("SELECT 1 FROM sqlite_schema WHERE name='objects_parent'").fetchone())

    def interrupt_publication(self, *arguments):
        libc = ctypes.CDLL(None, use_errno=True)
        watch = libc.inotify_init1(os.O_CLOEXEC | os.O_NONBLOCK)
        self.assertGreaterEqual(watch, 0)
        self.assertGreaterEqual(libc.inotify_add_watch(watch, os.fsencode(self.root), 0x100), 0)
        process = subprocess.Popen([BINARY, *map(str, arguments)], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            # EVENT_WAIT: staging directory creation; timeout fails the interruption fixture.
            self.assertTrue(select.select([watch], [], [], 10)[0], 'No publication stage created')
            os.kill(process.pid, signal.SIGSTOP)
            os.waitpid(process.pid, os.WUNTRACED)
            self.assertTrue(list(self.root.glob('.md-image-*')))
            process.kill()
            process.communicate(timeout=10)
        finally:
            if process.poll() is None:
                process.kill()
                process.communicate(timeout=10)
            os.close(watch)

    def test_killed_rootfs_import_and_backup_retry_without_partial_publication(self):
        source = self.root / 'rootfs.tar'
        source.write_bytes(tar([('large', 'file', b'a' * (32 * 1024 * 1024))]))
        self.interrupt_publication('rootfs', source, self.destination)
        self.assertFalse(self.destination.exists())
        self.command('rootfs', source, self.destination)
        self.assertFalse(list(self.root.glob('.md-image-*')))
        backup = self.root / 'backup.tar.zst'
        self.interrupt_publication('backup', self.destination, backup)
        self.assertFalse(backup.exists())
        self.command('backup', self.destination, backup)
        self.assertFalse(list(self.root.glob('.md-image-*')))
        self.command('restore', backup, self.root / 'restored')
        self.assertEqual(self.body('large', self.root / 'restored').stat().st_size, 32 * 1024 * 1024)

    def test_recovery_retains_live_stage_and_never_follows_symlinks(self):
        stage = self.root / ('.md-image-' + 'a' * 32)
        stage.mkdir(mode=0o700)
        (stage / 'partial').write_text('unfinished')
        fd = os.open(stage, os.O_RDONLY | os.O_DIRECTORY)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.command('recover-staging', self.root)
            self.assertTrue(stage.exists())
        finally:
            os.close(fd)
        self.command('recover-staging', self.root)
        self.assertFalse(stage.exists())
        outside = self.root / 'unrelated'
        outside.mkdir()
        (outside / 'keep').write_text('keep')
        stage.symlink_to(outside, target_is_directory=True)
        self.command('recover-staging', self.root, success=False)
        self.assertEqual((outside / 'keep').read_text(), 'keep')

    def test_image_helper_dies_with_its_declared_owner(self):
        fifo = self.root / 'blocked-input'
        try:
            os.mkfifo(fifo)
        except PermissionError as error:
            self.skipTest(f'native FIFO fixture unavailable under host policy: {error}')
        driver = '''import os,subprocess,sys
env = dict(os.environ, MAGICDESK_IMAGE_OWNER_PID=str(os.getpid()))
child = subprocess.Popen(sys.argv[1:], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
print(child.pid, flush=True)
sys.stdin.read()
'''
        owner = subprocess.Popen([sys.executable, '-c', driver, BINARY, 'rootfs', str(fifo), str(self.destination)],
                                 stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        child = int(owner.stdout.readline())
        # pidfd_open has the generic Linux syscall number on the ARM64 fixture host.
        pidfd = ctypes.CDLL(None, use_errno=True).syscall(434, child, 0)
        try:
            self.assertGreaterEqual(pidfd, 0)
            self.assertFalse(select.select([pidfd], [], [], 0)[0])
            owner.kill()
            owner.communicate(timeout=10)
            # EVENT_WAIT: pidfd becomes readable on child exit; timeout fails owner-death propagation.
            self.assertTrue(select.select([pidfd], [], [], 10)[0])
            self.assertFalse(self.destination.exists())
        finally:
            if pidfd >= 0: os.close(pidfd)
            if owner.poll() is None: owner.kill()
            owner.communicate(timeout=10)

    def test_applications_read_guest_paths_and_account_home_without_launch(self):
        entries = [('etc', 'dir', ''), ('etc/passwd', 'file', b'root:x:0:0:root:/root:/bin/sh\n'),
                   ('root', 'dir', ''), ('root/.local', 'dir', ''), ('root/.local/share', 'dir', ''),
                   ('root/.local/share/applications', 'dir', ''),
                   ('root/.local/share/applications/a.desktop', 'file', b'[Desktop Entry]\nHidden=true\n'),
                   ('usr', 'dir', ''), ('usr/share', 'dir', ''), ('usr/share/applications', 'dir', ''),
                   ('usr/share/applications/a.desktop', 'file', b'[Desktop Entry]\nName=A\nExec=true\n'),
                   ('usr/share/applications/link.desktop', 'sym', '/usr/share/applications/a.desktop'),
                   ('usr/share/applications/not-regular.desktop', 'fifo', '')]
        self.run_import(self.layout([entries]), preserve=True)
        records = [json.loads(line) for line in self.command('applications', self.destination).stdout.splitlines()]
        self.assertEqual(len(records), 3)
        self.assertEqual(records[0]['path'], '/root/.local/share/applications/a.desktop')
        self.assertIn('Hidden=true', records[0]['text'])

    def test_explicit_blob_cache_keeps_full_digest_validation(self):
        layout = self.layout()
        blobs = self.root / 'cache'
        (layout.path / 'blobs/sha256').rename(blobs)
        self.run_import(layout, args=('--blobs', str(blobs)))
        self.assertEqual(self.body('hello').read_bytes(), b'world')
        self.destination = self.root / 'corrupt'
        digest = layout.layers[0]['digest'].split(':')[1]
        (blobs / digest).write_bytes(b'corrupted')
        self.run_import(layout, success=False, args=('--blobs', str(blobs)))

    def test_inspection_shares_store_admission(self):
        self.run_import(self.layout())
        instance = self.root / 'instance'
        subprocess.run([BINARY, 'create', str(self.destination), str(instance)],
                       check=True, capture_output=True, timeout=60)
        ready, stop = threading.Event(), threading.Event()
        errors = []

        def writer():
            lock = os.open(instance / 'objects', os.O_RDONLY | os.O_DIRECTORY)
            try:
                with database(instance / 'namespace.db', timeout=0) as db:
                    while not stop.is_set():
                        # EVENT_WAIT: the same kernel gate used by namespace writers;
                        # the test completion bound fails a stuck participant.
                        fcntl.flock(lock, fcntl.LOCK_EX)
                        try:
                            db.execute('BEGIN IMMEDIATE')
                            db.execute("UPDATE properties SET value=value WHERE key='image-users'")
                            db.commit()
                        finally:
                            fcntl.flock(lock, fcntl.LOCK_UN)
                        ready.set()
            except Exception as error:
                errors.append(error)
                ready.set()
            finally:
                os.close(lock)

        thread = threading.Thread(target=writer)
        thread.start()
        try:
            # EVENT_WAIT: first committed write, not a settling delay.
            self.assertTrue(ready.wait(10), 'writer did not start')
            for _ in range(50):
                result = subprocess.run([BINARY, 'inspect', str(instance)],
                                        capture_output=True, text=True, timeout=10)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(json.loads(result.stdout)['kind'], 'instance')
                self.assertFalse(errors, errors)
        finally:
            stop.set()
            # EVENT_WAIT: writer completion; failure does not count as cleanup.
            thread.join(10)
            self.assertFalse(thread.is_alive(), 'writer did not stop')
        self.assertFalse(errors, errors)

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
            with database(self.destination / 'namespace.db') as db:
                actual = db.execute('SELECT uid,gid,mode FROM objects WHERE object=?',
                                    (backing.name,)).fetchone()
            self.assertEqual(actual, metadata)
            self.assertEqual(backing.lstat().st_uid, os.getuid())
            self.assertFalse(backing.lstat().st_mode & 0o6000)
        self.assertEqual(self.object('d/a'), self.object('d/alias'))
        instance = self.root / 'instance'
        subprocess.run([BINARY, 'create', str(self.destination), str(instance)], check=True,
                       capture_output=True, timeout=60)
        with database(instance / 'namespace.db') as db:
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
        with database(self.destination / 'namespace.db') as db:
            self.assertEqual(db.execute('SELECT value FROM file_capabilities WHERE object=?',
                                        (backing.name,)).fetchone(), (value,))
        instance = self.root / 'instance'
        subprocess.run([BINARY, 'create', str(self.destination), str(instance)], check=True, capture_output=True)
        with database(instance / 'namespace.db') as db:
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
        with database(self.destination / 'namespace.db') as db:
            self.assertEqual(db.execute('SELECT value FROM file_capabilities').fetchone(), (value,))

    def test_acl_import_snapshot_and_replacement(self):
        acl = 'user::rwx,user:fixture:r-x:1234,group::---,mask::r-x,other::---'
        layout = self.layout([
            [('folder', 'dir', '', 0o750, 0, 0, {},
              {'SCHILY.acl.access': acl, 'SCHILY.acl.default': acl}),
             ('program', 'file', b'old', 0o750, 0, 0, {}, {'SCHILY.acl.access': acl}),
             ('alias', 'hard', 'program')], [('program', 'file', b'new')]])
        self.run_import(layout, preserve=True)
        with database(self.destination / 'namespace.db') as db:
            self.assertEqual(db.execute('SELECT acl_mask FROM objects WHERE object=?',
                                        (self.object('folder').name,)).fetchone(), (3,))
            self.assertEqual(db.execute('SELECT acl_mask FROM objects WHERE object=?',
                                        (self.object('alias').name,)).fetchone(), (1,))
            self.assertEqual(db.execute('SELECT acl_mask FROM objects WHERE object=?',
                                        (self.object('program').name,)).fetchone(), (0,))
            expected = db.execute('SELECT object,type,value FROM inode_acls ORDER BY object,type').fetchall()
        instance = self.root / 'instance'
        subprocess.run([BINARY, 'create', self.destination, instance], check=True, capture_output=True)
        with database(instance / 'namespace.db') as db:
            self.assertEqual(db.execute('SELECT object,type,value FROM inode_acls ORDER BY object,type').fetchall(), expected)

    def test_acl_requires_ownership_policy(self):
        self.run_import(self.layout([[('folder', 'dir', '', 0o750, 0, 0, {},
            {'SCHILY.acl.default': 'user::rwx,group::r-x,other::---'})]]), success=False)

    def test_unknown_attribute(self):
        self.run_import(self.layout([[('bad', 'file', b'x', 0o755, 0, 0,
                                       {'security.unknown': b'value'})]]), success=False)

    def test_trusted_attributes_use_guest_metadata_in_shared_layers(self):
        self.pool_import(self.layout([[('file', 'file', b'body', 0o600, 0, 0,
                                       {'trusted.fixture': b'\x00value\xff', 'trusted.empty': b''})]]))
        with database(self.destination / 'namespace.db') as db:
            self.assertEqual(db.execute('SELECT name,value FROM inode_xattrs ORDER BY name').fetchall(),
                             [('trusted.empty', b''), ('trusted.fixture', b'\x00value\xff')])
        self.assertEqual(self.body('file').read_bytes(), b'body')

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
        with database(self.destination / 'namespace.db') as db:
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

    def test_devices_are_logical_inodes(self):
        self.run_import(self.layout([[('device', 'device', ''), ('alias', 'hard', 'device'),
                                     ('block', 'block', '')]]))
        self.assertEqual(self.object('device'), self.object('alias'))
        self.assertTrue(self.object('device').is_file())
        with database(self.destination / 'namespace.db') as db:
            self.assertEqual(db.execute('SELECT kind,rdev FROM objects WHERE rdev!=0 ORDER BY kind').fetchall(),
                             [(stat.S_IFCHR, os.makedev(1, 3)), (stat.S_IFBLK, os.makedev(8, 0))])
        instance = self.root / 'instance'
        self.command('create', self.destination, instance)
        with database(instance / 'namespace.db') as db:
            self.assertEqual(db.execute('SELECT kind,rdev FROM objects WHERE rdev!=0 ORDER BY kind').fetchall(),
                             [(stat.S_IFCHR, os.makedev(1, 3)), (stat.S_IFBLK, os.makedev(8, 0))])
        archive = self.root / 'backup.tar.zst'
        self.command('backup', self.destination, archive)
        restored = self.root / 'restored'
        self.command('restore', archive, restored)
        with database(restored / 'namespace.db') as db:
            self.assertEqual(db.execute('SELECT kind,rdev FROM objects WHERE rdev!=0 ORDER BY kind').fetchall(),
                             [(stat.S_IFCHR, os.makedev(1, 3)), (stat.S_IFBLK, os.makedev(8, 0))])

    def test_device_entries_preserved_without_host_nodes(self):
        layout = self.layout([[('dev', 'dir', ''), ('dev/null', 'device', ''),
                               ('dev/console', 'device', ''), ('dev/disk/example', 'block', ''),
                               ('dev/shm', 'dir', ''), ('dev/shm/value', 'file', b'shared')]])
        self.run_import(layout)
        for path in ('dev/null', 'dev/console', 'dev/disk/example'):
            self.assertTrue(self.object(path).is_file())
        self.assertEqual(self.object('dev/shm/value').read_bytes(), b'shared')

    def test_host_device_replaces_lower_placeholder(self):
        layout = self.layout([[('dev/null', 'file', b'not a device'),
                               ('dev/disk/old', 'file', b'old')],
                              [('dev/null', 'device', ''), ('dev/disk', 'block', '')]])
        self.run_import(layout)
        self.assertTrue(self.object('dev/null').is_file())
        self.assertTrue(self.object('dev/disk').is_file())
        self.assertIsNone(self.object('dev/disk/old'))

    def test_device_paths_preserve_archive_validation(self):
        for path in ('/dev/null', 'dev/../null'):
            with self.subTest(path=path), tempfile.TemporaryDirectory() as temp:
                layout = Layout(pathlib.Path(temp) / 'layout', [[(path, 'device', '')]])
                self.destination = pathlib.Path(temp) / 'image'
                self.run_import(layout, False)

    def test_device_metadata_preserves_validation(self):
        cases = [
            [('dev/null', 'device', ''), ('dev/./null', 'device', '')],
            [('dev/.wh.null', 'device', '')],
            [('dev/null', 'device', '', 0o644, -1, 0)],
            [('dev/null', 'device', '', 0o644, 0, 0, {'user.hidden': b'value'})],
        ]
        for entries in cases:
            with self.subTest(entries=entries), tempfile.TemporaryDirectory() as temp:
                layout = Layout(pathlib.Path(temp) / 'layout', [entries])
                self.destination = pathlib.Path(temp) / 'image'
                self.run_import(layout, False)

    def test_device_symlink_parent_stays_inside_image(self):
        self.run_import(self.layout([[('dev', 'sym', 'outside'), ('dev/null', 'device', '')]]))
        self.assertEqual(os.readlink(self.object('dev')), 'outside')
        self.assertTrue(self.object('outside/null').is_file())

    def test_whiteout_data_rejected(self):
        self.run_import(self.layout([[('.wh.old', 'file', b'bad')]]), False)

    def test_fifo_image_and_independent_instances(self):
        self.run_import(self.layout([[('pipe', 'fifo', '', 0o640, 123, 456),
                                     ('alias', 'hard', 'pipe')]]), preserve=True)
        original = self.object('pipe')
        # FIFO names persist as metadata; live streams belong to kernel pipes.
        self.assertTrue(stat.S_ISREG(original.stat().st_mode))
        with database(self.destination / 'namespace.db') as db:
            self.assertEqual(db.execute('SELECT kind,mode,uid,gid FROM objects WHERE object=?',
                                       (original.name,)).fetchone(), (stat.S_IFIFO, 0o640, 123, 456))
        self.assertEqual(original, self.object('alias'))
        instances = []
        for name in ('one', 'two'):
            destination = self.root / name
            result = subprocess.run([BINARY, 'create', str(self.destination), str(destination)],
                                    capture_output=True, text=True, timeout=15)
            self.assertEqual(result.returncode, 0, result.stderr)
            instances.append(destination / 'objects' / original.name)
        for fifo in instances:
            self.assertTrue(stat.S_ISREG(fifo.stat().st_mode))
            self.assertEqual(fifo.stat().st_size, 0)
        self.assertNotEqual(instances[0].stat().st_ino, instances[1].stat().st_ino)
        for name in ('one', 'two'):
            with database(self.root / name / 'namespace.db') as db:
                self.assertEqual(db.execute('SELECT count(*) FROM fifo_pins').fetchone()[0], 0)

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

    def command(self, *args, success=True):
        result = subprocess.run([BINARY, *map(str, args)], capture_output=True, text=True, timeout=60)
        diagnostic = f'exit={result.returncode}\n{result.stdout}{result.stderr}'
        self.assertGreaterEqual(result.returncode, 0, diagnostic)
        self.assertEqual(result.returncode == 0, success, diagnostic)
        return result

    def test_resolver_preserves_existing_and_requires_idle_instance(self):
        self.run_import(self.layout([[('etc', 'dir', ''), ('etc/resolv.conf', 'file', b'nameserver 192.0.2.1\n')]]))
        instance = self.root / 'instance'
        self.command('create', self.destination, instance)
        self.command('resolver', instance, 'nameserver 192.0.2.2\n')
        self.assertEqual(self.body('etc/resolv.conf', instance).read_bytes(), b'nameserver 192.0.2.1\n')
        self.command('resolver', instance, 'nameserver 192.0.2.2\n', '--replace')
        self.assertEqual(self.body('etc/resolv.conf', instance).read_bytes(), b'nameserver 192.0.2.2\n')
        fd = os.open(instance, os.O_RDONLY | os.O_DIRECTORY)
        try:
            fcntl.flock(fd, fcntl.LOCK_SH)
            result = self.command('resolver', instance, 'nameserver 192.0.2.4\n', '--replace', success=False)
            self.assertIn('errno=16', result.stderr)
        finally:
            os.close(fd)
        self.assertEqual(self.body('etc/resolv.conf', instance).read_bytes(), b'nameserver 192.0.2.2\n')
        self.assertEqual(self.body('etc/resolv.conf').read_bytes(), b'nameserver 192.0.2.1\n')
        self.command('resolver', self.destination, 'changed', success=False)

    def test_resolver_initializes_missing_and_does_not_follow_symlink(self):
        self.run_import(self.layout([[('etc', 'dir', ''), ('target', 'file', b'untouched'), ('etc/resolv.conf', 'sym', '/target')]]))
        instance = self.root / 'instance'
        self.command('create', self.destination, instance)
        self.command('resolver', instance, 'nameserver 192.0.2.3\n')
        self.assertEqual(self.body('target', instance).read_bytes(), b'untouched')
        self.command('resolver', instance, 'nameserver 192.0.2.3\n', '--replace')
        self.assertEqual(self.body('target', instance).read_bytes(), b'untouched')
        self.assertEqual(self.body('etc/resolv.conf', instance).read_bytes(), b'nameserver 192.0.2.3\n')

    def test_bounded_data_export_uses_guest_names_and_skips_symlinks(self):
        self.run_import(self.layout([[('xkb', 'dir', ''), ('xkb/rules', 'dir', ''),
                                     ('xkb/rules/evdev', 'file', b'keyboard'), ('xkb/alias', 'sym', '/outside')]]))
        target = self.root / 'exported'
        self.command('export-tree', self.destination, '/xkb', target)
        self.assertEqual((target / 'rules/evdev').read_bytes(), b'keyboard')
        self.assertFalse((target / 'alias').exists())
        self.command('export-tree', self.destination, '/xkb', target, success=False)

    def body(self, path, store=None):
        store = store or self.destination
        with database(store / 'namespace.db') as db:
            object_id = ROOT
            for name in path.strip('/').split('/'):
                row = db.execute('SELECT object FROM names WHERE parent=? AND name=?',
                                 (object_id, name.encode())).fetchone()
                if row is None:
                    return None
                object_id = row[0]
            source, backing = db.execute('SELECT source,backing FROM objects WHERE object=?',
                                         (object_id,)).fetchone()
            directory = store / pathlib.Path(db.execute('SELECT path FROM sources WHERE scope=? AND id=?', (ROOT, source)).fetchone()[0]) \
                if source else store / 'objects'
            return directory.resolve() / backing

    def pool_import(self, layout, destination=None):
        pool = self.root / 'layers'
        pool.mkdir(exist_ok=True)
        self.command('import', layout.path, destination or self.destination,
                     '--preserve-ownership', '--layers', pool)
        return pool

    def test_shared_layer_bodies_and_flat_namespaces(self):
        common = [('data', 'dir', ''), ('data/file', 'file', b'lower'), ('data/alias', 'hard', 'data/file')]
        first = self.layout([common])
        second = Layout(self.root / 'layout2', [common, [('extra', 'file', b'new')]])
        pool = self.pool_import(first)
        sibling = self.root / 'sibling'
        self.pool_import(second, sibling)
        self.assertEqual(self.body('data/file'), self.body('data/file', sibling))
        self.assertEqual(self.body('data/file'), self.body('data/alias'))
        self.assertEqual(len(list(pool.iterdir())), 2)
        self.assertFalse(list(pool.rglob('namespace.db')))
        for image in (self.destination, sibling):
            with database(image / 'namespace.db') as db:
                self.assertEqual(db.execute('SELECT count(*) FROM objects WHERE kind=32768 AND source=0').fetchone(), (0,))
        instance = self.root / 'instance'
        self.command('create', self.destination, instance)
        layer_root = self.body('data/file').parent.parent
        check = pathlib.Path(BINARY).with_name('libmagicdesk_guest_test_snapshot.so')
        result = subprocess.run([check, '--imported', instance, layer_root], capture_output=True, text=True, timeout=60)
        self.assertEqual(result.returncode, 0, result.stdout+result.stderr)
        self.assertEqual(self.body('data/file', instance).read_bytes(), b'upper')
        self.assertEqual(self.body('data/file', sibling).read_bytes(), b'lower')
        self.assertEqual(self.body('data/file').read_bytes(), b'lower')

    def test_pool_does_not_merge_equal_files(self):
        self.pool_import(self.layout([[('a', 'file', b'equal'), ('b', 'file', b'equal'), ('c', 'hard', 'a')]]))
        self.assertNotEqual(self.body('a'), self.body('b'))
        self.assertEqual(self.body('a'), self.body('c'))

    def test_pool_large_tree_cleanup_keeps_live_local_backings(self):
        lower = [('old', 'dir', ''), ('old/body', 'file', b'old')]
        upper = [('.wh.old', 'file', b'')]
        for i in range(2048):
            upper.extend([(f'd{i}', 'dir', ''), (f'd{i}/body', 'file', b'body'),
                          (f'd{i}/link', 'sym', 'body')])
        self.pool_import(self.layout([lower, upper]))
        self.assertIsNone(self.body('old'))
        with database(self.destination / 'namespace.db') as db:
            expected = {row[0] for row in db.execute('SELECT backing FROM backings WHERE source=0')}
        actual = {p.name for p in (self.destination / 'objects').iterdir()}
        self.assertEqual(expected, actual)
        self.assertEqual(4097, len(actual))
        self.assertEqual(b'body', self.body('d2047/body').read_bytes())

    def test_pool_repeated_layer_retains_independent_inodes(self):
        common = [('a', 'file', b'lower')]
        layout = self.layout([common, [('old', 'hard', 'a')], common])
        self.pool_import(layout)
        self.assertNotEqual(self.body('a'), self.body('old'))
        self.assertEqual(self.body('a').read_bytes(), b'lower')
        self.assertEqual(self.body('old').read_bytes(), b'lower')

    def test_pool_whiteouts_do_not_mutate_other_images(self):
        common = [('dir', 'dir', ''), ('dir/a', 'file', b'a'), ('dir/b', 'file', b'b')]
        self.pool_import(self.layout([common]))
        other = self.root / 'other'
        layout = Layout(self.root / 'layout2', [common, [('dir/new', 'file', b'new'),
                            ('dir/.wh..wh..opq', 'file', b'')]])
        self.pool_import(layout, other)
        self.assertIsNone(self.body('dir/a', other))
        self.assertEqual(self.body('dir/new', other).read_bytes(), b'new')
        self.assertEqual(self.body('dir/a').read_bytes(), b'a')

    def test_pool_concurrent_publication(self):
        layout = self.layout([[('a', 'file', b'value'*10000)]])
        pool = self.root / 'layers'; pool.mkdir()
        paths = [self.root / ('image'+str(i)) for i in range(4)]
        processes = [subprocess.Popen([BINARY, 'import', str(layout.path), str(path),
                     '--preserve-ownership', '--layers', str(pool)], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                     for path in paths]
        try:
            for process in processes:
                out, err = process.communicate(timeout=60)
                self.assertEqual(process.returncode, 0, out+err)
        finally:
            for process in processes:
                if process.poll() is None:
                    process.kill(); process.wait(timeout=10)
                process.stdout.close(); process.stderr.close()
        self.assertEqual(len(list(pool.iterdir())), 1)
        self.assertEqual(len({self.body('a', path) for path in paths}), 1)

    def test_pool_checks_source_digest_on_reuse(self):
        layout = self.layout()
        pool = self.pool_import(layout)
        blob = layout.path / 'blobs/sha256' / layout.layers[0]['digest'][7:]
        data = blob.read_bytes(); blob.write_bytes(data[:-1]+bytes([data[-1]^1]))
        self.command('import', layout.path, self.root / 'bad', '--preserve-ownership', '--layers', pool, success=False)
        self.assertFalse((self.root / 'bad').exists())

    def test_pool_drops_unreachable_layer_dependencies(self):
        layout = self.layout([[('old', 'dir', ''), ('old/file', 'file', b'lower')],
                              [('.wh.old', 'file', b''), ('new', 'file', b'upper')]])
        self.pool_import(layout)
        self.assertIsNone(self.body('old'))
        info = json.loads(self.command('inspect', self.destination).stdout)
        self.assertEqual(len(info['sources']), 1)
        self.assertIn(layout.config['rootfs']['diff_ids'][1][7:], info['sources'][0]['path'])
        self.assertEqual(len(list((self.destination / 'objects').iterdir())), 1)
        with database(self.destination / 'namespace.db') as db:
            self.assertEqual(db.execute('SELECT count(*) FROM objects').fetchone(), (2,))

    def test_pool_attribute_corruption_is_not_repaired_in_place(self):
        layout = self.layout()
        pool = self.pool_import(layout)
        body = self.body('hello')
        body.write_bytes(b'changed-size')
        self.command('import', layout.path, self.root / 'rejected', '--preserve-ownership', '--layers', pool, success=False)
        self.assertEqual(body.read_bytes(), b'changed-size')

    def test_restore_rejects_invalid_metadata_and_duplicate_header(self):
        header = encoded({'format': 1, 'guestUsers': 1, 'imageConfig': {}})
        for i, entries in enumerate((
                [('magicdesk-backup.json', 'file', header), ('magicdesk-backup.json', 'file', header)],
                [('magicdesk-backup.json', 'file', b'{"format":1}')],
                [('magicdesk-backup.json', 'file', header), ('rootfs/../../escaped', 'file', b'bad')])):
            archive = self.root / ('invalid'+str(i)+'.tar')
            archive.write_bytes(tar(entries))
            self.command('restore', archive, self.destination, success=False)
            self.assertFalse(self.destination.exists())
        self.assertFalse(list(self.root.glob('.md-image-*')))

    def test_backup_is_independent_of_layers(self):
        capability = bytes.fromhex('0100000200040000000000000000000000000000')
        acl = 'user::rwx,user:fixture:r-x:1234,group::---,mask::r-x,other::---'
        layout = self.layout([[('a', 'file', b'content', 0o4750, 123, 456,
                               {'security.capability': capability}, {'SCHILY.acl.access': acl}),
                               ('b', 'hard', 'a'), ('link', 'sym', 'a'), ('pipe', 'fifo', '')]])
        pool = self.pool_import(layout)
        backup = self.root / 'backup.tar.zst'
        with database(self.destination / 'namespace.db') as db:
            # Offline fixture represents metadata after a guest chmod, independently
            # of libarchive's mapping of tar ACL entries into its mode field.
            db.execute('UPDATE objects SET mode=? WHERE kind=32768', (0o4700,))
            expected_acl = db.execute('SELECT value FROM inode_acls').fetchone()[0]
        self.command('backup', self.destination, backup)
        shutil.rmtree(self.destination); shutil.rmtree(pool)
        self.command('restore', backup, self.destination)
        self.assertEqual(self.body('a').read_bytes(), b'content')
        self.assertEqual(self.body('a'), self.body('b'))
        self.assertEqual(os.readlink(self.body('link')), 'a')
        with database(self.destination / 'namespace.db') as db:
            self.assertEqual(db.execute('SELECT count(*) FROM sources').fetchone(), (0,))
            self.assertEqual(db.execute('SELECT value FROM file_capabilities').fetchone(), (capability,))
            self.assertEqual(db.execute('SELECT count(*) FROM inode_acls').fetchone(), (1,))
            self.assertEqual(db.execute('SELECT value FROM inode_acls').fetchone(), (expected_acl,))
            self.assertEqual(db.execute('SELECT count(*) FROM objects WHERE kind=4096').fetchone(), (1,))
            self.assertEqual(db.execute('SELECT uid,gid,mode FROM objects WHERE kind=32768').fetchone(), (123, 456, 0o4700))
        info = json.loads(self.command('inspect', self.destination).stdout)
        self.assertEqual(info['kind'], 'instance')
        self.assertEqual(info['config'], layout.config)

    def test_frozen_portable_backup_v1(self):
        archive = self.root / 'historical.tar'
        archive.write_bytes(frozen_backup_v1())
        self.command('restore', archive, self.destination)
        self.assertEqual(self.body('file').read_bytes(), b'contents')
        self.assertEqual(self.body('file'), self.body('alias'))
        self.assertEqual(self.body('native').read_bytes(), b'native')
        with database(self.destination / 'namespace.db') as db:
            self.assertEqual(db.execute('SELECT count(*) FROM sources').fetchone(), (0,))
            self.assertEqual(db.execute('SELECT uid,gid,mode FROM objects WHERE uid=42').fetchone(),
                             (42, 43, 0o6750))
        info = json.loads(self.command('inspect', self.destination).stdout)
        self.assertEqual(info['guestUsers'], 1)
        self.assertEqual(info['config'], {'config': {'Cmd': ['/bin/sh']}})

    def test_backup_rejects_live_store_and_existing_output(self):
        self.run_import(self.layout())
        backup = self.root / 'backup.tar.zst'
        fd = os.open(self.destination, os.O_RDONLY | os.O_DIRECTORY)
        try:
            fcntl.flock(fd, fcntl.LOCK_SH)
            result = self.command('backup', self.destination, backup, success=False)
            self.assertIn('errno=16', result.stderr)
            self.assertFalse(backup.exists())
        finally:
            os.close(fd)
        self.command('backup', self.destination, backup)
        original = backup.read_bytes()
        self.command('backup', self.destination, backup, success=False)
        self.assertEqual(backup.read_bytes(), original)
        self.assertFalse(list(self.root.glob('*.part-*')))

    def test_removal_requires_exclusive_store_and_layer_lifetimes(self):
        pool = self.pool_import(self.layout())
        layer = next(pool.iterdir())
        for target, command in ((self.destination, 'remove'), (layer, 'remove-layer')):
            fd = os.open(target, os.O_RDONLY | os.O_DIRECTORY)
            try:
                fcntl.flock(fd, fcntl.LOCK_SH)
                result = self.command(command, target, success=False)
                self.assertIn('errno=16', result.stderr)
                self.assertTrue(target.exists())
            finally:
                os.close(fd)
        self.command('remove', self.destination)
        self.command('remove-layer', layer)
        self.assertFalse(self.destination.exists())
        self.assertFalse(layer.exists())
        self.assertFalse(list(self.root.rglob('.md-remove-*')))

    def test_maintenance_is_explicit_and_preserves_referenced_bodies(self):
        self.run_import(self.layout([[('file', 'file', b'keep'), ('alias', 'hard', 'file')]]))
        objects = self.destination / 'objects'
        orphan = objects / ('a' * 32)
        orphan.write_bytes(b'uncommitted')
        directory = objects / ('b' * 32)
        directory.mkdir()
        (directory / 'leaf').write_bytes(b'uncommitted leaf')
        outside = self.root / 'outside'
        outside.write_bytes(b'outside')
        link = objects / ('c' * 32)
        link.symlink_to(outside)
        self.command('prepare', self.destination)
        self.assertTrue(orphan.exists())
        self.command('maintain', self.destination)
        self.assertFalse(orphan.exists())
        self.assertFalse(directory.exists())
        self.assertFalse(link.is_symlink())
        self.assertEqual(outside.read_bytes(), b'outside')
        self.assertEqual(self.body('file').read_bytes(), b'keep')
        self.assertEqual(self.body('file'), self.body('alias'))
        self.command('maintain', self.destination)

    def test_maintenance_rejects_live_owner_and_preserves_shared_sources(self):
        pool = self.pool_import(self.layout())
        instance = self.root / 'instance'
        self.command('create', self.destination, instance)
        orphan = instance / 'objects' / ('a' * 32)
        orphan.write_bytes(b'uncommitted')
        body = self.body('hello', instance)
        self.assertEqual(body, self.body('hello'))
        fd = os.open(instance, os.O_RDONLY | os.O_DIRECTORY)
        try:
            fcntl.flock(fd, fcntl.LOCK_SH)
            result = self.command('maintain', instance, success=False)
            self.assertIn('errno=16', result.stderr)
            self.assertEqual(orphan.read_bytes(), b'uncommitted')
        finally:
            os.close(fd)
        self.command('maintain', instance)
        self.assertFalse(orphan.exists())
        self.assertEqual(body.read_bytes(), b'world')
        self.assertTrue(pool.exists())

    def test_interrupted_removal_can_finish_without_a_valid_database(self):
        self.run_import(self.layout())
        original = self.destination
        tomb = original.with_name('.md-remove-' + original.name)
        original.rename(tomb)
        (tomb / 'namespace.db').unlink()
        self.command('remove', original)
        self.assertFalse(tomb.exists())

    def test_removal_never_admits_database_or_opens_sources(self):
        for state in ('old', 'future', 'corrupt', 'missing', 'missing-source', 'incomplete'):
            with self.subTest(state=state):
                target = self.root / state
                target.mkdir()
                (target / 'objects').mkdir()
                if state in ('old', 'future', 'missing-source'):
                    with database(target / 'namespace.db') as db:
                        db.execute('PRAGMA user_version=' + ('14' if state == 'old' else '999'))
                        db.execute('CREATE TABLE sources(path TEXT)')
                        db.execute('INSERT INTO sources VALUES(?)', (str(self.root / 'gone/objects'),))
                elif state == 'corrupt':
                    (target / 'namespace.db').write_bytes(b'not a database')
                elif state == 'incomplete':
                    (target / 'objects').rmdir()
                fd = os.open(target, os.O_RDONLY | os.O_DIRECTORY)
                try:
                    fcntl.flock(fd, fcntl.LOCK_SH)
                    result = self.command('remove', target, success=False)
                    self.assertIn('errno=16', result.stderr)
                    self.assertTrue(target.exists())
                finally:
                    os.close(fd)
                self.command('remove', target)
                self.assertFalse(target.exists())
                self.assertFalse(target.with_name('.md-remove-' + target.name).exists())

    def test_sources_are_read_without_schema_admission_or_lower_opens(self):
        self.destination.mkdir()
        expected = str(self.root / 'absent/objects')
        with database(self.destination / 'namespace.db') as db:
            db.execute('PRAGMA user_version=999')
            db.execute('CREATE TABLE sources(path TEXT)')
            db.executemany('INSERT INTO sources VALUES(?)', [(expected,), (expected,)])
        before = (self.destination / 'namespace.db').read_bytes()
        self.assertEqual(json.loads(self.command('sources', self.destination).stdout), [{'path': expected}])
        self.assertEqual((self.destination / 'namespace.db').read_bytes(), before)
        fd = os.open(self.destination, os.O_RDONLY | os.O_DIRECTORY)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX)
            self.assertIn('errno=16', self.command('sources', self.destination, success=False).stderr)
        finally:
            os.close(fd)
        (self.destination / 'namespace.db').unlink()
        self.command('sources', self.destination, success=False)
        self.assertFalse((self.destination / 'namespace.db').exists())

    def test_removal_does_not_follow_symlink_targets(self):
        self.destination.mkdir()
        (self.destination / 'keep').write_bytes(b'world')
        alias = self.root / 'alias'
        alias.symlink_to(self.destination, target_is_directory=True)
        self.command('remove', alias, success=False)
        self.assertEqual((self.destination / 'keep').read_bytes(), b'world')
        outside = self.root / 'outside'
        outside.mkdir()
        (outside / 'keep').write_bytes(b'keep')
        (self.destination / 'external').symlink_to(outside, target_is_directory=True)
        self.command('remove', self.destination)
        self.assertEqual((outside / 'keep').read_bytes(), b'keep')

    def test_backup_user_attributes_and_times(self):
        self.run_import(self.layout())
        body = self.body('hello')
        libc = ctypes.CDLL(None, use_errno=True)
        value = b'value\0binary'
        self.assertEqual(libc.setxattr(os.fsencode(body), b'user.fixture', value, len(value), 0), 0)
        os.utime(body, ns=(123456789000000123, 123456789000000456))
        backup = self.root / 'backup.tar.zst'
        self.command('backup', self.destination, backup)
        restored = self.root / 'restored'
        self.command('restore', backup, restored)
        body = self.body('hello', restored)
        data = ctypes.create_string_buffer(64)
        libc.getxattr.restype = ctypes.c_ssize_t
        self.assertEqual(libc.getxattr(os.fsencode(body), b'user.fixture', data, 64), len(value))
        self.assertEqual(data.raw[:len(value)], value)
        self.assertEqual(body.stat().st_mtime_ns, 123456789000000456)
        self.assertEqual(body.stat().st_atime_ns, 123456789000000123)
        self.assertEqual(json.loads(self.command('inspect', restored).stdout)['guestUsers'], 0)

    def test_backup_omits_private_native_metadata(self):
        self.run_import(self.layout(), preserve=True)
        body = self.body('hello')
        libc = ctypes.CDLL(None, use_errno=True)
        private = b'user.shroot.metadata'
        self.assertEqual(libc.setxattr(os.fsencode(body), private, b'private', 7, 0), 0)
        backup = self.root / 'backup.tar.zst'
        self.command('backup', self.destination, backup)
        restored = self.root / 'restored'
        self.command('restore', backup, restored)
        libc.getxattr.restype = ctypes.c_ssize_t
        self.assertEqual(libc.getxattr(os.fsencode(self.body('hello', restored)), private, None, 0), -1)
        self.assertEqual(ctypes.get_errno(), errno.ENODATA)
        with database(self.destination / 'namespace.db') as original, database(restored / 'namespace.db') as copy:
            query = "SELECT mode,uid,gid FROM objects WHERE kind=32768 ORDER BY mode,uid,gid"
            self.assertEqual(original.execute(query).fetchall(), copy.execute(query).fetchall())

    def test_restore_rejects_private_native_metadata(self):
        header = encoded({'format': 1, 'guestUsers': 1, 'imageConfig': {}})
        archive = self.root / 'private.tar'
        archive.write_bytes(tar([
            ('magicdesk-backup.json', 'file', header),
            ('rootfs/file', 'file', b'body', 0o644, 0, 0,
             {'magicdesk.backup.mode': (0o644).to_bytes(2, 'little'), 'user.shroot.metadata': b'forged'}),
        ]))
        result = self.command('restore', archive, self.destination, success=False)
        self.assertIn(os.strerror(errno.EPERM), result.stderr)
        self.assertFalse(self.destination.exists())
        self.assertFalse(list(self.root.glob('.md-image-*')))

    def test_rootfs_archive_preserves_whiteout_names(self):
        source = self.root / 'rootfs.tar.gz'
        source.write_bytes(gzip.compress(tar([('.wh.user-file', 'file', b'ordinary')])) )
        self.command('rootfs', source, self.destination)
        self.assertEqual(self.body('.wh.user-file').read_bytes(), b'ordinary')
        instance = self.root / 'instance'
        self.command('create', self.destination, instance)
        self.assertEqual(json.loads(self.command('inspect', instance).stdout)['config']['config']['Cmd'], ['/bin/sh'])

    def test_overlay_control_attributes_snapshot_backup_and_tombstone(self):
        source = self.root / 'rootfs.tar.gz'
        source.write_bytes(gzip.compress(tar([
            ('upper', 'dir', '', 0o755, 0, 0, {'user.overlay.opaque': b'y'}),
            ('upper/deleted', 'file', b'', 0o600, 0, 0, {'trusted.overlay.whiteout': b''}),
            ('upper/label', 'file', b'data', 0o600, 0, 0, {'trusted.fixture': b'\x00value\xff'}),
        ])))
        self.command('rootfs', source, self.destination)
        instance = self.root / 'instance'
        self.command('create', self.destination, instance)
        for store in (self.destination, instance):
            with database(store / 'namespace.db') as db:
                self.assertEqual(db.execute('SELECT name,value FROM inode_xattrs ORDER BY name').fetchall(),
                                 [('trusted.fixture', b'\x00value\xff'), ('trusted.overlay.whiteout', b''),
                                  ('user.overlay.opaque', b'y')])
        body = self.body('upper', instance)
        self.assertEqual(ctypes.CDLL(None).setxattr(os.fsencode(body), b'user.overlay.opaque', b'y', 1, 0), 0)
        with database(instance / 'namespace.db') as db:
            db.execute("UPDATE inode_xattrs SET value=NULL WHERE name='user.overlay.opaque'")
        archive = self.root / 'backup.tar.zst'
        restored = self.root / 'restored'
        self.command('backup', instance, archive)
        self.command('restore', archive, restored)
        with database(restored / 'namespace.db') as db:
            self.assertEqual(db.execute('SELECT name,value FROM inode_xattrs ORDER BY name').fetchall(),
                             [('trusted.fixture', b'\x00value\xff'), ('trusted.overlay.whiteout', b'')])
        libc = ctypes.CDLL(None, use_errno=True)
        libc.getxattr.restype = ctypes.c_ssize_t
        self.assertEqual(libc.getxattr(os.fsencode(self.body('upper', restored)), b'user.overlay.opaque', None, 0), -1)
        self.assertEqual(ctypes.get_errno(), errno.ENODATA)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('build', type=pathlib.Path)
    args, rest = parser.parse_known_args()
    BINARY = str((args.build / 'libmagicdesk_guest_image.so').resolve())
    unittest.main(argv=[__file__, *rest])
