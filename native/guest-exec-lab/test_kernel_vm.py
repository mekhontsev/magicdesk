#!/usr/bin/env python3
"""Run a static filesystem contract under a real Linux kernel in QEMU TCG.

The disposable VM has no network or host filesystem attachment. Its one test
runs as UID 1000. This is a kernel oracle, not a Shroot compatibility result.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import uuid


def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def contract_archive(work, init, fixture):
    root = work / 'root'
    root.mkdir(parents=True)
    for name in ('proc', 'work', 'dev'):
        (root / name).mkdir()
    for source, name in ((init, 'init'), (fixture, 'contract')):
        shutil.copyfile(source, root / name)
        (root / name).chmod(0o755)
    archive = work / 'initramfs.cpio'
    with archive.open('wb') as output:
        subprocess.run(['cpio', '-o', '--format=newc', '--null', '--owner=0:0'],
                       cwd=root, input=b'proc\0work\0dev\0init\0contract\0', stdout=output, check=True)
    return archive


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('--qemu', default='qemu-system-aarch64')
    parser.add_argument('--kernel', type=Path, required=True)
    parser.add_argument('--fixture', type=Path, required=True)
    args = parser.parse_args()
    work = (args.build / ('kernel-vm-' + uuid.uuid4().hex)).resolve()
    report = {'passed': False, 'kernelOracleOnly': True}
    try:
        init = args.build / 'libmagicdesk_guest_vm_init.so'
        archive = contract_archive(work, init, args.fixture)
        report.update({'kernelSha256': sha256(args.kernel), 'fixtureSha256': sha256(args.fixture),
                       'initSha256': sha256(init)})
        command = [args.qemu, '-machine', 'virt', '-cpu', 'cortex-a72',
                   '-accel', 'tcg,thread=multi', '-smp', '2', '-m', '512',
                   '-nographic', '-monitor', 'none', '-nic', 'none', '-no-reboot',
                   '-kernel', str(args.kernel.resolve()), '-initrd', str(archive),
                   '-append', 'console=ttyAMA0 rdinit=/init magicdesk.kernel_fixture=1']
        report['command'] = command
        # EVENT_WAIT: VM powers off after its exact test child; timeout kills QEMU.
        with (work / 'serial.log').open('wb') as log:
            result = subprocess.run(command, stdin=subprocess.DEVNULL, stdout=log,
                                    stderr=subprocess.STDOUT, timeout=180)
        output = (work / 'serial.log').read_text(errors='replace')
        print(output, end='', flush=True)
        assert result.returncode == 0, result.returncode
        assert 'PASS kernel contract in VM status=0' in output, output
        report['passed'] = True
    except Exception as error:
        report['failure'] = repr(error)
        raise
    finally:
        work.mkdir(parents=True, exist_ok=True)
        (work / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        print('Report:', work / 'report.json', flush=True)


if __name__ == '__main__':
    main()
