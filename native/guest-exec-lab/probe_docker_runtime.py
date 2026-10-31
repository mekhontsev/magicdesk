#!/usr/bin/env python3
"""Probe stock Docker Engine in a prepared disposable Debian Shroot store.

Diagnostic profiles record missing capabilities rather than assert compatibility.
The routed/iptables/nftables --exercise-ports modes require TCP/UDP publication, peer
exchange and cleanup. --internet adds external availability checks separately.
Install Docker Engine in the store before running this probe (29+ for nftables).
"""
import argparse
import collections
import ipaddress
import json
import os
from pathlib import Path
import queue
import shlex
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import traceback


ROOTLESS_PROFILES = ('rootless', 'rootless-no-firewall', 'rootless-no-network')
NETWORK_PROFILES = ('routed', 'iptables', 'nftables')
PROFILES = ('default', 'no-network', *NETWORK_PROFILES, *ROOTLESS_PROFILES)


def wait_daemon_api(endpoint, report, timeout=45):
    deadline = time.monotonic() + timeout
    attempts = report.setdefault('startupAttempts', [])
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise queue.Empty
        try:
            result = subprocess.run(['docker', '--host', endpoint, 'version', '--format', '{{json .Server}}'],
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=min(2, remaining))
            attempts.append(dict(exitCode=result.returncode, output=result.stdout[-2000:]))
            if result.returncode == 0:
                return 'ready'
        except subprocess.TimeoutExpired:
            attempts.append(dict(timeout=True))
        # Bounded API polling: service-manager process readiness does not mean
        # the Docker socket serves requests. Expiry is a startup failure.
        threading.Event().wait(min(0.1, max(0, deadline - time.monotonic())))


