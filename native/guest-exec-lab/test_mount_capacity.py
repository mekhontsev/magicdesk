#!/usr/bin/env python3
"""Larger live mount trees, namespace copies, retained FDs and slot reuse."""
import argparse
from contextlib import ExitStack
import ctypes
import os
from pathlib import Path
import sys
import tempfile


def guest():
    libc = ctypes.CDLL(None, use_errno=True)
    libc.syscall.restype = ctypes.c_long

    def call(nr, *args):
        values = [ctypes.c_char_p(str(a).encode()) if isinstance(a, (str, Path))
                  else ctypes.c_long(a) for a in args]
        result = libc.syscall(ctypes.c_long(nr), *values)
        if result < 0:
            raise OSError(ctypes.get_errno(), str((nr, args)))
        return result

    with tempfile.TemporaryDirectory(prefix='md-mount-scale-') as directory, ExitStack() as cleanup:
        root = Path(directory)
        source = root / 'source'
        source.mkdir()
        (source / 'marker').write_text('shared')
        targets = [root / str(i) for i in range(192)]
        for target in targets:
            target.mkdir()
            call(40, source, target, 0, 4096, 0)
            cleanup.callback(libc.syscall, ctypes.c_long(39), ctypes.c_char_p(str(target).encode()), ctypes.c_long(2))
            assert (target / 'marker').read_text() == 'shared'
        lines = Path('/proc/self/mountinfo').read_text().splitlines()
        ids = [int(line.split()[0]) for line in lines if line.split()[4] in set(map(str, targets))]
        assert len(ids) == len(targets) and len(set(ids)) == len(targets), ids
        retained = os.open(targets[-1], os.O_RDONLY | os.O_DIRECTORY)
        cleanup.callback(os.close, retained)
        print(f'PASS {len(targets)} simultaneous bind mounts with distinct identities and actual IO', flush=True)

        child = os.fork()
        if child == 0:
            try:
                call(97, 0x20000)
                for target in targets:
                    call(39, target, 2)
                    assert not (target / 'marker').exists()
                fd = os.open('marker', os.O_RDONLY, dir_fd=retained)
                assert os.read(fd, 20) == b'shared'
                os.close(fd)
                for target in targets:
                    call(40, source, target, 0, 4096, 0)
                    assert (target / 'marker').read_text() == 'shared'
                os._exit(0)
            except BaseException:
                import traceback
                traceback.print_exc()
                os._exit(1)
        # EVENT_WAIT: child namespace test exits; supervised deadline fails a hang.
        assert os.waitpid(child, 0)[1] == 0
        for target in targets:
            assert (target / 'marker').read_text() == 'shared'
        print('PASS namespace-copy isolation, detached old-view FD and reusable mount slots', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('store')
    args = parser.parse_args()
    from test_oci_services import Suite
    suite = Suite(args.build, args.build)
    target = '/tmp/md-mount-scale-' + suite.report['id']
    prefix = [suite.runner, '--store', args.store, '--user', '0:0']
    try:
        suite.upload(Path(__file__), suite.base + '/test.py')
        suite.command(prefix + ['--', '/bin/mkdir', target])
        suite.command(prefix + ['--bind-ro', suite.base, target, '--deadline-seconds', '120', '--',
                                '/usr/bin/unshare', '--mount', '/usr/bin/python3', target + '/test.py', '--guest'],
                      timeout=140)
        suite.report['passed'] = True
    finally:
        try:
            suite.command(prefix + ['--', '/bin/rmdir', target])
        finally:
            try:
                suite.command(['rm', '-rf', suite.base])
            finally:
                suite.finish()


if __name__ == '__main__':
    if sys.argv[1:] == ['--guest']:
        guest()
    else:
        main()
