#!/usr/bin/env python3
"""Exercise a stock read-only FUSE filesystem, including concurrent readers."""
from concurrent.futures import ThreadPoolExecutor
from collections import deque
import errno
import ctypes
import mmap
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import threading
import queue
import select
import time
import traceback

client = next((path for name in ('squashfuse_ll', 'squashfuse', 'snapfuse')
               if (path := shutil.which(name))), None)
assert client, 'Install the distribution squashfuse package'
print('FUSE squashfs client:', client, flush=True)
work = Path(tempfile.mkdtemp(prefix='md-fuse-squashfs-'))
work.chmod(0o755)
source, mount = work / 'source', work / 'mount'
blocked = work / 'noexec'
source.mkdir()
mount.mkdir()
blocked.mkdir()
content = bytes(range(256)) * 1024
(source / 'file').write_bytes(content)
(source / 'link').symlink_to('file')
(source / 'directory').mkdir()
(source / 'directory' / 'small').write_text('nested\n')
shutil.copy2('/usr/bin/true', source / 'program')
shutil.copy2('/usr/bin/id', source / 'identity')
(source / 'identity').chmod(0o4755)
libm = Path(subprocess.check_output(['cc', '-print-file-name=libm.so.6'], text=True).strip()).resolve(strict=True)
shutil.copy2(libm, source / 'library.so')
(source / 'script').write_text('#!/bin/sh\nprintf "fuse-script-ok\\n"\n')
(source / 'script').chmod(0o755)
(source / 'sourced').write_text('if :; then\n' + '# padding to span transport pages\n' * 8192
                              + 'printf "fuse-source-ok\\n"\nfi\n')
(work / 'origin-library.c').write_text('int origin_value(void) { return 42; }\n')
(work / 'origin-program.c').write_text(r'''
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
extern int origin_value(void);
int main(int argc, char **argv) {
    assert(origin_value()==42);
    if (argc==2) { execl("/proc/self/exe",argv[0],"--done",argv[1],(char *)0); return 1; }
    assert(argc==3 && !strcmp(argv[1],"--done"));
    char path[4096]; ssize_t n=readlink("/proc/self/exe",path,sizeof(path)-1);
    assert(n>0); path[n]=0; assert(!strcmp(path,argv[2]));
    puts("PASS FUSE proc-exe reexec preserves image identity and ORIGIN library");
    return 0;
}
''')
subprocess.run(['cc', '-shared', '-fPIC', str(work / 'origin-library.c'),
                '-o', str(source / 'liborigin.so')], check=True)
subprocess.run(['cc', str(work / 'origin-program.c'), '-L' + str(source), '-lorigin',
                '-Wl,-rpath,$ORIGIN', '-o', str(source / 'origin-program')], check=True)
os.link(source / 'origin-program', source / 'origin-alias')
subprocess.run(['mksquashfs', str(source), str(work / 'image'), '-noappend',
                '-processors', '1', '-no-progress'], check=True)
log = open(work / 'daemon.log', 'w+')
daemon = subprocess.Popen([client, '-f', '-d', '-o', 'ro,allow_other', str(work / 'image'), str(mount)],
                          stdout=log, stderr=log)
