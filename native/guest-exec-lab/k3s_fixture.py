#!/usr/bin/env python3
"""Shared stock-k3s workload, isolated node lifecycle and staged guest driver."""
import hashlib
from datetime import datetime
import json
import os
from pathlib import Path
import platform
import shlex
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import uuid


NODE_STATE_DIRECTORIES = ('/etc/rancher', '/var/lib/kubelet', '/var/lib/rancher', '/var/lib/cni', '/var/log')

STATE = '''
mount -t sysfs sysfs /sys
mount -t cgroup2 none /sys/fs/cgroup
mount -t tmpfs tmpfs /run
mount -t tmpfs tmpfs /tmp
# Retain distribution inputs, including certificate stores reached through /etc.
for state in ''' + shlex.join(NODE_STATE_DIRECTORIES) + '''; do
    mount -t tmpfs tmpfs "$state"
done
'''


def prepare_node_state(suite, store, created):
    from init_fixture import GUEST_PATH
    # Prepare only missing mountpoints before an agent's read-only store bind.
    # Track partial success too; cleanup removes only our own empty directories.
    result = suite.observe(suite.guest(store, '--user', '0:0', '--env', 'PATH=' + GUEST_PATH) + ['--', '/bin/sh', '-ec',
        'for path do\n'
        '  if [ ! -d "$path" ]; then\n'
        '    mkdir -- "$path"\n'
        '    printf "K3S_STATE_DIR %s\\n" "$path"\n'
        '  fi\n'
        'done', 'prepare-state', *NODE_STATE_DIRECTORIES])
    for line in result['output'].splitlines():
        if line.startswith('K3S_STATE_DIR '):
            path = line.removeprefix('K3S_STATE_DIR ')
            assert path in NODE_STATE_DIRECTORIES, line
            created.append((store, path))
    assert result['exitCode'] == 0, result


def command(*args):
    result = subprocess.run(args, text=True, capture_output=True, timeout=20)
    print('NODE_COMMAND', args, result.returncode, result.stdout, result.stderr, flush=True)
    assert result.returncode == 0, result
    return result.stdout


def pid1_command(*argv):
    """Keep a signal-handling PID 1 for workloads without their own init logic."""
    return shlex.join(argv) + ''' &
child=$!
stop() {
    trap '' TERM INT
    kill -TERM "$child" 2>/dev/null || :
    wait "$child" || :
    exit 0
}
trap stop TERM INT
wait "$child"
'''


def prepare_images(inputs, data):
    """Populate stock k3s's image inbox without changing its import policy."""
    for archive in sorted(Path(inputs).glob('images-*.tar')):
        images = Path(data) / 'agent/images'
        images.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(archive, images / archive.name)


