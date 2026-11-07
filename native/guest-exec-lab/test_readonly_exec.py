#!/usr/bin/env python3
"""First execution of native leaves on read-only mount and superblock views."""
import argparse
import ctypes
import errno
import os
from pathlib import Path
import shutil
import sys
import tempfile


MS_RDONLY, MS_REMOUNT, MS_BIND = 1, 32, 4096


def mount(source, target, kind=None, flags=0):
    libc = ctypes.CDLL(None, use_errno=True)
    result = libc.mount(source and os.fsencode(source), os.fsencode(target),
                        kind and os.fsencode(kind), ctypes.c_ulong(flags), None)
    if result:
        raise OSError(ctypes.get_errno(), 'mount ' + target)


def child(path, device, inode, repeat):
    info = os.stat('/proc/self/exe')
    assert (info.st_dev, info.st_ino) == (device, inode), (info, device, inode)
    with open('/proc/self/exe', 'rb') as image:
        assert image.read(4) == b'\x7fELF'
        assert os.fstatvfs(image.fileno()).f_flag & os.ST_RDONLY
    if repeat:
        os.execve('/proc/self/exe', ['python3', __file__, '--child', path,
                                    str(device), str(inode), '0'], os.environ)
    for operation in (lambda: os.open(path, os.O_WRONLY),
                      lambda: os.chmod(path, 0o755),
                      lambda: os.unlink(path),
                      lambda: os.rename(path, path + '.moved'),
                      lambda: os.open(path + '.new', os.O_CREAT | os.O_WRONLY, 0o600)):
        try:
            operation()
        except OSError as error:
            assert error.errno == errno.EROFS, error
        else:
            raise AssertionError('read-only guest mutation accepted')
    libc = ctypes.CDLL(None, use_errno=True)
    watcher = libc.inotify_init1(os.O_CLOEXEC | os.O_NONBLOCK)
    assert watcher >= 0
    # A different leaf requires its own publication after the read-only remount.
    watch = libc.inotify_add_watch(watcher, os.fsencode(path + '.watch'), 0x1)
    assert watch >= 0, ctypes.get_errno()
    os.close(watcher)
    print('PASS readonly image: first exec, proc reexec, watch and denied mutations', flush=True)


def guest():
    libc = ctypes.CDLL(None, use_errno=True)
    with tempfile.TemporaryDirectory(prefix='md-readonly-exec-') as root:
        for kind in ('base', 'bind', 'tmpfs-bind', 'tmpfs-super'):
            directory = root + '/' + kind
            os.mkdir(directory)
            pid = os.fork()
            if not pid:
                try:
                    assert not libc.unshare(0x20000), ctypes.get_errno()
                    if kind.startswith('tmpfs'):
                        mount('tmpfs', directory, 'tmpfs')
                    image = directory + '/python'
                    shutil.copyfile('/usr/bin/python3', image)
                    os.chmod(image, 0o755)
                    Path(image + '.watch').write_bytes(b'watch')
                    info = os.stat(image)
                    if kind == 'bind':
                        mount(directory, directory, flags=MS_BIND)
                    target = '/' if kind == 'base' else directory
                    flags = MS_RDONLY | MS_REMOUNT | (0 if kind == 'tmpfs-super' else MS_BIND)
                    mount(None, target, flags=flags)
                    os.execve(image, ['python3', __file__, '--child', image,
                                     str(info.st_dev), str(info.st_ino), '1'],
                              dict(os.environ, PYTHONHOME='/usr'))
                except BaseException:
                    import traceback
                    traceback.print_exc()
                    os._exit(90)
            # EVENT_WAIT: exact child completion; supervised deadline bounds a stalled exec.
            status = os.waitpid(pid, 0)[1]
            assert status == 0, (kind, status)
            print('PASS readonly mount:', kind, flush=True)
        image = root + '/detached'
        Path(image).touch()
        pid = os.fork()
        if not pid:
            try:
                assert not libc.unshare(0x20000), ctypes.get_errno()
                mount('/proc/self/exe', image, flags=MS_BIND)
                mount(None, image, flags=MS_BIND | MS_REMOUNT | MS_RDONLY)
                fd = os.open(image, os.O_PATH | os.O_CLOEXEC)
                assert not libc.umount2(os.fsencode(image), 2), ctypes.get_errno()
                os.unlink(image)
                os.execve(fd, ['python3', __file__, '--detached'], os.environ)
            except BaseException:
                import traceback
                traceback.print_exc()
                os._exit(90)
        status = os.waitpid(pid, 0)[1]
        assert status == 0, ('detached', status)


def detached():
    for path in ('/proc/self/exe', '/proc/%d/exe' % os.getpid()):
        with open(path, 'rb') as image:
            assert image.read(4) == b'\x7fELF'
            assert os.fstatvfs(image.fileno()).f_flag & os.ST_RDONLY
        try:
            os.open(path, os.O_WRONLY)
        except OSError as error:
            assert error.errno in (errno.EROFS, errno.ETXTBSY), error
        else:
            raise AssertionError('writable executable from detached readonly mount')
    if not os.environ.get('MD_READONLY_REEXEC'):
        os.execve('/proc/self/exe', ['python3', __file__, '--detached'],
                  dict(os.environ, MD_READONLY_REEXEC='1'))
    print('PASS detached readonly image: fd exec, self/foreign proc and reexec', flush=True)


def main():
    from test_oci_services import add_machine_entry_argument
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('store')
    add_machine_entry_argument(parser)
    args = parser.parse_args()
    from test_oci_services import Suite
    suite = Suite(args.build, args.build, machine_entry=args.machine_entry)
    target = '/tmp/md-readonly-exec-' + suite.report['id']
    prefix = [suite.runner, '--store', args.store, '--user', '0:0']
    try:
        suite.upload(Path(__file__), suite.base + '/test.py')
        suite.command(prefix + ['--', '/bin/mkdir', target])
        suite.command(suite.guest(args.store, '--user', '0:0', '--bind-ro', suite.base,
                                  target, '--deadline-seconds', '90', '--',
                                  '/usr/bin/python3', target + '/test.py', '--guest'), timeout=110)
        suite.report['passed'] = True
    finally:
        try:
            suite.command(prefix + ['--', '/bin/rmdir', target])
        finally:
            suite.finish()


if __name__ == '__main__':
    if sys.argv[1:] == ['--guest']:
        guest()
    elif sys.argv[1:2] == ['--child']:
        child(sys.argv[2], *map(int, sys.argv[3:]))
    elif sys.argv[1:] == ['--detached']:
        detached()
    else:
        main()
