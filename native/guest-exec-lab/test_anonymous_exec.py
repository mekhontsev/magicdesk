#!/usr/bin/env python3
"""Anonymous ELF exec, current-image custody and fork/thread replacement."""
import argparse
import ctypes
import errno
import fcntl
import mmap
import os
from pathlib import Path
import sys
import threading


def child(device, inode, remaining):
    libc = ctypes.CDLL(None, use_errno=True)
    assert libc.syscall(436, 3, ctypes.c_uint(-1), 0) == 0
    link = os.readlink('/proc/self/exe')
    assert link.startswith('/memfd:md-exec-'), link
    fd = os.open('/proc/self/exe', os.O_RDONLY | os.O_CLOEXEC)
    info = os.fstat(fd)
    assert (info.st_dev, info.st_ino) == (device, inode), (info, device, inode)
    assert os.read(fd, 4) == b'\x7fELF'
    assert fcntl.fcntl(fd, fcntl.F_GETFL) & os.O_ACCMODE == os.O_RDONLY
    seals = fcntl.fcntl(fd, fcntl.F_GET_SEALS)
    assert seals >= 0
    duplicate = os.dup(fd)
    assert os.lseek(duplicate, 0, os.SEEK_CUR) == 4
    assert os.lseek(duplicate, 0, os.SEEK_END) == info.st_size
    assert os.lseek(fd, 0, os.SEEK_SET) == 0
    os.close(duplicate)
    with mmap.mmap(fd, 4096, access=mmap.ACCESS_READ) as mapped:
        assert mapped[:4] == b'\x7fELF'
    with mmap.mmap(fd, 4096, access=mmap.ACCESS_COPY) as mapped:
        mapped[0] = 0
    assert os.pread(fd, 4, 0) == b'\x7fELF'
    try:
        mmap.mmap(fd, 4096, access=mmap.ACCESS_WRITE)
        raise AssertionError('writable shared executable mapping')
    except OSError as error:
        assert error.errno == errno.EACCES, error
    other = os.open('/proc/self/exe', os.O_RDONLY | os.O_CLOEXEC)
    assert os.read(other, 4) == b'\x7fELF', 'proc image must have independent offsets'
    fcntl.fcntl(other, fcntl.F_SETFL, os.O_NONBLOCK)
    assert fcntl.fcntl(other, fcntl.F_GETFL) & os.O_NONBLOCK
    assert not fcntl.fcntl(fd, fcntl.F_GETFL) & os.O_NONBLOCK
    os.close(other)
    assert os.stat('/proc/self/exe').st_ino == inode
    try:
        os.open('/proc/self/exe', os.O_WRONLY)
        raise AssertionError('writable executable')
    except OSError as error:
        assert error.errno == errno.ETXTBSY, error
    if remaining:
        read_end, write_end = os.pipe()
        nested = os.fork()
        if nested == 0:
            os.close(write_end)
            assert os.read(read_end, 1) == b'x'
            os.close(read_end)
            os.execve('/proc/self/exe', arguments(device, inode, remaining - 1), environment())
        os.close(read_end)
        assert os.readlink(f'/proc/{nested}/fd/{read_end}').startswith('pipe:[')
        with open(f'/proc/{nested}/exe', 'rb') as image:
            assert os.fstat(image.fileno()).st_ino == inode
            assert image.read(4) == b'\x7fELF'
        os.write(write_end, b'x')
        os.close(write_end)
        assert os.waitpid(nested, 0)[1] == 0
    os.close(fd)
    print('PASS anonymous image: close_range, inode, independent opens, fork and reexec', flush=True)


def environment():
    return dict(os.environ, PYTHONHOME='/usr')


def arguments(device, inode, remaining):
    return ['python3', __file__, '--child', str(device), str(inode), str(remaining)]


def guest():
    for sealed in (False, True):
        fd = os.memfd_create('md-exec-python', os.MFD_CLOEXEC | os.MFD_ALLOW_SEALING)
        with open('/usr/bin/python3', 'rb') as source:
            while data := source.read(65536):
                assert os.write(fd, data) == len(data)
        assert os.fstat(fd).st_mode & 0o111, 'kernel memfd must permit execution'
        if sealed:
            fcntl.fcntl(fd, fcntl.F_ADD_SEALS,
                        fcntl.F_SEAL_WRITE | fcntl.F_SEAL_GROW | fcntl.F_SEAL_SHRINK | fcntl.F_SEAL_SEAL)
        info = os.fstat(fd)
        pid = os.fork()
        if pid == 0:
            def execute():
                os.execve(fd, arguments(info.st_dev, info.st_ino, 2), environment())
            try:
                if sealed:
                    worker = threading.Thread(target=execute)
                    worker.start()
                    worker.join()
                    os._exit(91)
                execute()
            except BaseException:
                import traceback
                traceback.print_exc()
                os._exit(90)
        # EVENT_WAIT: exact child exit; the outer supervised deadline fails a hang.
        status = os.waitpid(pid, 0)[1]
        assert status == 0, (sealed, status)
        os.close(fd)
    print('PASS anonymous ELF: unsealed, sealed, CLOEXEC and nonleader exec', flush=True)


def main():
    from test_oci_services import Suite, add_machine_entry_argument
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('store')
    add_machine_entry_argument(parser)
    args = parser.parse_args()
    suite = Suite(args.build, args.build, machine_entry=args.machine_entry)
    target = '/tmp/md-anonymous-exec-' + suite.report['id']
    prefix = suite.guest(args.store, '--user', '0:0')
    try:
        suite.upload(Path(__file__), suite.base + '/test.py')
        suite.command(prefix + ['--', '/bin/mkdir', target])
        suite.command(prefix + ['--bind-ro', suite.base, target, '--deadline-seconds', '90', '--',
                                '/usr/bin/python3', target + '/test.py', '--guest'], timeout=110)
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
        child(*map(int, sys.argv[2:]))
    else:
        main()
