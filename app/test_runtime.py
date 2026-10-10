import hashlib
import subprocess
import os
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

class Runtime(unittest.TestCase):
    def test_checksums(self):
        for row in (ROOT/'runtime.sha256').read_text().splitlines():
            expected, name = row.split('  ', 1)
            self.assertEqual(hashlib.sha256((ROOT/name).read_bytes()).hexdigest(), expected, name)

    def test_shell_syntax(self):
        shell = os.environ.get('JC_SHELL', 'C:/Program Files/Git/bin/sh.exe' if os.name=='nt' else 'sh')
        for p in list((ROOT/'app/assets').glob('*.sh')) + list((ROOT/'tools').rglob('*.sh')) + list((ROOT/'ksu').glob('*.sh')):
            self.assertNotIn(b'\r', p.read_bytes(), str(p))
            subprocess.run([shell, '-n', str(p)], check=True)

    def test_built_apk(self):
        with zipfile.ZipFile(ROOT/'dist/JC_Camera.apk') as z:
            for p in (ROOT/'app/assets').iterdir():
                self.assertEqual(z.read('assets/'+p.name), p.read_bytes(), p.name)
            self.assertEqual(z.read('lib/arm64-v8a/libjcrawsurface.so'),
                (ROOT/'app/native/prebuilt/arm64-v8a/libjcrawsurface.so').read_bytes())
            self.assertIn('classes.dex', z.namelist())

    def test_ksu(self):
        with zipfile.ZipFile(ROOT/'dist/JC_Camera_KSU.zip') as z:
            self.assertEqual(len(z.namelist()), len(set(z.namelist())))
            self.assertIn('skip_mount', z.namelist())
            for row in z.read('runtime/runtime.sha256').decode().splitlines():
                expected, name = row.split('  ', 1)
                self.assertEqual(hashlib.sha256(z.read('runtime/'+name)).hexdigest(), expected, name)
            self.assertFalse(any(name.endswith(('.keystore','.jks','.md')) for name in z.namelist()))

if __name__=='__main__':
    unittest.main(verbosity=2)