def guest(profile, image, trace=False, cgroup='none', cgroupns='private', trace_runtime=False,
          no_new_keyring=False, trace_filesystem=False, create_count=0, trace_network=False, observer='',
          storage_driver='vfs', data_root='', userland_proxy=True, internet=False, endpoint='', ipv6=False,
          internet_ipv6=False, ipv6_host='::1', rootless_user=''):
    if rootless_user and os.getuid() == 0:
        import pwd
        assert pwd.getpwnam(rootless_user).pw_uid != 0, 'Select an ordinary guest user'
        subprocess.run(['mount', '-t', 'sysfs', 'sysfs', '/sys'], check=True)
        if cgroup != 'none':
            assert cgroup == 'v2', 'Rootless Docker requires cgroup v2'
            subprocess.run(['mount', '-t', 'cgroup2', 'none', '/sys/fs/cgroup'], check=True)
        command = shlex.join([sys.executable, *sys.argv])
        os.execvp('su', ['su', '-l', '-s', '/bin/sh', '-c', command, rootless_user])
    if rootless_user:
        assert os.getuid() != 0, 'Rootless Docker must start as an ordinary guest user'
    defaults = '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'
    os.environ['PATH'] = os.pathsep.join(dict.fromkeys(
        path for path in (os.environ.get('PATH', '') + os.pathsep + defaults).split(os.pathsep) if path))
    preparation = []
    def mount(*args):
        command = ['mount', *args]
        if trace:
            command = ['strace', '-f', '-s', '200', '-e',
                       'trace=mount,fsopen,fsconfig,fsmount,move_mount,mount_setattr', *command]
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        preparation.append({'command': command,
                            'exitCode': result.returncode, 'output': result.stdout})
        if result.returncode:
            raise RuntimeError(preparation[-1])
    if cgroup != 'none' and not rootless_user:
        root = Path('/sys/fs/cgroup')
        root.mkdir(parents=True, exist_ok=True)
        if cgroup == 'v2':
            mount('-t', 'cgroup2', 'none', str(root))
        else:
            mount('-t', 'tmpfs', 'none', str(root))
            (root / 'devices').mkdir()
            mount('-t', 'cgroup', '-o', 'devices', 'none', str(root / 'devices'))
    base = Path(tempfile.mkdtemp(prefix='md-docker-'))
    if rootless_user:
        runtime = base / 'runtime'
        runtime.mkdir(mode=0o700)
        os.environ['XDG_RUNTIME_DIR'] = str(runtime)
    external = bool(endpoint)
    if external:
        assert cgroup == 'none' and not any((trace, trace_runtime, trace_filesystem, trace_network,
                                            no_new_keyring, data_root))
    # Probe profiles must not collide with the installer's system daemon config.
    config = base / 'daemon.json'
    if not external:
        config.write_text('{}\n')
        endpoint = 'unix://' + str(base / 'docker.sock')
    argv = ['dockerd', '--config-file', str(config), '--debug', '--host', endpoint,
            '--pidfile', str(base / 'docker.pid'), '--data-root', data_root or str(base / 'data'),
            '--exec-root', str(base / 'run')]
    if profile in ROOTLESS_PROFILES:
        argv += ['--rootless', '--group', '0']
    if rootless_user:
        script = (shutil.which('dockerd-rootless.sh') or shutil.which('dockerd-rootless')
                  or '/usr/share/docker.io/contrib/dockerd-rootless.sh')
        assert Path(script).is_file(), 'Install the distribution rootless Docker scripts'
        argv[0] = script
        argv += ['--storage-driver=' + storage_driver]
    if profile in ('no-network', 'rootless-no-firewall', 'rootless-no-network', 'routed'):
        argv += ['--iptables=false', '--ip6tables=false', '--ip-forward=false',
                 '--ip-masq=false']
    if profile in ('no-network', 'rootless-no-network'):
        argv += ['--bridge=none', '--storage-driver=' + storage_driver]
    if profile in NETWORK_PROFILES:
        argv += ['--storage-driver=' + storage_driver,
                 '--userland-proxy=' + str(userland_proxy).lower()]
    if profile == 'nftables':
        argv += ['--firewall-backend=nftables']
    elif profile == 'iptables':
        argv += ['--iptables=true']
    if trace_runtime or no_new_keyring:
        wrapper = base / 'runc-trace'
        wrapper.write_text('#!/usr/bin/python3\nimport os, sys\n'
                           'args = sys.argv[1:]\n'
                           + ('for i, arg in enumerate(args):\n'
                              '    if arg in ("create", "run"):\n'
                              '        args.insert(i + 1, "--no-new-keyring")\n'
                              '        break\n' if no_new_keyring else '')
                           + 'command = [' + repr(shutil.which('runc')) + ', *args]\n'
                           + ('command = ["/usr/bin/strace", "-D", "-f", "-s", "256", "-e", '
                              '"trace=%process,%file,%signal,%network,prctl,capget,capset,read,write,keyctl", "-o", '
                              + repr(str(base / 'runc-')) + ' + str(os.getpid()) + ".log", *command]\n'
                              if trace_runtime else '')
                           + 'os.execv(command[0], command)\n')
        wrapper.chmod(0o700)
        argv += ['--add-runtime=mdtrace=' + str(wrapper), '--default-runtime=mdtrace']
    if trace_network:
        argv = ['strace', '-D', '-f', '-s', '512', '-e',
                'trace=%network,ioctl', '-o', str(base / 'network.log'), *argv]
    elif trace_filesystem:
        argv = ['strace', '-D', '-f', '-s', '256', '-e',
                'trace=%file,getdents64,lseek', '-o', str(base / 'filesystem.log'), *argv]
    elif trace:
        argv = ['strace', '-f', '-yy', '-s', '256', '-e', 'trace=fchown,fchownat', *argv]
    report = {'profile': profile, 'cgroup': cgroup, 'cgroupns': cgroupns, 'preparation': preparation,
              'guestUid': os.getuid(), 'rootlessUser': rootless_user,
              'newKeyringDisabled': no_new_keyring,
              'command': [] if external else argv, 'externalDaemon': external, 'endpoint': endpoint,
              'directory': str(base),
              'apiReady': False, 'commands': [], 'portPublicationTested': False}
    records = queue.Queue()
    first, tail, important = [], collections.deque(maxlen=30), collections.deque(maxlen=60)
    log_count = 0
    process = None if external else subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                                     text=True, errors='replace')

    def drain():
        nonlocal log_count
        try:
            with (base / 'daemon.log').open('w', buffering=1) as log:
                for line in process.stdout:
                    log.write(line)
                    log_count += 1
                    if len(first) < 220:
                        first.append(line)
                    else:
                        tail.append(line)
                    if ('level=error' in line or 'failed to ' in line or '= -1 ' in line) and 'Error accepting connection' not in line:
                        important.append(line)
                    if 'API listen on' in line:
                        records.put('ready')
        finally:
            records.put('eof')

    reader = None
    if process:
        reader = threading.Thread(target=drain)
        reader.start()

    def client(*args):
        command = ['docker', '--host', endpoint, *args]
        try:
            # EVENT_WAIT: client completion; timeout fails this command.
            # VFS materializes the complete image for every container. Its
            # creation bound must cover that copy, independently of network IO.
            timeout = 360 if args[0] in ('run', 'create') else 300 if args[0]=='pull' else 90
            result = subprocess.run(command, stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, text=True, timeout=timeout)
            item = {'command': command, 'exitCode': result.returncode,
                    'output': result.stdout[-20000:]}
        except subprocess.TimeoutExpired as error:
            item = {'command': command, 'timeout': True,
                    'output': (error.stdout or b'').decode(errors='replace')[-20000:]}
        report['commands'].append(item)
        with (base / 'progress.jsonl').open('a') as progress:
            progress.write(json.dumps(item) + '\n')
        return item.get('exitCode') == 0

    def exercise_ports():
        from docker_network_fixture import observe, wait_service
        names = (base.name + '-first', base.name + '-second')
        network = base.name + '-network'
        conflict = base.name + '-conflict'
        report['portConflictContainer'] = conflict
        ports = []
        family = ['--ipv6'] if ipv6 else []
        host = '[' + ipv6_host + ']' if ipv6 else '127.0.0.1'
        def external(**request):
            return observe(observer, ipv6=ipv6, **request)
        assert client('network', 'create', *family, network)
        try:
            for name in names:
                port = external(operation='allocate')['port']
                ports.append(port)
                external(operation='handoff', port=port)
                assert client('run', '-d', '--name', name, '--network', network,
                              '-p', f'{host}:{port}:8080/tcp', '-p', f'{host}:{port}:8080/udp',
                              '--cgroupns', cgroupns, '--mount',
                              'type=bind,src=' + str(Path(__file__).with_name('docker_network_fixture.py'))
                              + ',dst=/fixture.py,readonly', image, 'python3', '/fixture.py', name, *family)
                wait_service(['docker', '--host', endpoint, 'logs', '-f', name])
                report.setdefault('portChecks', []).append(external(operation='exchange', port=port, token=name, repeats=20))
            for source, target in (names, tuple(reversed(names))):
                field = 'GlobalIPv6Address' if ipv6 else 'IPAddress'
                assert client('inspect', '--format', '{{range .NetworkSettings.Networks}}{{.' + field + '}}{{end}}', target)
                address = str(ipaddress.ip_address(report['commands'][-1]['output'].strip()))
                assert client('exec', source, 'python3', '/fixture.py', '--client', address, '8080', target, '20', *family)
                assert client('exec', source, 'python3', '/fixture.py', '--client', target, '8080', target, '20', *family)
            report['containerPeerExchangePassed'] = True
            report['namedPeerExchangePassed'] = True
            outbound = observe(observer, operation='outbound')['url']
            for name in names:
                assert client('exec', name, 'python3', '/fixture.py', '--http', outbound)
            report['localOutboundPassed'] = True
            assert not client('run', '--rm', '--name', conflict, '--network', network,
                              '-p', f'{host}:{ports[0]}:8080', image, '/bin/true')
            assert 'already allocated' in report['commands'][-1]['output'] \
                or 'address already in use' in report['commands'][-1]['output'], report['commands'][-1]
            report['portPublicationTested'] = True
            if internet:
                report['internetResults'] = [client('exec', name, 'python3', '/fixture.py', '--internet') for name in names]
                report['internetPassed'] = all(report['internetResults'])
            if internet_ipv6:
                report['internetIpv6Results'] = [client('exec', name, 'python3', '/fixture.py', '--internet-ipv6') for name in names]
                report['internetIpv6Passed'] = all(report['internetIpv6Results'])
        except Exception:
            report['networkRules'] = subprocess.run(['nft', '-a', 'list', 'ruleset'], capture_output=True,
                                                    text=True, timeout=15).stdout
            report['containerDiagnostics'] = []
            for name in names:
                for operation in ('inspect', 'logs'):
                    diagnostic = subprocess.run(['docker', '--host', endpoint, operation, name],
                                                capture_output=True, text=True, timeout=15)
                    report['containerDiagnostics'].append(dict(name=name, operation=operation,
                        exitCode=diagnostic.returncode, output=(diagnostic.stdout + diagnostic.stderr)[-12000:]))
            raise
        finally:
            removed = client('rm', '-f', *names, conflict)
            network_removed = client('network', 'rm', network)
            assert removed and network_removed, report['commands'][-2:]
        for port in ports:
            report['portChecks'].append(external(operation='released', port=port))
        report['portPublicationPassed'] = True

    try:
        # EVENT_WAIT: daemon API log or output EOF; timeout is not readiness.
        event = wait_daemon_api(endpoint, report) if external else records.get(timeout=45)
        if event == 'ready':
            report['apiReady'] = client('version', '--format', '{{json .Server}}')
            assert client('info', '--format', '{{json .}}')
            if rootless_user:
                info = json.loads(report['commands'][-1]['output'])
                assert 'name=rootless' in info['SecurityOptions'], info['SecurityOptions']
                assert any('seccomp' in option for option in info['SecurityOptions']), info['SecurityOptions']
            if report['apiReady'] and (client('image', 'inspect', image) or client('pull', image)):
                for number in range(create_count):
                    name = 'md-copy-' + str(number)
                    if not client('create', '--name', name, '--network', 'none', '--cgroupns',
                                  cgroupns, image, '/bin/true'):
                        break
                    if not client('rm', name):
                        break
                for network in ('host', 'none'):
                    client('run', '--rm', '--network', network, '--cgroupns', cgroupns, image,
                           '/bin/sh', '-c', 'echo DOCKER_CONTAINER_EXECUTED; id')
                if profile in NETWORK_PROFILES or external or rootless_user:
                    if observer:
                        exercise_ports()
                    else:
                        client('run', '--rm', '--network', 'bridge', '-p', '127.0.0.1:39871:80',
                           '--cgroupns', cgroupns, image, '/bin/sh', '-c',
                           'echo DOCKER_BRIDGE_EXECUTED; id' +
                           ('; wget -T 15 -qO- http://example.com' if internet else ''))
        else:
            report['startupEnded'] = True
    except queue.Empty:
        report['startupTimeout'] = True
    except Exception:
        report['failure'] = traceback.format_exc()
    finally:
        if process:
            if process.poll() is None:
                process.terminate()
            try:
                # EVENT_WAIT: Docker shutdown; escalation is scoped to this daemon.
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
                report['forcedShutdown'] = True
            reader.join(timeout=10)
            report['daemonExitCode'] = process.returncode
        else:
            report['daemonRetained'] = client('version', '--format', '{{json .Server}}')
        report['logLines'] = log_count
        report['log'] = ''.join(first + list(tail))
        report['errors'] = list(important)
        report['readerDrained'] = reader is None or not reader.is_alive()
        if trace_runtime:
            report['runtimeTraces'] = {}
            for path in sorted(base.glob('runc-*.log')):
                lines = path.read_text(errors='replace').splitlines()
                report['runtimeTraces'][path.name] = {
                    'lines': len(lines), 'tail': lines[-200:],
                    'errors': [line for line in lines if '= -1 ' in line][-100:]}
        if trace_filesystem:
            count, tail, conflicts = 0, collections.deque(maxlen=100), collections.deque(maxlen=60)
            recent, cleanup = collections.deque(maxlen=160), collections.deque(maxlen=6)
            following = 0
            with (base / 'filesystem.log').open(errors='replace') as stream:
                for line in stream:
                    count += 1
                    line = line.rstrip()
                    tail.append(line)
                    recent.append(line)
                    if following:
                        cleanup[-1]['lines'].append(line)
                        following -= 1
                    if 'EEXIST' in line:
                        conflicts.append(line)
                    if 'ENOTEMPTY' in line and not following:
                        cleanup.append({'at': count, 'lines': list(recent)})
                        following = 80
            report['filesystemTrace'] = {'lines': count, 'tail': list(tail), 'conflicts': list(conflicts),
                                         'cleanupFailures': list(cleanup)}
        if trace_network:
            with (base / 'network.log').open(errors='replace') as stream:
                report['networkTrace'] = list(collections.deque((line for line in stream
                    if not any(marker in line for marker in ('--- SIGURG ', '--- SIGCHLD ', '+++ exited with'))), maxlen=180))
        print('DOCKER_REPORT ' + json.dumps(report), flush=True)
        if reader and reader.is_alive():
            os._exit(2)


