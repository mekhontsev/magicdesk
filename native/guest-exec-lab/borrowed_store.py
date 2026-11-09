#!/usr/bin/env python3
"""Root-assisted, reversible test access to an inactive installed Shroot store.

Only preparation runs as root. Guests use the existing FixtureExecutor. Data
and logical metadata are not copied; bind mounts retain physical inode identity.
"""
import argparse
from contextlib import ExitStack
import ctypes
import errno
import fcntl
import json
import os
from pathlib import Path
import shlex
import signal
import stat
import subprocess
import sys
import tempfile


class Labels:
    # Android's Python does not necessarily expose os.*xattr.
    def __init__(self):
        self.libc = ctypes.CDLL(None, use_errno=True)
        self.libc.lgetxattr.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_void_p, ctypes.c_size_t]
        self.libc.lgetxattr.restype = ctypes.c_ssize_t
        self.libc.lsetxattr.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_void_p, ctypes.c_size_t, ctypes.c_int]
        self.libc.lsetxattr.restype = ctypes.c_int
        self.buffer = ctypes.create_string_buffer(65536)

    def get(self, path, name='security.selinux'):
        buffer = self.buffer
        size = self.libc.lgetxattr(os.fsencode(path), name.encode(), buffer, len(buffer))
        if size < 0:
            code = ctypes.get_errno()
            if code == errno.ENODATA:
                return b''
            raise OSError(code, os.strerror(code), str(path))
        return buffer.raw[:size]

    def set(self, path, value):
        if self.libc.lsetxattr(os.fsencode(path), b'security.selinux', value, len(value), 0):
            code = ctypes.get_errno()
            raise OSError(code, os.strerror(code), str(path))


def identity(path):
    value = os.lstat(path)
    return [value.st_dev, value.st_ino]


def entries(root):
    """Never traverse symlinks or another filesystem."""
    device = os.lstat(root).st_dev
    pending = [Path(root)]
    while pending:
        path = pending.pop()
        info = os.lstat(path)
        if info.st_dev != device:
            raise RuntimeError('Nested filesystem: ' + str(path))
        yield path, info
        if stat.S_ISDIR(info.st_mode):
            with os.scandir(path) as directory:
                pending.extend(Path(item.path) for item in directory)


def access(path, info, labels):
    return [info.st_uid, info.st_gid, labels.get(path).hex()]


def set_access(path, info, owner, labels):
    uid, gid, label = owner
    if (info.st_uid, info.st_gid) != (uid, gid):
        os.chown(path, uid, gid, follow_symlinks=False)
        # chown can clear set-ID bits. Physical mode is part of guest semantics.
        if not stat.S_ISLNK(info.st_mode) and info.st_mode & (stat.S_ISUID | stat.S_ISGID):
            os.chmod(path, stat.S_IMODE(info.st_mode), follow_symlinks=False)
    value = bytes.fromhex(label)
    if labels.get(path) != value:
        labels.set(path, value)


def inventory(roots, labels):
    records, linked = [], {}
    for root in roots:
        default = access(root, os.lstat(root), labels)
        record = dict(path=str(root), identity=identity(root), default=default, entries=0)
        for path, info in entries(root):
            record['entries'] += 1
            owner = access(path, info, labels)
            key = f'{info.st_dev}:{info.st_ino}'
            if owner != default:
                raise RuntimeError('Mixed host ownership/labels require explicit preparation: ' + str(path))
            if stat.S_ISREG(info.st_mode) and info.st_nlink > 1:
                count, expected = linked.get(key, (0, info.st_nlink))
                linked[key] = count + 1, expected
            if labels.get(path, 'security.capability'):
                raise RuntimeError('Host file capabilities require separate preservation: ' + str(path))
        records.append(record)
    if any(count != expected for count, expected in linked.values()):
        raise RuntimeError('A physical hardlink leaves the borrowed dependency closure')
    return records


