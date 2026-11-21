#!/usr/bin/env python3
"""Stock init-managed k3s: persistent identity and workload across whole launches."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import traceback

from k3s_fixture import Kubectl, command, kubelet_pods, network_diagnostics, pid1_command, prepare_images, wait_node_ready
from systemd_ready import wait_systemd_unit


def ready_containers(pod, previous):
    statuses = pod.get('status', {}).get('containerStatuses') or []
    if not statuses or not all(c.get('ready') and c.get('state', {}).get('running')
                               and c.get('containerID') for c in statuses):
        return []
    current = [c['containerID'] for c in statuses]
    return current if not set(current).intersection(previous) else []


def backup_location(name, identity, index):
    # Keep service/runlevel backups outside init's discovery directories, on
    # the same /etc filesystem. Data trees retain same-directory atomic moves.
    path = Path(name)
    if path.is_relative_to('/etc'):
        return Path('/etc') / ('.' + identity + '-backups') / str(index)
    return path.with_name(path.name + '.md-' + identity)


def install(root, init, provider='upstream'):
    owned = ['/etc/rancher/k3s', '/etc/rancher/node',
             '/var/lib/rancher/k3s', '/var/log/k3s.log']
    if provider == 'upstream':
        owned += ['/usr/local/bin/k3s', '/usr/local/bin/k3s-killall.sh',
                  '/usr/local/bin/k3s-uninstall.sh']
        owned += (['/etc/systemd/system/k3s.service', '/etc/systemd/system/k3s.service.env',
               '/etc/systemd/system/multi-user.target.wants/k3s.service'] if init == 'systemd' else
              ['/etc/init.d/k3s', '/etc/logrotate.d/k3s', '/etc/runlevels/default/k3s'])
    assert not any(os.path.lexists(p) for p in owned), 'An existing k3s installation must not be replaced'
    if provider == 'upstream':
        owned += [p for p in ('/usr/local/bin/kubectl', '/usr/local/bin/crictl', '/usr/local/bin/ctr')
                  if not os.path.lexists(p)]
    parents = [p for p in ('/etc/rancher', '/var/lib/rancher') if not Path(p).exists()]
    backups = {}
    backup_directories = set()
    for name in ('/var/lib/kubelet', '/var/lib/cni', '/var/log/pods', '/var/log/containers',
                 '/etc/init.d/magicdesk-session', '/etc/runlevels/default/magicdesk-session'):
        saved = str(backup_location(name, root.name, len(backups)))
        assert not os.path.lexists(saved)
        backups[name] = saved if os.path.lexists(name) else None
        if backups[name] and Path(saved).parent != Path(name).parent:
            backup_directories.add(str(Path(saved).parent))
    assert not any(os.path.lexists(path) for path in backup_directories)
    manifest = dict(owned=owned, parents=parents, backups=backups, provider=provider,
                    backupDirectories=sorted(backup_directories))
    (root / 'installation.json').write_text(json.dumps(manifest))
    for path in backup_directories:
        Path(path).mkdir()
    for name, saved in backups.items():
        if saved:
            os.rename(name, saved)
    for parent in parents:
        Path(parent).mkdir(parents=True)
    if provider == 'upstream':
        shutil.copyfile(root / 'k3s', '/usr/local/bin/k3s')
        os.chmod('/usr/local/bin/k3s', 0o755)
    prepare_images(root, '/var/lib/rancher/k3s')


def cleanup(root):
    path = root / 'installation.json'
    if not path.exists():
        return
    data = json.loads(path.read_text())
    for name in [*data['owned'], *data['backups']]:
        saved = data['backups'].get(name)
        if saved and not os.path.lexists(saved):
            continue  # Interrupted preparation did not move this original yet.
        path = Path(name)
        if path.is_dir() and not path.is_symlink():
            shutil.rmtree(path)
        else:
            path.unlink(missing_ok=True)
    for name, saved in data['backups'].items():
        if saved and os.path.lexists(saved):
            os.rename(saved, name)
    for name in data.get('backupDirectories', []):
        try:
            Path(name).rmdir()
        except FileNotFoundError:
            pass
    for name in reversed(data['parents']):
        try:
            Path(name).rmdir()
        except FileNotFoundError:
            pass


def poweroff(init):
    command(*(['systemctl', 'poweroff', '--no-block'] if init == 'systemd' else
              ['openrc-shutdown', '--poweroff', 'now']))


def cleanup_boot(root, init):
    passed = False
    try:
        if (root / 'installation.json').exists():
            service = '/etc/systemd/system/k3s.service' if init == 'systemd' else '/etc/init.d/k3s'
            if Path(service).exists():
                command(*(['systemctl', 'stop', 'k3s'] if init == 'systemd' else
                          ['rc-service', 'k3s', 'stop']))
            killall = Path('/usr/local/bin/k3s-killall.sh')
            if killall.exists():
                command('/bin/sh', str(killall))
            cleanup(root)
        passed = True
    except Exception:
        traceback.print_exc()
    finally:
        with open('/dev/console', 'w') as console:
            print('K3S_BOOT_CLEANUP', passed, file=console, flush=True)
        poweroff(init)


def observe(root, init, phase, started, provider='upstream'):
    report = dict(phase=phase, startedAfter=started, passed=False, checks=[])
    executable = shutil.which('k3s') if provider == 'nixos' else '/usr/local/bin/k3s'
    kubectl = Kubectl(executable)
    try:
        assert executable, 'k3s is absent from the activated console PATH: ' + os.environ.get('PATH', '')
        assert os.isatty(0) and os.tcgetpgrp(0) == os.getpgrp(), 'console lacks foreground PTY ownership'
        report['checks'].append('foreground-console-pty')
        if phase == 0 and provider == 'upstream':
            install(root, init)
            env = dict(os.environ, INSTALL_K3S_SKIP_DOWNLOAD='true')
            subprocess.run(['/bin/sh', str(root / 'install.sh'), 'server'],
                           env=env, check=True, timeout=180)
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            answer = kubectl('--request-timeout=5s', 'get', '--raw=/readyz', check=False, timeout=15)
            if answer.returncode == 0 and answer.stdout.strip() == 'ok':
                break
            time.sleep(1)  # Bounded API readiness, not a presumed boot delay.
        else:
            raise AssertionError('API readiness timeout')
        kubectl('wait', 'nodes', '--all', '--for=create', '--timeout=90s')
        nodes = json.loads(kubectl('get', 'nodes', '-o', 'json'))['items']
        assert len(nodes) == 1, nodes
        node = nodes[0]
        # The launcher captured this boundary before starting the new init.
        # Persisted Ready from the previous boot cannot establish readiness.
        wait_node_ready(kubectl, node['metadata']['name'], started)
        report['checks'].append('fresh-node-ready')
        kubectl('wait', '-n', 'kube-system', 'deployment/coredns', '--for=create', '--timeout=90s')
        pull_limit = 600 if phase == 0 else 240
        kubectl('rollout', 'status', '-n', 'kube-system', 'deployment/coredns',
                '--timeout=' + str(pull_limit) + 's', timeout=pull_limit + 15)
        if init == 'systemd':
            command('systemctl', 'is-enabled', 'k3s')
            # API readiness can precede the service's READY=1 notification.
            # Observe the stock start; never restart it to satisfy this check.
            wait_systemd_unit(lambda timeout: subprocess.run(
                ['systemctl', 'is-active', 'k3s'], text=True, capture_output=True, timeout=timeout))
        else:
            command('rc-service', 'k3s', 'status')
            assert Path('/etc/runlevels/default/k3s').is_symlink()
        report['checks'].append('stock-enabled-service')
        kubectl('wait', '-n', 'kube-system', 'deployment/local-path-provisioner',
                '--for=create', '--timeout=90s')
        kubectl('rollout', 'status', '-n', 'kube-system', 'deployment/local-path-provisioner',
                '--timeout=' + str(pull_limit) + 's', timeout=pull_limit + 15)
        if phase == 0:
            resources = [dict(apiVersion='v1', kind='PersistentVolumeClaim', metadata=dict(name='md-boot'),
                spec=dict(accessModes=['ReadWriteOnce'], storageClassName='local-path',
                          resources=dict(requests=dict(storage='16Mi')))),
                dict(apiVersion='v1', kind='Pod', metadata=dict(name='md-boot', labels=dict(app='md-boot')),
                spec=dict(containers=[dict(name='http', image='docker.io/library/busybox:1.37',
                    command=['sh', '-ec', 'mkdir -p /www; echo MD_BOOT_SERVICE > /www/index.html; '
                             + pid1_command('httpd', '-f', '-p', '8080', '-h', '/www')],
                    readinessProbe=dict(httpGet=dict(path='/', port=8080)),
                    volumeMounts=[dict(name='data', mountPath='/data')])],
                    volumes=[dict(name='data', persistentVolumeClaim=dict(claimName='md-boot'))])),
                dict(apiVersion='v1', kind='Service', metadata=dict(name='md-boot'),
                     spec=dict(selector=dict(app='md-boot'), ports=[dict(port=80, targetPort=8080)]))]
            manifest = root / 'workload.json'
            manifest.write_text(json.dumps(dict(apiVersion='v1', kind='List', items=resources)))
            kubectl('apply', '-f', str(manifest))
        kubectl('wait', 'pod/md-boot', '--for=condition=Ready', '--timeout=180s', timeout=195)
        # API pod status survives a node restart. Wait for this kubelet to own
        # the pod before exec, rather than retrying a mutating workload action.
        uid = kubectl('get', 'pod/md-boot', '-o', 'jsonpath={.metadata.uid}')
        previous = (json.loads((root / ('phase-' + str(phase - 1) + '.json')).read_text())['containers']
                    if phase else [])
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            pod = kubelet_pods(kubectl, node['metadata']['name']).get(uid, {})
            containers = ready_containers(pod, previous)
            if containers:
                break
            time.sleep(1)  # Bounded kubelet inventory reconciliation.
        else:
            raise AssertionError('kubelet did not recover the persisted pod')
        identity = dict(node=node['metadata']['uid'], pod=uid,
            namespace=kubectl('get', 'namespace/default', '-o', 'jsonpath={.metadata.uid}'),
            volume=kubectl('get', 'pvc/md-boot', '-o', 'jsonpath={.spec.volumeName}'),
            service=kubectl('get', 'service/md-boot', '-o', 'jsonpath={.spec.clusterIP}'))
        for name in ('server/token', 'server/tls/server-ca.crt'):
            identity[name] = hashlib.sha256((Path('/var/lib/rancher/k3s') / name).read_bytes()).hexdigest()
        saved = root / 'identity.json'
        if phase == 0:
            saved.write_text(json.dumps(identity))
        else:
            assert identity == json.loads(saved.read_text()), (identity, saved.read_text())
            value = kubectl('exec', 'md-boot', '--', 'cat', '/data/value').strip()
            assert value == 'DURABLE_' + str(phase - 1), value
            report['checks'].append('identity-and-pvc-recovered')
        kubectl('exec', 'md-boot', '--', 'sh', '-ec',
                'printf %s DURABLE_' + str(phase) + ' > /data/value; sync')
        answer = kubectl('exec', 'md-boot', '--', 'nslookup', 'kubernetes.default.svc.cluster.local')
        assert '10.43.0.1' in answer, answer
        answer = kubectl('exec', 'md-boot', '--', 'wget', '-T', '15', '-qO-',
                         'http://md-boot.default.svc.cluster.local')
        assert answer.strip() == 'MD_BOOT_SERVICE', answer
        report['checks'].append('pod-dns-service-and-synced-write')
        if init == 'systemd':
            assert command('systemctl', 'show', 'k3s', '-p', 'NRestarts', '--value').strip() == '0'
        report.update(passed=True, identity=identity, containers=containers)
    except Exception as error:
        report['error'] = repr(error)
        traceback.print_exc()
        network_diagnostics()
        try:
            if init == 'systemd':
                command('systemctl', 'show', 'k3s', '-p', 'LoadState', '-p', 'ActiveState',
                        '-p', 'UnitFileState', '-p', 'FragmentPath', '-p', 'Result')
                command('systemctl', 'list-jobs', '--no-pager')
                command('journalctl', '-b', '-u', 'k3s', '--no-pager', '-n', '200')
            else:
                print(Path('/var/log/k3s.log').read_text()[-65536:], flush=True)
            path = Path('/var/lib/rancher/k3s/agent/containerd/containerd.log')
            if path.exists():
                print(path.read_text()[-65536:], flush=True)
        except Exception as error:
            print('DIAGNOSTICS', repr(error), flush=True)
        for args in [('get', 'pods', '-A', '-o', 'wide'), ('get', 'events', '-A')]:
            try:
                kubectl('--request-timeout=5s', *args, check=False, timeout=15)
            except Exception:
                pass
        try:
            pods = json.loads(kubectl('--request-timeout=5s', 'get', 'pods', '-A', '-o=json', timeout=15))['items']
            for pod in pods:
                for container in pod.get('status', {}).get('containerStatuses', []):
                    if container.get('ready') and not container.get('restartCount'):
                        continue
                    metadata = pod['metadata']
                    args = ['--request-timeout=5s', 'logs', '-n', metadata['namespace'], metadata['name'],
                            '-c', container['name'], '--tail=60']
                    kubectl(*args, check=False, timeout=15)
                    if container.get('restartCount'):
                        kubectl(*args, '--previous', check=False, timeout=15)
        except Exception as error:
            print('POD_DIAGNOSTICS', repr(error), flush=True)
    (root / ('phase-' + str(phase) + '.json')).write_text(json.dumps(report))
    with open('/dev/console', 'w') as console:
        print('K3S_BOOT_RESULT', phase, report['passed'], file=console, flush=True)
    if report['passed'] and phase == 1:
        # EVENT_WAIT: host kills the exact registered supervisor after this receipt.
        import signal
        signal.alarm(90)
        while True:
            signal.pause()
    poweroff(init)


if __name__ == '__main__':
    mode, directory, init, *arguments = sys.argv[1:]
    root = Path(directory)
    if mode == 'cleanup':
        cleanup_boot(root, init)
    elif mode == 'observe':
        log = open(root / ('phase-' + arguments[0] + '.log'), 'w')
        os.dup2(log.fileno(), 1)
        os.dup2(log.fileno(), 2)
        observe(root, init, int(arguments[0]), float(arguments[1]),
                arguments[2] if len(arguments) > 2 else 'upstream')
    elif mode == 'prepare':
        install(root, init, arguments[0])
    else:
        raise ValueError(mode)