def main():
    from test_oci_services import Suite, add_machine_entry_argument
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('store')
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--profile', choices=PROFILES)
    mode.add_argument('--rootless-user', default='', help='Run the stock rootless launcher as this guest user')
    parser.add_argument('--image', help='Defaults to Python for port tests, BusyBox otherwise')
    parser.add_argument('--storage-driver', choices=('vfs', 'overlay2'), default='vfs')
    parser.add_argument('--data-root', default='', help='Reuse this disposable daemon image cache')
    parser.add_argument('--userland-proxy', action=argparse.BooleanOptionalAction, default=True)
    parser.add_argument('--internet', action='store_true', help='Also require external DNS and HTTP')
    parser.add_argument('--ipv6', action='store_true', help='Require a dual-stack bridge and IPv6 TCP/UDP, publication and named peers')
    parser.add_argument('--ipv6-host', default='::1', help='Native Android IPv6 address for the external port observer')
    parser.add_argument('--internet-ipv6', action='store_true', help='Require public IPv6 UDP/TCP DNS and verified HTTPS')
    tracing = parser.add_mutually_exclusive_group()
    tracing.add_argument('--trace', action='store_true')
    tracing.add_argument('--trace-runtime', action='store_true')
    tracing.add_argument('--trace-filesystem', action='store_true',
                        help='Record daemon file syscalls in the disposable guest directory')
    tracing.add_argument('--trace-network', action='store_true')
    parser.add_argument('--create-count', type=int, default=0, choices=range(21),
                        help='Exercise repeated VFS layer creation/removal before starting containers')
    parser.add_argument('--no-new-keyring', action='store_true',
                        help='Diagnostic variant: runc inherits the session keyring instead of creating one')
    parser.add_argument('--cgroup', choices=('none', 'v2', 'devices'), default='none')
    parser.add_argument('--cgroupns', choices=('private', 'host'), default='private')
    parser.add_argument('--exercise-ports', action='store_true',
                        help='Verify TCP/UDP publication from outside Shroot; use a Python image')
    add_machine_entry_argument(parser)
    args = parser.parse_args()
    args.profile = args.profile or ('rootless' if args.rootless_user else 'rootless-no-network')
    if args.ipv6 and not args.exercise_ports:
        parser.error('--ipv6 requires --exercise-ports')
    if (args.internet_ipv6 or args.ipv6_host != '::1') and not args.ipv6:
        parser.error('--internet-ipv6 and --ipv6-host require --ipv6')
    if args.exercise_ports and args.profile not in NETWORK_PROFILES and not args.rootless_user:
        parser.error('--exercise-ports requires a network profile or --rootless-user')
    if args.image is None:
        args.image = 'python:3.14-alpine' if args.exercise_ports else 'busybox:1.37.0'
    suite = Suite(args.build, args.build, machine_entry=args.machine_entry)
    suite.report.update(store=args.store, probe=True, profile=args.profile, cgroup=args.cgroup)
    target = '/tmp/md-docker-probe-' + suite.report['id']
    prefix = suite.guest(args.store, '--user', '0:0')
    observer = None
    created = False
    try:
        suite.transport.upload(suite.client, Path(__file__), suite.base + '/probe.py')
        if args.exercise_ports:
            from docker_network_fixture import Observer
            observer = Observer(args.ipv6_host)
            suite.transport.upload(suite.client, Path(__file__).with_name('docker_network_fixture.py'),
                                   suite.base + '/docker_network_fixture.py')
        suite.command(prefix + ['--', '/bin/mkdir', target])
        created = True
        deadline = 1800 if observer else 900
        runner = prefix + ['--bind-ro', suite.base, target, '--deadline-seconds', str(deadline), '--']
        if args.profile in NETWORK_PROFILES or args.rootless_user:
            runner = prefix + ['--network', 'routed', *runner[len(prefix):]]
        suite.report['versions'] = suite.command(runner + ['/bin/sh', '-ec',
            'dockerd --version; docker --version; containerd --version; runc --version'])
        if args.profile in ROOTLESS_PROFILES and not args.rootless_user:
            runner += ['/usr/bin/unshare', '--user', '--map-users=0:0:65536',
                       '--map-groups=0:0:65536', '--mount']
            # Mounting cgroups in a user namespace requires a cgroup namespace
            # owned by that user namespace, independently of Docker's setting.
            if args.cgroup != 'none':
                runner += ['--cgroup']
        output = suite.command(runner + ['/usr/bin/python3', target + '/probe.py', '--guest',
                                        args.profile, args.image, str(int(args.trace)), args.cgroup,
                                        args.cgroupns, str(int(args.trace_runtime)), str(int(args.no_new_keyring)),
                                        str(int(args.trace_filesystem)), str(args.create_count),
                                        str(int(args.trace_network)), observer.address if observer else '',
                                        args.storage_driver, args.data_root,
                                        str(int(args.userland_proxy)), str(int(args.internet)), str(int(args.ipv6)),
                                        str(int(args.internet_ipv6)), args.ipv6_host, args.rootless_user],
                               quiet=True, timeout=deadline + 30)
        reports = [line.removeprefix('DOCKER_REPORT ') for line in output.splitlines()
                   if line.startswith('DOCKER_REPORT ')]
        assert len(reports) == 1, output[-2000:]
        suite.report['probeResult'] = json.loads(reports[0])
        print(json.dumps({key: value for key, value in suite.report['probeResult'].items()
                          if key != 'log'}, indent=2), flush=True)
        if observer:
            assert suite.report['probeResult'].get('portPublicationPassed'), 'Port matrix failed; see ' + str(suite.output)
            assert suite.report['probeResult'].get('containerPeerExchangePassed'), suite.report['probeResult']
            assert suite.report['probeResult']['daemonExitCode'] == 0, suite.report['probeResult']
            assert suite.report['probeResult']['readerDrained'], suite.report['probeResult']
            if args.internet:
                assert suite.report['probeResult'].get('internetPassed'), 'External DNS/HTTP failed; local matrix is recorded separately'
            if args.internet_ipv6:
                assert suite.report['probeResult'].get('internetIpv6Passed'), 'External IPv6 DNS/HTTPS failed'
            for item in suite.report['probeResult']['commands']:
                if suite.report['probeResult'].get('portConflictContainer') in item['command'] and 'run' in item['command']:
                    continue
                if item['command'][3:5] == ['image', 'inspect']:
                    continue
                assert item.get('exitCode') == 0, item
            suite.report['passed'] = True
    finally:
        if observer:
            observer.close()
            suite.report['externalPortChecks'] = observer.records
        try:
            if created:
                suite.command(prefix + ['--', '/bin/rmdir', target])
        finally:
            suite.finish()


if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] == '--guest':
        guest(sys.argv[2], sys.argv[3], bool(int(sys.argv[4])), sys.argv[5], sys.argv[6],
              bool(int(sys.argv[7])), bool(int(sys.argv[8])), bool(int(sys.argv[9])), int(sys.argv[10]), bool(int(sys.argv[11])),
              sys.argv[12] if len(sys.argv)>12 else '', sys.argv[13] if len(sys.argv)>13 else 'vfs',
              sys.argv[14] if len(sys.argv)>14 else '',
              bool(int(sys.argv[15])) if len(sys.argv)>15 else True,
              bool(int(sys.argv[16])) if len(sys.argv)>16 else False,
              ipv6=bool(int(sys.argv[17])) if len(sys.argv)>17 else False,
              internet_ipv6=bool(int(sys.argv[18])) if len(sys.argv)>18 else False,
              ipv6_host=sys.argv[19] if len(sys.argv)>19 else '::1',
              rootless_user=sys.argv[20] if len(sys.argv)>20 else '')
    else:
        main()
