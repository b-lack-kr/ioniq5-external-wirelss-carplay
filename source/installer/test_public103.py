"""Public 1.0.3 clean installation and signed public 1.0.2 retirement contract."""
import hashlib
import os
import shutil
import unittest
from zipfile import ZipFile
import fixture103 as fixture

write = fixture.write
PUBLIC_102 = fixture.PROJECT/'neev-carplay/publishing/20261109-v102-reinstall-fix/assets/NEEV_260902_CarPlay_1.0.2_USB.zip'
PUBLIC_102_SHA = 'eb2f41243987676b7a3d81e8096be6d67ab1da2e218f2c1d5d3a2be90489d409'


def tree(path):
    return {str(f.relative_to(path)): ('link', os.readlink(f)) if f.is_symlink()
            else ('file', hashlib.sha256(f.read_bytes()).hexdigest()) if f.is_file()
            else ('dir',) for f in sorted(path.rglob('*'))}


class Public103(fixture.Installation):
    def original_public103(self, state='RESTORED', version='none'):
        archive_path = fixture.PROJECT/'neev-carplay/publishing/20261109-v103-release/assets/NEEV_260902_CarPlay_1.0.3_USB.zip'
        self.assertEqual(hashlib.sha256(archive_path.read_bytes()).hexdigest(),
                         '76174dfab95ae3a8b97e7f0a6ce492ec49a507194e9135103deef90dd29e8ef2')
        with ZipFile(archive_path) as archive:
            for name in archive.namelist():
                if name.startswith('NEEV_CP/') and not name.endswith('/'):
                    target = self.R/'kit'/name[len('NEEV_CP/'):]
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(archive.read(name))
        write(self.R/'STATUS', state+'\n')
        write(self.R/'mode', 'fresh\n')
        write(self.R/'backup/keep', 'failed install rollback evidence')
        write(self.W/'UPGRADE_CURRENT.txt', version+'\n')
        write(self.usb/'packages/usbwifi.hotspot.zip', 'vendor fixture')
        return self.R

    def test_original103_failed_fresh_rollback_retries_with_new_kit(self):
        old = self.original_public103()
        before = tree(old)
        self.assertEqual(self.run_usb(), 'COMPLETE')
        archives = list(self.W.parent.glob('neev-usbcp-removed-*'))
        self.assertEqual(len(archives), 1)
        self.assertEqual(tree(archives[0]/'public-release-1.0.3'), before)
        self.assertEqual((self.R/'mode').read_text().strip(), 'fresh')

    def test_original103_uninstalled_reinstalls_with_new_kit(self):
        old = self.original_public103('INSTALLED', '1.0.3')
        before = tree(old)
        self.assertEqual(self.run_usb(), 'COMPLETE')
        archive = next(self.W.parent.glob('neev-usbcp-removed-*'))
        self.assertEqual(tree(archive/'public-release-1.0.3'), before)

    def test_original103_modified_rollback_not_accepted(self):
        old = self.original_public103()
        write(old/'kit/operate.sh', 'tampered')
        before = tree(self.W)
        self.assertEqual(self.run_usb(False), 'LOCAL_TRANSACTION_DAMAGED')
        self.assertEqual(tree(self.W), before)

    def test_original103_rollback_pending_plan_is_not_applied(self):
        self.original_public103()
        write(self.root/'foreign-plan', 'pending')
        before = tree(self.W)
        self.assertEqual(self.run_usb(False), 'APPLY_CHANGES_AFTER_REMOVE')
        self.assertEqual(tree(self.W), before)

    def test_original103_rollback_wrong_mode_not_retired(self):
        self.original_public103()
        write(self.R/'mode', '1.0.1\n')
        before = tree(self.W)
        self.assertEqual(self.run_usb(False), 'UNOWNED_REMOVED_SETUP')
        self.assertEqual(tree(self.W), before)

    def public102(self):
        self.assertEqual(hashlib.sha256(PUBLIC_102.read_bytes()).hexdigest(), PUBLIC_102_SHA)
        record = self.W/'public-release-1.0.2'
        with ZipFile(PUBLIC_102) as archive:
            for name in archive.namelist():
                if name.startswith('NEEV_CP/') and not name.endswith('/'):
                    target = record/'kit'/name[len('NEEV_CP/'):]
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(archive.read(name))
        write(record/'STATUS', 'INSTALLED\n')
        write(record/'mode', 'fresh\n')
        write(record/'backup/keep', 'previous rollback evidence')
        write(self.W/'UPGRADE_CURRENT.txt', '1.0.2\n')
        write(self.W/'config/hotspot.conf', 'previous-test-identity')
        write(self.W/'config/ap-identity', 'previous-test-id')
        write(self.H/'settings/neev-usb-carplay.json', '{"carplay_tbt":false}')
        shutil.copytree(record/'kit/payload/mod', self.M)
        write(self.H/'state/applied.fixture', '')
        return record

    def remove_mod(self):
        shutil.rmtree(self.M)
        (self.H/'state/applied.fixture').unlink()
        write(self.usb/'packages/usbwifi.hotspot.zip', 'vendor fixture')

    def test_fresh_repeat_separate_namespace(self):
        self.assertEqual(self.run_usb(), 'COMPLETE')
        self.assertEqual(self.R.name, 'public-release-1.0.3')
        self.assertEqual((self.W/'UPGRADE_CURRENT.txt').read_text().strip(), '1.0.3')
        self.assertFalse((self.W/'public-1.0.3').exists())
        self.assertEqual(self.run_usb(), 'COMPLETE')

    def test_current103_uninstall_reinstall_parks_only_owned_history(self):
        self.assertEqual(self.run_usb(), 'COMPLETE')
        before = tree(self.R)
        self.remove_mod()
        write(self.H/'settings/unrelated.json', '{"keep":true}')
        write(self.H/'mods/unrelated/keep', 'foreign package')
        self.assertEqual(self.run_usb(), 'COMPLETE')
        archives = list(self.W.parent.glob('neev-usbcp-removed-*'))
        self.assertEqual(len(archives), 1)
        self.assertEqual(tree(archives[0]/'public-release-1.0.3'), before)
        self.assertEqual((self.H/'mods/unrelated/keep').read_text(), 'foreign package')
        self.assertTrue((self.H/'settings/unrelated.json').exists())

    def test_public102_uninstall_fresh103_preserves_old_bytes(self):
        old = self.public102()
        before = tree(old)
        self.remove_mod()
        self.assertEqual(self.run_usb(), 'COMPLETE')
        self.assertEqual((self.R/'mode').read_text().strip(), 'fresh')
        self.assertFalse((self.W/'public-release-1.0.2').exists())
        archives = list(self.W.parent.glob('neev-usbcp-removed-*'))
        self.assertEqual(len(archives), 1)
        self.assertEqual(tree(archives[0]/'public-release-1.0.2'), before)
        self.assertTrue((archives[0]/'.manual-uninstall-settings.json').is_file())

    def test_active_public102_clear_uninstall_instruction_without_mutation(self):
        self.public102()
        before, hmg = tree(self.W), tree(self.H)
        self.assertEqual(self.run_usb(False), 'UNINSTALL_PREVIOUS_RELEASE')
        self.assertEqual(tree(self.W), before)
        self.assertEqual(tree(self.H), hmg)

    def test_modified_removed_public102_never_parked(self):
        old = self.public102()
        self.remove_mod()
        write(old/'kit/operate.sh', 'changed')
        before, hmg = tree(self.W), tree(self.H)
        self.assertEqual(self.run_usb(False), 'UNOWNED_REMOVED_SETUP')
        self.assertEqual(tree(self.W), before)
        self.assertEqual(tree(self.H), hmg)

    def test_public102_cannot_impersonate_current103(self):
        old = self.public102()
        shutil.copytree(old, self.R)
        write(self.W/'UPGRADE_CURRENT.txt', '1.0.3\n')
        self.remove_mod()
        before = tree(self.W)
        self.assertEqual(self.run_usb(False), 'LOCAL_TRANSACTION_DAMAGED')
        self.assertEqual(tree(self.W), before)

    def test_historical_internal103_name_does_not_authorize_public103(self):
        self.assertEqual(self.run_usb(), 'COMPLETE')
        self.R.rename(self.W/'public-1.0.3')
        self.remove_mod()
        before = tree(self.W)
        self.assertEqual(self.run_usb(False), 'UNOWNED_REMOVED_SETUP')
        self.assertEqual(tree(self.W), before)

    def test_modified_current103_never_replaced(self):
        self.assertEqual(self.run_usb(), 'COMPLETE')
        write(self.R/'kit/operate.sh', 'changed')
        self.remove_mod()
        before = tree(self.W)
        self.assertEqual(self.run_usb(False), 'LOCAL_TRANSACTION_DAMAGED')
        self.assertEqual(tree(self.W), before)

    def test_unapplied_remove_never_applies_unrelated_changes(self):
        self.public102()
        shutil.rmtree(self.M)
        write(self.usb/'packages/usbwifi.hotspot.zip', 'vendor fixture')
        before = tree(self.W)
        self.assertEqual(self.run_usb(False), 'APPLY_CHANGES_AFTER_REMOVE')
        self.assertEqual(tree(self.W), before)

    def test_public101_upgrade_and_explicit_restore_still_supported(self):
        old = self.public101()
        before, mod = tree(old), tree(self.M)
        self.assertEqual(self.run_usb(), 'COMPLETE')
        self.assertEqual((self.R/'mode').read_text().strip(), '1.0.1')
        self.assertEqual(tree(old), before)
        self.restore()
        self.assertEqual(tree(self.M), mod)
        self.assertEqual((self.W/'UPGRADE_CURRENT.txt').read_text().strip(), '1.0.1')


if __name__ == '__main__':
    unittest.main(verbosity=2)
