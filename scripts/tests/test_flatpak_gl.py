"""The installer's embedded GL-extension publisher, without a Flatpak install."""
import configparser
import json
import os
from pathlib import Path
import subprocess
import tempfile
import types
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / 'install_linux.sh'


class FlatpakGLTest(unittest.TestCase):
    def setUp(self):
        source = SCRIPT.read_text().split("cat <<'FLATPAK_GL_PY'\n", 1)[1].split('\nFLATPAK_GL_PY', 1)[0]
        self.helper = types.ModuleType('flatpak_gl')
        exec(compile(source, str(SCRIPT) + ':flatpak_gl_helper', 'exec'), self.helper.__dict__)
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.base = self.root / 'flatpak'
        self.prefix = self.root / 'mesa'
        (self.prefix / 'lib').mkdir(parents=True)
        (self.prefix / '.complete').write_text('recipe-one\n')
        for name in ['libvulkan_freedreno.so', 'libEGL_mesa.so.0', 'libgallium.so']:
            (self.prefix / 'lib' / name).write_bytes(b'library')
        for path, library in [('vulkan/icd.d/freedreno_icd.aarch64.json', 'libvulkan_freedreno.so'),
                              ('glvnd/egl_vendor.d/50_mesa.json', 'libEGL_mesa.so.0')]:
            manifest = self.prefix / 'share' / path
            manifest.parent.mkdir(parents=True, exist_ok=True)
            manifest.write_text(json.dumps({'file_format_version': '1.0.0',
                                           'ICD': {'library_path': str(self.prefix / 'lib' / library)}}))
        self.verifier = patch.object(self.helper, 'verify_runtime')
        self.verify = self.verifier.start()
        self.addCleanup(self.verifier.stop)
        self.link = self.base / 'extension' / self.helper.EXTENSION / 'aarch64/25.08'

    def runtime(self, name='org.freedesktop.Platform', commit='commit-one', layout='lib/aarch64-linux-gnu/GL'):
        base = self.base / 'runtime' / name / 'aarch64/25.08'
        deployment = base / commit
        deployment.mkdir(parents=True)
        (deployment / 'files').mkdir()
        (deployment / 'metadata').write_text(
            '[Runtime]\nname=' + name + '\n[Extension org.freedesktop.Platform.GL]\n'
            'versions=25.08;25.08-extra;1.4\nsubdirectories=true\ndirectory=' + layout + '\n')
        (base / 'active').unlink(missing_ok=True)
        (base / 'active').symlink_to(commit)
        return deployment

    def test_no_flatpak_is_noop(self):
        self.helper.prepare(self.base, self.prefix)
        self.assertFalse(self.base.exists())

    def test_immutable_store_identity_does_not_require_mutating_the_prefix(self):
        (self.prefix / '.complete').unlink()
        store_path = Path('/nix/store/' + 'a' * 32 + '-magicdesk-mesa-26.2.3')
        with patch.object(Path, 'resolve', return_value=store_path):
            self.assertEqual(str(store_path), self.helper.build_identity(self.prefix))
        self.assertFalse((self.prefix / '.complete').exists())
        with self.assertRaisesRegex(ValueError, 'Incomplete Mesa build'):
            self.helper.build_identity(self.prefix)

    def test_readonly_source_directories_do_not_prevent_failed_admission_cleanup(self):
        self.runtime()
        (self.prefix / 'lib').chmod(0o555)
        self.addCleanup((self.prefix / 'lib').chmod, 0o755)
        self.verify.side_effect = ValueError('incompatible')
        with self.assertRaisesRegex(ValueError, 'incompatible'):
            self.helper.prepare(self.base, self.prefix)
        self.assertEqual(0o555, (self.prefix / 'lib').stat().st_mode & 0o777)
        self.assertEqual([], list((self.base / '.magicdesk-gl').glob('.prepare-*')))

    def test_system_extension_parents_remain_readable_with_private_umask(self):
        self.runtime()
        before = os.umask(0o077)
        try:
            self.helper.prepare(self.base, self.prefix)
        finally:
            os.umask(before)
        for directory in (self.link, self.link.parent, self.link.parent.parent,
                          self.base / 'extension', self.base / 'overrides'):
            self.assertEqual(directory.stat().st_mode & 0o777, 0o755)

    def test_publish_relocated_library_bundle_without_rebuild(self):
        self.runtime()
        self.helper.prepare(self.base, self.prefix)
        self.assertTrue(self.link.is_dir())
        self.assertFalse(self.link.is_symlink())
        self.verify.assert_called_once()
        icd = json.loads((self.link / 'vulkan/icd.d/magicdesk.json').read_text())
        self.assertEqual(icd['ICD']['library_path'],
                         '/usr/lib/aarch64-linux-gnu/GL/host/lib/libvulkan_freedreno.so')
        self.assertEqual((self.link / 'lib/libgallium.so').read_bytes(), b'library')
        self.assertFalse((self.link.parent / '1.4').exists())
        self.assertFalse((self.link.parent / '25.08-extra').exists())

    def test_unchanged_receipt_does_not_repeat_abi_probe(self):
        self.runtime()
        self.helper.prepare(self.base, self.prefix)
        target = self.link.stat().st_ino
        self.helper.prepare(self.base, self.prefix)
        self.verify.assert_called_once()
        self.assertEqual(self.link.stat().st_ino, target)

    def test_environment_keeps_user_permissions_and_explicit_driver_choice(self):
        self.runtime()
        path = self.base / 'overrides/global'
        path.parent.mkdir()
        path.write_text('[Context]\nfilesystems=~/Documents;\nunset-environment=OTHER;\n'
                        '[Environment]\nVK_DRIVER_FILES=/custom/icd.json\n')
        path.chmod(0o600)
        self.helper.prepare(self.base, self.prefix)
        data = configparser.ConfigParser(interpolation=None)
        data.optionxform = str
        data.read(path)
        self.assertEqual(data['Context']['filesystems'], '~/Documents;')
        self.assertEqual(data['Environment']['VK_DRIVER_FILES'], '/custom/icd.json')
        unset = data['Context']['unset-environment'].split(';')
        self.assertIn('OTHER', unset)
        self.assertIn('__EGL_VENDOR_LIBRARY_FILENAMES', unset)
        self.assertIn('GBM_BACKENDS_PATH', unset)
        self.assertNotIn('VK_DRIVER_FILES', unset)
        self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        before = path.read_bytes()
        self.helper.prepare(self.base, self.prefix)
        self.assertEqual(path.read_bytes(), before)

    def test_failed_admission_does_not_change_environment(self):
        self.runtime()
        self.verify.side_effect = ValueError('incompatible')
        with self.assertRaises(ValueError):
            self.helper.prepare(self.base, self.prefix)
        self.assertFalse((self.base / 'overrides/global').exists())

    def test_admission_loads_gbm_backend_as_well_as_top_level_libraries(self):
        runtime = self.runtime()
        loader = runtime / 'files/lib/ld-linux-aarch64.so.1'
        loader.parent.mkdir()
        loader.touch()
        backend = self.prefix / 'lib/gbm/dri_gbm.so'
        backend.parent.mkdir()
        backend.touch()
        backend.with_name('alias_gbm.so').symlink_to(backend.name)
        self.verifier.stop()
        with patch.object(self.helper.subprocess, 'run') as run:
            run.return_value.returncode = 0
            run.return_value.stdout = 'MD_MESA_PROBE_OK\n'
            self.helper.verify_runtime(runtime / 'metadata', self.prefix)
        command = run.call_args.args[0]
        self.assertEqual(command[-3:], ['--preload', '/probe', '/usr/bin/true'])
        libraries = run.call_args.kwargs['env']['MD_MESA_PROBE_LIBRARIES'].splitlines()
        self.assertIn('/mdgl/lib/gbm/dri_gbm.so', libraries)
        self.assertIn('/mdgl/lib/libgallium.so', libraries)
        self.assertNotIn('/mdgl/lib/gbm/alias_gbm.so', libraries)

    def test_loader_ignoring_the_probe_is_not_an_abi_pass(self):
        runtime = self.runtime()
        loader = runtime / 'files/lib/ld-linux-aarch64.so.1'
        loader.parent.mkdir()
        loader.touch()
        self.verifier.stop()
        with patch.object(self.helper.subprocess, 'run') as run:
            run.return_value = subprocess.CompletedProcess([], 0, 'ERROR: preload ignored\n')
            with self.assertRaisesRegex(ValueError, 'did not execute'):
                self.helper.verify_runtime(runtime / 'metadata', self.prefix)

    def test_update_is_atomic_and_retains_running_sandbox_bundle(self):
        self.runtime()
        self.helper.prepare(self.base, self.prefix)
        before = self.link.stat().st_ino
        (self.prefix / '.complete').write_text('recipe-two\n')
        self.helper.prepare(self.base, self.prefix)
        self.assertNotEqual(self.link.stat().st_ino, before)
        retired = list((self.base / '.magicdesk-gl').glob('retired-*'))
        self.assertEqual(len(retired), 1)
        self.assertEqual(retired[0].stat().st_ino, before)
        self.assertTrue((retired[0] / 'lib/libgallium.so').is_file())

    def test_failed_retirement_after_exchange_never_deletes_old_libraries(self):
        self.runtime()
        self.helper.prepare(self.base, self.prefix)
        old = self.link.stat().st_ino
        (self.prefix / '.complete').write_text('recipe-two\n')
        rename = os.rename

        def fail_retirement(source, target):
            if Path(target).name.startswith('retired-'):
                raise OSError('test retirement failure')
            return rename(source, target)

        with patch.object(self.helper.os, 'rename', side_effect=fail_retirement):
            with self.assertRaisesRegex(ValueError, 'retirement failure'):
                self.helper.prepare(self.base, self.prefix)
        retained = [p for p in (self.base / '.magicdesk-gl').iterdir()
                    if p.is_dir() and p.stat().st_ino == old]
        self.assertEqual(len(retained), 1)
        self.assertEqual((retained[0] / 'lib/libgallium.so').read_bytes(), b'library')

    def test_new_runtime_commit_requires_admission_again(self):
        self.runtime()
        self.helper.prepare(self.base, self.prefix)
        before = self.link.stat().st_ino
        self.runtime(commit='commit-two')
        self.helper.prepare(self.base, self.prefix)
        self.assertEqual(self.verify.call_count, 2)
        self.assertEqual(self.link.stat().st_ino, before)
        self.assertEqual(list((self.base / '.magicdesk-gl').glob('retired-*')), [])

    def test_all_runtimes_sharing_a_branch_must_load_it(self):
        self.runtime()
        self.runtime('org.kde.Platform')
        self.helper.prepare(self.base, self.prefix)
        self.assertEqual(self.verify.call_count, 2)

    def test_driver_abi_failure_uses_sdk_and_rechecks_every_runtime(self):
        self.runtime()
        self.runtime('org.kde.Platform')
        self.verify.side_effect = [self.helper.MesaABIError('GLIBC version'), None, None]
        with patch.object(self.helper, 'sdk_build', return_value=self.prefix) as build:
            self.helper.prepare(self.base, self.prefix, build_sdk=True)
        build.assert_called_once()
        self.assertEqual(self.verify.call_count, 3)
        self.assertTrue((self.link / '.magicdesk-build').is_file())
        self.helper.prepare(self.base, self.prefix)
        self.assertEqual(self.verify.call_count, 3)

    def test_default_abi_failure_never_starts_a_second_mesa_build(self):
        self.runtime()
        self.verify.side_effect = self.helper.MesaABIError('GLIBC version')
        with patch.object(self.helper, 'sdk_build') as build:
            with self.assertRaisesRegex(ValueError, 'No Mesa compilation was started') as error:
                self.helper.prepare(self.base, self.prefix)
        build.assert_not_called()
        self.assertIn('magicdesk-flatpak-gl --build-sdk', str(error.exception))
        self.assertFalse(self.link.exists())
        self.assertEqual(list((self.base / '.magicdesk-gl').glob('.prepare-*')), [])

    def test_sdk_build_requires_an_explicit_command_option(self):
        self.helper.CONFIG = self.root / 'mesa-prefix'
        self.helper.CONFIG.write_text(str(self.prefix))
        for explicit in (False, True):
            with self.subTest(explicit=explicit), \
                    patch.object(self.helper.sys, 'argv', ['magicdesk-flatpak-gl'] +
                                 (['--build-sdk'] if explicit else []) + [str(self.base)]), \
                    patch.object(self.helper, 'prepare') as prepare:
                self.helper.main()
                prepare.assert_called_once_with(self.base, self.prefix, build_sdk=explicit)

    def test_sandbox_failure_never_requests_an_sdk_build(self):
        self.runtime()
        self.verify.side_effect = ValueError('bwrap: Permission denied')
        with patch.object(self.helper, 'sdk_build') as build:
            with self.assertRaisesRegex(ValueError, 'bwrap'):
                self.helper.prepare(self.base, self.prefix)
        build.assert_not_called()
        self.assertFalse(self.link.exists())

    def test_failed_sdk_admission_does_not_publish(self):
        self.runtime()
        self.verify.side_effect = self.helper.MesaABIError('still incompatible')
        with patch.object(self.helper, 'sdk_build', return_value=self.prefix):
            with self.assertRaisesRegex(ValueError, 'still incompatible'):
                self.helper.prepare(self.base, self.prefix, build_sdk=True)
        self.assertFalse(self.link.exists())
        self.assertEqual(list((self.base / '.magicdesk-gl').glob('.prepare-*')), [])

    def test_incomplete_sdk_metadata_retires_incompatible_old_selection(self):
        self.runtime()
        self.helper.prepare(self.base, self.prefix)
        old = self.link.stat().st_ino
        self.runtime(commit='needs-sdk')
        self.verify.side_effect = self.helper.MesaABIError('GLIBC version')
        with patch.object(self.helper, 'sdk_build', side_effect=configparser.NoOptionError('sdk', 'Runtime')):
            with self.assertRaisesRegex(ValueError, 'sdk'):
                self.helper.prepare(self.base, self.prefix, build_sdk=True)
        self.assertFalse(self.link.exists())
        self.assertEqual(len([p for p in (self.base / '.magicdesk-gl').glob('retired-*')
                              if p.stat().st_ino == old]), 1)
        self.assertEqual(list((self.base / '.magicdesk-gl').glob('.prepare-*')), [])

    def sdk_config(self):
        runtime = self.runtime()
        metadata = runtime / 'metadata'
        metadata.write_text(metadata.read_text().replace('[Runtime]\n',
            '[Runtime]\nruntime=org.freedesktop.Platform/aarch64/25.08\n'
            'sdk=org.freedesktop.Sdk/aarch64/25.08\n'))
        source = self.root / 'source'
        source.mkdir()
        (source / '.patched').touch()
        config = self.root / 'mesa-build.json'
        config.write_text(json.dumps(dict(source=str(source), identity='recipe-one', jobs=2,
                                         options=['-Dvulkan-drivers=freedreno'])))
        self.helper.BUILD_CONFIG = config
        return metadata

    def test_missing_sdk_does_not_start_nested_install_transaction(self):
        metadata = self.sdk_config()
        with patch.dict(os.environ, XDG_DATA_HOME=str(self.root)), \
                patch.object(self.helper.subprocess, 'run') as run:
            run.side_effect = [subprocess.CompletedProcess([], 1, '', 'missing'),
                               subprocess.CompletedProcess([], 0, 'flathub\n', '')]
            with self.assertRaisesRegex(ValueError, 'flatpak --user install flathub org.freedesktop.Sdk'):
                self.helper.sdk_build(self.base, self.prefix, metadata, self.root)
        self.assertEqual(run.call_count, 2)
        self.assertTrue(all('info' in call.args[0] for call in run.call_args_list))

    def test_sdk_source_identity_is_checked_before_commands(self):
        metadata = self.sdk_config()
        (self.prefix / '.complete').write_text('other-recipe\n')
        with patch.object(self.helper.subprocess, 'run') as run:
            with self.assertRaisesRegex(ValueError, 'source selection'):
                self.helper.sdk_build(self.base, self.prefix, metadata, self.root)
        run.assert_not_called()

    def sdk_runner(self, fail_build=False):
        sdk = self.root / 'deployment'
        sdk.mkdir()
        (sdk / 'metadata').touch()
        module = self.root / 'portable-module'
        module.mkdir()
        (module / '__init__.py').write_text('# portable module\n')
        (module / 'host-extension.so').write_bytes(b'host binary')
        outputs = []

        def run(command, **kwargs):
            if 'info' in command:
                return subprocess.CompletedProcess(command, 0, str(sdk) + '\n', '')
            if 'build-init' in command:
                app = Path(command[3])
                app.mkdir()
                (app / 'metadata').touch()
            elif 'setup' in command:
                output = Path(next(v.split('=', 1)[1] for v in command if v.startswith('--prefix=')))
                outputs.append(output)
                build = output.parent / 'build'
                build.mkdir(exist_ok=True)
                (build / 'build.ninja').touch()
                (build / 'retained.o').write_bytes(b'object')
            elif 'ninja' in command and fail_build:
                raise subprocess.CalledProcessError(1, command)
            elif 'install' in command:
                stage = Path(command[command.index('--destdir') + 1])
                (stage / str(outputs[-1]).lstrip('/')).mkdir(parents=True)
            return subprocess.CompletedProcess(command, 0, '', '')

        return run, types.SimpleNamespace(__file__=str(module / '__init__.py'))

    def test_sdk_build_uses_recipe_options_and_caches_independently_of_job_count(self):
        metadata = self.sdk_config()
        runner, module = self.sdk_runner()
        with patch.dict(os.environ, XDG_DATA_HOME=str(self.root)), \
                patch.object(self.helper.importlib, 'import_module', return_value=module), \
                patch.object(self.helper.subprocess, 'run', side_effect=runner) as run:
            output = self.helper.sdk_build(self.base, self.prefix, metadata, self.root)
            commands = [call.args[0] for call in run.call_args_list]
            self.assertIn('-Dvulkan-drivers=freedreno', next(c for c in commands if 'setup' in c))
            self.assertEqual(next(c for c in commands if 'ninja' in c)[-2:], ['-j', '2'])
            self.assertEqual(next(c for c in commands if 'build-init' in c)[-3:],
                             ['org.freedesktop.Sdk', 'org.freedesktop.Platform', '25.08'])
            self.assertTrue((output / '.complete').is_file())
            modules = output.parent / 'python'
            self.assertTrue((modules / 'yaml/__init__.py').is_file())
            self.assertFalse(list(modules.rglob('*.so')))
            config = json.loads(self.helper.BUILD_CONFIG.read_text())
            config['jobs'] = 8
            self.helper.BUILD_CONFIG.write_text(json.dumps(config))
            run.reset_mock()
            self.assertEqual(self.helper.sdk_build(self.base, self.prefix, metadata, self.root), output)
            self.assertEqual(run.call_count, 1)
            self.assertIn('info', run.call_args.args[0])

    def test_failed_sdk_build_retains_objects_but_not_a_complete_prefix(self):
        metadata = self.sdk_config()
        runner, module = self.sdk_runner(fail_build=True)
        with patch.dict(os.environ, XDG_DATA_HOME=str(self.root)), \
                patch.object(self.helper.importlib, 'import_module', return_value=module), \
                patch.object(self.helper.subprocess, 'run', side_effect=runner):
            with self.assertRaises(subprocess.CalledProcessError):
                self.helper.sdk_build(self.base, self.prefix, metadata, self.root)
        work, = self.root.glob('sdk-*')
        self.assertEqual((work / 'build/retained.o').read_bytes(), b'object')
        self.assertFalse((work / 'prefix').exists())

    def test_abi_failure_retires_old_selection_without_publishing_candidate(self):
        self.runtime()
        self.helper.prepare(self.base, self.prefix)
        old = self.link.stat().st_ino
        self.runtime(commit='incompatible')
        self.verify.side_effect = ValueError('missing GLIBC symbol')
        with self.assertRaisesRegex(ValueError, 'missing GLIBC'):
            self.helper.prepare(self.base, self.prefix)
        self.assertFalse(self.link.exists())
        retired = list((self.base / '.magicdesk-gl').glob('retired-*'))
        self.assertEqual(retired[0].stat().st_ino, old)
        self.assertEqual(list((self.base / '.magicdesk-gl').glob('.prepare-*')), [])

    def test_does_not_replace_foreign_host_extension(self):
        self.runtime()
        self.link.mkdir(parents=True)
        with self.assertRaisesRegex(ValueError, 'independently installed'):
            self.helper.prepare(self.base, self.prefix)
        self.assertTrue(self.link.is_dir())
        self.verify.assert_not_called()

    def test_disable_only_retires_owned_selections(self):
        self.runtime()
        self.helper.prepare(self.base, self.prefix)
        foreign = self.link.parent / '24.08'
        foreign.mkdir()
        self.helper.prepare(self.base, None)
        self.assertFalse(self.link.exists())
        self.assertTrue(foreign.is_dir())

    def test_rejects_path_escape(self):
        self.runtime(layout='lib/../../outside')
        with self.assertRaisesRegex(ValueError, 'Invalid GL'):
            self.helper.prepare(self.base, self.prefix)

    def test_installer_and_desktop_use_the_same_helper(self):
        script = SCRIPT.read_text()
        self.assertIn('exec /usr/local/bin/magicdesk-flatpak-gl "$1"', script)
        self.assertIn('magicdesk-flatpak-gl || echo', script)
        self.assertNotIn('FLATPAK_GL_DRIVERS=host', script)


if __name__ == '__main__':
    unittest.main()