def closure(library, store, image):
    library, store = library.resolve(strict=True), store.resolve(strict=True)
    pending, found = [store], set()
    while pending:
        root = pending.pop()
        relative = root.relative_to(library)
        if len(relative.parts) != 2 or relative.parts[0] not in ('instances', 'images', 'layers'):
            raise ValueError('Not a library-owned store: ' + str(root))
        if root in found:
            continue
        found.add(root)
        if not (root / 'namespace.db').exists():
            if relative.parts[0] != 'layers' or not (root / 'objects').is_dir():
                raise ValueError('Missing namespace: ' + str(root))
            continue
        sources = json.loads(subprocess.check_output([str(image), 'sources', str(root)], text=True))
        for source in sources:
            path = Path(source['path'])
            if path.is_absolute():
                raise ValueError('Prepare relative sources with the native image tool first: ' + str(root))
            objects = (root / path).resolve(strict=True)
            if objects.name != 'objects':
                raise ValueError('Unexpected source: ' + str(objects))
            pending.append(objects.parent)
    return sorted(found)


def write_journal(path, data):
    fd, name = tempfile.mkstemp(prefix=path.name + '.', dir=path.parent)
    temporary = Path(name)
    try:
        with os.fdopen(fd, 'w') as stream:
            json.dump(data, stream)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)
    fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def lock_stores(stack, records):
    descriptors = []
    for record in records:
        path = record['path']
        fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        stack.callback(os.close, fd)
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if identity(path) != record['identity']:
            raise RuntimeError('Store identity changed: ' + path)
        descriptors.append(fd)
    return descriptors


def mounts():
    def decode(text):
        return text.replace('\\040', ' ').replace('\\011', '\t').replace('\\012', '\n').replace('\\134', '\\')
    return {Path(decode(line.split()[4])) for line in Path('/proc/self/mountinfo').read_text().splitlines()}


def restore(journal, data, labels):
    with ExitStack() as stack:
        lock_stores(stack, data['roots'])
        active = mounts()
        for record in data['roots']:
            root = Path(record['path'])
            target = Path(record['target'])
            if target in active:
                if identity(target) != record['identity']:
                    raise RuntimeError('Unexpected mounted store: ' + str(target))
                subprocess.run(['/system/bin/umount', str(target)], check=True)
            for path, info in entries(root):
                set_access(path, info, record['default'], labels)
        # Only the helper's empty scaffolding is removed, never guest data.
        target = Path(data['target'])
        if target.exists():
            for base, directories, files in os.walk(target, topdown=False):
                if files:
                    raise RuntimeError('Unexpected files in borrowed mount scaffolding: ' + base)
                os.rmdir(base)
        data['restored'] = True
        write_journal(journal, data)


def prepare(args, stack, labels):
    if args.journal.exists():
        raise FileExistsError('Use a new journal or restore the existing one: ' + str(args.journal))
    library = args.library.resolve(strict=True)
    catalog = os.open(library / 'library.lock', os.O_RDWR | os.O_NOFOLLOW)
    stack.callback(os.close, catalog)
    fcntl.lockf(catalog, fcntl.LOCK_EX | fcntl.LOCK_NB)
    roots = closure(library, args.store, args.image)
    descriptors = lock_stores(stack, [dict(path=str(root), identity=identity(root)) for root in roots])
    if any(mount == root or root in mount.parents for mount in mounts() for root in roots):
        raise RuntimeError('An existing mount overlaps a borrowed store')
    target = args.target.resolve()
    if target.exists() or not target.parent.is_dir() or target.is_relative_to(library):
        raise ValueError('Borrow target must be a new directory outside the source library')
    parent = os.stat(target.parent)
    if parent.st_uid != args.uid or args.uid < 10000:
        raise ValueError('Borrow target must belong to the selected application')
    owner = [args.uid, parent.st_gid, labels.get(target.parent).hex()]
    records = inventory(roots, labels)
    for record in records:
        record['target'] = str(target / Path(record['path']).relative_to(library))
    data = dict(library=str(library), target=str(target), roots=records, restored=False,
                store=str(target / args.store.resolve().relative_to(library)), owner=owner)
    write_journal(args.journal, data)
    return data, descriptors


