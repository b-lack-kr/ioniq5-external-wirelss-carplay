"""Exercise real build_payload output, including uninstall followed by reinstall.

Use --payload-dir with a previously signed USB kit's NEEV_CP directory. The
actual mod, Setup.jar, maps and target manifest are builder inputs. Only Android
paths, IPC, firmware contents and vendor loading are modeled for shell execution;
the builder's public-mod hash is never supplied or repaired by the test fixture.
No vehicle, mounted USB or author key is accessed.
"""
import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import unittest
from unittest.mock import patch

import build_installer
import fixture

PAYLOAD = None
ORIGINAL_LAUNCHER = None
ORIGINAL_LAUNCHER_SHA256 = 'a3d6f59985d68add167ec2803b6ef99233c1443d49cec2bd8b241b53a43aaec6'


class BuiltPayload(unittest.TestCase):
    def setUp(self):
        fixture.Installation.setUp(self)
        # Keep only the host IPC environment. Discard its hand-rendered USB kit.
        self.vendor_stub = (self.pack / 'import-hkg.sh').read_text()
        self.target_stub = (self.pack / 'payload/TARGET.md5').read_text()
        shutil.rmtree(self.usb)

    run_usb = fixture.Installation.run_usb

    def build(self, name='built'):
        out = self.root / name
        build_installer.build_payload(
            PAYLOAD / 'payload/mod', PAYLOAD / 'payload/setup.jar',
            PAYLOAD / 'payload/maps', PAYLOAD / 'payload/TARGET.md5',
            PAYLOAD, out)
        return out

    def route_built_output(self, built):
        shutil.copytree(built, self.usb)
        self.old_pack_md5 = build_installer.md5(self.pack / 'MANIFEST.md5')
        for script in (self.usb / 'NEMESIS.sh', self.pack / 'operate.sh'):
            script.write_text(self.route_paths(script.read_text()))
        (self.pack / 'import-hkg.sh').write_text(self.vendor_stub)
        (self.pack / 'payload/TARGET.md5').write_text(self.target_stub)
        # Routing changed the kit bytes. Refresh only its enclosing kit digest;
        # never replace the PUBLIC_MOD_MD5 token or the builder's mod digest.
        members = [p for p in sorted(self.pack.rglob('*'))
                   if p.is_file() and p != self.pack / 'MANIFEST.md5']
        (self.pack / 'MANIFEST.md5').write_text(''.join(
            f'{build_installer.md5(p)}  {p.relative_to(self.pack).as_posix()}\n'
            for p in members))
        wrapper = self.usb / 'NEMESIS.sh'
        wrapper.write_text(wrapper.read_text().replace(
            'MANIFEST=' + self.old_pack_md5 + '\n',
            'MANIFEST=' + build_installer.md5(self.pack / 'MANIFEST.md5') + '\n'))

    def route_paths(self, content):
        for prefix in ('/data/', '/proc/', '/system/'):
            content = content.replace(prefix, str(self.root) + prefix)
        return content.replace(str(self.root) + '/data/nemesis/bin\n',
                               str(self.root) + '/data/nemesis/bin:/bin:/usr/bin\n')

    def test_original_public_record_accepts_new_launcher_then_clean_reinstall(self):
        built = self.build()
        expected = build_installer.md5(PAYLOAD / 'payload/mod/META-HMGAPI/manifest.json')
        wrapper = (built / 'NEMESIS.sh').read_text()
        self.assertIn('verify_removed_source "$record/kit/payload/mod" "' + expected + '"', wrapper)
        self.assertIn('PUBLIC_MANIFEST=' + expected, (built / 'NEEV_CP/operate.sh').read_text())
        for script in built.rglob('*.sh'):
            self.assertIsNone(re.search(r'@[A-Z][A-Z0-9_]*@', script.read_text()), str(script))
        # The old installed transaction contains only NEEV_CP, not the launcher.
        # Rebuilding the launcher must leave every old transaction member intact.
        def files(folder):
            return {p.relative_to(folder).as_posix(): p.read_bytes()
                    for p in folder.rglob('*') if p.is_file()}
        self.assertEqual(files(built / 'NEEV_CP'), files(PAYLOAD))
        original = ORIGINAL_LAUNCHER.read_bytes()
        self.assertEqual(hashlib.sha256(original).hexdigest(), ORIGINAL_LAUNCHER_SHA256)
        self.route_built_output(built)
        corrected = (self.usb / 'NEMESIS.sh').read_text()
        # Create the installed record using the actual previously published
        # launcher. Its broken removal token is not exercised by a fresh install.
        old = self.route_paths(original.decode()).replace(
            'MANIFEST=' + self.old_pack_md5 + '\n',
            'MANIFEST=' + build_installer.md5(self.pack / 'MANIFEST.md5') + '\n')
        (self.usb / 'NEMESIS.sh').write_text(old)
        self.assertEqual(self.run_usb(), 'COMPLETE')
        before = {p.relative_to(self.R).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                  for p in self.R.rglob('*') if p.is_file()}
        (self.usb / 'NEMESIS.sh').write_text(corrected)
        self.assertEqual(self.run_usb(), 'COMPLETE')
        self.assertEqual({p.relative_to(self.R).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                          for p in self.R.rglob('*') if p.is_file()}, before)
        self.assertFalse(list(self.W.parent.glob('neev-usbcp-removed-*')))
        # Model the native preuninstall's real ordering: remove the mod, then
        # apply its pending file restoration. The completed install record stays.
        for operation in (('remove', 'neev-usb-carplay'), ('apply',)):
            subprocess.run([str(self.bin / 'app_process'), '/system/bin',
                            'hmgapi.engine.Engine', *operation],
                           env=dict(os.environ, TEST_UNIT=str(self.root)), check=True)
        self.assertFalse(self.M.exists())
        self.assertFalse((self.H / 'state/applied.fixture').exists())
        fixture.write(self.usb / 'packages/usbwifi.hotspot.zip', 'vendor fixture')
        self.assertEqual(self.run_usb(), 'COMPLETE')
        self.assertEqual((self.R / 'mode').read_text().strip(), 'fresh')
        archives = list(self.W.parent.glob('neev-usbcp-removed-*'))
        self.assertEqual(len(archives), 1)
        archived = archives[0] / 'public-release-1.0.2'
        self.assertEqual({p.relative_to(archived).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                          for p in archived.rglob('*') if p.is_file()}, before)
        self.assertEqual((self.M / 'META-HMGAPI/manifest.json').read_bytes(),
                         (PAYLOAD / 'payload/mod/META-HMGAPI/manifest.json').read_bytes())

    def test_builder_rejects_unresolved_tokens_with_digits(self):
        templates = self.root / 'templates-under-test'
        shutil.copytree(build_installer.HERE / 'templates', templates / 'templates')
        for name in ('NEMESIS.sh', 'operate.sh'):
            with self.subTest(template=name):
                template = templates / 'templates' / name
                original = template.read_text()
                template.write_text(original + '\n# @UNRESOLVED_MD5@\n')
                try:
                    with patch.object(build_installer, 'HERE', templates):
                        with self.assertRaisesRegex(ValueError, '@UNRESOLVED_MD5@'):
                            self.build('unresolved-' + name)
                finally:
                    template.write_text(original)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--payload-dir', type=Path, required=True)
    parser.add_argument('--original-launcher', type=Path,
                        help='Previously published NEEV_CP_INSTALL.sh (defaults beside payload directory)')
    args = parser.parse_args()
    PAYLOAD = args.payload_dir.resolve()
    ORIGINAL_LAUNCHER = (args.original_launcher or PAYLOAD.parent / 'NEEV_CP_INSTALL.sh').resolve()
    result = unittest.TextTestRunner(verbosity=2).run(
        unittest.defaultTestLoader.loadTestsFromTestCase(BuiltPayload))
    raise SystemExit(0 if result.wasSuccessful() else 1)
