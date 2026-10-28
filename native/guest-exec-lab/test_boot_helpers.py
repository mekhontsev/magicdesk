#!/usr/bin/env python3
"""Host checks for shared nested-system readiness and TCP/UDP fixtures."""
from pathlib import Path
import io
import errno
import json
import runpy
import socket
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, call, patch
import urllib.error

from init_fixture import installed_boot, start_service, enable_runit_service, RUNIT_START
from installed_desktop import attach_window, keep_display_awake, stop_owned_resources, wait_controller
from test_os_boot import check_runit_boot, check_systemd_boot
from test_nixos_boot import boot_script
from test_snap import PREPARE as SNAP_PREPARE
from test_booted_libvirt import prepare_libvirt

sys.path.insert(0, str(Path(__file__).with_name('fixtures')))
from network_exchange import exchange, inbound_port
from systemd_ready import wait_systemd


class BootHelpers(unittest.TestCase):
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
        client.call.return_value = dict(held=True, displayId=0, leaseId='phone-lease')
        state = dict(display=dict(id=7), awakeLease='fixture-lease')
        keep_display_awake(client, state, statefile, 0)
        self.assertEqual([
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
        client.call.side_effect = RuntimeError('lease ownership changed')
        state = dict(display=dict(id=7), awakeLease='fixture-lease')
        with self.assertRaises(RuntimeError):
            keep_display_awake(client, state, statefile, 0)
        self.assertEqual('fixture-lease', state['awakeLease'])
        self.assertEqual(1, client.call.call_count)
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

    def test_systemd_startup_success_cannot_hide_shutdown_failures(self):
        boot = 'running\n0 loaded units listed.\nPASS boot service IO and restart\n'
        check_systemd_boot(boot + 'dbus.service: Deactivated successfully.\n')
        for unit in ('lxc.service', 'podman-restart.service', 'mnt-fixture.mount'):
            for result in ('exit-code', 'timeout', 'signal'):
                with self.subTest(unit=unit, result=result), self.assertRaisesRegex(AssertionError, unit):
                    check_systemd_boot(boot + f"\x1b[31m{unit}: Failed with result '{result}'.\x1b[0m\r\n")
        with self.assertRaises(AssertionError):
            check_systemd_boot('systemd-test-generator failed with exit status 1\n' + boot)

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
                        self.assertIn('/run/systemd/network/00-md-fixture-uplink.network', boot)
                        self.assertIn('Name=eth0\n[Network]\nDHCP=no\nIPv6AcceptRA=no\n'
                                      'LinkLocalAddressing=no\nKeepConfiguration=static\n', boot)
                        self.assertNotIn('/etc/systemd/network', boot)
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
        probe = Mock(side_effect=[
            subprocess.CompletedProcess([], 1, '', 'Failed to connect: No such file or directory'),
            subprocess.CompletedProcess([], 0, 'running\n', '')])
        alive = Mock()
        with patch('systemd_ready.threading.Event') as event:
            wait_systemd(probe, alive)
        alive.assert_called_once()
        event.return_value.wait.assert_called_once()

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
