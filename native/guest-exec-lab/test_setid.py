#!/usr/bin/env python3
"""Stored executable privileges without per-program launch admission."""
import argparse
import ctypes
import errno
import json
import os
from pathlib import Path
import shutil
import struct
import sys


def guest(directory):
    libc = ctypes.CDLL(None, use_errno=True)
    source = directory + '/probe'
    paths = {}
    for name, uid, gid, mode in (
            ('plain', 0, 0, 0o755), ('suid', 0, 0, 0o4755),
            ('sgid', 0, 2345, 0o2755), ('both', 0, 2345, 0o6755),
            ('other', 1234, 2345, 0o6755), ('no-group-x', 0, 2345, 0o2745),
            ('execute-only', 0, 0, 0o111), ('suid-execute-only', 0, 1000, 0o4110),
            ('read-only', 0, 0, 0o444), ('suid-read-only', 0, 1000, 0o4440)):
        path = directory + '/' + name
        shutil.copyfile(source, path)
        os.chown(path, uid, gid)
        os.chmod(path, mode)
        paths[name] = path

    def check(name, euid, egid, *, fd=False, nnp=False, path=None, unlink=False, traced=False,
              userns=False, capabilities=None, secure=None, drop=None, error=None, unreadable=False,
              closed=()):
        path = path or paths[name]
        descriptor = os.open(path, (os.O_PATH if fd == 'path' else os.O_RDONLY) | os.O_CLOEXEC) if fd else None
        if unlink:
            os.unlink(path)
        reader, writer = os.pipe()
        pid = os.fork()
        if not pid:
            try:
                os.close(reader)
                report_fd = writer if closed else 1
                if closed:
                    os.set_inheritable(writer, True)
                else:
                    os.dup2(writer, 1)
                    os.close(writer)
                if traced:
                    assert libc.ptrace(0, 0, 0, 0) == 0, ctypes.get_errno()
                if drop is not None:
                    assert libc.prctl(24, drop, 0, 0, 0) == 0
                os.setgroups([])
                os.setgid(1000)
                os.setuid(1000)
                def no_new_privs(expected):
                    assert libc.prctl(39, 0, 0, 0, 0) == expected
                    for entry in ('self', 'thread-self'):
                        values = dict(line.split(':', 1) for line in
                                      Path('/proc/' + entry + '/status').read_text().splitlines())
                        assert int(values['NoNewPrivs']) == expected, (entry, values['NoNewPrivs'], expected)
                no_new_privs(0)
                if unreadable:
                    assert not os.access(path, os.R_OK)
                    assert os.access(path, os.X_OK)
                    try:
                        opened = os.open(path, os.O_RDONLY)
                    except OSError as failure:
                        assert failure.errno == errno.EACCES, failure
                    else:
                        os.close(opened)
                        raise AssertionError('Execute permission must not grant data read access')
                if nnp:
                    assert libc.prctl(38, 1, 0, 0, 0) == 0
                    no_new_privs(1)
                if userns:
                    assert libc.unshare(0x10000000) == 0
                    Path('/proc/self/uid_map').write_text('0 1000 1\n')
                    Path('/proc/self/setgroups').write_text('deny\n')
                    Path('/proc/self/gid_map').write_text('0 1000 1\n')
                try:
                    for number in closed:
                        os.close(number)
                    arguments = [path]
                    if closed:
                        arguments += ['--report-fd', str(report_fd),
                                      str(sum(1 << number for number in closed))]
                    os.execve(descriptor if fd else path, arguments,
                              dict(os.environ, LD_LIBRARY_PATH=directory + '/untrusted'))
                except OSError as failure:
                    if error is None or failure.errno != error:
                        raise
                    assert os.getresuid() == (1000, 1000, 1000)
                    os._exit(0)
            except BaseException:
                import traceback
                traceback.print_exc()
                os._exit(90)
        os.close(writer)
        if traced:
            # EVENT_WAIT: nested tracer stops are continued until the exact
            # child exits; the outer runtime deadline cancels a stuck tracer.
            while True:
                _, status = os.waitpid(pid, 0)
                if not os.WIFSTOPPED(status):
                    break
                assert libc.ptrace(7, pid, 0, 0) == 0
        with os.fdopen(reader) as stream:
            output = stream.read()
        # EVENT_WAIT: exact exec child exits; the supervised deadline fails a hang.
        if not traced:
            status = os.waitpid(pid, 0)[1]
        if descriptor is not None:
            os.close(descriptor)
        assert status == 0, (name, closed, status, output)
        if error is not None:
            assert not output, (name, 'exec unexpectedly succeeded', output)
            print('PASS executable privileges rejected', name, error, flush=True)
            return
        result = json.loads(output)
        observed_caps = result.pop('caps')
        if capabilities is not None:
            assert observed_caps == capabilities, (name, observed_caps, capabilities)
        real = 0 if userns else 1000
        if secure is None:
            secure = int(euid != real or egid != real)
        expected = dict(uid=[real, euid, euid, euid], gid=[real, egid, egid, egid],
                        aux=[real, euid, real, egid], secure=secure, env=1-secure,
                        library=7 if secure else 99)
        assert result == expected, (name, fd, nnp, result, expected)
        print('PASS set-ID', name, 'fd' if fd else 'path', 'NNP' if nnp else '',
              'closed=' + str(closed) if closed else '', flush=True)

    for name, uid, gid in [('plain', 1000, 1000), ('suid', 0, 1000),
                         ('sgid', 1000, 2345), ('both', 0, 2345),
                         ('other', 1234, 2345), ('no-group-x', 1000, 1000)]:
        check(name, uid, gid)
        check(name, uid, gid, fd=True)
    for name, uid in [('execute-only', 1000), ('suid-execute-only', 0)]:
        check(name, uid, 1000, unreadable=True)
        check(name, uid, 1000, fd=True, unreadable=True)
        check(name, uid, 1000, fd='path', unreadable=True)
    check('suid-execute-only', 1000, 1000, nnp=True, unreadable=True)
    for name in ('read-only', 'suid-read-only'):
        for descriptor in (False, True, 'path'):
            check(name, 1000, 1000, fd=descriptor, error=errno.EACCES)
    check('both', 1000, 1000, nnp=True)
    check('both', 1000, 1000, nnp=True, fd=True)
    for closed in ((0,), (1,), (2,), (0, 1, 2)):
        check('both', 0, 2345, closed=closed)
        check('both', 0, 2345, fd=True, closed=closed)
    check('both', 1000, 1000, traced=True)
    check('both', 0, 0, userns=True)
    for name, effective in [('cap-p', False), ('cap-ep', True)]:
        path = directory + '/' + name
        shutil.copyfile(source, path)
        os.chmod(path, 0o755)
        os.setxattr(path, 'security.capability', struct.pack('<IIIII', 0x02000000 | effective, 2, 0, 0, 0))
        paths[name] = path
        for descriptor in (False, True):
            check(name, 1000, 1000, fd=descriptor, capabilities=[2, 2 if effective else 0], secure=1)
            check(name, 1000, 1000, fd=descriptor, nnp=True, capabilities=[0, 0], secure=int(effective))
        check(name, 1000, 1000, traced=True, capabilities=[0, 0], secure=0)
        check(name, 1000, 1000, drop=1, error=errno.EPERM if effective else None,
              capabilities=[0, 0], secure=0)
    scoped = directory + '/scoped'
    shutil.copyfile(source, scoped)
    os.chmod(scoped, 0o755)
    os.setxattr(scoped, 'security.capability', struct.pack('<IIIIII', 0x03000001, 2, 0, 0, 0, 2000))
    check('scoped', 1000, 1000, path=scoped, capabilities=[0, 0], secure=0)
    os.chown(paths['cap-p'], 1000, 1000)
    check('cap-p', 1000, 1000, capabilities=[0, 0], secure=0)
    os.chown(paths['cap-p'], 0, 0)
    script = directory + '/script'
    Path(script).write_text('#!' + paths['plain'] + '\n')
    os.chmod(script, 0o6755)
    check('script', 1000, 1000, path=script)
    alias = directory + '/alias'
    os.mkdir(alias)
    assert libc.mount(os.fsencode(directory), os.fsencode(alias), None, 4096, None) == 0
    try:
        assert libc.mount(None, os.fsencode(alias), None, 4096 | 32 | 2, None) == 0
        check('both', 1000, 1000, path=alias + '/both')
        check('both', 1000, 1000, path=alias + '/both', fd=True)
        check('cap-ep', 1000, 1000, path=alias + '/cap-ep', capabilities=[0, 0], secure=0)
        check('cap-ep', 1000, 1000, path=alias + '/cap-ep', fd=True, capabilities=[0, 0], secure=0)
        check('execute-only', 1000, 1000, path=alias + '/execute-only', unreadable=True)
        check('suid-execute-only', 1000, 1000, path=alias + '/suid-execute-only', unreadable=True)
        check('suid-execute-only', 1000, 1000, path=alias + '/suid-execute-only', fd='path', unreadable=True)
        check('both', 0, 2345)
    finally:
        assert libc.umount2(os.fsencode(alias), 0) == 0
        os.rmdir(alias)
    check('other', 1234, 2345, fd=True, unlink=True)
    check('cap-ep', 1000, 1000, fd=True, unlink=True, capabilities=[2, 2], secure=1)
    broken = directory + '/invalid'
    Path(broken).write_text('not an executable')
    os.chmod(broken, 0o4755)
    child = os.fork()
    if not child:
        os.setgid(1000)
        os.setuid(1000)
        try:
            os.execve(broken, [broken], os.environ)
        except OSError as error:
            os._exit(0 if error.errno == errno.ENOEXEC and os.getresuid() == (1000, 1000, 1000) else 91)
        os._exit(92)
    assert os.waitpid(child, 0)[1] == 0
    print('PASS stored set-ID: IDs, auxv, secure environment, scripts, NNP, nosuid and unlinked exec', flush=True)


