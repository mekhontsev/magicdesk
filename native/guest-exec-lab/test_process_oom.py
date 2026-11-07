#!/usr/bin/env python3
"""Process OOM policy through the ordinary guest proc mount and credentials."""
import errno
import os
import subprocess
import threading

MODERN = '/proc/self/oom_score_adj'
LEGACY = '/proc/self/oom_adj'


def read(path=MODERN):
    with open(path) as source:
        return int(source.read())


def write(value, path=MODERN):
    text = str(value).encode()
    fd = os.open(path, os.O_WRONLY)
    try:
        assert os.write(fd, text) == min(len(text), 31)
        assert os.lseek(fd, 0, os.SEEK_CUR) == 0
    finally:
        os.close(fd)


def denied(error, action):
    try:
        action()
    except OSError as failure:
        assert failure.errno == error, (failure, error)
    else:
        raise AssertionError('operation unexpectedly succeeded')


def child(action):
    pid = os.fork()
    if not pid:
        try:
            action()
        except BaseException:
            import traceback
            traceback.print_exc()
            os._exit(1)
        os._exit(0)
    assert os.waitpid(pid, 0) == (pid, 0)


def unprivileged():
    os.setgid(1234)
    os.setuid(1234)
    denied(errno.EACCES, lambda: write(-1000))
    write(250)
    write(-900)
    assert read() == -900
    write(250)
    denied(errno.EACCES, lambda: write(0, LEGACY))
    denied(errno.EACCES, lambda: write(0, f'/proc/{os.getppid()}/oom_score_adj'))


def run():
    write(-900)
    assert read() == -900 and read(LEGACY) == -15
    for value in (-1001, 1001, 'bad', ''):
        denied(errno.EINVAL, lambda: write(value))
    denied(errno.ERANGE, lambda: write('99999999999999999999999999999999'))
    denied(errno.EINVAL, lambda: write(16, LEGACY))
    assert read() == -900
    fd = os.open(MODERN, os.O_RDONLY)
    try:
        assert os.read(fd, 2) == b'-9'
        duplicate = os.dup(fd)
        try:
            assert os.read(duplicate, 16) == b'00\n'
        finally:
            os.close(duplicate)
        assert os.pread(fd, 16, 0) == b'-900\n'
        denied(errno.EBADF, lambda: os.write(fd, b'0'))
    finally:
        os.close(fd)
    print('PASS OOM range, legacy conversion, read positions and access modes', flush=True)
    observed = []
    thread = threading.Thread(target=lambda: (write(-700), observed.append(read())))
    thread.start()
    thread.join()
    assert observed == [-700] and read() == -700
    write(-900)
    child(lambda: (write(-600), os.execl('/bin/sh', 'sh', '-c',
                 'test "$(cat /proc/self/oom_score_adj)" = -600')))
    assert read() == -900
    child(unprivileged)
    assert read() == -900
    print('PASS OOM thread sharing, fork isolation, exec and credential enforcement', flush=True)
    write(-17, LEGACY)
    assert read() == -1000 and read(LEGACY) == -17
    write(15, LEGACY)
    assert read() == 1000 and read(LEGACY) == 15
    write(-900)
    fd = os.open(MODERN, os.O_RDWR)
    try:
        assert os.writev(fd, [b'-850', b'bad']) == 4
        assert read() == -850
        assert os.pwrite(fd, b'-900', 42) == 4
        assert read() == -900
        assert os.write(fd, b'0' + b' ' * 80) == 31
        assert read() == 0
    finally:
        os.close(fd)
    print('PASS OOM positional writes, writev and bounded proc input', flush=True)
    subprocess.run(['/usr/bin/unshare', '--mount', '--pid', '--fork', '/bin/sh', '-ec',
                    'mount -t proc proc /proc; echo -999 > /proc/self/oom_score_adj; '
                    'test "$(cat /proc/self/oom_score_adj)" = -999; '
                    'mount -o remount,ro /proc; ! echo 0 > /proc/self/oom_score_adj'], check=True)
    print('PASS OOM private proc mount and read-only policy', flush=True)


if __name__ == '__main__':
    run()
