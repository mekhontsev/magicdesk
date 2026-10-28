#!/usr/bin/env python3
"""Stock Podman/crun in a disposable prepared Linux store under shell UID.

The default exercises bridge networking and native overlay. Alternative storage
and network modes isolate failures; they do not certify the default workflow.
No seccomp/privilege bypass is passed to Podman or its container runtime.
"""
import argparse
import collections
import ipaddress
import json
import os
from pathlib import Path
import resource
import selectors
import shlex
import signal
import subprocess
import sys


def guest(options):
    defaults = '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'
    os.environ['PATH'] = os.pathsep.join(dict.fromkeys(
        path for path in (os.environ.get('PATH', '') + os.pathsep + defaults).split(os.pathsep) if path))
    report = {'options': options, 'commands': [], 'passed': False}
    base = Path(options['directory'])
    base.mkdir(parents=True, exist_ok=True)
    if options.get('captureNft'):
        os.environ['MD_NFT_CAPTURE'] = str(base / 'nft')
        os.environ['PATH'] = str(Path(__file__).parent) + ':' + os.environ['PATH']
    prefix = ['podman', '--log-level', 'info', '--root', options['cache'],
              '--runroot', options['cache'] + '-run', '--storage-driver', options['storage']]
    limits = ['--cgroups', options['cgroups']]
    report['inheritedLimits'] = {}
    for name, kind in (('nofile', resource.RLIMIT_NOFILE), ('nproc', resource.RLIMIT_NPROC)):
        soft, hard = resource.getrlimit(kind)
        report['inheritedLimits'][name] = {'soft': soft, 'hard': hard}
        if options['hostLimits']:
            limits += ['--ulimit', f'{name}={soft}:{hard}']
    if options['unlimitedPids']:
        limits += ['--pids-limit', '-1']
    name = 'md-podman-probe'
    created = False

    def process_snapshot():
        result = []
        for entry in Path('/proc').iterdir():
            if not entry.name.isdigit():
                continue
            try:
                command = (entry / 'cmdline').read_bytes().replace(b'\0', b' ').decode(errors='replace')
                if not any(name in command for name in ('podman', 'conmon', 'crun', 'aardvark-dns', 'fixture.py')):
                    continue
                tasks = []
                for task in (entry / 'task').iterdir():
                    try:
                        tasks.append({'tid': task.name, 'status': (task / 'status').read_text(),
                                      'wchan': (task / 'wchan').read_text()})
                    except OSError:
                        pass
                result.append({'pid': entry.name, 'command': command, 'tasks': tasks})
            except OSError:
                pass
        return result

    def run(argv, timeout=90, required=True):
        print('RUN ' + repr(argv), flush=True)
        if options['trace'] and argv[:len(prefix)] == prefix and argv[len(prefix):len(prefix)+1] in (['rm'], ['exec']):
            argv = ['strace', '-f', '-tt', '-T', '-s', '4096', '-e', 'trace=%network,%signal,%file,read,close,poll,ppoll,epoll_wait,epoll_pwait,pidfd_open,pidfd_send_signal',
                    '-o', str(base / f'trace-{len(report["commands"]):03d}.log'), *argv]
        with subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              text=True) as process:
            try:
                # EVENT_WAIT: exact child exit and output EOF. The deadline fails
                # either wait; retain the live process state before cancellation.
                output, _ = process.communicate(timeout=timeout)
                item = {'argv': argv, 'exitCode': process.returncode, 'output': output}
            except subprocess.TimeoutExpired as error:
                item = {'argv': argv, 'timeout': True, 'pid': process.pid,
                        'childExitCode': process.poll(), 'processes': process_snapshot(),
                        'output': (error.stdout or b'').decode(errors='replace')}
                process.kill()
                process.stdout.close()
                process.wait(timeout=10)
        report['commands'].append(item)
        (base / 'progress.json').write_text(json.dumps(report))
        print(item['output'], end='', flush=True)
        if required:
            assert item.get('exitCode') == 0, {key: value for key, value in item.items() if key != 'processes'}
        return item

    def trace_dns():
        for entry in Path('/proc').iterdir():
            if not entry.name.isdigit():
                continue
            try:
                args = (entry / 'cmdline').read_bytes().split(b'\0')
            except OSError:
                continue
            if not args or Path(os.fsdecode(args[0])).name != 'aardvark-dns':
                continue
            process = subprocess.Popen(['strace', '-f', '-tt', '-T', '-s', '2048', '-e',
                       'trace=%network,close,poll,ppoll,epoll_wait,epoll_pwait',
                       '-o', str(base / 'trace-dns.log'), '-p', entry.name],
                       stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            try:
                with selectors.DefaultSelector() as selector:
                    selector.register(process.stderr, selectors.EVENT_READ)
                    # EVENT_WAIT: tracer attachment diagnostic, not a settling delay.
                    assert selector.select(10), 'DNS tracer did not attach'
                    line = process.stderr.readline()
                    assert b'attached' in line, line
                return process
            except BaseException:
                process.terminate()
                process.communicate(timeout=10)
                raise
        raise AssertionError('DNS service not found')

    def exercise_ports():
        from docker_network_fixture import observe, wait_service
        network = 'md-podman-ports'
        names = ('md-podman-first', 'md-podman-second')
        ports = []
        image = options['portsImage']
        ipv6 = options['ipv6']
        host = '[' + options['ipv6PublishHost'] + ']' if ipv6 else '127.0.0.1'
        family = ['--ipv6'] if ipv6 else []
        def external(**request):
            return observe(options['observer'], ipv6=ipv6, **request)
        if run(prefix + ['image', 'exists', image], required=False).get('exitCode') != 0:
            run(prefix + ['pull', image], timeout=180)
        if run(prefix + ['network', 'exists', network], required=False).get('exitCode') == 0:
            run(prefix + ['network', 'rm', '-f', network])
        run(prefix + ['network', 'create', *family, network])
        tracer = None
        try:
            for name in names:
                port = external(operation='allocate')['port']
                ports.append(port)
                external(operation='handoff', port=port)
                run(prefix + ['run', '-d', '--name', name, '--log-driver', 'k8s-file', '--network', network, *limits,
                              '-p', f'{host}:{port}:8080/tcp', '-p', f'{host}:{port}:8080/udp',
                              '--mount', 'type=bind,src=' + str(Path(__file__).with_name('docker_network_fixture.py'))
                              + ',dst=/fixture.py,readonly', image, 'python3', '/fixture.py', name, *family])
                wait_service(prefix + ['logs', '-f', name])
                if options['trace'] and tracer is None:
                    tracer = trace_dns()
                report.setdefault('portChecks', []).append(external(operation='exchange', port=port, token=name, repeats=20))
            for source, target in (names, tuple(reversed(names))):
                run(prefix + ['exec', source, 'python3', '/fixture.py', '--client', target, '8080', target, '20', *family])
            report['namedPeerExchangePassed'] = True
            outbound = observe(options['observer'], operation='outbound')['url']
            for _ in range(options['execRepeats']):
                for name in names:
                    run(prefix + ['exec', name, 'python3', '/fixture.py', '--http', outbound])
            report['localOutboundPassed'] = True
            if tracer:
                tracer.send_signal(signal.SIGINT)
                # EVENT_WAIT: detach while the multithreaded resolver remains
                # alive; its eventual shutdown cannot hide a lost interrupt.
                try:
                    _, errors = tracer.communicate(timeout=10)
                except subprocess.TimeoutExpired:
                    report['traceDetachTimeout'] = {'pid': tracer.pid, 'processes': process_snapshot()}
                    raise
                assert tracer.returncode in (0, -signal.SIGINT), errors.decode(errors='replace')
                report['traceDetachPassed'] = True
                for source, target in (names, tuple(reversed(names))):
                    run(prefix + ['exec', source, 'python3', '/fixture.py', '--client', target, '8080', target, '20', *family])
            conflict = run(prefix + ['run', '--rm', '--name', 'md-podman-conflict', '--network', network,
                                    *limits, '-p', f'{host}:{ports[0]}:8080', image, '/bin/true'], required=False)
            assert conflict.get('exitCode') != 0 and ('already allocated' in conflict['output']
                    or 'address already in use' in conflict['output'].lower()), conflict
            if options['internet']:
                report['internetResults'] = [run(prefix + ['exec', name, 'python3', '/fixture.py', '--internet'],
                                                   required=False) for name in names]
                report['internetPassed'] = all(item.get('exitCode') == 0 for item in report['internetResults'])
            if options['internetIpv6']:
                report['internetIpv6Results'] = [run(prefix + ['exec', name, 'python3', '/fixture.py', '--internet-ipv6'])
                                                for name in names]
                report['internetIpv6Passed'] = True
        except Exception as error:
            report['portFailure'] = repr(error)
            run(['nft', 'list', 'ruleset'], required=False)
            run(['cat', '/etc/resolv.conf'], required=False)
            raise
        finally:
            try:
                removed = run(prefix + ['rm', '-f', '--ignore', *names, 'md-podman-conflict'], required=False)
                network_removed = run(prefix + ['network', 'rm', network], required=False)
            finally:
                if tracer:
                    try:
                        # EVENT_WAIT: DNS service exit drains its tracer too.
                        tracer.communicate(timeout=10)
                    except subprocess.TimeoutExpired:
                        tracer.kill()
                        tracer.communicate(timeout=10)
                        report['traceShutdownFailure'] = 'Tracer survived network teardown'
            if 'portFailure' not in report:
                assert removed.get('exitCode') == 0 and network_removed.get('exitCode') == 0, 'Network cleanup failed'
                assert 'traceShutdownFailure' not in report, report['traceShutdownFailure']
        for port in ports:
            report['portChecks'].append(external(operation='released', port=port))
        report['portPublicationPassed'] = True

    try:
        run(['mount', '-t', 'sysfs', 'sysfs', '/sys'])
        run(['mount', '-t', 'cgroup2', 'none', '/sys/fs/cgroup'])
        run(prefix + ['info', '--format', 'json'])
        if run(prefix + ['image', 'exists', options['image']], required=False).get('exitCode') != 0:
            run(prefix + ['pull', options['image']], timeout=180)
        created = True
        command = prefix + ['run', '--rm', '--name', name, '--log-driver', 'k8s-file',
                            '--network', options['network'], *limits, options['image'],
                            '/bin/sh', '-ec', 'id; echo PODMAN_CONTAINER_OK']
        result = run(command)
        assert 'PODMAN_CONTAINER_OK' in result['output'].splitlines(), result
        if options['observer']:
            exercise_ports()
        if options['network'] != 'none' and options['internet']:
            result = run(prefix + ['run', '--rm', '--name', name, '--log-driver', 'k8s-file',
                          '--network', options['network'], *limits, options['image'], '/bin/sh', '-ec',
                          'wget -T 15 -qO /tmp/page http://example.com; test -s /tmp/page; echo PODMAN_INTERNET_OK'])
            assert 'PODMAN_INTERNET_OK' in result['output'].splitlines(), result
        if options['observer'] and options['internet']:
            assert report.get('internetPassed'), 'External DNS/HTTP failed; local matrix is recorded separately'
        report['passed'] = True
    except Exception as error:
        report['failure'] = repr(error)
    finally:
        if created:
            cleanup = run(prefix + ['rm', '-f', '--ignore', name], required=False)
            report['passed'] &= cleanup.get('exitCode') == 0
        report['traceErrors'] = []
        for trace in sorted(base.glob('trace-*.log')):
            report['traceErrors'] += [trace.name + ': ' + line for line in trace.read_text(errors='replace').splitlines()
                                     if 'EOPNOTSUPP' in line or 'operation not supported' in line.lower()
                                     or '\\241\\377' in line or '\\352\\377' in line or 'error=-' in line]
            with trace.open(errors='replace') as source:
                report['traceTail'] = list(collections.deque(source, maxlen=120))
        print('PODMAN_REPORT ' + json.dumps(report), flush=True)
        (base / 'progress.json').write_text(json.dumps(report))


def main():
    from test_oci_services import Suite, add_machine_entry_argument
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('store')
    parser.add_argument('--storage', choices=('overlay', 'vfs'), default='overlay')
    parser.add_argument('--network', choices=('bridge', 'host', 'none'), default='bridge')
    parser.add_argument('--cgroups', choices=('enabled', 'disabled'), default='enabled',
                        help='Explicit diagnostic mode; disabled does not certify cgroup support')
    parser.add_argument('--image', default='docker.io/library/alpine:3.23')
    parser.add_argument('--cache', default='', help='Owned disposable Podman image cache')
    parser.add_argument('--trace', action='store_true')
    parser.add_argument('--capture-nft', action='store_true', help='Record unchanged nft input in the fixture directory')
    parser.add_argument('--diagnostics', action='store_true', help='Record native runtime state transitions')
    parser.add_argument('--exec-repeats', type=int, default=1,
                        help='Repeat local outbound exec and complete process/output teardown')
    parser.add_argument('--deadline-seconds', type=int, help='Override the diagnostic runtime failure bound')
    parser.add_argument('--exercise-ports', action='store_true')
    parser.add_argument('--ipv6', action='store_true', help='Exercise a dual-stack bridge, IPv6 publication and named IPv6 peers')
    parser.add_argument('--ipv6-host', default='::1', help='Native Android IPv6 address for the external port observer')
    parser.add_argument('--ipv6-publish-host', type=ipaddress.IPv6Address,
                        help='Guest bind address; defaults to --ipv6-host. Use :: for wildcard publication')
    parser.add_argument('--internet-ipv6', action='store_true', help='Require public IPv6 UDP/TCP DNS and verified HTTPS')
    parser.add_argument('--ports-image', default='docker.io/library/python:3.14-alpine')
    parser.add_argument('--internet', action='store_true', help='Also require external DNS and HTTP')
    parser.add_argument('--host-limits', action='store_true', help='Set nofile/nproc explicitly to inherited shell limits')
    parser.add_argument('--unlimited-pids', action='store_true', help='Explicitly disable the optional pids-controller limit')
    add_machine_entry_argument(parser)
    args = parser.parse_args()
    if args.exercise_ports and args.network != 'bridge':
        parser.error('--exercise-ports requires bridge')
    if args.ipv6 and not args.exercise_ports:
        parser.error('--ipv6 requires --exercise-ports')
    if (args.internet_ipv6 or args.ipv6_host != '::1' or args.ipv6_publish_host is not None) and not args.ipv6:
        parser.error('--internet-ipv6, --ipv6-host and --ipv6-publish-host require --ipv6')
    if not 1 <= args.exec_repeats <= 50:
        parser.error('--exec-repeats must be between 1 and 50')
    suite = Suite(Path('build/podman-results'), args.build, machine_entry=args.machine_entry)
    from docker_network_fixture import Observer
    observer = Observer(args.ipv6_host) if args.exercise_ports else None
    target = '/tmp/md-podman-probe-' + suite.report['id']
    runner = suite.guest(args.store, '--user', '0:0', '--network', 'routed')
    if args.diagnostics:
        runner += ['--diagnostics']
    created = False
    try:
        suite.transport.upload(suite.client, Path(__file__), suite.base + '/probe.py')
        suite.transport.upload(suite.client, Path(__file__).with_name('docker_network_fixture.py'),
                               suite.base + '/docker_network_fixture.py')
        if args.capture_nft:
            suite.transport.upload(suite.client, Path(__file__).with_name('fixtures') / 'nft-capture.py',
                                   suite.base + '/nft')
            suite.command(['chmod', '755', suite.base + '/nft'])
        suite.command(runner + ['--', '/bin/mkdir', target]); created = True
        options = {'directory': '/tmp/md-podman-' + suite.report['id'],
                   # Podman 4 limits runroot (including "-run") to 50 bytes.
                   'cache': args.cache or '/tmp/md-pc-' + suite.report['id'],
                   'storage': args.storage, 'network': args.network, 'image': args.image,
                   'cgroups': args.cgroups,
                   'internet': args.internet, 'portsImage': args.ports_image,
                   'ipv6': args.ipv6, 'ipv6Host': args.ipv6_host, 'internetIpv6': args.internet_ipv6,
                   'ipv6PublishHost': str(args.ipv6_publish_host) if args.ipv6_publish_host is not None else args.ipv6_host,
                   'trace': args.trace, 'hostLimits': args.host_limits, 'unlimitedPids': args.unlimited_pids,
                   'execRepeats': args.exec_repeats,
                   'captureNft': args.capture_nft,
                   'observer': observer.address if observer else ''}
        deadline = args.deadline_seconds or (900 if observer else 300)
        command = runner + ['--bind-ro', suite.base, target, '--deadline-seconds', str(deadline), '--',
                            '/usr/bin/python3', target + '/probe.py', '--guest', json.dumps(options)]
        remote_log = suite.base + '/runtime.log'
        local_log = suite.output.with_suffix('.log')
        try:
            suite.command(['/system/bin/sh', '-c', shlex.join(command) + ' >' +
                           shlex.quote(remote_log) + ' 2>&1'], timeout=deadline+20)
        finally:
            suite.transport.download(suite.client, remote_log, local_log, overwrite=True)
            output = local_log.read_text(errors='replace')
            suite.record_entry(output)
            suite.report['runtimeLog'] = str(local_log)
        reports = [line.removeprefix('PODMAN_REPORT ') for line in output.splitlines() if line.startswith('PODMAN_REPORT ')]
        assert len(reports) == 1, output[-2000:]
        report = json.loads(reports[0]); suite.report['podman'] = report
        assert report['passed'], report.get('failure', 'container cleanup failed')
        suite.report['passed'] = True
    finally:
        if observer:
            observer.close()
            suite.report['externalPortChecks'] = observer.records
        try:
            if created:
                suite.command(runner + ['--', '/bin/rmdir', target])
        finally:
            suite.finish()


if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] == '--guest':
        guest(json.loads(sys.argv[2]))
    else:
        main()
