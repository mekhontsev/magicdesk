#!/usr/bin/env python3
"""Prepare a dedicated OCI/CNI worker under the booted distribution's init."""
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tarfile
import threading
import time


def main():
    init, archive, report_path = sys.argv[1:]
    report = dict(passed=False, commands=[])

    def run(*args, required=True):
        result = subprocess.run(args, text=True, capture_output=True, timeout=60)
        report['commands'].append(dict(argv=args, code=result.returncode,
                                       stdout=result.stdout, stderr=result.stderr))
        if required:
            assert result.returncode == 0, report['commands'][-1]
        return result

    try:
        if archive != '-':
            with tarfile.open(archive) as source:
                source.extractall('/usr/local', filter='data')
        daemon = shutil.which('buildkitd')
        assert daemon and shutil.which('buildctl'), 'BuildKit must be installed or supplied as an official archive'
        base = Path(__file__).parent
        candidates = [Path('/usr/lib/cni'), Path('/usr/libexec/cni'), Path('/opt/cni/bin')]
        host_local = shutil.which('host-local')
        if host_local:
            candidates.append(Path(host_local).resolve().parent)
        plugins = ('bridge', 'host-local', 'loopback')
        cni = next((p for p in candidates if all((p / name).is_file() for name in plugins)), None)
        assert cni is not None, 'Missing packaged CNI bridge, host-local and loopback plugins'
        address = 'unix:///run/md-fixture-buildkit.sock'
        (base / 'buildkit.toml').write_text('''root = "/var/lib/md-fixture-buildkit"
[grpc]
  address = ["''' + address + '''"]
[worker.oci]
  enabled = true
  networkMode = "cni"
  cniConfigPath = "''' + str(base / 'buildkit.conflist') + '''"
  cniBinaryPath = "''' + str(cni) + '''"
[worker.containerd]
  enabled = false
''')
        (base / 'buildkit.conflist').write_text(json.dumps(dict(cniVersion='1.0.0', name='md-buildkit', plugins=[
            dict(type='bridge', bridge='md-buildkit0', isGateway=True, ipMasq=True,
                 ipam=dict(type='host-local', ranges=[[dict(subnet='10.231.245.0/24')]],
                           routes=[dict(dst='0.0.0.0/0')])), dict(type='loopback')])) + '\n')
        command = [daemon, '--config', str(base / 'buildkit.toml')]
        if init == 'systemd':
            run('systemd-run', '--unit=md-fixture-buildkit', '--collect',
                '--property=Delegate=yes', '--setenv=PATH=' + os.environ['PATH'], *command)
        elif init == 'runit':
            service = Path('/run/md-runit-services/md-fixture-buildkit')
            service.mkdir()
            script = service / 'run'
            script.write_text('#!/bin/sh\nexec ' + shlex.join(command) + '\n')
            script.chmod(0o755)
            Path('/etc/runit/runsvdir/default/md-fixture-buildkit').symlink_to(service)
        elif init == 'openrc':
            script = Path('/etc/init.d/md-fixture-buildkit')
            script.write_text('#!/sbin/openrc-run\ncommand=' + shlex.quote(daemon) + '\n'
                              'command_args=' + shlex.quote(shlex.join(command[1:])) + '\n'
                              'command_background=yes\npidfile=/run/md-fixture-buildkit.pid\n'
                              'output_log=' + shlex.quote(str(base / 'buildkit-daemon.log')) + '\n'
                              'error_log=' + shlex.quote(str(base / 'buildkit-daemon.log')) + '\n')
            script.chmod(0o755)
            run('rc-service', 'md-fixture-buildkit', 'start')
        else:
            raise ValueError('Unsupported init: ' + init)
        deadline = time.monotonic() + 30
        while True:
            ready = run('buildctl', '--addr', address, 'debug', 'workers', required=False)
            if ready.returncode == 0:
                break
            assert time.monotonic() < deadline, report['commands'][-1]
            # PROTOCOL_BACKOFF: only the selected worker's response is success;
            # the deadline fails startup, including runit discovery of its link.
            threading.Event().wait(0.1)
        report['passed'] = True
    finally:
        Path(report_path).write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
