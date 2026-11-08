"""Release preparation invariants independent of the not-yet-final signed build."""
from pathlib import Path
import importlib.util
import json
import re
import tempfile
import unittest

BASE = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('package_release', BASE/'package_release.py')
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class Contract(unittest.TestCase):
    def test_placeholder_pins_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'pins.json'
            path.write_text(json.dumps({release.KIT:'@PENDING@',release.SRC:'@PENDING@'}))
            with self.assertRaisesRegex(ValueError,'hashes are not set'):
                release.load_pins(path)

    def test_scripts_share_public_download_and_hash_tokens(self):
        for path in (BASE/'source/USB-준비-macOS.command', BASE/'source/tools/Prepare-USB.ps1'):
            data=path.read_bytes()
            self.assertEqual(data.count(release.TOKEN),1)
            self.assertEqual(data.count(release.HASH_TOKEN),1)
            self.assertIn(release.KIT.encode(),data)
            self.assertNotIn('31개'.encode(),data)
        self.assertTrue(release.RELEASE_BASE.endswith('/v1.0.2'))

    def test_windows_guid_fix_and_selected_volume_contract_are_preserved(self):
        script=(BASE/'source/tools/WindowsUsbVolume.ps1').read_text(encoding='utf-8-sig')
        self.assertIn('CreateDirectoryW',script)
        self.assertIn('New-UsbPayloadParent $root $name',script)
        self.assertIn('$root = $current.VolumePath',script)
        self.assertIn("$phrase = 'DELETE ' + $selected.Letter + ':'",script)
        self.assertIn('$($expected.Count)개',script)
        for command in ('Format-Volume','Initialize-Disk','Clear-Disk','New-Partition','Remove-Partition','Resize-Partition'):
            self.assertNotRegex(script, r'(?im)^\s*'+re.escape(command)+r'\b')

    def test_mac_checks_all_payload_members_before_trigger(self):
        script=(BASE/'source/USB-준비-macOS.command').read_text()
        self.assertIn('cmp -s "$stage/$name" "$name"',script)
        self.assertLess(script.index('cmp -s "$stage/$name" "$name"'),script.index('marker=.neev-descriptor-$$'))
        self.assertLess(script.index('cmp -s "$stage/$name" "$name"'),script.index('auto_marker=.neev-autostart-$$'))
        self.assertLess(script.index('mv "$auto_marker" USB_AUTO.sh'),script.index('marker=.neev-descriptor-$$'))
        self.assertIn('"$expected_files"',script)
        self.assertIn('[ "$answer" = "DELETE $selected" ]',script)


if __name__ == '__main__':
    unittest.main(verbosity=2)
