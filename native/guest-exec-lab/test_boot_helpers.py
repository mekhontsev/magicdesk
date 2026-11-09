#!/usr/bin/env python3
"""Host checks for shared nested-system readiness and TCP/UDP fixtures."""
from pathlib import Path
import io
import errno
import json
import os
import runpy
import select
import signal
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock, call, patch
import urllib.error

from init_fixture import installed_boot, start_service, enable_runit_service, RUNIT_START, GUEST_PATH
from installed_desktop import attach_window, keep_display_awake, stop_owned_resources, wait_controller
from test_os_boot import check_runit_boot, check_systemd_boot
from test_nixos_boot import boot_script
from test_snap import PREPARE as SNAP_PREPARE
from test_booted_libvirt import prepare_libvirt
import test_booted_nspawn
import test_booted_containers

sys.path.insert(0, str(Path(__file__).with_name('fixtures')))
from network_exchange import exchange, inbound_port
from systemd_ready import wait_systemd, wait_systemd_unit


class BootHelpers(unittest.TestCase):
    def test_container_outbound_reconnects_and_never_retries_failure(self):
        from docker_network_fixture import http_exchange
        response = Mock(status=200)
        response.read.return_value = b'token'
        context = Mock()
        context.__enter__ = Mock(return_value=response)
        context.__exit__ = Mock(return_value=False)
        url = 'http://127.0.0.1:1234/token/outbound'
        with patch('docker_network_fixture.urllib.request.urlopen', return_value=context) as connect, \
                patch('sys.stdout', new=io.StringIO()):
            http_exchange(url, repeats=3)
        self.assertEqual([call(url, timeout=5)] * 3, connect.call_args_list)
        self.assertEqual(3, context.__exit__.call_count)
        error = TimeoutError('connect')
        with patch('docker_network_fixture.urllib.request.urlopen', side_effect=[context, error]) as connect:
            with self.assertRaisesRegex(AssertionError, 'connection 2/3 failed') as caught:
                http_exchange(url, repeats=3)
        self.assertIs(error, caught.exception.__cause__)
        self.assertEqual(2, connect.call_count)

    def test_published_controller_opens_only_its_owned_port(self):
        for init in ('systemd', 'openrc', 'runit'):
            with self.subTest(init=init):
                local, _ = installed_boot('/fixture', 12345, 'token', init)
                public, _ = installed_boot('/fixture', 12345, 'token', init,
                                           control_address='0.0.0.0')
                self.assertNotIn('iptables -w', local)
                self.assertIn('iptables -w 5 -I INPUT -i eth0 -p tcp --dport 12345', public)
                self.assertNotIn('PREROUTING', public)
                self.assertNotIn('iptables -F', public)

    def test_libvirt_default_image_uses_the_shared_vm_contract(self):
        import test_booted_libvirt
        archive = Path('/test/contract.cpio')
        with patch('sys.argv', ['test_booted_libvirt.py', '--session', '/test/session.json',
                               '--kernel', '/test/kernel', '--build', '/test/build']), \
                patch('test_kernel_vm.contract_archive', return_value=archive) as create, \
                patch('test_booted_libvirt.run_fixture') as run:
            test_booted_libvirt.main()
        self.assertEqual((Path('/test/build/libmagicdesk_guest_vm_init.so'),
                          Path('/test/build/libmagicdesk_guest_landlock_guest.so')),
                         create.call_args.args[1:])
        self.assertEqual([Path('/test/kernel'), archive], run.call_args.args[2])

    def test_vm_contract_archive_contains_only_owned_fixture_files(self):
        from test_kernel_vm import contract_archive
        with tempfile.TemporaryDirectory() as directory:
            parent = Path(directory)
            init, contract = parent / 'vm-init', parent / 'fixture'
            init.write_bytes(b'init')
            contract.write_bytes(b'contract')
            work = parent / 'vm'
            with patch('test_kernel_vm.subprocess.run') as cpio:
                archive = contract_archive(work, init, contract)
            self.assertEqual(work / 'initramfs.cpio', archive)
            self.assertEqual({'proc', 'work', 'dev', 'init', 'contract'},
                             {entry.name for entry in (work / 'root').iterdir()})
            self.assertEqual(b'contract', (work / 'root/contract').read_bytes())
            self.assertEqual(0o755, (work / 'root/init').stat().st_mode & 0o777)
            self.assertEqual(b'proc\0work\0dev\0init\0contract\0', cpio.call_args.kwargs['input'])
            self.assertTrue(cpio.call_args.kwargs['check'])

    def test_service_ports_reserve_both_protocols_outside_ephemeral_range(self):
        from docker_network_fixture import Observer
        observer = Observer.__new__(Observer)
        observer.reservations = []
        try:
            ports = [observer.allocate_port() for _ in range(3)]
            self.assertEqual(3, len(set(ports)))
            self.assertTrue(all(1024 <= port < 32768 for port in ports))
            for index, port in enumerate(ports):
                self.assertEqual([port, port], [s.getsockname()[1] for s in
                    observer.reservations[index * 2:index * 2 + 2]])
        finally:
            for connection in observer.reservations:
                connection.close()

    def test_service_port_allocation_retries_collisions_only(self):
        from docker_network_fixture import Observer
        for error in (errno.EADDRINUSE, errno.EACCES):
            with self.subTest(error=error):
                observer = Observer.__new__(Observer)
                observer.reservations = []
                sockets = [Mock() for _ in range(4)]
                sockets[1].bind.side_effect = OSError(error, 'bind')
                for connection in sockets:
                    connection.getsockname.return_value = ('127.0.0.1', 12349)
                with patch('docker_network_fixture.socket.socket', side_effect=sockets):
                    if error == errno.EADDRINUSE:
                        self.assertEqual(12349, observer.allocate_port())
                        self.assertEqual(sockets[2:], observer.reservations)
                    else:
                        with self.assertRaises(OSError) as caught:
                            observer.allocate_port()
                        self.assertEqual(error, caught.exception.errno)
                        self.assertEqual([], observer.reservations)
                for connection in sockets[:2]:
                    connection.close.assert_called_once()

    def test_port_release_waits_for_both_protocols_with_a_deadline(self):
        from docker_network_fixture import Observer
        for error, times, retry in ((errno.EADDRINUSE, [0, 0], True),
                                    (errno.EADDRINUSE, [0, 5], False),
                                    (errno.EACCES, [0, 0], False)):
            with self.subTest(error=error, retry=retry):
                sockets = [Mock() for _ in range(4 if retry else 2)]
                sockets[1].bind.side_effect = OSError(error, 'bind')
                with patch('docker_network_fixture.socket.socket', side_effect=sockets), \
                        patch('docker_network_fixture.time.monotonic', side_effect=times), \
                        patch('docker_network_fixture.select.select') as pause:
                    if retry:
                        Observer.wait_released(socket.AF_INET, '127.0.0.1', 12349)
                        pause.assert_called_once()
                    else:
                        with self.assertRaises(OSError) as caught:
                            Observer.wait_released(socket.AF_INET, '127.0.0.1', 12349)
                        self.assertEqual(error, caught.exception.errno)
                        pause.assert_not_called()
                for connection in sockets:
                    connection.bind.assert_called_once_with(('127.0.0.1', 12349))
                    connection.close.assert_called_once()

    def test_k3s_images_use_the_stock_inbox_only_when_supplied(self):
        from k3s_fixture import prepare_images
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            inputs, data = root / 'inputs', root / 'data'
            inputs.mkdir()
            prepare_images(inputs, data)
            self.assertFalse(data.exists())
            (inputs / 'images-0.tar').write_bytes(b'airgap')
            (inputs / 'images-1.tar').write_bytes(b'workload')
            (inputs / 'unrelated.tar').write_bytes(b'not selected')
            prepare_images(inputs, data)
            self.assertEqual({'images-0.tar', 'images-1.tar'},
                             {path.name for path in (data / 'agent/images').iterdir()})
            self.assertEqual(b'airgap', (data / 'agent/images/images-0.tar').read_bytes())
            self.assertEqual(b'workload', (data / 'agent/images/images-1.tar').read_bytes())

    @unittest.skipUnless(Path('/proc/self/status').exists(), 'Linux signal dispositions')
    def test_k3s_pid1_forwards_termination_and_reaps_its_workload(self):
        from k3s_fixture import pid1_command
        child = ('import os, signal\n'
                 'def stop(*_):\n'
                 '    os.write(1, b"TERM\\n")\n'
                 '    os._exit(0)\n'
                 'signal.signal(signal.SIGTERM, stop)\n'
                 'signal.alarm(10)\n'
                 'os.write(1, b"READY\\n")\n'
                 'signal.pause()\n')
        with subprocess.Popen(['sh', '-ec', pid1_command(sys.executable, '-c', child)],
                              stdout=subprocess.PIPE, text=True) as process:
            try:
                # EVENT_WAIT: child readiness plus the parent's installed handler.
                # The deadline fails the test; no fixed settling delay is used.
                self.assertTrue(select.select([process.stdout], [], [], 5)[0])
                self.assertEqual('READY\n', process.stdout.readline())
                deadline = time.monotonic() + 5
                while True:
                    status = dict(line.split(':', 1) for line in
                                  Path(f'/proc/{process.pid}/status').read_text().splitlines())
                    if int(status['SigCgt'], 16) & (1 << (signal.SIGTERM - 1)):
                        break
                    self.assertLess(time.monotonic(), deadline)
                    select.select([], [], [], .01)
                process.terminate()
                self.assertEqual(0, process.wait(timeout=5))
                self.assertEqual('TERM\n', process.communicate(timeout=5)[0])
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait()

    def test_k3s_pid1_retains_normal_workload_exit(self):
        from k3s_fixture import pid1_command
        result = subprocess.run(['sh', '-ec', pid1_command('sh', '-c', 'exit 37')], timeout=5)
        self.assertEqual(37, result.returncode)

    def test_systemd_unit_waits_for_notification_not_api_readiness(self):
        probe = Mock(side_effect=[subprocess.CompletedProcess([], 3, 'activating\n', ''),
                                  subprocess.CompletedProcess([], 0, 'active\n', '')])
        with patch('systemd_ready.threading.Event') as event:
            wait_systemd_unit(probe)
        self.assertEqual(2, probe.call_count)
        event.return_value.wait.assert_called_once()
        for state in ('inactive', 'failed', 'deactivating'):
            with self.subTest(state=state):
                probe = Mock(return_value=subprocess.CompletedProcess([], 3, state, ''))
                with self.assertRaises(AssertionError):
                    wait_systemd_unit(probe)
                probe.assert_called_once()

    def test_k3s_isolation_retains_distribution_inputs(self):
        from k3s_fixture import NODE_STATE_DIRECTORIES, STATE, guest_command
        self.assertNotIn('mount -t tmpfs tmpfs /var\n', STATE)
        self.assertNotIn('overlay', STATE)
        self.assertNotIn('mkdir', STATE)
        self.assertEqual(('/etc/rancher', '/var/lib/kubelet', '/var/lib/rancher', '/var/lib/cni', '/var/log'),
                         NODE_STATE_DIRECTORIES)
        self.assertIn('mount -t tmpfs tmpfs "$state"', STATE)
        self.assertIn(STATE, guest_command('/fixture/test.py', '/fixture/k3s')[5])

    def test_k3s_tools_survive_private_run_and_agent_pivot(self):
        from k3s_fixture import guest_command
        self.assertIn('/nix/var/nix/profiles/system/sw/bin', GUEST_PATH.split(':'))
        self.assertNotIn('/run/current-system', GUEST_PATH)
        command = guest_command('/fixture/test.py', '/fixture/k3s')
        self.assertEqual('unshare', command[0])
        self.assertIn('exec python3 ', command[5])
        self.assertNotIn('/usr/bin', ' '.join(command))

    def test_k3s_restart_checks_are_explicit_and_cannot_be_skipped(self):
        from k3s_fixture import guest_command
        self.assertEqual('1', guest_command('/test.py', '/k3s')[-1])
        command = guest_command('/test.py', '/k3s', restart_checks=3)
        self.assertEqual('3', command[-1])
        self.assertIn('"$6"', command[5])
        for count in (0, -1):
            with self.assertRaises(ValueError):
                guest_command('/test.py', '/k3s', restart_checks=count)

    def test_k3s_state_preparation_tracks_partial_creation(self):
        from k3s_fixture import prepare_node_state
        suite, created = Mock(), []
        suite.guest.return_value = ['shroot', '--store', '/selected']
        suite.observe.return_value = dict(exitCode=1, output='K3S_STATE_DIR /etc/rancher\nmkdir failed\n')
        with self.assertRaises(AssertionError):
            prepare_node_state(suite, '/selected', created)
        self.assertEqual([('/selected', '/etc/rancher')], created)
        suite.guest.assert_called_once_with('/selected', '--user', '0:0', '--env', 'PATH=' + GUEST_PATH)
        suite.observe.return_value = dict(exitCode=0, output='')
        prepare_node_state(suite, '/existing', created)
        self.assertEqual([('/selected', '/etc/rancher')], created)

    def test_k3s_backups_stay_out_of_init_discovery(self):
        location = runpy.run_path(str(Path(__file__).with_name('fixtures') / 'k3s-boot.py'))['backup_location']
        script = location('/etc/init.d/magicdesk-session', 'fixture', 0)
        enabled = location('/etc/runlevels/default/magicdesk-session', 'fixture', 1)
        self.assertEqual(Path('/etc/.fixture-backups'), script.parent)
        self.assertEqual(script.parent, enabled.parent)
        self.assertNotEqual(script, enabled)
        self.assertEqual(Path('/var/lib/kubelet.md-fixture'), location('/var/lib/kubelet', 'fixture', 2))

    def test_whole_boot_requires_ready_replacement_containers(self):
        ready = runpy.run_path(str(Path(__file__).with_name('fixtures') / 'k3s-boot.py'))['ready_containers']
        self.assertEqual([], ready({}, []))
        status = dict(ready=True, state=dict(running=dict(startedAt='now')), containerID='old')
        pod = dict(status=dict(containerStatuses=[status]))
        self.assertEqual(['old'], ready(pod, []))
        self.assertEqual([], ready(pod, ['old']))
        status['containerID'] = 'new'
        self.assertEqual(['new'], ready(pod, ['old']))
        status['ready'] = False
        self.assertEqual([], ready(pod, ['old']))
        status['ready'] = True
        status['state'] = dict(terminated={})
        self.assertEqual([], ready(pod, ['old']))

    def test_kubelet_inventory_handles_boot_and_recovery_states(self):
        from k3s_fixture import kubelet_pods
        client = Mock()
        for result in (subprocess.CompletedProcess([], 1, 'unavailable'),
                       subprocess.CompletedProcess([], 0, '{"items": null}'),
                       subprocess.CompletedProcess([], 0, '{"items": []}')):
            client.return_value = result
            self.assertEqual({}, kubelet_pods(client, 'node'))
        pod = {'metadata': {'uid': 'expected'}}
        client.return_value = subprocess.CompletedProcess([], 0, json.dumps({'items': [pod]}))
        self.assertEqual({'expected': pod}, kubelet_pods(client, 'node'))

    def test_kubelet_ready_requires_current_process_heartbeat(self):
        from k3s_fixture import node_ready_since
        for value in (None, [], [{'type': 'Ready', 'status': 'True'}],
                      [{'type': 'Ready', 'status': 'True', 'lastHeartbeatTime': '1970-01-01T00:00:10Z'}],
                      [{'type': 'Ready', 'status': 'False', 'lastHeartbeatTime': '1970-01-01T00:00:12Z'}]):
            with self.subTest(value=value):
                self.assertFalse(node_ready_since(value, 10.5))
        for stamp in ('1970-01-01T00:00:11Z', '1970-01-01T01:00:11+01:00'):
            self.assertTrue(node_ready_since([dict(type='Ready', status='True', lastHeartbeatTime=stamp)], 10.5))

    def test_kubelet_ready_reconciles_stale_state_with_one_deadline(self):
        from k3s_fixture import wait_node_ready
        stale = dict(type='Ready', status='True', lastHeartbeatTime='1970-01-01T00:00:10Z')
        fresh = dict(stale, lastHeartbeatTime='1970-01-01T00:00:12Z')
        client = Mock(side_effect=[subprocess.CompletedProcess([], 0, json.dumps([value]))
                                   for value in (stale, fresh)])
        alive = Mock()
        with patch('k3s_fixture.time.monotonic', side_effect=[0, 1, 2, 3]), \
                patch('k3s_fixture.time.sleep') as sleep:
            wait_node_ready(client, 'node', 10.5, alive)
        self.assertEqual(client.call_count, 2)
        self.assertEqual(alive.call_count, 2)
        sleep.assert_called_once_with(1)
        client = Mock(return_value=subprocess.CompletedProcess([], 0, json.dumps([stale])))
        with patch('k3s_fixture.time.monotonic', side_effect=[0, 1, 89.5, 90]), \
                patch('k3s_fixture.time.sleep') as sleep:
            with self.assertRaisesRegex(AssertionError, 'fresh Ready'):
                wait_node_ready(client, 'node', 10.5, alive)
        sleep.assert_called_once_with(0.5)
        self.assertEqual(client.call_count, 1)

    def test_init_managed_kubelet_uses_launch_boundary(self):
        from k3s_fixture import wait_node_ready
        client = Mock(return_value=subprocess.CompletedProcess([], 0, json.dumps([
            dict(type='Ready', status='True', lastHeartbeatTime='1970-01-01T00:00:12Z')])))
        with patch('k3s_fixture.time.monotonic', side_effect=[0, 1]), \
                patch('k3s_fixture.time.sleep') as sleep:
            wait_node_ready(client, 'node', 10.5)
        client.assert_called_once()
        sleep.assert_not_called()

    def test_k3s_cleanup_restores_files_and_dangling_symlinks(self):
        cleanup = runpy.run_path(str(Path(__file__).with_name('fixtures') / 'k3s-boot.py'))['cleanup']
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            old_file, old_link = root / 'old-file', root / 'old-link'
            old_file.write_text('original')
            old_link.symlink_to('missing-target')
            current_file, current_link, owned = root / 'file', root / 'link', root / 'owned'
            current_file.mkdir()
            (current_file / 'temporary').write_text('fixture')
            current_link.write_text('replacement')
            owned.write_text('fixture')
            backups = root / 'backups'
            backups.mkdir()
            saved_file, saved_link = backups / 'file', backups / 'link'
            old_file.rename(saved_file)
            old_link.rename(saved_link)
            (root / 'installation.json').write_text(json.dumps(dict(
                owned=[str(owned)], parents=[], backupDirectories=[str(backups)],
                backups={str(current_file): str(saved_file), str(current_link): str(saved_link)})))
            cleanup(root)
            self.assertEqual('original', current_file.read_text())
            self.assertEqual('missing-target', os.readlink(current_link))
            self.assertFalse(owned.exists())
            self.assertFalse(backups.exists())

    def test_k3s_cleanup_preserves_original_before_backup_was_moved(self):
        cleanup = runpy.run_path(str(Path(__file__).with_name('fixtures') / 'k3s-boot.py'))['cleanup']
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = root / 'original'
            original.write_text('keep')
            (root / 'installation.json').write_text(json.dumps(dict(
                owned=[], parents=[], backups={str(original): str(root / 'not-moved')})))
            cleanup(root)
            self.assertEqual('keep', original.read_text())

    def test_init_fixture_import_does_not_require_host_repository(self):
        source = Path(__file__).with_name('init_fixture.py').read_text()
        with tempfile.TemporaryDirectory() as directory:
            script = Path(directory) / 'init_fixture.py'
            script.write_text(source)
            subprocess.run([sys.executable, str(script)], check=True, timeout=5)

    def test_openrc_observer_import_needs_only_staged_guest_files(self):
        with tempfile.TemporaryDirectory() as directory:
            for name in ('test_os_boot.py', 'init_fixture.py'):
                (Path(directory) / name).write_text(Path(__file__).with_name(name).read_text())
            subprocess.run([sys.executable, '-I', '-c',
                            'import sys; sys.path.insert(0, sys.argv[1]); import test_os_boot',
                            directory], check=True, timeout=5)

    def test_container_observer_rejects_private_fixture_loopback_before_startup(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / 'session.json'
            for profile in ('bridge', 'none'):
                with self.subTest(profile=profile):
                    state.write_text(json.dumps(dict(closed=False, networkProfile=profile)))
                    with patch.object(sys, 'argv', ['test_booted_containers.py',
                            '--session', str(state), '--engine', 'podman']), \
                            patch.object(test_booted_containers, 'Suite') as suite, \
                            patch.object(test_booted_containers, 'Observer') as observer:
                        with self.assertRaisesRegex(AssertionError, 'Android loopback'):
                            test_booted_containers.main()
                        suite.assert_not_called()
                        observer.assert_not_called()

    @unittest.skipUnless(hasattr(os, 'pidfd_open'), 'Requires Python Linux pidfd support')
    def test_editor_readiness_observes_exact_launcher_exit(self):
        accept = runpy.run_path(str(Path(__file__).with_name('fixtures') /
                                   'vscode-gui.py'))['accept_editor']
        for code in (7, 23):
            with self.subTest(code=code), socket.socket() as listener:
                listener.bind(('127.0.0.1', 0))
                listener.listen()
                with subprocess.Popen([sys.executable, '-c', f'raise SystemExit({code})']) as child:
                    with self.assertRaisesRegex(RuntimeError, f'readiness: {code}'):
                        accept(listener, child, time.monotonic() + 5)

    @unittest.skipUnless(hasattr(os, 'pidfd_open'), 'Requires Python Linux pidfd support')
    def test_editor_readiness_survives_successful_cli_handoff(self):
        accept = runpy.run_path(str(Path(__file__).with_name('fixtures') /
                                   'vscode-gui.py'))['accept_editor']
        for connected in (False, True):
            with self.subTest(connected=connected):
                self.check_successful_cli_handoff(accept, connected)

    def check_successful_cli_handoff(self, accept, connected):
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            listener.listen()
            with subprocess.Popen([sys.executable, '-c', 'pass']) as child:
                observer = os.pidfd_open(child.pid)
                try:
                    ready = select.poll()
                    ready.register(observer, select.POLLIN)
                    # Observe exit without reaping: accept_editor owns wait().
                    self.assertTrue(ready.poll(5000))
                finally:
                    os.close(observer)
                if connected:
                    with socket.create_connection(listener.getsockname()) as peer:
                        peer.sendall(b'reloaded')
                        with accept(listener, child, time.monotonic() + 5) as connection:
                            self.assertEqual(b'reloaded', connection.recv(8))
                else:
                    with self.assertRaises(TimeoutError):
                        accept(listener, child, time.monotonic())
                self.assertEqual(0, child.returncode)

    @unittest.skipUnless(hasattr(os, 'pidfd_open'), 'Requires Python Linux pidfd support')
    def test_editor_readiness_accepts_connection_and_bounds_missing_event(self):
        accept = runpy.run_path(str(Path(__file__).with_name('fixtures') /
                                   'vscode-gui.py'))['accept_editor']
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            listener.listen()
            with subprocess.Popen([sys.executable, '-c', 'import sys; sys.stdin.read()'],
                                  stdin=subprocess.PIPE) as child:
                try:
                    with self.assertRaises(TimeoutError):
                        accept(listener, child, time.monotonic())
                    with socket.create_connection(listener.getsockname()) as peer:
                        with accept(listener, child, time.monotonic() + 5) as connection:
                            peer.sendall(b'fixture')
                            self.assertEqual(b'fixture', connection.recv(7))
                finally:
                    child.stdin.close()

    def test_nspawn_leader_matches_parent_and_namespace_without_procps(self):
        children = runpy.run_path(str(Path(__file__).with_name('fixtures') /
                                     'nspawn-command-boot.py'))['namespace_children']
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for pid, parent, namespace in ((11, 10, 'pid:[2]'), (12, 9, 'pid:[2]'),
                                           (13, 10, 'pid:[1]')):
                task = root / str(pid)
                (task / 'ns').mkdir(parents=True)
                (task / 'status').write_text(f'Name:\tchild\nPPid:\t{parent}\n')
                (task / 'ns/pid').symlink_to(namespace)
            (root / '14').mkdir()  # A child may disappear during enumeration.
            (root / 'self').symlink_to('11')
            self.assertEqual([11], children(10, 'pid:[2]', root))
            self.assertEqual([], children(9, 'pid:[1]', root))

    def test_nspawn_command_uses_separate_fixture(self):
        with patch.object(sys, 'argv', ['nspawn', '--session', 'session.json',
                                      '--machine', 'test-image', '--command']), \
                patch.object(test_booted_nspawn, 'run_fixture') as launch:
            test_booted_nspawn.main()
            launch.assert_called_once_with(Path('session.json'), 'nspawn-command', ['--machine', 'test-image'])

    def test_nspawn_command_rejects_archive_before_launch(self):
        with patch.object(sys, 'argv', ['nspawn', '--session', 'session.json',
                                      '--archive', 'rootfs.tar', '--command']), \
                patch.object(test_booted_nspawn, 'run_fixture') as launch, \
                patch.object(sys, 'stderr', io.StringIO()), self.assertRaises(SystemExit):
            test_booted_nspawn.main()
        launch.assert_not_called()

    def test_fixture_attach_reuses_only_its_live_window_on_selected_display(self):
        for hosts, opens in (([], True),
                             ([dict(attached=True, windowId=3, displayId=7)], False),
                             ([dict(attached=False, windowId=3, displayId=7)], True),
                             ([dict(attached=True, windowId=4, displayId=7)], True),
                             ([dict(attached=True, windowId=3, displayId=8)], True)):
            with self.subTest(hosts=hosts):
                client, statefile = Mock(), Mock()
                client.call.return_value = dict(matched=True, session=dict(hosts=hosts,
                    windows=[dict(windowId=3, mapped=True, parentWindowId=0)]))
                state = dict(sessionId='fixture', display=dict(id=7, uniqueId='display-7'))
                attach_window(client, state, statefile)
                launches = [c for c in client.call.call_args_list if c.args[0] == 'graphics.open_window']
                self.assertEqual([call('graphics.open_window', dict(sessionId='fixture', windowId=3,
                    placement='display', displayId=7, uniqueId='display-7'))] if opens else [], launches)
                self.assertEqual(3, state['windowId'])
                self.assertEqual('graphics_host_attached', client.call.call_args.args[1]['condition'])

    def test_editor_fixture_rejects_missing_extension_before_launch(self):
        main = runpy.run_path(str(Path(__file__).with_name('fixtures') / 'vscode-gui.py'))['main']
        for listing in ('', 'ms-vscode.cpptools-extension-pack\n'):
            with self.subTest(listing=listing), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / '.cache').mkdir()
                driver = root / 'driver.js'
                driver.touch()
                args = ['vscode-gui.py', '--output', str(root / 'result'),
                        '--driver', str(driver), '--code', '/fixture/code']
                with patch.object(sys, 'argv', args), patch.object(Path, 'home', return_value=root), \
                        patch('shutil.which', return_value='/fixture/gdb'), \
                        patch('subprocess.check_output', return_value=listing) as query, \
                        patch('subprocess.run') as launch:
                    with self.assertRaisesRegex(RuntimeError, 'selected code launcher'):
                        main()
                    query.assert_called_once_with(['/fixture/code', '--list-extensions'],
                                                  text=True, timeout=300)
                    launch.assert_not_called()
                self.assertFalse(json.loads((root / 'result/gui-report.json').read_text())['passed'])

    def test_graphical_fixture_holds_its_display_and_renews_exact_lease(self):
        client, statefile = Mock(), Mock()
        client.call.return_value = dict(held=True, displayId=7, leaseId='fixture-lease')
        state = dict(display=dict(id=7))
        keep_display_awake(client, state, statefile)
        client.call.assert_called_once_with('device.keep_awake',
                                           dict(displayId=7, durationMillis=1800000))
        self.assertEqual('fixture-lease', json.loads(statefile.write_text.call_args.args[0])['awakeLease'])
        client.call.side_effect = [dict(automationAwake=dict(held=True, leaseId='fixture-lease')),
                                  dict(held=True, displayId=7, leaseId='fixture-lease')]
        keep_display_awake(client, state, statefile)
        client.call.assert_called_with('device.keep_awake',
            dict(displayId=7, durationMillis=1800000, leaseId='fixture-lease'))
        client.reset_mock()
        keep_display_awake(client, dict(headless=True), statefile)
        client.call.assert_not_called()

    def test_graphical_fixture_does_not_replace_another_awake_owner(self):
        client, statefile = Mock(), Mock()
        client.call.side_effect = RuntimeError('another awake lease is held')
        with self.assertRaises(RuntimeError):
            keep_display_awake(client, dict(display=dict(id=7)), statefile)
        self.assertEqual(1, client.call.call_count)
        statefile.write_text.assert_not_called()

    def test_graphical_fixture_can_switch_its_lease_to_phone(self):
        client, statefile = Mock(), Mock()
        client.call.side_effect = [dict(automationAwake=dict(held=True, leaseId='fixture-lease')),
                                  {}, dict(held=True, displayId=0, leaseId='phone-lease'),
                                  dict(automationAwake=dict(held=True, leaseId='phone-lease')),
                                  dict(held=True, displayId=0, leaseId='phone-lease')]
        state = dict(display=dict(id=7), awakeLease='fixture-lease')
        keep_display_awake(client, state, statefile, 0)
        self.assertEqual([
            call('get_state'),
            call('device.release_awake', dict(leaseId='fixture-lease')),
            call('device.keep_awake', dict(displayId=0, durationMillis=1800000)),
        ], client.call.call_args_list)
        self.assertNotIn('awakeLease', json.loads(statefile.write_text.call_args_list[0].args[0]))
        self.assertEqual(0, state['awakeDisplayId'])
        keep_display_awake(client, state, statefile)
        client.call.assert_called_with('device.keep_awake',
            dict(displayId=0, durationMillis=1800000, leaseId='phone-lease'))

    def test_graphical_fixture_retains_old_lease_if_release_fails(self):
        client, statefile = Mock(), Mock()
        client.call.side_effect = [dict(automationAwake=dict(held=True, leaseId='fixture-lease')),
                                  RuntimeError('lease ownership changed')]
        state = dict(display=dict(id=7), awakeLease='fixture-lease')
        with self.assertRaises(RuntimeError):
            keep_display_awake(client, state, statefile, 0)
        self.assertEqual('fixture-lease', state['awakeLease'])
        self.assertEqual(2, client.call.call_count)
        statefile.write_text.assert_not_called()

    def test_graphical_fixture_reacquires_expired_lease(self):
        client, statefile = Mock(), Mock()
        client.call.side_effect = [dict(automationAwake=dict(held=False)),
                                  dict(held=True, displayId=7, leaseId='new-lease')]
        state = dict(display=dict(id=7), awakeLease='expired-lease')
        keep_display_awake(client, state, statefile)
        client.call.assert_called_with('device.keep_awake',
                                       dict(displayId=7, durationMillis=1800000))
        self.assertEqual('new-lease', state['awakeLease'])

    def test_graphical_fixture_does_not_replace_successor_lease(self):
        client, statefile = Mock(), Mock()
        client.call.return_value = dict(automationAwake=dict(held=True, leaseId='other-lease'))
        state = dict(display=dict(id=7), awakeLease='expired-lease')
        with self.assertRaisesRegex(RuntimeError, 'another awake lease'):
            keep_display_awake(client, state, statefile)
        client.call.assert_called_once_with('get_state')
        statefile.write_text.assert_not_called()

    def test_installed_nixos_uses_selected_generation(self):
        system = '/nix/store/fixture-system'
        for user in (None, 'fixture'):
            boot, driver = installed_boot('/opt/fixture', 12345, 'token', 'systemd', user, system)
            self.assertIn(system + '/sw/bin/python3', boot)
            self.assertIn(system + '/activate', boot)
            self.assertIn(system + '/systemd/lib/systemd/systemd', boot)
            self.assertLess(boot.index(system + '/activate'), boot.index('exec ' + system + '/sw/bin/setpriv'))
            for script in (boot, driver):
                if script:
                    subprocess.run(['sh', '-n'], input=script, text=True, check=True)

    def test_libvirt_uses_packaged_service_and_does_not_hide_start_failure(self):
        for init in ('systemd', 'openrc', 'runit'):
            command = prepare_libvirt(dict(init=init))
            subprocess.run(['sh', '-n'], input=command, text=True, check=True)
            self.assertIn('virtqemud', command)
            self.assertIn('libvirtd', command)
            self.assertNotIn('|| true', command)
            if init == 'systemd':
                self.assertIn('for policy in enabled loaded', command)
                self.assertIn('systemctl start "$unit"\n    exit $?', command)
            else:
                self.assertIn('elif ', command)
                self.assertIn(start_service(init, 'virtlogd'), command)

    def test_libvirt_network_starts_modular_network_owner_only_when_needed(self):
        for init in ('systemd', 'openrc', 'runit'):
            with self.subTest(init=init):
                self.assertNotIn('virtnetworkd', prepare_libvirt(dict(init=init)))
                command = prepare_libvirt(dict(init=init), network=True)
                subprocess.run(['sh', '-n'], input=command, text=True, check=True)
                if init == 'systemd':
                    self.assertIn('if [ "$service" = virtqemud ]; then\n'
                                  '      systemctl start virtnetworkd.socket || exit $?\n', command)
                else:
                    modular, monolithic = command.split('elif ', 1)
                    self.assertIn(' && ' + start_service(init, 'virtnetworkd'), modular)
                    self.assertNotIn('virtnetworkd', monolithic)

    def test_nixos_boot_selects_the_common_executor_before_device_access(self):
        from test_nixos_boot import main
        for mode in ('app', 'shell'):
            with self.subTest(mode=mode), patch.object(sys, 'argv', [
                    'test_nixos_boot.py', '--store', '/fixture/store', '--executor', mode]), \
                    patch('test_oci_services.Suite', side_effect=RuntimeError('fixture boundary')) as suite:
                with self.assertRaisesRegex(RuntimeError, 'fixture boundary'):
                    main()
                suite.assert_called_once_with(Path('build/nixos-boot'), None, executor=mode)

    def test_nixos_boot_keeps_activation_and_selected_network_policy(self):
        for dhcp in (False, True):
            with self.subTest(dhcp=dhcp):
                boot = boot_script('/nix/store/system', '/nix/store/python/bin/python3', '/mnt/fixture', dhcp)
                self.assertLess(boot.index('/nix/store/system/activate'), boot.index('exec /sbin/init'))
                self.assertIn('systemctl is-system-running --wait', boot)
                self.assertIn('PASS boot service IO and restart', boot)
                self.assertEqual('umount /run/netns/md-nixos-client' in boot, dhcp)
                self.assertEqual('systemctl restart dhcpcd.service' in boot, dhcp)
                subprocess.run(['sh', '-n'], input=boot, text=True, check=True)

    def test_snap_prepares_existing_base_mounts(self):
        self.assertIn('systemctl start dbus.socket snapd.mounts.target snapd.socket\n'
                      'systemctl start snapd.service\nsnap version\n', SNAP_PREPARE)
        subprocess.run(['sh', '-n'], input=SNAP_PREPARE, text=True, check=True)

    def test_observer_cannot_hide_failed_runit_stage(self):
        observer = '\nPASS runit stock PID1 boot and service lifecycle\n'
        check_runit_boot('Mounting filesystems [done]' + observer)
        for failed in ('[fail]', '[ fail ]', '\x1b[1;34m[\x1b[1;35mfail\x1b[1;34m]\x1b[m'):
            with self.subTest(failed=failed), self.assertRaises(AssertionError):
                check_runit_boot('Mounting filesystems ' + failed + observer)
        for failure in ('Bad system call', 'Segmentation fault', 'Bus error',
                        'Illegal instruction', 'Aborted'):
            for suffix in ('', ' (core dumped)'):
                with self.subTest(failure=failure, suffix=suffix), self.assertRaises(AssertionError):
                    check_runit_boot('/etc/runit/1: 5: ' + failure + suffix + observer)
        check_runit_boot('dmesg: read kernel buffer failed: Function not implemented\n' + observer)

    def test_systemd_startup_success_cannot_hide_shutdown_failures(self):
        boot = 'running\n0 loaded units listed.\nPASS boot service IO and restart\n'
        check_systemd_boot(boot + 'dbus.service: Deactivated successfully.\n')
        for unit in ('lxc.service', 'podman-restart.service', 'mnt-fixture.mount'):
            for result in ('exit-code', 'timeout', 'signal'):
                with self.subTest(unit=unit, result=result), self.assertRaisesRegex(AssertionError, unit):
                    check_systemd_boot(boot + f"\x1b[31m{unit}: Failed with result '{result}'.\x1b[0m\r\n")
        with self.assertRaises(AssertionError):
            check_systemd_boot('systemd-test-generator failed with exit status 1\n' + boot)
        with self.assertRaisesRegex(AssertionError, '/etc/resolv.conf'):
            check_systemd_boot(boot + '[\x1b[31mFAILED\x1b[0m] Failed unmounting /etc/resolv.conf.\r\n')

    def test_init_services(self):
        for init in ('systemd', 'openrc', 'runit'):
            for user in (None, 'fixture'):
                with self.subTest(init=init, user=user):
                    boot, driver = installed_boot('/opt/fixture', 12345, 'test-token', init, user)
                    self.assertIn('session-controller.py /opt/fixture 12345 test-token', boot)
                    self.assertEqual('driver.sh' in boot, user is not None)
                    self.assertEqual(driver is not None, user is not None)
                    self.assertEqual('"${MAGICDESK_AUDIO_PULSE:-}"' in boot, user is not None)
                    self.assertEqual('/opt/fixture/audio-endpoint' in boot, user is not None)
                    self.assertNotIn('weston', boot)
                    self.assertIn('mount --rbind /dev /run/md-host-dev', boot)
                    self.assertIn('umount -l /run/md-host-dev', boot)
                    for name, target in [('fd', '/proc/self/fd'), ('stdin', '/proc/self/fd/0'),
                                         ('stdout', '/proc/self/fd/1'), ('stderr', '/proc/self/fd/2')]:
                        self.assertIn(f'ln -s {target} /dev/{name}', boot)
                    if init == 'systemd' and user is not None:
                        self.assertNotIn('systemctl start dbus.', driver)
                        self.assertIn('Requires=basic.target dbus.service systemd-logind.service systemd-user-sessions.service\n'
                                      'After=basic.target dbus.service systemd-logind.service systemd-user-sessions.service\n', boot)
                        self.assertIn('systemctl start user@$uid.service', driver)
                        self.assertIn('--property=StandardOutput=append:/opt/fixture/driver.log', driver)
                        self.assertIn('--property=StandardError=inherit', driver)
                    if init == 'systemd' and user is None:
                        self.assertNotIn('systemd-logind.service', boot)
                    if init == 'systemd':
                        self.assertIn('/run/systemd/network/80-magicdesk-uplink.network', boot)
                        self.assertIn('Name=eth0\n[Network]\nDHCP=no\nIPv6AcceptRA=no\n'
                                      'LinkLocalAddressing=no\nKeepConfiguration=static\n', boot)
                        self.assertNotIn('cat > /etc/systemd/network', boot)
                        # Only the focused target opts out. Its services must
                        # follow sysinit, including the stock /tmp preparation.
                        self.assertEqual(boot.count('DefaultDependencies=no'), 1)
                    if init == 'runit':
                        self.assertIn('mount --bind /run/md-runit/runsvdir /etc/runit/runsvdir', boot)
                        self.assertNotIn('mount --bind /run/md-runit /etc/runit\n', boot)
                    for script in (boot, driver):
                        if script is not None:
                            subprocess.run(['sh', '-n'], input=script, text=True, check=True)

    def test_service_uses_selected_init(self):
        for init, expected in [('systemd', 'systemctl start docker.service'),
                               ('openrc', 'rc-service docker start'),
                               ('runit', ' /etc/runit/runsvdir/default/docker')]:
            command = start_service(init, 'docker')
            self.assertIn(expected, command)
            subprocess.run(['sh', '-n'], input=command, text=True, check=True)
        with self.assertRaises(ValueError):
            start_service('unknown', 'docker')

    def test_runit_waits_for_fifo_reader(self):
        with patch('sys.argv', ['fixture', '/service/test']), \
                patch('os.open', side_effect=[FileNotFoundError(errno.ENOENT, 'missing'),
                                             OSError(errno.ENXIO, 'no reader'), 7]) as opened, \
                patch('os.close') as close, patch('os.execvp') as execute, \
                patch('threading.Event') as event:
            exec(RUNIT_START, {})
        self.assertEqual(opened.call_count, 3)
        self.assertEqual(event.return_value.wait.call_count, 2)
        close.assert_called_once_with(7)
        execute.assert_called_once_with('sv', ['sv', '-w', '30', 'start', '/service/test'])

    def test_runit_stock_service_layouts(self):
        for layout in ('etc/sv', 'etc/runit/sv'):
            with self.subTest(layout=layout), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                target = root / 'etc/runit/runsvdir/default/docker'
                target.parent.mkdir(parents=True)
                source = root / layout / 'docker'
                source.mkdir(parents=True)
                (source / 'run').write_text('#!/bin/sh\nexec dockerd\n')
                command = enable_runit_service('docker').replace('/etc/', directory + '/etc/')
                for _ in range(2):
                    subprocess.run(['sh', '-eu', '-c', command], check=True, timeout=5)
                self.assertEqual(target.resolve(), source)
                (source / 'run').unlink()
                self.assertNotEqual(0, subprocess.run(['sh', '-eu', '-c', command], timeout=5).returncode)

    def test_runit_timeout_is_not_started(self):
        with patch('sys.argv', ['fixture', '/service/test']), \
                patch('os.open', side_effect=FileNotFoundError(errno.ENOENT, 'missing')), \
                patch('time.monotonic', side_effect=[0, 31]), patch('os.execvp') as execute:
            with self.assertRaises(TimeoutError):
                exec(RUNIT_START, {})
        execute.assert_not_called()

    def test_controller_connection_then_ready(self):
        with patch('installed_desktop.urllib.request.urlopen', side_effect=[
                urllib.error.URLError(ConnectionRefusedError(errno.ECONNREFUSED, 'not listening')),
                io.BytesIO(b'{"uid": 0}')]) as connect, patch('installed_desktop.threading.Event') as event:
            wait_controller(dict(rootPort=12345, token='test-token'))
        self.assertEqual(connect.call_count, 2)
        self.assertEqual(connect.call_args.args[0].get_header('Authorization'), 'Bearer test-token')
        event.return_value.wait.assert_called_once()

    def test_stop_waits_for_owned_launch_and_preserves_other_resources(self):
        state = dict(id='owned', environment=dict(store='/store'), runner='/runner',
                     sessionId='session', display=dict(id=12, uniqueId='fixture-display'))
        owned = json.dumps(dict(label='owned', launchId='launch')) + '\n'
        other = json.dumps(dict(label='other', launchId='unrelated')) + '\n'
        shell = Mock(side_effect=[owned + other, '', owned + other, other])
        client = Mock()
        client.call.side_effect = [dict(sessions=[dict(sessionId='other')]), dict(matched=True),
                                  dict(displays=[dict(id=13, uniqueId='other')]), dict(matched=True)]
        with patch('installed_desktop.threading.Event') as event:
            stop_owned_resources(client, shell, state)
        self.assertEqual([call.args[0] for call in shell.call_args_list], [
            '/runner image launches /store', '/runner image stop /store launch',
            '/runner image launches /store', '/runner image launches /store'])
        self.assertEqual([call.args[0] for call in client.call.call_args_list], [
            'graphics.list', 'wait_for_state', 'list_displays', 'wait_for_state'])
        event.return_value.wait.assert_called_once()

    def test_stop_timeout_keeps_graphics_for_diagnosis(self):
        state = dict(id='owned', environment=dict(store='/store'), runner='/runner')
        launch = json.dumps(dict(label='owned', launchId='launch'))
        shell, client = Mock(return_value=launch), Mock()
        with patch('installed_desktop.time.monotonic', side_effect=[0, 31]):
            with self.assertRaisesRegex(AssertionError, 'did not stop'):
                stop_owned_resources(client, shell, state)
        client.call.assert_not_called()

    def test_stop_releases_only_its_phone_awake_lease(self):
        for current in ('owned-lease', 'another-lease', None):
            with self.subTest(current=current):
                state = dict(id='owned', environment=dict(store='/store'), runner='/runner',
                             awakeDisplayId=0, awakeLease='owned-lease')
                shell, client = Mock(return_value=''), Mock()
                client.call.return_value = dict(automationAwake=dict(leaseId=current))
                stop_owned_resources(client, shell, state)
                calls = [c for c in client.call.call_args_list if c.args[0] == 'device.release_awake']
                self.assertEqual([call('device.release_awake', dict(leaseId='owned-lease'))]
                                 if current == 'owned-lease' else [], calls)

    def test_stop_removes_only_exact_graphics_and_display(self):
        state = dict(id='owned', environment=dict(store='/store'), runner='/runner',
                     sessionId='session', display=dict(id=12, uniqueId='fixture-display'))
        shell, client = Mock(return_value=''), Mock()
        client.call.side_effect = [dict(sessions=[dict(sessionId='session')]), {}, dict(matched=True),
                                  dict(displays=[dict(id=12, uniqueId='fixture-display')]),
                                  {}, dict(matched=True)]
        stop_owned_resources(client, shell, state)
        self.assertEqual([call.args for call in client.call.call_args_list if call.args[0] in (
            'graphics.stop', 'remove_display')], [
                ('graphics.stop', dict(sessionId='session')),
                ('remove_display', dict(displayId=12, uniqueId='fixture-display'))])

    def test_controller_rejection_is_not_retried(self):
        error = urllib.error.HTTPError('http://127.0.0.1', 403, 'Forbidden', {}, None)
        with error, patch('installed_desktop.urllib.request.urlopen', side_effect=error) as connect:
            with self.assertRaises(urllib.error.HTTPError):
                wait_controller(dict(rootPort=12345, token='test-token'))
        connect.assert_called_once()

    def test_user_controller_requires_selected_identity(self):
        state = dict(rootPort=12345, userPort=12346, userUid=1001, token='test-token')
        with patch('installed_desktop.urllib.request.urlopen', return_value=io.BytesIO(b'{"uid": 1001}')) as connect:
            wait_controller(state, user=True)
        self.assertEqual(connect.call_args.args[0].full_url, 'http://127.0.0.1:12346')
        for uid in (0, 1000):
            with self.subTest(uid=uid), patch('installed_desktop.urllib.request.urlopen',
                    return_value=io.BytesIO(json.dumps(dict(uid=uid)).encode())) as connect:
                with self.assertRaises(AssertionError):
                    wait_controller(state, user=True)
            connect.assert_called_once()

    def test_controller_deadline_is_failure(self):
        with patch('installed_desktop.time.monotonic', side_effect=[0, 91]):
            with self.assertRaisesRegex(AssertionError, 'did not become ready'):
                wait_controller(dict(rootPort=12345, token='test-token'))

    def test_ready(self):
        probe = Mock(return_value=subprocess.CompletedProcess([], 0, 'running\n', ''))
        alive = Mock()
        wait_systemd(probe, alive)
        alive.assert_not_called()
        self.assertGreater(probe.call_args.args[0], 0)

    def test_connection_then_ready(self):
        for error in ('No such file or directory', 'Connection refused', 'Resource temporarily unavailable'):
            with self.subTest(error=error):
                probe = Mock(side_effect=[
                    subprocess.CompletedProcess([], 1, '', 'Failed to connect: ' + error),
                    subprocess.CompletedProcess([], 0, 'running\n', '')])
                alive = Mock()
                with patch('systemd_ready.threading.Event') as event:
                    wait_systemd(probe, alive)
                alive.assert_called_once()
                event.return_value.wait.assert_called_once()

    def test_other_connection_errors_fail(self):
        for error in ('Permission denied', 'Protocol error', 'Connection timed out'):
            with self.subTest(error=error):
                probe = Mock(return_value=subprocess.CompletedProcess([], 1, '', 'Failed to connect: ' + error))
                with self.assertRaises(AssertionError):
                    wait_systemd(probe, Mock())
                probe.assert_called_once()

    def test_connection_eagain_does_not_extend_deadline(self):
        probe = Mock(return_value=subprocess.CompletedProcess([], 1, '',
                     'Failed to connect: Resource temporarily unavailable'))
        alive = Mock()
        with patch('systemd_ready.time.monotonic', side_effect=[0, 1, 91]):
            with self.assertRaisesRegex(AssertionError, 'did not become ready'):
                wait_systemd(probe, alive)
        probe.assert_called_once()
        alive.assert_not_called()

    def test_degraded_is_not_retried(self):
        probe = Mock(return_value=subprocess.CompletedProcess([], 1, 'degraded\n', ''))
        with self.assertRaises(AssertionError):
            wait_systemd(probe, Mock())
        probe.assert_called_once()

    def test_starting_requires_running(self):
        probe = Mock(side_effect=[
            subprocess.CompletedProcess([], 1, 'initializing\n', ''),
            subprocess.CompletedProcess([], 1, 'starting\n', ''),
            subprocess.CompletedProcess([], 0, 'running\n', '')])
        alive = Mock()
        with patch('systemd_ready.threading.Event') as event:
            wait_systemd(probe, alive)
        self.assertEqual(alive.call_count, 2)
        self.assertEqual(event.return_value.wait.call_count, 2)

    def test_nonstarting_states_are_not_retried(self):
        for state in ('offline', 'failed', 'stopping', 'maintenance', 'unknown'):
            with self.subTest(state=state):
                probe = Mock(return_value=subprocess.CompletedProcess([], 1, state + '\n', ''))
                with self.assertRaises(AssertionError):
                    wait_systemd(probe, Mock())
                probe.assert_called_once()

    def test_deadline_is_failure(self):
        probe = Mock()
        with patch('systemd_ready.time.monotonic', side_effect=[0, 91]):
            with self.assertRaisesRegex(AssertionError, 'did not become ready'):
                wait_systemd(probe, Mock())
        probe.assert_not_called()

    def test_network(self):
        created = []
        real_socket = socket.socket
        phases = []

        def create(*args, **kwargs):
            stream = real_socket(*args, **kwargs)
            if kwargs.get('fileno') is None:
                created.append(stream)
            return stream

        def service(_python, *argv):
            phases.append(len(created))
            self.assertEqual(len(phases), len(created))
            self.assertTrue(all(stream.fileno() == -1 for stream in created[:-1]))
            self.assertEqual(10, created[-1].gettimeout())
            return subprocess.run([sys.executable, *argv], capture_output=True, text=True,
                                  check=True, timeout=10)
        with patch('network_exchange.socket.socket', create), \
                patch('network_exchange.shutil.which', return_value=None):
            self.assertEqual(exchange(service, '127.0.0.1'), dict(tcp=20, udp=20))
        self.assertEqual([1, 2], phases)
        self.assertTrue(all(stream.fileno() == -1 for stream in created))

    def test_network_permission_is_scoped_and_removed_on_failure(self):
        with patch('network_exchange.shutil.which', return_value='/test/iptables'), \
                patch('network_exchange.subprocess.run') as run:
            with self.assertRaisesRegex(RuntimeError, 'guest failed'):
                with inbound_port('10.231.247.1', 12345, socket.SOCK_DGRAM):
                    raise RuntimeError('guest failed')
        self.assertEqual(len(run.call_args_list), 2)
        insert, remove = [call.args[0] for call in run.call_args_list]
        self.assertEqual(insert[3], '-I')
        self.assertEqual(remove[3], '-D')
        self.assertEqual(insert[4:], ['INPUT', '-d', '10.231.247.1', '-p', 'udp',
                                     '--dport', '12345', '-j', 'ACCEPT'])
        self.assertEqual(insert[4:], remove[4:])


if __name__ == '__main__':
    unittest.main()