class Kubectl:
    """Bounded stock client shared by process and full-boot scenarios."""
    def __init__(self, binary, env=None):
        self.binary, self.env = binary, env

    def __call__(self, *args, check=True, timeout=120):
        # EVENT_WAIT: kubectl watch/response; timeout is a failure, not readiness.
        result = subprocess.run([self.binary, 'kubectl', *args], env=self.env, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
        print('KUBECTL', args, result.returncode, result.stdout, flush=True)
        if check:
            assert result.returncode == 0, result.stdout
        return result.stdout if check else result


def kubelet_pods(kubectl, node):
    """The kubelet may publish a null inventory while recovering its state."""
    result = kubectl('--request-timeout=5s', 'get',
        '--raw=/api/v1/nodes/' + node + '/proxy/pods', check=False, timeout=15)
    if result.returncode:
        return {}
    return {pod['metadata']['uid']: pod for pod in (json.loads(result.stdout)['items'] or [])}


def node_ready_since(conditions, started):
    for condition in conditions or []:
        if condition.get('type') != 'Ready' or condition.get('status') != 'True':
            continue
        heartbeat = condition.get('lastHeartbeatTime')
        if heartbeat and datetime.fromisoformat(heartbeat.replace('Z', '+00:00')).timestamp() >= started:
            return True
    return False


def wait_node_ready(kubectl, node, started, check_alive=None, timeout=90):
    deadline = time.monotonic() + timeout
    while (remaining := deadline - time.monotonic()) > 0:
        if check_alive is not None:
            check_alive()
        result = kubectl('--request-timeout=5s', 'get', 'node/' + node,
                         '-o=jsonpath={.status.conditions}', check=False, timeout=min(15, remaining))
        if result.returncode == 0 and node_ready_since(json.loads(result.stdout or 'null'), started):
            return
        # Bounded readiness reconciliation: a persisted Ready condition from
        # the previous kubelet cannot certify the process just started.
        time.sleep(min(1, max(0, deadline - time.monotonic())))
    raise AssertionError('Node did not publish fresh Ready state: ' + node)


def network_diagnostics():
    for args in (('ip', '-d', 'link'), ('ip', 'addr'), ('ip', 'route', 'show', 'table', 'all'),
                 ('ip', 'neigh'), ('bridge', 'fdb'), ('nft', 'list', 'ruleset')):
        try:
            command(*args)
        except Exception as error:
            print('NETWORK_DIAGNOSTICS', repr(error), flush=True)


def snapshot_diagnostics():
    """Bounded metadata only; do not collect container files or credentials."""
    root = Path('/tmp/k3s-data/agent/containerd')
    for snapshotter in sorted(root.glob('io.containerd.snapshotter.v1.*')):
        for directory in sorted(snapshotter.glob('snapshots/*/fs'))[:64]:
            try:
                paths = [directory, *sorted(directory.iterdir())[:20]]
                metadata = []
                for path in paths:
                    info = path.lstat()
                    metadata.append(dict(path=str(path), mode=oct(info.st_mode),
                                         uid=info.st_uid, gid=info.st_gid))
                print('SNAPSHOT_METADATA', json.dumps(metadata), flush=True)
            except OSError as error:
                print('SNAPSHOT_METADATA_ERROR', str(directory), repr(error), flush=True)


def enter_agent_root(root, k3s, control_fd):
    # Retain the installed root read-only; only node configuration and service
    # state are private. Device/proc providers belong to this launch, not the store.
    configuration = tempfile.mkdtemp(prefix='k3s-agent-etc-')
    command('cp', '-a', root + '/etc/.', configuration)
    command('mount', '--bind', configuration, root + '/etc')
    fixtures = str(Path(k3s).parent)
    command('mount', '--bind', fixtures, root + fixtures)
    command('mount', '--rbind', '/dev', root + '/dev')
    command('mount', '--bind', '/proc', root + '/proc')
    # A node needs a namespace root, not only a process chroot: container exec
    # joins its mount namespace and selects that namespace's root again.
    command('pivot_root', root, root + '/run')
    os.chdir('/')
    command('umount', '-l', '/run')
    Path('/etc/rancher').mkdir(exist_ok=True)
    os.set_inheritable(control_fd, True)
    os.execvp('python3', ['python3', fixtures + '/test.py', '--agent',
                               k3s, str(control_fd), ''])


def agent(k3s, control_fd, root):
    if root:
        enter_agent_root(root, k3s, control_fd)
    distribution = platform.freedesktop_os_release()
    reentered = command('nsenter', '--mount=/proc/self/ns/mnt', '--', sys.executable, '-c',
        'import json, platform; print(json.dumps(platform.freedesktop_os_release()))')
    assert json.loads(reentered) == distribution, 'node mount-namespace root differs from process root'
    channel = socket.socket(fileno=control_fd)
    channel.send(b'namespace')
    assert channel.recv(32) == b'linked'
    command('ip', 'link', 'set', 'lo', 'up')
    command('ip', 'link', 'set', 'eth0', 'up')
    command('ip', 'addr', 'add', '192.0.2.2/24', 'dev', 'eth0')
    command('ip', 'route', 'add', 'default', 'via', '192.0.2.1')
    # Each node owns its runtime, mounts and cgroup subtree, while installed
    # executables remain shared read-only inputs to this topology fixture.
    subprocess.run(['/bin/sh', '-ec', STATE], check=True)
    prepare_images(Path(k3s).parent, '/tmp/k3s-data')
    Path('/sys/fs/cgroup/init/cgroup.procs').write_text(str(os.getpid()))
    controllers = Path('/sys/fs/cgroup/cgroup.controllers').read_text().split()
    Path('/sys/fs/cgroup/cgroup.subtree_control').write_text(' '.join('+' + c for c in controllers))
    channel.send(b'prepared')
    channel.send(json.dumps(distribution).encode())
    process = None
    with open('/tmp/agent.log', 'w') as log:
        try:
            while True:
                # EVENT_WAIT: parent owns start/restart/stop; EOF retires the node.
                action = channel.recv(32)
                if action == b'diagnostics':
                    network_diagnostics()
                    channel.send(action)
                    continue
                if process is not None:
                    if process.poll() is None:
                        process.send_signal(signal.SIGTERM)
                        process.wait(timeout=45)
                    assert process.returncode == 0, f'agent exited: {process.returncode}'
                    process = None
                if action not in (b'start', b'restart'):
                    channel.send(b'stopped')
                    break
                process = subprocess.Popen([k3s, 'agent', '--server', 'https://192.0.2.1:6443',
                    '--data-dir', '/tmp/k3s-data', '--node-name', 'md-k3s-agent',
                    '--node-ip', '192.0.2.2', '--flannel-iface', 'eth0'],
                    stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
                channel.send(b'started')
        finally:
            if process is not None and process.poll() is None:
                process.kill()
                process.wait()
            print('AGENT_LOG', Path('/tmp/agent.log').read_text(), flush=True)
            path = Path('/tmp/k3s-data/agent/containerd/containerd.log')
            if path.exists():
                print('AGENT_CONTAINERD_LOG', path.read_text(), flush=True)
    channel.close()
    return 0


class Agent:
    def __init__(self, k3s, env, root):
        self.channel, child = socket.socketpair(socket.AF_UNIX, socket.SOCK_SEQPACKET)
        self.channel.settimeout(60)
        self.log = open('/tmp/agent-host.log', 'w')
        controllers = Path('/sys/fs/cgroup/cgroup.controllers').read_text().split()
        Path('/sys/fs/cgroup/cgroup.subtree_control').write_text(' '.join('+' + c for c in controllers))
        Path('/sys/fs/cgroup/md-agent').mkdir()
        Path('/sys/fs/cgroup/md-agent/init').mkdir()
        self.process = subprocess.Popen(['/bin/sh', '-ec',
            'echo $$ > /sys/fs/cgroup/md-agent/cgroup.procs; exec "$@"', 'agent',
            'unshare', '-mpunCf', '--mount-proc', sys.executable,
            __file__, '--agent', k3s, str(child.fileno()), root], env=env,
            pass_fds=(child.fileno(),), stdout=self.log, stderr=subprocess.STDOUT)
        child.close()
        try:
            assert self.channel.recv(32) == b'namespace'
            Path('/sys/fs/cgroup/md-agent/init/cgroup.procs').write_text(str(self.process.pid))
            command('ip', 'link', 'add', 'md-server', 'type', 'veth', 'peer', 'name', 'md-agent')
            command('ip', 'link', 'set', 'md-agent', 'netns', str(self.process.pid), 'name', 'eth0')
            command('ip', 'addr', 'add', '192.0.2.1/24', 'dev', 'md-server')
            command('ip', 'link', 'set', 'md-server', 'up')
            self.channel.send(b'linked')
            assert self.channel.recv(32) == b'prepared'
            self.distribution = json.loads(self.channel.recv(16384))
        except BaseException:
            self.process.kill()
            self.process.wait()
            self.channel.close()
            self.log.close()
            print(Path('/tmp/agent-host.log').read_text(), flush=True)
            raise

    def action(self, name):
        self.channel.send(name.encode())
        assert self.channel.recv(32) == {'stop': b'stopped', 'diagnostics': b'diagnostics'}.get(name, b'started')

    def close(self):
        try:
            self.action('stop')
            self.process.wait(timeout=60)
            assert self.process.returncode == 0, self.process.returncode
            assert not any(link['ifname'] == 'md-server' for link in json.loads(command('ip', '-j', 'link')))
        finally:
            self.channel.close()
            if self.process.poll() is None:
                self.process.kill()
                self.process.wait()
            self.log.close()
            print('AGENT_HOST_LOG', Path('/tmp/agent-host.log').read_text(), flush=True)


def guest(k3s, backend, nodes, agent_root, restart_checks=1):
    prepare_images(Path(k3s).parent, '/tmp/k3s-data')
    env = dict(os.environ, KUBECONFIG='/tmp/kubeconfig', K3S_DATA_DIR='/tmp/k3s-data', K3S_TOKEN=uuid.uuid4().hex)
    server = None
    remote = None
    report = {'passed': False, 'checks': [], 'serverDistribution': platform.freedesktop_os_release(),
              'restartChecks': restart_checks, 'completedRestarts': 0}
    report['version'] = subprocess.check_output([k3s, '--version'], env=env, text=True, timeout=15).strip()
    print('K3S_VERSION', report['version'], flush=True)

    kubectl = Kubectl(k3s, env)

    def start(log):
        options = [] if backend == 'vxlan' else ['--flannel-backend', backend]
        if nodes == 2:
            options += ['--node-ip', '192.0.2.1', '--flannel-iface', 'md-server']
        started = time.time()
        process = subprocess.Popen([k3s, 'server', '--data-dir', '/tmp/k3s-data',
            '--node-name', 'md-k3s-lab', *options,
            '--write-kubeconfig', '/tmp/kubeconfig'], env=env,
            stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        process.started_at = started
        return process

    def ready():
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            assert server.poll() is None, 'k3s server exited'
            if Path('/tmp/kubeconfig').exists():
                result = kubectl('--request-timeout=5s', 'get', '--raw=/readyz',
                                 check=False, timeout=15)
                if result.returncode == 0 and result.stdout.strip() == 'ok':
                    break
            # Bounded API readiness reconciliation; k3s supplies no startup FD.
            time.sleep(1)
        else:
            raise AssertionError('API readiness timeout')
        kubectl('wait', 'node/md-k3s-lab', '--for=create', '--timeout=90s')
        def check_alive():
            assert server.poll() is None, f'k3s server exited: {server.returncode}'
        wait_node_ready(kubectl, 'md-k3s-lab', server.started_at, check_alive)
        kubectl('wait', '-n', 'kube-system', 'deployment/coredns', '--for=create', '--timeout=90s')
        kubectl('rollout', 'status', '-n', 'kube-system', 'deployment/coredns',
                '--timeout=240s', timeout=255)

    def network():
        expected = {}
        for item in json.loads(kubectl('get', 'pod/md-http', 'pod/md-client', '-o', 'json'))['items']:
            expected.setdefault(item['spec']['nodeName'], set()).add(item['metadata']['uid'])
        assert len(expected) == nodes, expected
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            assert server.poll() is None, f'k3s server exited: {server.returncode}'
            converged = True
            for node, identities in expected.items():
                converged &= identities <= kubelet_pods(kubectl, node).keys()
            if converged:
                break
            # API Ready can survive a kubelet restart. Reconcile the kubelet's
            # own pod inventory before executing; never retry a workload action.
            time.sleep(1)
        else:
            raise AssertionError('kubelet did not reacquire the expected pods')
        summary = json.loads(kubectl('get', '--raw=/api/v1/nodes/md-k3s-lab/proxy/stats/summary'))
        filesystem = summary['node']['fs']
        assert filesystem['capacityBytes'] > 0 and filesystem['availableBytes'] >= 0, filesystem
        report.setdefault('rootFilesystems', []).append(filesystem)
        for pod in (('md-client', 'md-http') if remote else ('md-client',)):
            answer = kubectl('exec', pod, '--', 'nslookup', 'kubernetes.default.svc.cluster.local')
            assert 'kubernetes.default.svc.cluster.local' in answer and '10.43.0.1' in answer
        answer = kubectl('exec', 'md-client', '--', 'wget', '-T', '15', '-qO-',
                         'http://md-http.default.svc.cluster.local')
        assert answer.strip() == 'SHROOT_K3S_SERVICE', answer

    def stop():
        if server is not None:
            if server.poll() is None:
                server.send_signal(signal.SIGTERM)
                # EVENT_WAIT: stock server shutdown; forced termination is a failure.
                server.wait(timeout=45)
            assert server.returncode == 0, f'unclean k3s shutdown: {server.returncode}'

    def policies():
        policy = {'apiVersion': 'networking.k8s.io/v1', 'kind': 'NetworkPolicy',
                  'metadata': {'name': 'md-ingress'},
                  'spec': {'podSelector': {'matchLabels': {'run': 'md-http'}},
                           'policyTypes': ['Ingress'], 'ingress': []}}
        path = Path('/tmp/policy.json')

        def apply():
            path.write_text(json.dumps(policy))
            kubectl('apply', '-f', str(path))

        def traffic(allowed):
            ip = kubectl('get', 'pod/md-http', '-o', 'jsonpath={.status.podIP}')
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline:
                assert server.poll() is None, 'server exited during policy convergence'
                result = kubectl('exec', 'md-client', '--', 'wget', '-q', '-T', '2', '-O', '-',
                                 'http://' + ip + ':8080/', check=False, timeout=10)
                if allowed and result.returncode == 0 and result.stdout.strip() == 'SHROOT_K3S_SERVICE':
                    return
                if not allowed and result.returncode != 0 and ('refused' in result.stdout.lower()
                                                               or 'timed out' in result.stdout.lower()):
                    return
                # Bounded observation of the controller's asynchronous reconciliation.
                time.sleep(1)
            raise AssertionError(f'NetworkPolicy traffic did not converge: allowed={allowed}')

        apply()
        traffic(False)
        policy['spec']['ingress'] = [{'from': [{'podSelector': {'matchLabels': {'run': 'md-client'}}}],
                                      'ports': [{'protocol': 'TCP', 'port': 8080}]}]
        apply()
        traffic(True)
        policy['spec']['ingress'] = []
        apply()
        traffic(False)
        kubectl('delete', 'networkpolicy/md-ingress')
        traffic(True)
        report['checks'].append('network-policy-deny-allow-update-delete')

    def workload():
        def apply(value):
            path = Path('/tmp/workload.json')
            path.write_text(json.dumps(value))
            kubectl('apply', '-f', str(path))

        apply({'apiVersion': 'v1', 'kind': 'PersistentVolumeClaim', 'metadata': {'name': 'md-data'},
               'spec': {'accessModes': ['ReadWriteOnce'], 'storageClassName': 'local-path',
                        'resources': {'requests': {'storage': '16Mi'}}}})
        deployment = {'apiVersion': 'apps/v1', 'kind': 'Deployment', 'metadata': {'name': 'md-web'},
                      'spec': {'replicas': 2, 'selector': {'matchLabels': {'app': 'md-web'}},
                               'template': {'metadata': {'labels': {'app': 'md-web'}}, 'spec': {
                                   'containers': [{'name': 'http', 'image': 'docker.io/library/busybox:1.37',
                                       'command': ['sh', '-ec', 'mkdir -p /www; echo "$VERSION" > /www/index.html; '
                                                   + pid1_command('httpd', '-f', '-p', '8080', '-h', '/www')],
                                       'env': [{'name': 'VERSION', 'value': 'MD_VERSION_1'}],
                                       'readinessProbe': {'httpGet': {'path': '/', 'port': 8080}},
                                       'volumeMounts': [{'name': 'data', 'mountPath': '/data'}]}],
                                   'volumes': [{'name': 'data', 'persistentVolumeClaim': {'claimName': 'md-data'}}]}}}}
        apply(deployment)
        kubectl('rollout', 'status', 'deployment/md-web', '--timeout=180s', timeout=195)
        assert kubectl('get', 'pvc/md-data', '-o', 'jsonpath={.status.phase}') == 'Bound'
        kubectl('exec', 'deployment/md-web', '--', 'sh', '-ec', 'echo MD_PERSISTENT > /data/value')
        kubectl('scale', 'deployment/md-web', '--replicas=3')
        kubectl('rollout', 'status', 'deployment/md-web', '--timeout=120s', timeout=135)
        assert kubectl('get', 'deployment/md-web', '-o', 'jsonpath={.status.readyReplicas}') == '3'
        kubectl('set', 'env', 'deployment/md-web', 'VERSION=MD_VERSION_2')
        kubectl('rollout', 'status', 'deployment/md-web', '--timeout=180s', timeout=195)
        pods = json.loads(kubectl('get', 'pods', '-l', 'app=md-web', '-o', 'json'))['items']
        active = [p for p in pods if not p['metadata'].get('deletionTimestamp')]
        assert len(active) == 3
        for pod in active:
            name = pod['metadata']['name']
            assert kubectl('exec', name, '--', 'cat', '/data/value').strip() == 'MD_PERSISTENT'
            assert kubectl('exec', name, '--', 'wget', '-qO-', 'http://127.0.0.1:8080').strip() == 'MD_VERSION_2'
        report['checks'].append('deployment-scale-rolling-update-pvc')
        kubectl('expose', 'deployment/md-web', '--port=80', '--target-port=8080')
        apply({'apiVersion': 'networking.k8s.io/v1', 'kind': 'Ingress', 'metadata': {'name': 'md-web'},
               'spec': {'ingressClassName': 'traefik', 'rules': [{'host': 'md.test', 'http': {'paths': [{
                   'path': '/', 'pathType': 'Prefix',
                   'backend': {'service': {'name': 'md-web', 'port': {'number': 80}}}}]}}]}})
        kubectl('wait', '-n', 'kube-system', 'deployment/traefik', '--for=create', '--timeout=120s', timeout=135)
        kubectl('rollout', 'status', '-n', 'kube-system', 'deployment/traefik', '--timeout=180s', timeout=195)
        service = json.loads(kubectl('get', '-n', 'kube-system', 'service/traefik', '-o', 'json'))
        node = json.loads(kubectl('get', 'node/md-k3s-lab', '-o', 'json'))
        node_ip = next(a['address'] for a in node['status']['addresses'] if a['type'] == 'InternalIP')
        for address in (service['spec']['clusterIP'], node_ip):
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline:
                result = kubectl('exec', 'md-client', '--', 'wget', '-T', '3', '-qO-', '--header=Host: md.test',
                                 'http://' + address, check=False, timeout=15)
                if result.returncode == 0 and result.stdout.strip() == 'MD_VERSION_2':
                    break
                time.sleep(1)  # Bounded controller/ingress convergence observation.
            else:
                raise AssertionError('Traefik/ServiceLB did not serve ingress at ' + address)
        report['checks'].append('stock-traefik-servicelb-ingress')
        kubectl('delete', 'ingress/md-web', 'service/md-web', 'deployment/md-web', '--timeout=75s', timeout=90)
        kubectl('wait', 'pods', '-l', 'app=md-web', '--for=delete', '--timeout=75s', timeout=90)
        kubectl('delete', 'pvc/md-data', '--timeout=75s', timeout=90)

    with open('/tmp/server.log', 'w') as log:
        try:
            if nodes == 2:
                remote = Agent(k3s, env, agent_root)
                report['agentDistribution'] = remote.distribution
            server = start(log)
            ready()
            if remote:
                remote.action('start')
                kubectl('wait', 'node/md-k3s-agent', '--for=create', '--timeout=120s', timeout=135)
                kubectl('wait', 'node/md-k3s-agent', '--for=condition=Ready', '--timeout=180s', timeout=195)
                report['checks'].append('two-nodes-ready')
            report['checks'].append('node-and-coredns-ready')
            kubectl('run', 'md-http', '--image=docker.io/library/busybox:1.37',
                '--overrides=' + json.dumps({'spec': {'nodeName': 'md-k3s-agent' if remote else 'md-k3s-lab'}}),
                '--restart=Never', '--port=8080', '--command', '--', 'sh', '-ec',
                'mkdir /www; echo SHROOT_K3S_SERVICE > /www/index.html; '
                + pid1_command('httpd', '-f', '-p', '8080', '-h', '/www'))
            kubectl('run', 'md-client', '--image=docker.io/library/busybox:1.37',
                '--overrides=' + json.dumps({'spec': {'nodeName': 'md-k3s-lab'}}),
                '--restart=Never', '--command', '--', 'sh', '-ec', pid1_command('sleep', '1800'))
            for name in ('md-http', 'md-client'):
                kubectl('wait', 'pod/' + name, '--for=condition=Ready', '--timeout=120s', timeout=135)
            kubectl('expose', 'pod/md-http', '--port=80', '--target-port=8080')
            uid = kubectl('get', 'pod/md-http', '-o', 'jsonpath={.metadata.uid}')
            address = kubectl('get', 'service/md-http', '-o', 'jsonpath={.spec.clusterIP}')
            network()
            report['checks'].append('cross-pod-dns-and-service')
            policies()
            workload()
            if remote:
                remote.action('restart')
                network()
                report['checks'].append('agent-restart-preserves-workload')
            for index in range(restart_checks):
                stop()
                server = start(log)
                ready()
                assert kubectl('get', 'pod/md-http', '-o', 'jsonpath={.metadata.uid}') == uid
                assert kubectl('get', 'service/md-http', '-o', 'jsonpath={.spec.clusterIP}') == address
                for name in ('md-http', 'md-client'):
                    kubectl('wait', 'pod/' + name, '--for=condition=Ready', '--timeout=120s', timeout=135)
                network()
                report['completedRestarts'] = index + 1
                print('PASS server restart', index + 1, flush=True)
            report['checks'].append('server-restart-preserves-workload')
            # Default pod grace is 30 seconds; observation must also cover cleanup.
            kubectl('delete', 'pod/md-http', 'pod/md-client', 'service/md-http',
                    '--wait=true', '--timeout=75s', timeout=90)
            assert not json.loads(kubectl('get', 'pods', '-o', 'json'))['items']
            report['checks'].append('pod-and-service-deletion')
            stop()
            report['passed'] = True
        except Exception as error:
            report['error'] = repr(error)
        finally:
            if not report['passed']:
                network_diagnostics()
                snapshot_diagnostics()
                for pod in ('md-client', 'md-http'):
                    try:
                        kubectl('--request-timeout=5s', 'exec', pod, '--', '/bin/sh', '-c',
                            'printf "PATH=%s\\n" "$PATH"; ls -l /bin/sh /bin/nslookup; '
                            'cat /etc/os-release; cat /proc/self/mountinfo', check=False, timeout=20)
                    except Exception as error:
                        print('EXEC_DIAGNOSTICS', pod, repr(error), flush=True)
                if remote:
                    try:
                        remote.action('diagnostics')
                    except Exception as error:
                        print('AGENT_DIAGNOSTICS', repr(error), flush=True)
                try:
                    pods = json.loads(kubectl('--request-timeout=5s', 'get', 'pods', '-A', '-o', 'json', timeout=15))
                    for pod in pods['items']:
                        statuses = pod['status'].get('containerStatuses', [])
                        if not any(s.get('restartCount') or not s.get('ready') for s in statuses):
                            continue
                        for container in pod['spec']['containers']:
                            kubectl('--request-timeout=5s', 'logs', '-n', pod['metadata']['namespace'],
                                    pod['metadata']['name'], '-c', container['name'], '--tail=50', check=False, timeout=15)
                except Exception as error:
                    print('CONTAINER_DIAGNOSTICS', repr(error), flush=True)
                for args in [('get', 'nodes', '-o', 'wide'), ('get', 'pods', '-A', '-o', 'wide'),
                             ('get', 'events', '-A', '--sort-by=.metadata.creationTimestamp')]:
                    try:
                        kubectl('--request-timeout=5s', *args, check=False, timeout=15)
                    except Exception as error:
                        print('DIAGNOSTICS', repr(error), flush=True)
            try:
                if remote:
                    remote.close()
            except Exception as error:
                report.update(passed=False, agentShutdownError=repr(error))
            try:
                stop()
            except Exception as error:
                report.update(passed=False, shutdownError=repr(error))
                if server is not None and server.poll() is None:
                    server.kill()
                    server.wait()
    print('SERVER_LOG', Path('/tmp/server.log').read_text(), flush=True)
    containerd = Path('/tmp/k3s-data/agent/containerd/containerd.log')
    if containerd.exists():
        print('CONTAINERD_LOG', containerd.read_text(), flush=True)
    print('K3S_REPORT', json.dumps(report), flush=True)
    return 0 if report['passed'] else 1


def add_runtime_arguments(parser):
    from test_oci_services import add_machine_entry_argument
    from kernel_profile import KernelProfile
    from fixture_executor import FixtureExecutor, add_diagnostics_argument
    parser.add_argument('--output', type=Path)
    parser.add_argument('--store', help='Existing server store accessible to the selected executor; no copy')
    parser.add_argument('--backend', choices=('vxlan', 'host-gw'), default='vxlan')
    parser.add_argument('--restart-checks', type=int, default=1,
                        help='Independent server restart/workload checks; a failure is never retried')
    parser.add_argument('--images', type=Path, action='append', default=[],
                        help='Verified uncompressed airgap image tar; repeat for workload images')
    add_diagnostics_argument(parser)
    add_machine_entry_argument(parser)
    KernelProfile.add_argument(parser)
    FixtureExecutor.add_argument(parser)


def guest_command(fixture, binary, backend='vxlan', nodes=1, agent_root='', restart_checks=1):
    if restart_checks < 1:
        raise ValueError('At least one server restart is required')
    # Model the init service's creation policy, not Android's private app umask.
    script = 'umask 022\n' + STATE + '\nexec python3 "$1" --guest "$2" "$3" "$4" "$5" "$6"\n'
    return ['unshare', '-mpf', '--mount-proc', '/bin/sh', '-ec', script,
            'k3s', str(fixture), str(binary), backend, str(nodes), agent_root, str(restart_checks)]


def run(args):
    from fixture_executor import diagnostic_options
    from init_fixture import GUEST_PATH
    from test_oci_services import Suite
    if args.restart_checks < 1:
        raise ValueError('At least one server restart is required')
    suite = Suite(args.output or args.build or Path('build/k3s-results'), args.build,
                  machine_entry=args.machine_entry, kernel_profile=args.kernel_profile,
                  executor=args.executor)
    # Retain the fixture identity across distribution mounts and an agent pivot.
    target = '/md-k3s-' + suite.report['id']
    agent_target = target + '-agent'
    store = None
    created = False
    agent_created = False
    agent_fixture_created = False
    created_directories = []
    log = suite.output.with_suffix('.log')
    try:
        environment = suite.installed_environment(args.environment, args.store)
        suite.report['environment'] = environment
        store = environment['store']
        agent_environment = (suite.installed_environment(args.agent_environment, args.agent_store)
                             if args.agent_environment else None)
        suite.report['agentEnvironment'] = agent_environment
        agent_store = agent_environment['store'] if agent_environment else None
        with args.k3s.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        suite.report['k3s'] = {'sha256': digest, 'profile': str(args.nodes) + '-node-' + args.backend,
            'serviceUmask': '0022',
            'serverEnvironment': args.environment, 'agentEnvironment': args.agent_environment or args.environment}
        suite.upload(args.k3s, suite.base + '/k3s')
        assert suite.command(['sha256sum', suite.base + '/k3s'], quiet=True).split()[0] == digest
        suite.report['images'] = {}
        for index, archive in enumerate(args.images):
            name = 'images-' + str(index) + '.tar'
            with archive.open('rb') as stream:
                digest = hashlib.file_digest(stream, 'sha256').hexdigest()
            suite.upload(archive, suite.base + '/' + name)
            assert suite.command(['sha256sum', suite.base + '/' + name], quiet=True).split()[0] == digest
            suite.report['images'][name] = digest
        suite.upload(Path(__file__), suite.base + '/test.py')
        suite.chmod('700', suite.base + '/k3s')
        def node_prefix(node_store):
            return suite.guest(node_store, '--user', '0:0', '--env', 'PATH=' + GUEST_PATH)

        def node_command(node_store, *arguments):
            return node_prefix(node_store) + ['--', '/bin/sh', '-ec', 'exec "$@"', 'k3s', *arguments]

        prefix = node_prefix(store)
        suite.command(node_command(store, 'mkdir', target))
        created = True
        attachments = ['--bind-ro', suite.base, target]
        if agent_store:
            if agent_store != store:
                suite.command(node_command(agent_store, 'mkdir', target))
                agent_fixture_created = True
            suite.command(node_command(store, 'mkdir', agent_target))
            agent_created = True
            attachments += ['--bind-store-ro', agent_store, agent_target]
        for node_store in dict.fromkeys([store, *([agent_store] if agent_store else [])]):
            prepare_node_state(suite, node_store, created_directories)
        # All service state is private. The outer PID namespace retires shims and
        # workload descendants even if stock k3s deliberately leaves them running.
        command = prefix + diagnostic_options(args.diagnostics) + ['--statistics', '--network', 'routed',
            *attachments, '--deadline-seconds', '1200', '--', '/bin/sh', '-ec', 'exec "$@"', 'k3s',
            *guest_command(target + '/test.py', target + '/k3s', args.backend,
                           args.nodes, agent_target if agent_store else '', args.restart_checks)]
        try:
            print('K3s log: ' + suite.base + '/full.log', flush=True)
            suite.command(['sh', '-c', shlex.join(command) + ' > ' +
                           shlex.quote(suite.base + '/full.log') + ' 2>&1'], timeout=1230)
        finally:
            suite.download(suite.base + '/full.log', log)
            output = log.read_text()
            suite.record_entry(output)
            suite.kernel_profile.observe(output, required=True)
            reports = [json.loads(line.removeprefix('K3S_REPORT ')) for line in output.splitlines()
                       if line.startswith('K3S_REPORT ')]
            suite.report['cases'] = reports
        assert len(reports) == 1 and reports[0]['passed'], reports
        suite.report['passed'] = True
    except Exception as error:
        suite.report.update(passed=False, failure=repr(error))
        raise
    finally:
        try:
            for node_store, directory in reversed(created_directories):
                suite.command(node_command(node_store, 'rmdir', directory))
            if created:
                suite.command(node_command(store, 'rmdir', target))
            if agent_created:
                suite.command(node_command(store, 'rmdir', agent_target))
            if agent_fixture_created:
                suite.command(node_command(agent_store, 'rmdir', target))
            suite.command(['rm', '-rf', suite.base])
        except Exception as error:
            suite.report.update(passed=False, cleanupFailure=repr(error))
            raise
        finally:
            suite.finish()


if __name__ == '__main__':
    if len(sys.argv) == 5 and sys.argv[1] == '--agent':
        sys.exit(agent(sys.argv[2], int(sys.argv[3]), sys.argv[4]))
    if len(sys.argv) == 7 and sys.argv[1] == '--guest':
        sys.exit(guest(sys.argv[2], sys.argv[3], int(sys.argv[4]), sys.argv[5], int(sys.argv[6])))
    sys.exit('Use test_k3s.py or test_k3s_cluster.py to stage this fixture')
