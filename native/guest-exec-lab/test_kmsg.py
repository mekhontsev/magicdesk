#!/usr/bin/env python3
"""Guest-only kernel message device: records, cursors and event-driven readers."""
import argparse
from contextlib import ExitStack
import errno
import os
from pathlib import Path
import select
import stat
import sys
import threading


def guest():
    def denied(action, expected):
        try:
            action()
        except OSError as error:
            assert error.errno == expected, error
        else:
            raise AssertionError('unexpected success')

    with ExitStack() as cleanup:
        def opened(flags):
            fd = os.open('/dev/kmsg', flags | os.O_CLOEXEC)
            cleanup.callback(os.close, fd)
            return fd

        first = opened(os.O_RDONLY | os.O_NONBLOCK)
        second = opened(os.O_RDONLY | os.O_NONBLOCK)
        writer = opened(os.O_WRONLY)
        info = os.fstat(first)
        assert stat.S_ISCHR(info.st_mode) and info.st_rdev == os.makedev(1, 11)
        denied(lambda: os.read(first, 8192), errno.EAGAIN)
        epoll = cleanup.enter_context(select.epoll())
        epoll.register(first, select.EPOLLIN)
        assert epoll.poll(0) == []
        assert os.write(writer, b'<6>guest-log\n') == 13
        # EVENT_WAIT: published record readiness; timeout fails the test.
        assert epoll.poll(5) == [(first, select.EPOLLIN)]
        denied(lambda: os.read(first, 1), errno.EINVAL)
        denied(lambda: os.read(first, 8192), errno.EAGAIN)
        record = os.read(second, 8192)
        assert record.startswith(b'14,0,') and record.endswith(b',-;guest-log\n'), record
        assert epoll.poll(0) == []
        alias = os.dup(first)
        cleanup.callback(os.close, alias)
        assert os.write(writer, b'next\n') == 5
        assert os.read(alias, 8192).endswith(b';next\n')
        denied(lambda: os.read(first, 8192), errno.EAGAIN)
        assert os.lseek(first, 0, os.SEEK_SET) == 0
        assert os.read(first, 8192) == record
        assert os.lseek(first, 0, os.SEEK_END) == 0
        denied(lambda: os.lseek(first, 1, os.SEEK_SET), errno.ESPIPE)
        denied(lambda: os.lseek(first, 0, os.SEEK_CUR), errno.EINVAL)
        assert os.write(writer, b'a\x00b\\\n') == 5
        assert os.read(first, 8192).endswith(b';a\\x00b\\x5c\n')
        blocking = opened(os.O_RDONLY)
        os.lseek(blocking, 0, os.SEEK_END)
        result = []
        ready = threading.Event()

        def read():
            ready.set()
            result.append(os.read(blocking, 8192))

        thread = threading.Thread(target=read)
        thread.start()
        # EVENT_WAIT: reader admission and record delivery; timeout is failure.
        assert ready.wait(5)
        os.write(writer, b'wakeup')
        thread.join(5)
        assert not thread.is_alive() and result[0].endswith(b';wakeup\n'), result
        os.lseek(first, 0, os.SEEK_SET)
        for _ in range(300):
            os.write(writer, b'bounded')
        denied(lambda: os.read(first, 8192), errno.EPIPE)
        assert os.read(first, 8192).endswith(b';bounded\n')
        denied(lambda: os.write(writer, b'x' * 1025), errno.EINVAL)
        child = os.fork()
        if not child:
            os.setuid(1234)
            denied(lambda: os.open('/dev/kmsg', os.O_RDONLY), errno.EACCES)
            os._exit(0)
        # EVENT_WAIT: exact child exit, bounded by the supervised deadline.
        assert os.waitpid(child, 0)[1] == 0
    print('PASS kmsg: guest-only records, independent/dup cursors, poll, blocking read, overrun and permissions', flush=True)


def main():
    from test_oci_services import Suite, add_machine_entry_argument
    from kernel_profile import KernelProfile
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('store')
    add_machine_entry_argument(parser)
    KernelProfile.add_argument(parser)
    args = parser.parse_args()
    suite = Suite(args.build, args.build, machine_entry=args.machine_entry, kernel_profile=args.kernel_profile)
    target = '/tmp/md-kmsg-' + suite.report['id']
    prefix = suite.guest(args.store, '--user', '0:0')
    try:
        suite.upload(Path(__file__), suite.base + '/test.py')
        suite.command(prefix + ['--', '/bin/mkdir', target])
        suite.command(prefix + ['--bind-ro', suite.base, target, '--deadline-seconds', '45', '--',
                                '/usr/bin/python3', target + '/test.py', '--guest'], timeout=60)
        suite.report['passed'] = True
    finally:
        try:
            suite.command(prefix + ['--', '/bin/rmdir', target])
            suite.command(['rm', '-rf', suite.base])
        finally:
            suite.finish()


if __name__ == '__main__':
    guest() if sys.argv[1:] == ['--guest'] else main()