def main():
    from test_oci_services import Suite, add_machine_entry_argument
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('store')
    add_machine_entry_argument(parser)
    args = parser.parse_args()
    suite = Suite(args.build, args.build, machine_entry=args.machine_entry)
    target = '/tmp/md-setid-' + suite.report['id']
    prefix = suite.guest(args.store, '--user', '0:0')
    try:
        suite.transport.upload(suite.client, Path(__file__), suite.base + '/test.py')
        suite.transport.upload(suite.client, Path(__file__).parent / 'fixtures/setid-probe.c', suite.base + '/probe.c')
        suite.transport.upload(suite.client, Path(__file__).parent / 'fixtures/setid-library.c', suite.base + '/library.c')
        suite.command(prefix + ['--', '/bin/mkdir', '-m', '755', target])
        suite.command(prefix + ['--', '/bin/mkdir', '-m', '755', target + '/trusted', target + '/untrusted'])
        for name, value in [('trusted', 7), ('untrusted', 99)]:
            suite.command(prefix + ['--bind-ro', suite.base, '/mnt', '--', '/usr/bin/cc',
                '-shared', '-fPIC', '-O2', '-DSETID_LIBRARY_VALUE=' + str(value), '/mnt/library.c',
                '-o', target + '/' + name + '/libsetid-test.so'])
        suite.command(prefix + ['--bind-ro', suite.base, '/mnt', '--', '/usr/bin/cc',
                                '-O2', '-Wall', '-Wextra', '-Werror', '/mnt/probe.c',
                                '-L' + target + '/trusted', '-lsetid-test',
                                '-Wl,-rpath,' + target + '/trusted', '-o', target + '/probe'])
        suite.command(prefix + ['--bind-ro', suite.base, '/mnt', '--deadline-seconds', '90', '--',
                                '/usr/bin/python3', '/mnt/test.py', '--guest', target], timeout=110)
        user = suite.guest(args.store, '--user', '1000:1000')
        output = suite.command(user + ['--', target + '/both'])
        result, = [json.loads(line) for line in output.splitlines() if line.startswith('{"uid":')]
        assert result['uid'] == [1000, 0, 0, 0] and result['gid'] == [1000, 2345, 2345, 2345], result
        assert result['secure'] == 1
        print('PASS initial stored set-ID entry', flush=True)
        suite.report['passed'] = True
    finally:
        try:
            suite.command(prefix + ['--', '/bin/rm', '-rf', target])
        finally:
            suite.finish()


if __name__ == '__main__':
    if sys.argv[1:2] == ['--guest']:
        guest(sys.argv[2])
    else:
        main()
