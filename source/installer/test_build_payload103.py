"""Exercise the real 1.0.3 builder output without replacing its rendered mod hash.

Requires an already signed 1.0.3 NEEV_CP payload. Android IPC is modeled; this is
not an on-vehicle or cryptographic-signature integration test.
"""
import argparse
from pathlib import Path
import re
import shutil
import unittest
from unittest.mock import patch
import build_installer
import fixture103 as fixture
import test_build_payload as old_builder_tests

PAYLOAD = None


class BuiltPayload103(unittest.TestCase):
    def setUp(self):
        fixture.Installation.setUp(self)
        self.vendor_stub = (self.pack/'import-hkg.sh').read_text()
        self.target_stub = (self.pack/'payload/TARGET.md5').read_text()
        shutil.rmtree(self.usb)

    run_usb = fixture.Installation.run_usb
    route_paths = old_builder_tests.BuiltPayload.route_paths
    route_built_output = old_builder_tests.BuiltPayload.route_built_output

    def build(self, name='built'):
        out = self.root/name
        build_installer.build_payload(PAYLOAD/'payload/mod', PAYLOAD/'payload/setup.jar',
            PAYLOAD/'payload/maps', PAYLOAD/'payload/TARGET.md5', PAYLOAD, out)
        return out

    def test_actual_builder_hash_chain_and_uninstall_reinstall(self):
        built = self.build()
        manifest = build_installer.md5(PAYLOAD/'payload/mod/META-HMGAPI/manifest.json')
        self.assertIn('verify_removed_source "$record/kit/payload/mod" "'+manifest+'"',
                      (built/'NEMESIS.sh').read_text())
        self.assertIn('PUBLIC_MANIFEST='+manifest, (built/'NEEV_CP/operate.sh').read_text())
        for script in built.rglob('*.sh'):
            self.assertIsNone(re.search(r'@[A-Z][A-Z0-9_]*@', script.read_text()), str(script))
        self.route_built_output(built)
        self.assertEqual(self.run_usb(), 'COMPLETE')
        self.assertEqual(self.run_usb(), 'COMPLETE')
        before = (self.R/'kit/payload/mod/META-HMGAPI/manifest.json').read_bytes()
        shutil.rmtree(self.M)
        (self.H/'state/applied.fixture').unlink()
        fixture.write(self.usb/'packages/usbwifi.hotspot.zip', 'vendor fixture')
        self.assertEqual(self.run_usb(), 'COMPLETE')
        archives = list(self.W.parent.glob('neev-usbcp-removed-*'))
        self.assertEqual(len(archives), 1)
        self.assertEqual((archives[0]/'public-release-1.0.3/kit/payload/mod/META-HMGAPI/manifest.json').read_bytes(), before)

    def test_unknown_installer_token_is_rejected(self):
        templates = self.root/'templates'
        shutil.copytree(build_installer.HERE/'templates', templates)
        script = templates/'NEMESIS.sh'
        script.write_text(script.read_text()+'\n# @UNRENDERED_TEST_TOKEN@\n')
        with patch.object(build_installer, 'HERE', self.root):
            with self.assertRaisesRegex(ValueError, 'unresolved template token'):
                self.build()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--payload-dir', type=Path, required=True)
    args = parser.parse_args()
    PAYLOAD = args.payload_dir.resolve()
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(BuiltPayload103)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    raise SystemExit(0 if result.wasSuccessful() else 1)