def expose(data, descriptors, labels):
    target, owner = Path(data['target']), data['owner']
    try:
        target.mkdir(mode=0o700)
        set_access(target, os.lstat(target), owner, labels)
        for record in data['roots']:
            mountpoint = Path(record['target'])
            mountpoint.parent.mkdir(mode=0o700, exist_ok=True)
            set_access(mountpoint.parent, os.lstat(mountpoint.parent), owner, labels)
            mountpoint.mkdir(mode=0o700)
            for path, info in entries(record['path']):
                set_access(path, info, owner, labels)
            subprocess.run(['/system/bin/mount', '--bind', record['path'], str(mountpoint)], check=True)
    finally:
        for fd in descriptors:
            fcntl.flock(fd, fcntl.LOCK_UN)


class BorrowedStore:
    """Retain the catalog barrier; expose a raw store to any shared app fixture."""
    def __init__(self, suite, library, store, retain=False):
        if suite.executor.mode != 'app':
            raise ValueError('Borrowing requires an explicit app executor')
        self.journal = suite.output.parent.resolve() / ('borrow-' + suite.report['id'] + '.json')
        self.log = self.journal.with_suffix('.log').open('w')
        parent = suite.executor.home + '/no_backup/borrowed-stores'
        suite.command(['mkdir', '-p', parent], quiet=True)
        argv = [sys.executable, str(Path(__file__).resolve()), 'prepare', '--library', str(library),
                '--store', str(store), '--image', suite.image, '--uid', str(suite.executor.uid),
                '--target', parent + '/' + suite.report['id'], '--journal', str(self.journal)]
        if retain:
            argv.append('--retain')
        self.process = subprocess.Popen(['su', '-mm', '-c', shlex.join(argv)],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.log, text=True)
        try:
            line = self.process.stdout.readline()
            if not line:
                raise RuntimeError('Borrow preparation failed; see ' + str(self.journal.with_suffix('.log')))
            self.data = json.loads(line)
            self.store = self.data['store']
        except BaseException:
            self.close(check=False)
            raise
        suite.report['borrowedStore'] = dict(journal=str(self.journal), **self.data)

    def close(self, *, check=True):
        if self.process.stdin and not self.process.stdin.closed:
            self.process.stdin.close()
        code = self.process.wait()
        self.process.stdout.close()
        self.log.close()
        if code and check:
            raise RuntimeError('Borrow helper failed; retain/recover journal ' + str(self.journal))

    def __enter__(self):
        return self

    def __exit__(self, kind, value, traceback):
        self.close(check=kind is None)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('prepare', 'restore'))
    parser.add_argument('--journal', type=Path, required=True)
    parser.add_argument('--library', type=Path)
    parser.add_argument('--store', type=Path)
    parser.add_argument('--image', type=Path)
    parser.add_argument('--target', type=Path)
    parser.add_argument('--uid', type=int)
    parser.add_argument('--retain', action='store_true',
                        help='Keep prepared access after coordinator EOF; restore explicitly after the test batch')
    args = parser.parse_args()
    if os.getuid() != 0:
        parser.error('Only the host preparation helper requires root')
    if os.readlink('/proc/self/ns/mnt') != os.readlink('/proc/1/ns/mnt'):
        parser.error('Use the global mount namespace (su -mm) for preparation and recovery')
    # The coordinator handles Ctrl+C and closes stdin; finish metadata recovery.
    signal.signal(signal.SIGINT, signal.SIG_IGN)
    labels = Labels()
    with ExitStack() as stack:
        if args.action == 'prepare':
            if any(getattr(args, name) is None for name in ('library', 'store', 'image', 'target', 'uid')):
                parser.error('prepare requires library, store, image, target and uid')
            data, descriptors = prepare(args, stack, labels)
            exposed = False
            try:
                expose(data, descriptors, labels)
                exposed = True
                print(json.dumps(dict(store=data['store'], roots=len(data['roots']),
                                      retained=args.retain,
                                      entries=sum(root['entries'] for root in data['roots']))), flush=True)
                sys.stdin.read()
            finally:
                if not args.retain or not exposed:
                    restore(args.journal, data, labels)
        else:
            data = json.loads(args.journal.read_text())
            if not data['restored']:
                catalog = os.open(Path(data['library']) / 'library.lock', os.O_RDWR | os.O_NOFOLLOW)
                stack.callback(os.close, catalog)
                fcntl.lockf(catalog, fcntl.LOCK_EX | fcntl.LOCK_NB)
                restore(args.journal, data, labels)


if __name__ == '__main__':
    main()
