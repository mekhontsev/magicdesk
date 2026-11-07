#!/usr/bin/env python3
"""Stock runc with its generated OCI configuration, inside either executor."""
import argparse
import ctypes
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('k3s', type=Path)
    parser.add_argument('rootfs', type=Path)
    parser.add_argument('--split', action='store_true', help='Separate create/start lifecycle used by containerd')
    parser.add_argument('--seccomp', action='store_true', help='Install a guest filter before the init FIFO wait')
    parser.add_argument('--uid', type=int, default=0, help='Container process UID and GID')
    parser.add_argument('--seccomp-from', type=Path, help='Use the seccomp profile from an OCI configuration')
    parser.add_argument('--protected-observer', action='store_true', help='Keep the runtime parent non-dumpable')
    args = parser.parse_args()
    if args.protected_observer:
        assert ctypes.CDLL(None).prctl(4, 0, 0, 0, 0) == 0
    with tempfile.TemporaryDirectory(prefix='runc-contract-') as temporary:
        base = Path(temporary)
        env = dict(os.environ, K3S_DATA_DIR=str(base / 'data'))
        subprocess.run([str(args.k3s), 'kubectl', 'version', '--client'],
                       env=env, check=True, timeout=60)
        runtime = str(base / 'data/data/current/bin/runc')
        assert Path(runtime).is_file(), runtime
        subprocess.run([runtime, '--version'], check=True, timeout=15)
        bundle = base / 'bundle'
        rootfs = bundle / 'rootfs'
        rootfs.mkdir(parents=True)
        subprocess.run(['tar', '-xzf', str(args.rootfs), '-C', str(rootfs)],
                       check=True, timeout=30)
        subprocess.run([runtime, 'spec'], cwd=bundle, check=True, timeout=15)
        path = bundle / 'config.json'
        spec = json.loads(path.read_text())
        spec['process']['terminal'] = False
        spec['process']['user'] = {'uid': args.uid, 'gid': args.uid}
        spec['process']['args'] = ['/bin/sh', '-ec',
            f'test "$(id -u)" = {args.uid}; test "$$" = 1; '
            'test -r /proc/self/status; test -c /dev/null; '
            'printf "PASS stock OCI container\\n"']
        if args.seccomp:
            spec['linux']['seccomp'] = {'defaultAction': 'SCMP_ACT_ALLOW',
                'architectures': ['SCMP_ARCH_AARCH64'],
                'syscalls': [{'names': ['getpgid'], 'action': 'SCMP_ACT_ERRNO', 'errnoRet': 1}]}
        if args.seccomp_from:
            spec['linux']['seccomp'] = json.loads(args.seccomp_from.read_text())['linux']['seccomp']
        path.write_text(json.dumps(spec))
        command = [runtime, '--debug', '--log', str(base / 'runc.log'),
                   '--root', str(base / 'state')]
        try:
            if args.split:
                output = base / 'container.log'
                with output.open('w') as stream:
                    subprocess.run([*command, 'create', 'md-contract'], cwd=bundle,
                                   stdout=stream, stderr=stream, check=True, timeout=45)
                print('RUNC_CREATED', flush=True)
                saved = json.loads((base / 'state/md-contract/state.json').read_text())
                pid = saved['init_process_pid']
                print('RUNC_SAVED', pid, saved['init_process_start'], flush=True)
                with open(f'/proc/{pid}/stat') as stream:
                    observed = stream.read()
                print('RUNC_PROC_STAT', observed, flush=True)
                assert int(observed.rsplit(')', 1)[1].split()[19]) == saved['init_process_start']
                result = subprocess.run([*command, 'state', 'md-contract'], cwd=bundle,
                                        check=True, capture_output=True, text=True, timeout=10)
                print('RUNC_STATE', result.stdout, flush=True)
                assert json.loads(result.stdout)['status'] == 'created', result.stdout
                subprocess.run([*command, 'start', 'md-contract'], cwd=bundle, check=True, timeout=45)
                deadline = time.monotonic() + 15
                while True:
                    result = subprocess.run([*command, 'state', 'md-contract'], cwd=bundle,
                                            check=True, capture_output=True, text=True, timeout=10)
                    if json.loads(result.stdout)['status'] == 'stopped':
                        break
                    assert time.monotonic() < deadline, result.stdout
                    # Bounded observation of the container process, not a settling delay.
                    time.sleep(0.05)
                print('RUNC_OUTPUT', output.read_text(), flush=True)
                assert 'PASS stock OCI container' in output.read_text()
            else:
                result = subprocess.run([*command, 'run', 'md-contract'], cwd=bundle,
                                        text=True, capture_output=True, timeout=45)
                print('RUNC_RESULT', result.returncode, result.stdout, result.stderr, flush=True)
                assert result.returncode == 0 and 'PASS stock OCI container' in result.stdout
        finally:
            subprocess.run([*command, 'delete', '--force', 'md-contract'],
                           cwd=bundle, timeout=30, capture_output=True)
            log = base / 'runc.log'
            if log.exists():
                print('RUNC_LOG', log.read_text(), flush=True)


if __name__ == '__main__':
    main()
