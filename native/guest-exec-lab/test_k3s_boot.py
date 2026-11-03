#!/usr/bin/env python3
"""Stock k3s service and persistent recovery across clean and crashed Shroot launches."""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import tempfile
import time

from kernel_profile import KernelProfile
from test_oci_services import Suite, Server, add_machine_entry_argument


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('environment')
    parser.add_argument('k3s', type=Path)
    parser.add_argument('installer', type=Path)
    parser.add_argument('--init', required=True, choices=('systemd', 'openrc'))
    parser.add_argument('--images', type=Path, action='append', default=[],
                        help='Verified uncompressed airgap image tar; repeat for workload images')
    parser.add_argument('--output', type=Path, default=Path('build/k3s-boot'))
    add_machine_entry_argument(parser)
    KernelProfile.add_argument(parser)
    args = parser.parse_args()
    suite = Suite(args.output, args.build, machine_entry=args.machine_entry, kernel_profile=args.kernel_profile)
    target = '/mnt/md-k3s-boot-' + suite.report['id']
    store = None
    created = False
    installed = False
    owner = None
    try:
        environment = suite.installed_environment(args.environment)
        assert not environment['launches'], environment['launches']
        store = environment['store']
        prefix = suite.guest(store, '--user', '0:0')
        suite.command(prefix + ['--', '/bin/mkdir', target])
        created = True
        prefix += ['--bind', suite.base, target]
        source = Path(__file__).parent
        inputs = {'k3s': args.k3s, 'install.sh': args.installer,
                  'k3s-boot.py': source / 'fixtures/k3s-boot.py', 'k3s_fixture.py': source / 'k3s_fixture.py',
                  'crash-launch': args.build / 'libmagicdesk_guest_test_launch_registry.so'}
        for index, archive in enumerate(args.images):
            inputs['images-' + str(index) + '.tar'] = archive
        suite.report['inputs'] = {}
        for name, path in inputs.items():
            with path.open('rb') as stream:
                digest = hashlib.file_digest(stream, 'sha256').hexdigest()
            suite.transport.upload(suite.client, path, suite.base + '/' + name)
            assert suite.command(['sha256sum', suite.base + '/' + name], quiet=True).split()[0] == digest
            suite.report['inputs'][name] = digest
        suite.command(['chmod', '700', suite.base + '/crash-launch'])
        suite.command(['/system/bin/sh', '-c', 'cd "$1"; exec ./crash-launch', 'registry', suite.base])
        with tempfile.TemporaryDirectory() as directory:
            helper = Path(directory) / 'magicdesk-init'
            helper.write_text(subprocess.check_output(['sh', str(source.parents[1] / 'scripts/install_linux.sh'),
                                                      '--print-init-session'], text=True))
            suite.transport.upload(suite.client, helper, suite.base + '/magicdesk-init')
            suite.command(['chmod', '700', suite.base + '/magicdesk-init'])
            installed = True
            suite.command(prefix + ['--', '/usr/bin/python3', target + '/k3s-boot.py', 'install', target, args.init])
            for phase in range(3):
                label = suite.report['id'] + '-' + str(phase)
                # Initial registry pulls are not recovery latency. Later boots
                # retain downloaded images and use the shorter deadline.
                limit = 1200 if phase == 0 else 600
                launch = prefix + ['--network', 'routed', '--statistics',
                    '--env', 'MAGICDESK_GUEST_LABEL=' + label, '--deadline-seconds', str(limit), '--',
                    '/usr/bin/script', '-q', '-e', '-c',
                    shlex.join([target + '/magicdesk-init', '/usr/bin/python3', target + '/k3s-boot.py',
                                'observe', target, args.init, str(phase)]), '/dev/null']
                owner = Server(suite, launch, timeout=limit + 30)
                try:
                    owner.ready('K3S_BOOT_RESULT ' + str(phase), timeout=limit - 30)
                    receipt = args.output / (suite.report['id'] + '-phase-' + str(phase) + '.json')
                    suite.transport.download(suite.client, suite.base + '/phase-' + str(phase) + '.json', receipt)
                    report = json.loads(receipt.read_text())
                    suite.report['cases'].append(report)
                    assert report['passed'], report
                    if phase == 1:
                        launches = [json.loads(line) for line in suite.command(
                            suite.image_command('launches', store), quiet=True).splitlines() if line]
                        active, = [l for l in launches if l['label'] == label]
                        suite.report['crashedLaunch'] = active
                        suite.command([suite.base + '/crash-launch', '--crash', store, active['launchId']])
                        owner.completed(expected=125, timeout=120)
                        assert 'Shroot: supervisor terminated signal=9' in ''.join(owner.log)
                    else:
                        # Both stock init systems end the logical PID namespace
                        # with POWER_OFF, reported to the parent as SIGINT.
                        owner.completed(expected=130, timeout=120)
                finally:
                    suite.record_entry(''.join(owner.log))
                    owner.close()
                    owner = None
                    name = 'phase-' + str(phase) + '.log'
                    if suite.command(['/system/bin/sh', '-c',
                            'if [ -f "$1" ]; then printf yes; fi', 'log', suite.base + '/' + name], quiet=True) == 'yes':
                        suite.transport.download(suite.client, suite.base + '/' + name,
                                                 args.output / (suite.report['id'] + '-' + name))
                deadline = time.monotonic() + 30
                while time.monotonic() < deadline:
                    if not suite.command(suite.image_command('launches', store), quiet=True).strip():
                        break
                    time.sleep(.1)  # Bounded guardian teardown, not a settling delay.
                else:
                    raise AssertionError('launch registry did not drain')
        suite.report['passed'] = True
    except Exception as error:
        suite.report.update(passed=False, failure=repr(error))
        raise
    finally:
        try:
            if owner is not None:
                owner.close()
            if installed:
                suite.command(prefix + ['--', '/usr/bin/python3', target + '/k3s-boot.py', 'cleanup', target, args.init], timeout=240)
            if created:
                suite.command(suite.guest(store, '--user', '0:0') + ['--', '/bin/rmdir', target])
            suite.command(['rm', '-rf', suite.base])
        except Exception as error:
            suite.report.update(passed=False, cleanupFailure=repr(error))
            raise
        finally:
            suite.finish()


if __name__ == '__main__':
    main()