try:
    deadline = time.monotonic() + 15
    while str(mount) not in Path('/proc/self/mountinfo').read_text():
        assert daemon.poll() is None, 'daemon exited before mounting'
        assert time.monotonic() < deadline, 'FUSE mount not observed'
        # Bounded mount-state observation, never a readiness settling delay.
        time.sleep(0.02)
    assert mount.stat().st_dev != source.stat().st_dev
    assert sorted(p.name for p in mount.iterdir()) == ['directory', 'file', 'identity', 'liborigin.so', 'library.so', 'link', 'origin-alias', 'origin-program', 'program', 'script', 'sourced']
    assert (mount / 'directory' / 'small').read_text() == 'nested\n'
    assert os.readlink(mount / 'link') == 'file'
    print('FUSE exec source', (mount / 'program').stat(), flush=True)
    print('FUSE exec mount', next(line for line in Path('/proc/self/mountinfo').read_text().splitlines()
          if line.split()[4] == str(mount)), flush=True)
    subprocess.run([str(mount / 'program')], check=True)
    clean_env = dict(os.environ)
    clean_env.pop('LD_LIBRARY_PATH', None)
    subprocess.run([str(mount / 'origin-program'), str(mount / 'origin-program')],
                   env=clean_env, check=True)
    assert (mount / 'origin-alias').stat().st_ino == (mount / 'origin-program').stat().st_ino
    for name in ('origin-alias', 'origin-program', 'origin-alias'):
        subprocess.run([str(mount / name), str(mount / name)], env=clean_env, check=True)
    print('PASS FUSE hardlink exec and reexec retain the selected dentry', flush=True)
    # Admission reads provider metadata, not the sealed executable snapshot's
    # carrier. Exercise it with and without a logical set-ID transition.
    subprocess.run(['mount', '-i', '-o', 'remount,bind,ro,suid', str(mount)], check=True)
    subprocess.run([str(mount / 'program')], check=True)
    assert subprocess.check_output([str(mount / 'identity'), '-u'], user=1000, group=1000) == b'0\n'
    subprocess.run(['mount', '-i', '-o', 'remount,bind,ro,nosuid', str(mount)], check=True)
    assert subprocess.check_output([str(mount / 'identity'), '-u'], user=1000, group=1000) == b'1000\n'
    print('PASS FUSE executable source identity, set-ID and nosuid', flush=True)
    assert subprocess.check_output([str(mount / 'script')]) == b'fuse-script-ok\n'
    assert subprocess.check_output(['/bin/bash', '-c', '. "$1"', 'fuse-source',
                                    str(mount / 'sourced')]) == b'fuse-source-ok\n'
    fd = os.open(mount / 'file', os.O_RDONLY)
    try:
        assert os.read(fd, len(content)) == content
        assert os.read(fd, 1) == b''
        assert os.pread(fd, len(content), 0) == content
        assert os.lseek(fd, 0, os.SEEK_CUR) == len(content)
        # Large seek results must never be decoded as transport control bits.
        assert os.lseek(fd, (1 << 32) + 17, os.SEEK_SET) == (1 << 32) + 17
        assert os.read(fd, 1) == b''
        os.lseek(fd, 0, os.SEEK_SET)
        vectors = [bytearray(0), bytearray(3001), bytearray(32768),
                   bytearray(0), bytearray(len(content) - 35769)]
        assert os.readv(fd, vectors) == len(content)
        assert b''.join(vectors) == content
        class Iovec(ctypes.Structure):
            _fields_ = [('base', ctypes.c_void_p), ('length', ctypes.c_size_t)]
        first = ctypes.create_string_buffer(16384)
        bad = (Iovec * 2)(Iovec(ctypes.addressof(first), 16384), Iovec(None, 16384))
        libc_io = ctypes.CDLL(None, use_errno=True)
        libc_io.readv.argtypes = [ctypes.c_int, ctypes.POINTER(Iovec), ctypes.c_int]
        libc_io.readv.restype = ctypes.c_ssize_t
        os.lseek(fd, 0, os.SEEK_SET)
        assert libc_io.readv(fd, bad, 2) == 16384
        assert first.raw == content[:16384]
        assert os.lseek(fd, 0, os.SEEK_CUR) == 16384
    finally:
        os.close(fd)
    library = ctypes.CDLL(str(mount / 'library.so'))
    library.cos.argtypes = [ctypes.c_double]
    library.cos.restype = ctypes.c_double
    assert library.cos(0.0) == 1.0
    signal.signal(signal.SIGUSR1, lambda *_: None)
    # A synthetic provider wait is not an interruptible application mmap.
    # In particular a loader must not fail when a non-SA_RESTART handler runs.
    signal.siginterrupt(signal.SIGUSR1, True)
    fd = os.open(mount / 'file', os.O_RDONLY)
    daemon.send_signal(signal.SIGSTOP)
    stopped, status = os.waitpid(daemon.pid, os.WUNTRACED)
    assert stopped == daemon.pid and os.WIFSTOPPED(status)
    thread = queue.Queue()
    def interrupted_mapping():
        thread.put(threading.get_ident())
        with mmap.mmap(fd, len(content), flags=mmap.MAP_PRIVATE, prot=mmap.PROT_READ) as mapped:
            return mapped[:]
    pool = ThreadPoolExecutor(max_workers=1)
    pending = pool.submit(interrupted_mapping)
    try:
        ident = thread.get(timeout=5)
        for attempt in range(3):
            try:
                # EVENT_WAIT: no response is possible while the daemon is
                # stopped. A signal must not turn preparation into short IO.
                pending.result(timeout=.2)
                raise AssertionError('stopped daemon completed a mapping')
            except TimeoutError:
                pass
            signal.pthread_kill(ident, signal.SIGUSR1)
        daemon.send_signal(signal.SIGCONT)
        assert pending.result(timeout=10) == content
        print('PASS FUSE mapping preparation survives non-SA_RESTART signals', flush=True)
    finally:
        daemon.send_signal(signal.SIGCONT)
        pool.shutdown(wait=True)
        os.close(fd)
    def racing_mapping():
        thread.put(threading.get_ident())
        for attempt in range(64):
            descriptor = os.open(mount / 'file', os.O_RDONLY)
            try:
                with mmap.mmap(descriptor, len(content), flags=mmap.MAP_PRIVATE,
                               prot=mmap.PROT_READ) as mapped:
                    assert mapped[:] == content
            finally:
                os.close(descriptor)
    with ThreadPoolExecutor(max_workers=1) as pool:
        pending = pool.submit(racing_mapping)
        ident = thread.get(timeout=5)
        for attempt in range(200):
            if pending.done():
                break
            signal.pthread_kill(ident, signal.SIGUSR1)
            # EVENT_WAIT: bound signal cadence while racing real completions.
            try:
                pending.result(timeout=.002)
                break
            except TimeoutError:
                pass
        pending.result(timeout=30)
    print('PASS FUSE mapping completion/signal race over 64 independent opens', flush=True)
    def mapping_waiters():
        start = threading.Barrier(5)
        done = threading.Event()
        def waiter():
            descriptor = os.eventfd(0, os.EFD_CLOEXEC)
            count = 0
            try:
                with select.epoll() as epoll:
                    epoll.register(descriptor, select.EPOLLIN)
                    start.wait(timeout=10)
                    while not done.is_set():
                        assert epoll.poll(.001, 1) == []
                        count += 1
            finally:
                os.close(descriptor)
            return count
        with ThreadPoolExecutor(max_workers=4) as pool:
            pending = [pool.submit(waiter) for _ in range(4)]
            try:
                start.wait(timeout=10)
                with open(mount / 'file', 'rb') as stream:
                    for _ in range(512):
                        with mmap.mmap(stream.fileno(), mmap.PAGESIZE, flags=mmap.MAP_SHARED,
                                       prot=mmap.PROT_READ) as mapped:
                            assert mapped[:] == content[:mmap.PAGESIZE]
            finally:
                done.set()
            assert all(future.result(timeout=10) > 0 for future in pending)
        print('PASS FUSE shared mapping and concurrent native epoll admission', flush=True)
    mapping_waiters()
    def confined_mapping_completion():
        class Filter(ctypes.Structure):
            _fields_ = [('code', ctypes.c_ushort), ('jt', ctypes.c_ubyte),
                        ('jf', ctypes.c_ubyte), ('value', ctypes.c_uint)]
        class Program(ctypes.Structure):
            _fields_ = [('length', ctypes.c_ushort), ('filter', ctypes.POINTER(Filter))]
        instructions = (Filter * 4)(Filter(0x20, 0, 0, 0),
            Filter(0x15, 0, 1, 0xffffffff),
            Filter(0x06, 0, 0, 0x50000 | errno.EPERM),
            Filter(0x06, 0, 0, 0x7fff0000))
        program = Program(len(instructions), instructions)
        assert libc_io.prctl(38, 1, 0, 0, 0) == 0
        assert libc_io.syscall(277, 1, 0, ctypes.byref(program)) == 0
        fd = os.open(mount / 'file', os.O_RDONLY)
        for attempt in range(2048):
            with mmap.mmap(fd, len(content), flags=mmap.MAP_PRIVATE,
                           prot=mmap.PROT_READ) as mapped:
                assert mapped[0] == content[0]
        os.close(fd)
        mapping_waiters()
    child = os.fork()
    if not child:
        try:
            confined_mapping_completion()
        except BaseException:
            traceback.print_exc()
            os._exit(1)
        os._exit(0)
    waited, status = os.waitpid(child, 0)
    assert waited == child and status == 0, status
    print('PASS FUSE mapping early completion with seccomp rejecting invalid syscalls', flush=True)
    with open(mount / 'file', 'rb') as stream:
        with mmap.mmap(stream.fileno(), len(content), flags=mmap.MAP_PRIVATE,
                       prot=mmap.PROT_READ | mmap.PROT_WRITE) as mapped:
            assert mapped[:] == content
            mapped[0] = 73
            assert (mount / 'file').read_bytes() == content
        with mmap.mmap(stream.fileno(), mmap.PAGESIZE, flags=mmap.MAP_PRIVATE,
                       prot=mmap.PROT_READ, offset=mmap.PAGESIZE) as mapped:
            assert mapped[:] == content[mmap.PAGESIZE:2*mmap.PAGESIZE]
        with mmap.mmap(stream.fileno(), mmap.PAGESIZE, flags=mmap.MAP_SHARED,
                       prot=mmap.PROT_READ) as mapped:
            assert mapped[:] == content[:mmap.PAGESIZE]
        try:
            mmap.mmap(stream.fileno(), mmap.PAGESIZE, flags=mmap.MAP_SHARED,
                      prot=mmap.PROT_READ | mmap.PROT_WRITE)
        except OSError as error:
            assert error.errno == errno.EACCES, error
        else:
            raise AssertionError('writable shared mapping was accepted')
    subprocess.run(['mount', '--bind', str(mount), str(blocked)], check=True)
    subprocess.run(['mount', '-o', 'remount,bind,ro,noexec', str(blocked)], check=True)
    with open(blocked / 'file', 'rb') as stream:
        try:
            mmap.mmap(stream.fileno(), mmap.PAGESIZE, flags=mmap.MAP_PRIVATE,
                      prot=mmap.PROT_READ | mmap.PROT_EXEC)
        except OSError as error:
            assert error.errno == errno.EPERM, error
        else:
            raise AssertionError('noexec mapping was accepted')
        with mmap.mmap(stream.fileno(), mmap.PAGESIZE, flags=mmap.MAP_PRIVATE,
                       prot=mmap.PROT_READ | mmap.PROT_WRITE) as mapped:
            address = ctypes.addressof(ctypes.c_char.from_buffer(mapped))
            libc = ctypes.CDLL(None, use_errno=True)
            libc.mprotect.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_int]
            assert libc.mprotect(address, mmap.PAGESIZE, mmap.PROT_READ | mmap.PROT_EXEC) == -1
            assert ctypes.get_errno() in (errno.EPERM, errno.EACCES)
    subprocess.run(['umount', str(blocked)], check=True)
    print('FUSE private mappings, dynamic library and noexec checks passed', flush=True)
    def read(index):
        path = mount / ('link' if index % 2 else 'file')
        with open(path, 'rb') as stream:
            assert stream.seek(0, os.SEEK_END) == len(content)
            stream.seek(0)
            assert stream.read() == content
    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(read, range(16)))
    for path in (mount / 'file', mount / 'new'):
        try:
            with open(path, 'wb'):
                pass
        except OSError as error:
            assert error.errno == errno.EROFS, error
        else:
            raise AssertionError('read-only mount allowed writing')
    with open(mount / 'file', 'rb') as retained:
        subprocess.run(['umount', '-l', str(mount)], check=True)
        assert retained.read() == content
        assert not (mount / 'file').exists()
    daemon.wait(timeout=10)
    print('PASS squashfuse: reads, ELF/script execution, private mappings, shared library, noexec and concurrent IO', flush=True)
finally:
    subprocess.run(['umount', '-l', str(blocked)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(['umount', '-l', str(mount)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if daemon.poll() is None:
        daemon.terminate()
        try:
            daemon.wait(timeout=10)
        except subprocess.TimeoutExpired:
            daemon.kill()
            daemon.wait(timeout=10)
    log.flush()
    log.seek(0)
    print(''.join(deque(log, maxlen=100)), flush=True)
    log.close()
    shutil.rmtree(work)
