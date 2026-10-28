#!/usr/bin/env python3
"""Independent guest launches share FIFO streams, not only persisted names."""
import argparse
import hashlib
from pathlib import Path
import shlex
import sys


def transfer(role, root):
    import array
    import ctypes
    import os
    import select
    import signal
    import socket
    import stat
    signal.alarm(30)  # EVENT_WAIT: socket/FIFO peers; expiry fails a deadlock.
    assert ctypes.CDLL(None).prctl(4, 0, 0, 0, 0) == 0
    if role == 'received':
        fds = [int(fd) for fd in sys.argv[4:]]
        poll = select.poll()
        for fd in fds: poll.register(fd, select.POLLIN)
        assert poll.poll(0) == []
        writer = os.open(root + '/transfer', os.O_WRONLY | os.O_NONBLOCK)
        assert os.write(writer, b'hello') == 5
        assert sorted(poll.poll(1000)) == sorted((fd, select.POLLIN) for fd in fds)
        assert os.read(fds[1], 5) == b'hello'
        os.close(writer)
        assert sorted(poll.poll(1000)) == sorted((fd, select.POLLHUP) for fd in fds)
        for fd in fds: os.close(fd)
        os.unlink(root + '/transfer'); os.unlink(root + '/socket')
        print('PASS protected FIFO transfer after sender exit and receiver exec', flush=True)
        return
    channel = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    if role == 'receive':
        channel.bind(root + '/socket')
        channel.listen(2)
        os.mkfifo(root + '/transfer')
        print('TRANSFER ready', flush=True)
        sender, _ = channel.accept()
        barrier, _ = channel.accept()
        assert barrier.recv(1) == b'g'
        data, ancillary, flags, _ = sender.recvmsg(1, socket.CMSG_SPACE(8))
        assert data == b'f' and not flags and len(ancillary) == 1
        level, kind, rights = ancillary[0]
        assert level == socket.SOL_SOCKET and kind == socket.SCM_RIGHTS
        fds = array.array('i'); fds.frombytes(rights)
        assert len(fds) == 2 and all(stat.S_ISFIFO(os.fstat(fd).st_mode) for fd in fds)
        poll = select.poll()
        for fd in fds: poll.register(fd, select.POLLIN)
        assert poll.poll(0) == []
        barrier.close(); sender.close()
        channel.close()
        for fd in fds: os.set_inheritable(fd, True)
        os.execv(sys.executable, [sys.executable, __file__, '--guest', 'received', root]
                 + [str(fd) for fd in fds])
    else:
        channel.connect(root + '/socket')
        if role == 'send':
            fd = os.open(root + '/transfer', os.O_RDONLY | os.O_NONBLOCK)
            assert channel.sendmsg([b'f'], [(socket.SOL_SOCKET, socket.SCM_RIGHTS, array.array('i', [fd, fd]))]) == 1
            os.close(fd)
        else:
            channel.sendall(b'g')
            assert channel.recv(1) == b''
    channel.close()


def main():
    from test_oci_services import Suite, Server
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('--store', required=True)
    parser.add_argument('--go-fixture', type=Path,
                        help='Stock Go binary built from test_fifo_go.go by build_svc_go.py')
    args = parser.parse_args()
    suite = Suite(args.build, args.build)
    root = '/tmp/fifo-' + suite.report['id']

    def run(script):
        return [suite.runner, '--store', args.store, '--user', '0:0', '--', '/bin/sh', '-ec', script]

    try:
        suite.command(run('mkdir -m 700 ' + shlex.quote(root)))
        fixture='libmagicdesk_guest_service_runtime.so'
        suite.transport.upload(suite.client,args.build / fixture,suite.base + '/' + fixture)
        suite.command(['chmod','700',suite.base + '/' + fixture])
        suite.command([suite.runner,'--store',args.store,'--user','0:0',
                       '--bind-ro',suite.base,root,'--deadline-seconds','60','--',root + '/' + fixture,'fifo'])
        suite.command([suite.runner,'--store',args.store,'--user','0:0',
                       '--bind-ro',suite.base,root,'--deadline-seconds','60','--',root + '/' + fixture,'fifo-readiness'])
        for entry in (0, 1):
            suite.command([suite.runner, '--store', args.store, '--user', '0:0',
                           '--bind-ro', suite.base, root, '--env', f'SHROOT_MACHINE_ENTRY={entry}',
                           '--deadline-seconds', '60', '--', root + '/' + fixture, 'fifo-rendezvous'])
        if args.go_fixture:
            suite.report['goFixtureSha256'] = hashlib.sha256(args.go_fixture.read_bytes()).hexdigest()
            suite.transport.upload(suite.client, args.go_fixture, suite.base + '/fifo-go')
            suite.command(['chmod', '700', suite.base + '/fifo-go'])
            for entry in (0, 1):
                for repeat in range(3):
                    suite.command([suite.runner, '--store', args.store, '--user', '0:0',
                                   '--bind-ro', suite.base, root, '--env', f'SHROOT_MACHINE_ENTRY={entry}',
                                   '--deadline-seconds', '90', '--', root + '/fifo-go'], timeout=100)
                    suite.report['cases'].append({'goFifo': True, 'entry': entry,
                                                  'repeat': repeat, 'passed': True})
        suite.transport.upload(suite.client, Path(__file__), suite.base + '/fifo.py')
        suite.command(run('mkdir ' + shlex.quote(root + '/fixture')))
        def transferred(role):
            return [suite.runner, '--store', args.store, '--user', '0:0', '--bind-ro', suite.base,
                    root + '/fixture', '--deadline-seconds', '60', '--', '/usr/bin/python3',
                    root + '/fixture/fifo.py', '--guest', role, root]
        server = Server(suite, transferred('receive'))
        try:
            server.ready('TRANSFER ready')
            suite.command(transferred('send'))
            suite.command(transferred('continue'))
            server.completed()
        finally:
            server.close()
        suite.command(run('rmdir ' + shlex.quote(root + '/fixture')))
        for round in range(3):
            token = 'round-' + str(round)
            server = Server(suite, run('cd ' + shlex.quote(root) + '\n'
                'if [ ! -p request ]; then mkfifo request response; fi\n'
                'printf "FIFO ready\\n"\n'
                'IFS= read -r value <request\n'
                'test "$value" = ' + shlex.quote(token) + '\n'
                'printf "%s\\n" "$value" >response\n'))
            try:
                server.ready('FIFO ready')
                suite.command(run('cd ' + shlex.quote(root) + '\n'
                    'printf "%s\\n" ' + shlex.quote(token) + ' >request\n'
                    'IFS= read -r value <response\n'
                    'test "$value" = ' + shlex.quote(token)))
                server.completed()
                suite.report['cases'].append({'round': round, 'passed': True})
            finally:
                server.close()
        suite.command(run('rm ' + shlex.quote(root) + '/request ' + shlex.quote(root) + '/response; rmdir ' + shlex.quote(root)))
        suite.report['passed'] = True
    finally:
        suite.finish()


if __name__ == '__main__':
    if len(sys.argv)>1 and sys.argv[1]=='--guest':
        transfer(sys.argv[2], sys.argv[3])
    else:
        main()
