"""Distribution fixtures retain their selected store, executor and graphics owner."""
import json
import argparse
import importlib.util
import shlex
import shutil
import subprocess
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import Mock, mock_open, patch

LAB = Path(__file__).resolve().parents[2] / 'native/guest-exec-lab'
with patch.object(sys, 'path', [str(LAB), str(LAB / 'fixtures'), *sys.path]):
    import installed_desktop
    import k3s_fixture
    import test_k3s
    import test_k3s_cluster
    import test_k3s_boot
    import test_os_boot
    import init_fixture
    import fixture_app
    import test_booted_containers
    import probe_podman_runtime
    from probe_docker_runtime import ensure_image
    import probe_packet_runtime
    from fixture_executor import FixtureExecutor
    from test_oci_services import Suite
    specification = importlib.util.spec_from_file_location('k3s_boot_guest', LAB / 'fixtures/k3s-boot.py')
    k3s_boot_guest = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(k3s_boot_guest)
    specification = importlib.util.spec_from_file_location('buildkit_guest', LAB / 'fixtures/buildkit-build.py')
    buildkit_guest = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(buildkit_guest)


class MatrixFixtureTest(unittest.TestCase):
    def test_packet_vm_rejects_minimal_contract_initramfs(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(probe_packet_runtime.subprocess, 'Popen') as decompress, \
                patch.object(probe_packet_runtime.subprocess, 'run'):
            decompress.return_value.__enter__.return_value.wait.return_value = 0
            with self.assertRaisesRegex(ValueError, 'official Alpine initramfs; missing bin/sh'):
                probe_packet_runtime.vm_archive(Path(directory), Path('minimal.gz'), Path('packet-test'))

    def test_podman_default_network_retains_package_policy(self):
        self.assertEqual([], probe_podman_runtime.network_arguments('default'))
        for network in ('bridge', 'pasta', 'slirp4netns', 'host', 'none', 'fixture-net'):
            with self.subTest(network=network):
                self.assertEqual(['--network', network], probe_podman_runtime.network_arguments(network))

    def test_booted_container_identity_uses_existing_controller(self):
        for engine, rootless in (('docker', False), ('docker', True), ('podman', False), ('podman', True)):
            for trace in ((False, True) if engine == 'docker' and rootless else (False,)):
                with self.subTest(engine=engine, rootless=rootless, trace=trace), tempfile.TemporaryDirectory() as directory:
                    state = dict(id='session', name='fixture', init='systemd', environment={},
                                 directory='/host/fixture', target='/fixture', networkProfile='routed',
                                 user='tester', userPort=12345, rootPort=12346, token='test-token')
                    session = Path(directory) / 'session.json'
                    session.write_text(json.dumps(state))
                    argv = ['fixture', '--session', str(session), '--engine', engine]
                    if rootless:
                        argv.append('--rootless')
                    if trace:
                        argv.append('--trace-runtime')
                    if engine == 'docker':
                        argv += ['--image-archive', '/fixture/image.tar']
                    suite = Mock(report={})
                    with patch.object(sys, 'argv', argv), patch('builtins.print'), \
                            patch.object(test_booted_containers, 'Suite', return_value=suite), \
                            patch.object(test_booted_containers, 'Observer', return_value=Mock(address='observer')), \
                            patch.object(test_booted_containers.urllib.request, 'urlopen',
                                         side_effect=RuntimeError('request reached')) as send:
                        with self.assertRaisesRegex(RuntimeError, 'request reached'):
                            test_booted_containers.main()
                    request = send.call_args.args[0]
                    self.assertEqual('http://127.0.0.1:' + str(state['userPort' if rootless else 'rootPort']),
                                     request.full_url)
                    command = json.loads(request.data)['command']
                    wrapper = shlex.split(command)
                    self.assertEqual(test_booted_containers.PRESERVE_SYSTEM_MOUNTS, wrapper[2])
                    probe = wrapper[3]
                    self.assertEqual(not rootless and engine == 'docker', 'systemctl' in probe)
                    if engine == 'podman':
                        options = json.loads(shlex.split(probe)[-1])
                        self.assertTrue(options['prepared'])
                        self.assertEqual('enabled', options['cgroups'])
                        self.assertEqual('default' if rootless else 'bridge', options['network'])
                        self.assertEqual('tester' if rootless else None, options.get('rootlessUser'))
                    else:
                        options = json.loads(shlex.split(probe)[-1])
                        self.assertEqual('tester' if rootless else None, options.get('rootless_user'))
                        self.assertEqual('v2' if rootless else None, options.get('cgroup'))
                        self.assertEqual('/fixture/image.tar', options['image_archive'])
                        if rootless:
                            self.assertEqual(trace, options['trace_runtime'])
                    self.assertEqual(trace, suite.report['diagnostics']['runtime'])
                    suite.finish.assert_called_once()

    def test_docker_image_archive_never_falls_back_to_registry(self):
        for archive in ('', '/fixture/image.tar'):
            for results in ([True], [False, False], [False, True, False], [False, True, True]):
                with self.subTest(archive=archive, results=results):
                    client = Mock(side_effect=results)
                    self.assertEqual(results[-1], ensure_image(client, 'test:image', archive))
                    expected = [('image', 'inspect', 'test:image')]
                    if len(results) > 1:
                        expected.append(('load', '--input', archive) if archive else ('pull', 'test:image'))
                    if len(results) > 2:
                        expected.append(('image', 'inspect', 'test:image'))
                    self.assertEqual(expected, [call.args for call in client.call_args_list])

    def test_k3s_diagnostics_are_explicit(self):
        for option, expected in (([], None), (['--diagnostics'], 'all'),
                                 (['--diagnostics', 'signals'], 'signals')):
            parser = argparse.ArgumentParser()
            k3s_fixture.add_runtime_arguments(parser)
            self.assertEqual(expected, parser.parse_args(option).diagnostics)
            from fixture_executor import diagnostic_options
            flags = diagnostic_options(expected)
            self.assertEqual([] if expected is None else
                             ['--diagnostics' if expected == 'all' else '--diagnostics=signals'], flags)
            command = test_k3s_boot.boot_command(['/runner'], '/fixture', 'openrc', 'owned',
                                                60, 'observe', '0', diagnostics=expected)
            self.assertEqual(flags + ['--network'], command[1:2 + len(flags)])

    def test_buildkit_engine_selection_keeps_oci_sandbox_admission(self):
        worker = {'labels': {'org.mobyproject.buildkit.worker.network': 'cni',
                             'org.mobyproject.buildkit.worker.oci.process-mode': 'sandbox'}}
        for engine, prefix in (
                ('podman', ['podman', '--root', '/var/lib/md-fixture-podman',
                            '--runroot', '/var/lib/md-fixture-podman-run', '--storage-driver', 'overlay']),
                ('docker', ['docker', '--host', 'unix:///var/run/docker.sock'])):
            with self.subTest(engine=engine), tempfile.TemporaryDirectory() as directory:
                report = Path(directory) / 'report.json'
                argv = ['fixture', str(report), '--engine', engine, '--image', 'fixture:base']
                replies = [Mock(stdout='version\n', stderr='', returncode=0),
                           Mock(stdout='version\n', stderr='', returncode=0),
                           Mock(stdout=json.dumps([worker]), stderr='', returncode=0),
                           RuntimeError('engine reached')]
                with patch.object(sys, 'argv', argv), patch('builtins.print'), \
                        patch.object(buildkit_guest.subprocess, 'run', side_effect=replies) as run:
                    with self.assertRaisesRegex(RuntimeError, 'engine reached'):
                        buildkit_guest.main()
                self.assertEqual((*prefix, 'image', 'inspect', 'fixture:base'), run.call_args.args[0])
                result = json.loads(report.read_text())
                self.assertEqual([worker], result['workers'])
                self.assertEqual(engine, result['engine'])
                self.assertFalse(result['passed'])

    def test_app_transport_encloses_workload_and_cleanup_deadlines(self):
        for seconds in (1, 60, 1800 + 45, 3600 + 15, 7200):
            self.assertEqual(seconds, fixture_app.deadline_seconds(str(seconds)))
        for seconds in (0, -1, 7201):
            with self.assertRaises(argparse.ArgumentTypeError):
                fixture_app.deadline_seconds(seconds)

    def test_invalid_app_deadline_is_rejected_before_launch(self):
        owner = FixtureExecutor.__new__(FixtureExecutor)
        with patch('fixture_executor.subprocess.run') as launch:
            with self.assertRaises(argparse.ArgumentTypeError):
                owner._app_command('true', 7201)
        launch.assert_not_called()

    def test_app_command_borrows_its_owner_console(self):
        owner = FixtureExecutor.__new__(FixtureExecutor)
        owner.output = Path('/unused')
        owner.console = 'console-owned'
        owner.lock = threading.Lock()
        owner.closed = False
        owner.active = set()
        with patch('fixture_executor.subprocess.run', side_effect=RuntimeError('driver reached')) as launch:
            with self.assertRaisesRegex(RuntimeError, 'driver reached'):
                owner._app_command('true', 60)
        argv = launch.call_args.args[0]
        self.assertEqual('console-owned', argv[argv.index('--console-id') + 1])
        self.assertFalse(owner.active)

    def test_app_driver_closes_only_its_owned_console(self):
        for borrowed in (False, True):
            with self.subTest(borrowed=borrowed), tempfile.TemporaryDirectory() as directory:
                config = Path(directory) / 'config.toml'
                config.write_text('[mcp_servers.magicdesk]\nurl="http://fixture"\n'
                                  '[mcp_servers.magicdesk.http_headers]\nAuthorization="Bearer fixture"\n')
                argv = ['fixture', '--command', 'true', '--config', str(config), '--output', directory]
                if borrowed:
                    argv += ['--console-id', 'console-borrowed']
                client = Mock()
                launched = False
                def call(name, arguments=None):
                    nonlocal launched
                    if name == 'console.open':
                        return {'sessionId': 'console-owned'}
                    if name == 'console.execute' and not launched:
                        launched = True
                        raise RuntimeError('launch rejected')
                    return {'exitCode': 0}
                client.call.side_effect = call
                transport = Mock()
                transport.Client.return_value = client
                with patch.object(sys, 'argv', argv), \
                        patch.object(fixture_app.importlib.util, 'spec_from_file_location'), \
                        patch.object(fixture_app.importlib.util, 'module_from_spec', return_value=transport):
                    with self.assertRaisesRegex(RuntimeError, 'launch rejected'):
                        fixture_app.main()
                calls = [item.args[0] for item in client.call.call_args_list]
                self.assertEqual(not borrowed, 'console.open' in calls)
                self.assertEqual(not borrowed, 'console.close' in calls)

    def test_os_boot_retains_selected_executor(self):
        for executor in ('app', 'shell'):
            argv = ['fixture', '--executor', executor, '--store', '/selected/store',
                    '--build', 'build/native', '--output', '/unused']
            with self.subTest(executor=executor), patch.object(sys, 'argv', argv), \
                    patch('test_oci_services.Suite', side_effect=RuntimeError('suite reached')) as suite:
                with self.assertRaisesRegex(RuntimeError, 'suite reached'):
                    test_os_boot.main()
            suite.assert_called_once_with(Path('/unused'), Path('build/native'), executor=executor)

    def test_k3s_process_fixture_uses_service_umask(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory) / 'fixture.py'
            fixture.write_text('import os\nprint(oct(os.umask(0)))\n')
            with patch.object(k3s_fixture, 'STATE', ''):
                command = k3s_fixture.guest_command(fixture, '/k3s')
            script = command[5].replace('exec python3', 'exec ' + shlex.quote(sys.executable))
            result = subprocess.run(['sh', '-ec', 'umask 077\n' + script, *command[6:]],
                                    text=True, capture_output=True, timeout=5, check=True)
        self.assertEqual('0o22', result.stdout.strip())

    def test_k3s_agent_retains_root_fixture_path_across_pivot(self):
        with patch.object(k3s_fixture, 'command') as command, \
                patch.object(k3s_fixture.tempfile, 'mkdtemp', return_value='/tmp/config'), \
                patch.object(k3s_fixture.os, 'chdir'), \
                patch.object(k3s_fixture.os, 'set_inheritable'), \
                patch.object(Path, 'mkdir'), \
                patch.object(k3s_fixture.os, 'execvp', side_effect=RuntimeError('execve')) as execute:
            with self.assertRaisesRegex(RuntimeError, 'execve'):
                k3s_fixture.enter_agent_root('/agent', '/md-k3s-test/k3s', 9)
        command.assert_any_call('mount', '--bind', '/md-k3s-test', '/agent/md-k3s-test')
        execute.assert_called_once_with('python3', ['python3',
            '/md-k3s-test/test.py', '--agent', '/md-k3s-test/k3s', '9', ''])

    def test_k3s_uses_installed_or_explicit_runtime(self):
        for module, environments in ((test_k3s, ['steamos']),
                                     (test_k3s_cluster, ['debian', 'alpine'])):
            for build in ([], ['build/native']):
                with self.subTest(module=module.__name__, build=build):
                    argv = ['fixture', *build, *environments, '/binary/k3s',
                            '--executor', 'app', '--store', '/borrowed/server']
                    if module is test_k3s_cluster:
                        argv += ['--agent-store', '/borrowed/agent']
                    with patch.object(sys, 'argv', argv), patch.object(module, 'run') as run:
                        module.main()
                    args = run.call_args.args[0]
                    self.assertEqual(Path(build[0]) if build else None, args.build)
                    self.assertEqual('app', args.executor)
                    self.assertEqual('/borrowed/server', args.store)
                    self.assertEqual(Path('/binary/k3s'), args.k3s)
                    self.assertEqual(environments[0], args.environment)
                    self.assertEqual(environments[1] if len(environments) > 1 else None,
                                     args.agent_environment)

    def test_session_executor_cannot_be_changed(self):
        with self.assertRaisesRegex(ValueError, 'executor cannot be changed'):
            Suite(Path('/unused'), session={'executor': {'mode': 'app'}}, executor='shell')

    def test_k3s_boot_retains_selected_executor_and_store(self):
        for executor in ('app', 'shell'):
            argv = ['fixture', 'build/native', 'steamos', '/binary/k3s', '/install.sh',
                    '--init', 'systemd', '--executor', executor, '--store', '/selected/store']
            with self.subTest(executor=executor), patch.object(sys, 'argv', argv), \
                    patch.object(test_k3s_boot, 'run') as run:
                test_k3s_boot.main()
            args = run.call_args.args[0]
            self.assertEqual(executor, args.executor)
            self.assertEqual('/selected/store', args.store)
            self.assertEqual('steamos', args.environment)
            self.assertEqual(Path('build/native'), args.build)
            self.assertEqual(Path('/install.sh'), args.installer)

    def test_k3s_boot_cleanup_uses_the_same_init_view(self):
        prefix = ['/exact/runner', '--store', '/borrowed/store']
        for mode, arguments in (('observe', ('0',)), ('cleanup', ())):
            command = test_k3s_boot.boot_command(prefix, '/fixture with space', 'systemd',
                                               'owned-launch', 240, mode, *arguments)
            self.assertEqual(prefix, command[:len(prefix)])
            self.assertEqual('bridge', command[command.index('--network') + 1])
            script = command[command.index('-c') + 1]
            self.assertEqual(['/fixture with space/magicdesk-init', '/usr/bin/python3',
                              '/fixture with space/k3s-boot.py', mode, '/fixture with space',
                              'systemd', *arguments], shlex.split(script))

    def test_k3s_nixos_uses_packaged_service_without_upstream_installer(self):
        for executor in ('app', 'shell'):
            argv = ['fixture', 'build/native', 'nixos', '--init', 'systemd',
                    '--service-provider', 'nixos', '--executor', executor]
            with self.subTest(executor=executor), patch.object(sys, 'argv', argv), \
                    patch.object(test_k3s_boot, 'run') as run:
                test_k3s_boot.main()
            args = run.call_args.args[0]
            self.assertEqual('nixos', args.service_provider)
            self.assertIsNone(args.k3s)
            self.assertIsNone(args.installer)
            self.assertEqual(executor, args.executor)

    def test_k3s_nixos_boot_and_cleanup_use_selected_generation(self):
        binary = '/nix/store/selected-system/sw/bin'
        for mode, arguments in (('observe', ('0', '1.0', 'nixos')), ('cleanup', ())):
            command = test_k3s_boot.boot_command(['/runner'], '/fixture', 'systemd',
                                               'owned', 240, mode, *arguments, binary=binary)
            self.assertEqual(binary + '/script', command[command.index('--') + 1])
            self.assertEqual(['/fixture/magicdesk-init', binary + '/python3',
                              '/fixture/k3s-boot.py', mode, '/fixture', 'systemd', *arguments],
                             shlex.split(command[command.index('-c') + 1]))

    def test_k3s_boot_cleanup_stops_services_before_restoring_files(self):
        for init, stop in (('systemd', ('systemctl', 'stop', 'k3s')),
                           ('openrc', ('rc-service', 'k3s', 'stop'))):
            operations = []
            console = mock_open()
            with self.subTest(init=init), patch.object(Path, 'exists', return_value=True), \
                    patch.object(k3s_boot_guest, 'command', side_effect=lambda *a: operations.append(a)), \
                    patch.object(k3s_boot_guest, 'cleanup', side_effect=lambda p: operations.append(('restore', p))), \
                    patch('builtins.open', console):
                k3s_boot_guest.cleanup_boot(Path('/fixture'), init)
            self.assertEqual([stop, ('/bin/sh', '/usr/local/bin/k3s-killall.sh'),
                              ('restore', Path('/fixture'))], operations[:3])
            self.assertEqual('systemctl' if init == 'systemd' else 'openrc-shutdown', operations[3][0])
            output = ''.join(call.args[0] for call in console().write.call_args_list)
            self.assertIn('K3S_BOOT_CLEANUP True', output)

    def test_k3s_boot_cleanup_failure_still_powers_off(self):
        console = mock_open()
        with patch.object(Path, 'exists', return_value=True), \
                patch.object(k3s_boot_guest, 'command'), \
                patch.object(k3s_boot_guest, 'cleanup', side_effect=OSError('restore failed')), \
                patch.object(k3s_boot_guest.traceback, 'print_exc'), \
                patch.object(k3s_boot_guest, 'poweroff') as poweroff, patch('builtins.open', console):
            k3s_boot_guest.cleanup_boot(Path('/fixture'), 'systemd')
        poweroff.assert_called_once_with('systemd')
        output = ''.join(call.args[0] for call in console().write.call_args_list)
        self.assertIn('K3S_BOOT_CLEANUP False', output)

    def test_init_retirement_selects_only_its_exact_launch(self):
        suite = Mock()
        suite.image_command.side_effect = lambda *args: ['/image', *args]
        suite.command.side_effect = [json.dumps(dict(label='other', launchId='untouched')) + '\n' +
                                    json.dumps(dict(label='fixture', launchId='owned')), '']
        init_fixture.stop_init_launch(suite, '/store', 'fixture')
        self.assertEqual([['/image', 'launches', '/store'], ['/image', 'stop', '/store', 'owned']],
                         [call.args[0] for call in suite.command.call_args_list])

    def test_init_retirement_does_not_guess_between_duplicate_labels(self):
        suite = Mock()
        suite.command.return_value = '\n'.join(json.dumps(dict(label='fixture', launchId=identity))
                                                for identity in ('one', 'two'))
        with self.assertRaises(AssertionError):
            init_fixture.stop_init_launch(suite, '/store', 'fixture')
        suite.command.assert_called_once()

    def test_retained_session_keeps_its_identity_and_helpers(self):
        for mode in ('app', 'shell'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                client = Mock()
                client.call.return_value = dict(app={}, device={})
                owner = Mock(mode=mode, uid=10647 if mode == 'app' else 2000,
                             root='/fixture', console='console')
                session = dict(id='retained', ready=True, runner='/exact/run',
                               image='/exact/image', executor={'mode': mode})
                config = {'mcp_servers': {'magicdesk': {}}}
                with patch.object(Suite, 'new_client', return_value=client), \
                        patch('test_oci_services.FixtureExecutor', return_value=owner) as executor, \
                        patch('test_oci_services.KernelProfile') as profile, \
                        patch.object(Path, 'read_text', return_value=''), \
                        patch('test_oci_services.tomllib.loads', return_value=config):
                    suite = Suite(Path(directory), session=session)
                self.assertEqual(mode, executor.call_args.args[3])
                self.assertEqual('/exact/run', suite.runner)
                self.assertEqual('/exact/image', suite.image)
                self.assertEqual('retained', suite.report['session'])
                profile.return_value.bind_session.assert_called_once()
                owner.execute.assert_not_called()

    def test_raw_store_never_resolves_a_name(self):
        suite = Suite.__new__(Suite)
        suite.image = '/image'
        suite.command = Mock(side_effect=['', '{"format":17}', ''])
        result = suite.installed_environment('steamos', '/borrowed/store')
        self.assertEqual('/borrowed/store', result['store'])
        self.assertEqual([], result['launches'])
        self.assertEqual([['/image', command, '/borrowed/store']
                          for command in ('prepare', 'inspect', 'launches')],
                         [call.args[0] for call in suite.command.call_args_list])

    def test_offscreen_does_not_open_android_hosts_or_claim_awake(self):
        client = Mock()
        client.call.return_value = dict(matched=True, session={
            'offscreen': {'outputs': [{'windowId': 17}]}})
        state = dict(offscreen=True, sessionId='graphics')
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'session.json'
            installed_desktop.keep_display_awake(client, state, path)
            client.call.assert_not_called()
            installed_desktop.attach_window(client, state, path)
            self.assertEqual(17, json.loads(path.read_text())['windowId'])
        client.call.assert_called_once_with('wait_for_state', dict(
            condition='graphics_output_ready', sessionId='graphics', timeoutMillis=60000))

    def test_recipe_captures_shell_without_parent_environment(self):
        launch = installed_desktop.recipe_launch('/apk path/mksh', '/fixture', 'exec "$tool"', '/fixture/log')
        argv = shlex.split(launch)
        self.assertEqual(['MAGICDESK_TOOLS=/fixture',
                          'MAGICDESK_ANDROID_SHELL=/apk path/mksh', '/apk path/mksh',
                          '-c', 'exec "$tool"'], argv[:5])
        with self.assertRaises(AssertionError):
            installed_desktop.recipe_launch('', '/fixture', 'true', '/fixture/log')

    def test_recipe_accepts_apk_equals_in_executable_path(self):
        with tempfile.TemporaryDirectory() as directory:
            shell = Path(directory) / 'apk==' / 'sh'
            shell.parent.mkdir()
            shell.symlink_to(shutil.which('sh'))
            log = Path(directory) / 'output'
            recipe = 'printf "%s\\n" "$MAGICDESK_ANDROID_SHELL" "$MAGICDESK_TOOLS"'
            launch = installed_desktop.recipe_launch(str(shell), '/fixture', recipe, str(log))
            result = subprocess.run(['sh', '-c', launch], timeout=5)
            self.assertEqual(0, result.returncode, log.read_text())
            self.assertEqual([str(shell), '/fixture'], log.read_text().splitlines())

    def test_k3s_matrix_contains_every_recipe(self):
        matrix = (LAB / 'validation-matrix.md').read_text()
        table = matrix.split('### K3s\n', 1)[1].split('| Recipe ID | Shell | User |\n', 1)[1]
        table = table.split('\n\n', 1)[0]
        self.assertEqual({'debian', 'ubuntu', 'alpine', 'fedora', 'arch', 'opensuse',
                          'openkylin', 'deepin', 'manjaro', 'void', 'armtix', 'nixos', 'steamos'},
                         {line.split('|')[1].strip() for line in table.splitlines()
                          if line.startswith('| ') and not line.startswith(('| Recipe', '| ---'))})


if __name__ == '__main__':
    unittest.main()
