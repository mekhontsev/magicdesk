"""Snap GPU content preparation without a daemon or installed graphics stack."""
import json
import io
import os
from pathlib import Path
import subprocess
import tempfile
import types
import unittest
from unittest.mock import Mock, patch

SCRIPT = Path(__file__).resolve().parents[1] / 'install_linux.sh'


class SnapGPUTest(unittest.TestCase):
    generation = '2404'

    def setUp(self):
        source = SCRIPT.read_text().split("cat <<'SNAP_GPU_PY'\n", 1)[1].split('\nSNAP_GPU_PY', 1)[0]
        self.helper = types.ModuleType('snap_gpu')
        exec(compile(source, str(SCRIPT), 'exec'), self.helper.__dict__)
        self.gpu = self.helper.PROVIDERS['mesa-' + self.generation]
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.directory = Path(temp.name)
        self.prefix = self.directory / 'mesa'
        (self.prefix / 'lib').mkdir(parents=True)
        (self.prefix / '.complete').write_text('recipe-one\n')
        for path, name in [('vulkan/icd.d/freedreno_icd.aarch64.json', 'libvulkan_freedreno.so'),
                           ('glvnd/egl_vendor.d/50_mesa.json', 'libEGL_mesa.so.0')]:
            target = self.prefix / 'share' / path
            target.parent.mkdir(parents=True)
            target.write_text(json.dumps({'ICD': {'library_path': str(self.prefix / 'lib' / name)}}))
            (self.prefix / 'lib' / name).write_bytes(b'library')
        self.root = self.directory / 'bundle'
        self.root.mkdir()
        self.helper.CACHE = self.directory / 'cache'

    def test_bundle_exports_only_kernel_content_not_an_app_or_full_provider(self):
        self.helper.stage_bundle(self.gpu, self.prefix, self.root, '123456')
        metadata = (self.root / 'meta/snap.yaml').read_text()
        self.assertIn('confinement: strict', metadata)
        self.assertIn('content: ' + self.gpu.plug, metadata)
        self.assertIn('base: ' + self.gpu.base, metadata)
        self.assertNotIn('apps:', metadata)
        self.assertEqual((self.root / self.gpu.sentinel).read_text(), '123456\n')
        icd = json.loads((self.root / 'share/vulkan/icd.d/freedreno_icd.aarch64.json').read_text())
        self.assertEqual(icd['ICD']['library_path'], 'libvulkan_freedreno.so')
        self.assertNotIn(str(self.prefix), (self.root / self.gpu.mangler).read_text())

    def test_missing_icd_library_is_rejected(self):
        (self.prefix / 'lib/libvulkan_freedreno.so').unlink()
        with self.assertRaisesRegex(ValueError, 'Missing Mesa'):
            self.helper.stage_bundle(self.gpu, self.prefix, self.root, '123')

    def test_content_remains_readable_with_private_umask(self):
        before = os.umask(0o077)
        try:
            self.helper.stage_bundle(self.gpu, self.prefix, self.root, '123')
        finally:
            os.umask(before)
        for path in [self.root, *self.root.rglob('*')]:
            self.assertEqual(path.stat().st_mode & 0o777, 0o755 if path.is_dir() else 0o644)

    def test_component_readiness_is_a_publication_event_not_service_start(self):
        self.helper.SNAP_DATA = self.directory / 'data'
        target = self.helper.SNAP_DATA / self.gpu.provider / '123' / self.gpu.plug
        target.mkdir(parents=True)
        with patch.object(self.helper.select, 'select', return_value=([], [], [])):
            with self.assertRaisesRegex(ValueError, 'Timed out'):
                self.helper.wait_for_component(self.gpu, Path('/snap') / self.gpu.provider / '123', 'ready')
        def publish(*args):
            (target / self.gpu.sentinel).write_text('ready\n')
            return ([args[0][0]], [], [])
        with patch.object(self.helper.select, 'select', side_effect=publish) as wait:
            self.helper.wait_for_component(self.gpu, Path('/snap') / self.gpu.provider / '123', 'ready')
            self.assertEqual(wait.call_count, 1)
        with patch.object(self.helper.select, 'select') as wait:
            self.helper.wait_for_component(self.gpu, Path('/snap') / self.gpu.provider / '123', 'ready')
            wait.assert_not_called()

    def test_new_or_unpublished_content_restarts_monitor_but_ready_content_does_not(self):
        self.helper.SNAP_DATA = self.directory / 'data'
        marker = self.helper.SNAP_DATA / self.gpu.provider / '123' / self.gpu.plug / self.gpu.sentinel
        marker.parent.mkdir(parents=True)
        provider = Path('/snap') / self.gpu.provider / '123'
        for previous in (None, 'old', 'ready'):
            with self.subTest(previous=previous):
                marker.unlink(missing_ok=True)
                if previous is not None:
                    marker.write_text(previous + '\n')
                calls = []
                with patch.object(self.helper, 'run', side_effect=lambda argv: calls.append(argv)), \
                     patch.object(self.helper, 'wait_for_component',
                                  side_effect=lambda *args: calls.append(('wait', *args))):
                    self.helper.publish_component(self.gpu, provider, 'ready')
                expected = [['snap', 'start', '--enable', self.gpu.monitor]]
                if previous != 'ready':
                    expected.append(['snap', 'restart', self.gpu.monitor])
                self.assertEqual(calls, expected + [('wait', self.gpu, provider, 'ready')])

    def test_monitor_restart_failure_is_not_successful_publication(self):
        self.helper.SNAP_DATA = self.directory / 'data'
        def run(argv):
            if argv[1] == 'restart':
                raise subprocess.CalledProcessError(1, argv)
        with patch.object(self.helper, 'run', side_effect=run), \
             patch.object(self.helper, 'wait_for_component') as wait:
            with self.assertRaises(subprocess.CalledProcessError):
                self.helper.publish_component(self.gpu, Path('/snap') / self.gpu.provider / '123', 'new')
            wait.assert_not_called()

    def test_external_library_symlink_is_rejected(self):
        (self.prefix / 'lib/escape.so').symlink_to('/etc/passwd')
        with self.assertRaisesRegex(ValueError, 'escapes'):
            self.helper.stage_bundle(self.gpu, self.prefix, self.root, '123')

    def test_component_directory_can_be_created_after_service_start(self):
        self.helper.SNAP_DATA = self.directory / 'data'
        parent = self.helper.SNAP_DATA / self.gpu.provider / '123'
        parent.mkdir(parents=True)
        target = parent / self.gpu.plug
        def publish(*args):
            target.mkdir()
            (target / self.gpu.sentinel).write_text('ready\n')
            return ([args[0][0]], [], [])
        with patch.object(self.helper.select, 'select', side_effect=publish) as wait:
            self.helper.wait_for_component(self.gpu, Path('/snap') / self.gpu.provider / '123', 'ready')
            self.assertEqual(wait.call_count, 1)

    def test_never_replaces_a_foreign_gpu_connection(self):
        for name in (self.gpu.bundle, 'another-driver'):
            with self.subTest(name=name):
                response = io.BytesIO(json.dumps({'result': {'established': [{
                    'plug': {'snap': self.gpu.provider, 'plug': self.gpu.plug},
                    'slot': {'snap': name, 'slot': self.gpu.plug},
                }]}}).encode())
                response.status = 200
                connection = Mock()
                connection.getresponse.return_value = response
                with patch.object(self.helper.http.client, 'HTTPConnection', return_value=connection), \
                     patch.object(self.helper.socket, 'socket'):
                    if name == 'another-driver':
                        with self.assertRaisesRegex(ValueError, 'another GPU provider'):
                            self.helper.check_connections(self.helper.PROVIDERS.values())
                    else:
                        self.helper.check_connections(self.helper.PROVIDERS.values())
                connection.close.assert_called_once()

    def test_runtime_probe_uses_core_and_provider_not_host_libraries(self):
        self.helper.stage_bundle(self.gpu, self.prefix, self.root, '123')
        provider = self.directory / 'provider'
        library = provider / 'usr/lib/aarch64-linux-gnu/libvulkan.so.1'
        library.parent.mkdir(parents=True)
        library.touch()
        with patch.object(self.helper, 'run') as run:
            run.return_value.stdout = 'MD_MESA_PROBE_OK\n'
            self.helper.verify_runtime(self.gpu, self.root, Path('/snap') / self.gpu.base / '1', provider)
        argv = run.call_args.args[0]
        self.assertIn(f'/snap/{self.gpu.base}/1/usr', argv)
        self.assertIn('/mdgl/lib:/mdprovider/usr/lib/aarch64-linux-gnu:/usr/lib/aarch64-linux-gnu', argv)
        self.assertNotIn('LD_LIBRARY_PATH', run.call_args.kwargs['env'])
        self.assertEqual(argv[-3:], ['--preload', '/probe', '/usr/bin/true'])
        self.assertIn('/mdgl/lib/libEGL_mesa.so.0', run.call_args.kwargs['env']['MD_MESA_PROBE_LIBRARIES'])

    def test_failed_abi_probe_never_publishes_image(self):
        with patch.object(self.helper, 'verify_runtime', side_effect=ValueError('ABI mismatch')):
            with self.assertRaisesRegex(ValueError, 'ABI mismatch'):
                self.helper.prepare(self.gpu, self.prefix, Path('/base/1'), Path('/provider/1'), 2)
        self.assertEqual(list(self.helper.CACHE.iterdir()), [])

    def test_cached_identity_and_new_base_revision(self):
        def pack(argv, **kwargs):
            Path(argv[2]).write_bytes(b'squashfs')
        with patch.object(self.helper, 'verify_runtime') as verify, patch.object(self.helper, 'run', side_effect=pack):
            first, identity = self.helper.prepare(self.gpu, self.prefix, Path('/base/1'), Path('/provider/1'), 2)
            self.assertEqual((first, identity), self.helper.prepare(self.gpu, self.prefix, Path('/base/1'), Path('/provider/1'), 4))
            self.assertEqual(verify.call_count, 1)
            second, other = self.helper.prepare(self.gpu, self.prefix, Path('/base/2'), Path('/provider/1'), 2)
            self.assertNotEqual(identity, other)
            self.assertTrue(first.is_file() and second.is_file())
            self.assertEqual(verify.call_count, 2)

    def test_mangler_relocates_host_overrides_without_disabling_sandbox(self):
        script = self.helper.MANGLER + '\nprintf "%s\\n" "$LD_LIBRARY_PATH" "$VK_DRIVER_FILES" "$LIBGL_DRIVERS_PATH"'
        env = dict(os.environ, COMPONENT_PATH='/snap/consumer/123/gpu-2404-2',
                   LD_LIBRARY_PATH='/host/lib', VK_DRIVER_FILES='/host/icd.json')
        result = subprocess.run(['sh', '-ec', script], env=env, text=True, capture_output=True, timeout=5, check=True)
        lines = result.stdout.splitlines()
        self.assertEqual(lines[0], '/snap/consumer/123/gpu-2404-2/lib:/host/lib')
        self.assertEqual(lines[1], '/snap/consumer/123/gpu-2404-2/share/vulkan/icd.d/freedreno_icd.aarch64.json')
        self.assertNotIn('MOZ_', self.helper.MANGLER)
        self.assertNotIn('sandbox', self.helper.MANGLER)


class SnapGPUCore26Test(SnapGPUTest):
    generation = '2604'

    def test_coinstalled_providers_have_distinct_slots_and_library_layouts(self):
        self.helper.SNAPS = self.directory / 'snap'
        with self.assertRaisesRegex(ValueError, 'No supported'):
            self.helper.selected_providers(None)
        for provider in self.helper.PROVIDERS.values():
            root = self.helper.SNAPS / provider.provider / 'current'
            lib = provider.provider + '/usr/lib/aarch64-linux-gnu'
            (root / lib).mkdir(parents=True)
            (root / lib / 'libvulkan.so.1').touch()
            self.assertEqual(self.helper.provider_libraries(provider, root), lib)
        self.assertEqual([p.plug for p in self.helper.selected_providers(None)],
                         ['kernel-gpu-2404', 'kernel-gpu-2604'])
        self.assertEqual(self.helper.selected_providers('mesa-2604'), [self.gpu])


if __name__ == '__main__':
    unittest.main()
