"""Build the installer's libc-neutral runtime admission probe with the host compiler."""
import ctypes.util
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


@unittest.skipUnless(shutil.which('cc') and shutil.which('readelf'), 'C compiler/readelf required')
class MesaProbeTest(unittest.TestCase):
    def setUp(self):
        script = Path(__file__).resolve().parents[1] / 'install_linux.sh'
        setup = subprocess.check_output(['sh', str(script), '--print-graphics-setup'], text=True)
        source = setup.split('cat > "$work/flatpak-gl-probe.c" <<\'PROBE\'\n', 1)[1].split('\nPROBE\n', 1)[0]
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        path = Path(temporary.name)
        (path / 'probe.c').write_text(source)
        self.library = path / 'probe.so'
        subprocess.run(['cc', '-O2', '-Wall', '-Wextra', '-Werror', '-shared', '-fPIC', '-nostdlib',
                        str(path / 'probe.c'), '-o', str(self.library)], check=True)

    def test_no_libc_dependency(self):
        dynamic = subprocess.check_output(['readelf', '-d', str(self.library)], text=True)
        self.assertNotIn('(NEEDED)', dynamic)

    @unittest.skipIf(sys.platform == 'android' or hasattr(sys, 'getandroidapilevel'),
                     'Runtime preload requires the guest glibc loader, not Bionic')
    def test_loader_owns_libc_and_success_is_explicit(self):
        for libraries, code, text in [(ctypes.util.find_library('m'), 0, 'MD_MESA_PROBE_OK\n'),
                                      ('/missing-magicdesk-library.so', 1, '/missing-magicdesk-library.so:'),
                                      ('', 2, '')]:
            with self.subTest(libraries=libraries):
                self.assertIsNotNone(libraries)
                result = subprocess.run([sys.executable, '-c', 'raise SystemExit(77)'],
                    env=dict(os.environ, LD_PRELOAD=str(self.library), MD_MESA_PROBE_LIBRARIES=libraries),
                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=15)
                self.assertEqual(result.returncode, code, result.stdout)
                self.assertIn(text, result.stdout)


if __name__ == '__main__':
    unittest.main()
