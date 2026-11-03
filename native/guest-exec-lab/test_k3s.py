#!/usr/bin/env python3
"""Stock single-node k3s: policies, controllers, storage, ingress and restart."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import signal
import subprocess
import sys
import time


def guest(k3s):
    env = dict(os.environ, KUBECONFIG='/tmp/kubeconfig', K3S_DATA_DIR='/tmp/k3s-data')
    server = None
    report = {'passed': False, 'checks': []}
    report['version'] = subprocess.check_output([k3s, '--version'], env=env, text=True, timeout=15).strip()
    print('K3S_VERSION', report['version'], flush=True)

    def kubectl(*args, check=True, timeout=120):
        # EVENT_WAIT: kubectl watch/response; timeout is a failure, not readiness.
        result = subprocess.run([k3s, 'kubectl', *args], env=env, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
        print('KUBECTL', args, result.returncode, result.stdout, flush=True)
        if check:
            assert result.returncode == 0, result.stdout
        return result.stdout if check else result

    def start(log):
        process = subprocess.Popen([k3s, 'server', '--data-dir', '/tmp/k3s-data',
            '--node-name', 'md-k3s-lab', '--flannel-backend', 'host-gw',
            '--write-kubeconfig', '/tmp/kubeconfig'], env=env,
            stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
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
        kubectl('wait', 'node/md-k3s-lab', '--for=condition=Ready', '--timeout=90s')
        kubectl('wait', '-n', 'kube-system', 'deployment/coredns', '--for=create', '--timeout=90s')
        kubectl('rollout', 'status', '-n', 'kube-system', 'deployment/coredns',
                '--timeout=240s', timeout=255)

    def network():
        expected = {item['metadata']['uid'] for item in json.loads(
            kubectl('get', 'pod/md-http', 'pod/md-client', '-o', 'json'))['items']}
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            assert server.poll() is None, f'k3s server exited: {server.returncode}'
            result = kubectl('--request-timeout=5s', 'get',
                '--raw=/api/v1/nodes/md-k3s-lab/proxy/pods', check=False, timeout=15)
            if result.returncode == 0:
                observed = {item['metadata']['uid'] for item in (json.loads(result.stdout)['items'] or [])}
                if expected <= observed:
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
        answer = kubectl('exec', 'md-client', '--', 'nslookup', 'kubernetes.default.svc.cluster.local')
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
                                                   'exec httpd -f -p 8080 -h /www'],
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
            server = start(log)
            ready()
            report['checks'].append('node-and-coredns-ready')
            kubectl('run', 'md-http', '--image=docker.io/library/busybox:1.37',
                '--restart=Never', '--port=8080', '--command', '--', 'sh', '-ec',
                'mkdir /www; echo SHROOT_K3S_SERVICE > /www/index.html; exec httpd -f -p 8080 -h /www')
            kubectl('run', 'md-client', '--image=docker.io/library/busybox:1.37',
                '--restart=Never', '--command', '--', 'sleep', '1800')
            for name in ('md-http', 'md-client'):
                kubectl('wait', 'pod/' + name, '--for=condition=Ready', '--timeout=120s', timeout=135)
            kubectl('expose', 'pod/md-http', '--port=80', '--target-port=8080')
            uid = kubectl('get', 'pod/md-http', '-o', 'jsonpath={.metadata.uid}')
            address = kubectl('get', 'service/md-http', '-o', 'jsonpath={.spec.clusterIP}')
            network()
            report['checks'].append('cross-pod-dns-and-service')
            policies()
            workload()
            stop()
            server = start(log)
            ready()
            assert kubectl('get', 'pod/md-http', '-o', 'jsonpath={.metadata.uid}') == uid
            assert kubectl('get', 'service/md-http', '-o', 'jsonpath={.spec.clusterIP}') == address
            for name in ('md-http', 'md-client'):
                kubectl('wait', 'pod/' + name, '--for=condition=Ready', '--timeout=120s', timeout=135)
            network()
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
                stop()
            except Exception as error:
                report.update(passed=False, shutdownError=repr(error))
                if server.poll() is None:
                    server.kill()
                    server.wait()
    print('SERVER_LOG', Path('/tmp/server.log').read_text(), flush=True)
    containerd = Path('/tmp/k3s-data/agent/containerd/containerd.log')
    if containerd.exists():
        print('CONTAINERD_LOG', containerd.read_text(), flush=True)
    print('K3S_REPORT', json.dumps(report), flush=True)
    return 0 if report['passed'] else 1


def main():
    from test_oci_services import Suite, add_machine_entry_argument
    from kernel_profile import KernelProfile
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('environment', help='Installed recipe environment with Python 3 and util-linux')
    parser.add_argument('k3s', type=Path, help='Verified upstream ARM64 k3s binary; hash recorded in receipt')
    parser.add_argument('--output', type=Path)
    add_machine_entry_argument(parser)
    KernelProfile.add_argument(parser)
    args = parser.parse_args()
    suite = Suite(args.output or args.build, args.build, machine_entry=args.machine_entry,
                  kernel_profile=args.kernel_profile)
    target = '/mnt/md-k3s-' + suite.report['id']
    store = None
    created = False
    rancher_created = False
    log = suite.output.with_suffix('.log')
    try:
        store = suite.installed_environment(args.environment)['store']
        with args.k3s.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        suite.report['k3s'] = {'sha256': digest, 'profile': 'single-node-host-gw'}
        suite.transport.upload(suite.client, args.k3s, suite.base + '/k3s')
        assert suite.command(['sha256sum', suite.base + '/k3s'], quiet=True).split()[0] == digest
        suite.transport.upload(suite.client, Path(__file__), suite.base + '/test.py')
        suite.command(['chmod', '700', suite.base + '/k3s'])
        prefix = suite.guest(store, '--user', '0:0')
        suite.command(prefix + ['--', '/bin/mkdir', target])
        created = True
        output = suite.command(prefix + ['--', '/bin/sh', '-ec',
            'if [ ! -d /etc/rancher ]; then mkdir /etc/rancher; echo CREATED_RANCHER; fi'])
        rancher_created = 'CREATED_RANCHER' in output.splitlines()
        script = '''
mount -t sysfs sysfs /sys
mount -t cgroup2 none /sys/fs/cgroup
mount -t tmpfs tmpfs /run
mount -t tmpfs tmpfs /tmp
mount -t tmpfs tmpfs /var
mkdir -p /var/lib/kubelet /var/lib/rancher /var/lib/cni /var/log
mount -t tmpfs tmpfs /etc/rancher
exec /usr/bin/python3 "$1/test.py" --guest "$1/k3s"
'''
        # All service state is private. The outer PID namespace retires shims and
        # workload descendants even if stock k3s deliberately leaves them running.
        command = prefix + ['--statistics', '--network', 'routed',
            '--env', 'PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',
            '--bind-ro', suite.base, target, '--deadline-seconds', '900', '--',
            '/usr/bin/unshare', '-mpf', '--mount-proc', '/bin/sh', '-ec', script, 'k3s', target]
        try:
            suite.command(['sh', '-c', shlex.join(command) + ' > ' +
                           shlex.quote(suite.base + '/full.log') + ' 2>&1'], timeout=930)
        finally:
            suite.transport.download(suite.client, suite.base + '/full.log', log)
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
            if rancher_created:
                suite.command(suite.guest(store, '--user', '0:0') + ['--', '/bin/rmdir', '/etc/rancher'])
            if created:
                suite.command(suite.guest(store, '--user', '0:0') + ['--', '/bin/rmdir', target])
            suite.command(['rm', '-rf', suite.base])
        except Exception as error:
            suite.report.update(passed=False, cleanupFailure=repr(error))
            raise
        finally:
            suite.finish()


if __name__ == '__main__':
    sys.exit(guest(sys.argv[2]) if len(sys.argv) == 3 and sys.argv[1] == '--guest' else main())
