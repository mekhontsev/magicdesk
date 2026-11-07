#!/usr/bin/env python3
"""Kernel-compatible binary-format execution in a recipe-installed guest."""
import argparse
from pathlib import Path

from test_oci_services import Suite

SYSTEMD = r'''
import os, pathlib, py_compile, subprocess, tempfile
def run(*args):
    result = subprocess.run(args, text=True, capture_output=True, timeout=20)
    assert result.returncode == 0, (args, result.returncode, result.stdout, result.stderr)
    return result.stdout
root = pathlib.Path('/proc/sys/fs/binfmt_misc')
# Match the proc mount prepared by a guest OS boot, not the host fallback.
run('mount', '-t', 'proc', 'proc', '/proc')
run('mount', '-t', 'binfmt_misc', 'binfmt_misc', str(root))
try:
    run('/usr/lib/systemd/systemd-binfmt')
    entries = list(root.glob('python*'))
    assert entries, list(root.iterdir())
    with tempfile.TemporaryDirectory(prefix='md-binfmt-systemd-') as directory:
        source = pathlib.Path(directory) / 'program.py'
        binary = pathlib.Path(directory) / 'program.pyc'
        source.write_text('print("BINFMT_SYSTEMD_PYTHON_OK")\n')
        py_compile.compile(str(source), cfile=str(binary), doraise=True)
        binary.chmod(0o755)
        assert run(str(binary)).strip() == 'BINFMT_SYSTEMD_PYTHON_OK'
    run('/usr/lib/systemd/systemd-binfmt', '--unregister')
    assert not list(root.glob('python*'))
finally:
    run('umount', str(root))
    run('umount', '/proc')
print('PASS stock systemd-binfmt registration, native Python bytecode execution and unregister')
'''

QEMU = r'''
import pathlib, shutil, subprocess, sys, tempfile
def run(*args):
    result = subprocess.run(args, text=True, capture_output=True, timeout=30)
    assert result.returncode == 0, (args, result.returncode, result.stdout, result.stderr)
    return result.stdout
program = pathlib.Path(sys.argv[1])
magic = ''.join('\\x%02x' % byte for byte in program.read_bytes()[:20])
with tempfile.TemporaryDirectory(prefix='md-binfmt-qemu-') as directory:
    root = pathlib.Path(directory)
    interpreter = root / 'qemu'
    source = shutil.which('qemu-x86_64')
    assert source
    shutil.copy2(source, interpreter)
    mount = root / 'rules'
    mount.mkdir()
    run('mount', '-t', 'binfmt_misc', 'binfmt_misc', str(mount))
    try:
        assert run(str(interpreter), str(program)).strip() == 'BINFMT_QEMU_X86_64_OK'
        (mount / 'register').write_text(':qemu-test:M::' + magic + '::' + str(interpreter) + ':OCF')
        interpreter.unlink()
        assert run(str(program)).strip() == 'BINFMT_QEMU_X86_64_OK'
        (mount / 'qemu-test').write_text('-1')
    finally:
        run('umount', str(mount))
print('PASS foreign x86-64 ELF through packaged QEMU, fixed interpreter and original execfd')
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build', type=Path, required=True)
    parser.add_argument('--name', default='alpine')
    parser.add_argument('--systemd', action='store_true')
    parser.add_argument('--qemu', type=Path, help='x86-64 ELF fixture, built from fixtures/binfmt-x86_64.S; requires guest qemu-x86_64')
    args = parser.parse_args()
    suite = Suite(args.build, args.build)
    target = '/opt/md-binfmt-fixture'
    try:
        info = suite.installed_environment(args.name)
        suite.report['environment'] = info
        for name in ('test_binfmt_rule', 'binfmt_guest'):
            binary = 'libmagicdesk_guest_' + name + '.so'
            suite.upload(args.build / binary, suite.base + '/' + binary)
            suite.chmod('755', suite.base + '/' + binary)
        suite.command([suite.base + '/libmagicdesk_guest_test_binfmt_rule.so'])
        if args.qemu:
            suite.upload(args.qemu, suite.base + '/foreign-elf')
            suite.chmod('755', suite.base + '/foreign-elf')
        suite.command(suite.guest(info['store'], '--user', '0:0', '--', '/bin/mkdir', '-p', target))
        for mode in (0, 1):
            output = suite.command(suite.guest(info['store'], '--user', '0:0',
                '--env', 'SHROOT_MACHINE_ENTRY=' + str(mode), '--bind-ro', suite.base, target,
                '--deadline-seconds', '60', '--', target + '/libmagicdesk_guest_binfmt_guest.so'), timeout=90)
            assert 'PASS binfmt_misc' in output
            if args.systemd:
                suite.command(suite.guest(info['store'], '--user', '0:0',
                    '--env', 'SHROOT_MACHINE_ENTRY=' + str(mode), '--deadline-seconds', '90',
                    '--', '/usr/bin/python3', '-c', SYSTEMD), timeout=100)
            if args.qemu:
                suite.command(suite.guest(info['store'], '--user', '0:0',
                    '--env', 'SHROOT_MACHINE_ENTRY=' + str(mode), '--bind-ro', suite.base, target,
                    '--deadline-seconds', '90', '--', '/usr/bin/python3', '-c', QEMU,
                    target + '/foreign-elf'), timeout=100)
        suite.command(suite.guest(info['store'], '--user', '0:0', '--', '/bin/rmdir', target))
        suite.report['passed'] = True
    finally:
        suite.finish()


if __name__ == '__main__':
    main()
