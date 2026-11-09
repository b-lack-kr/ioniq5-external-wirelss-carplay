"""Public 1.0.3 USB install/collect dispatch, separate from internal 1.0.3."""
import shutil
import unittest
import usb_auto_fixture103 as fixture


class PublicUsb103(fixture.UsbAuto):
    def test_fresh_installs(self):
        self.without_printf()
        self.invoke('INSTALL_COMPLETE')
        self.assertEqual(self.calls.read_text(), 'native:install '+str(self.usb/'packages/hmgapi.neev-usb-carplay.zip')+'\n')

    def test_same_public103_collects_without_install(self):
        self.installed()
        self.invoke('COLLECT_COMPLETE')
        self.assertEqual(self.calls.read_text(), 'collect:--collect\n')

    def test_removed_public103_reinstalls(self):
        self.installed()
        shutil.rmtree(self.cp)
        self.pkg(0, '')
        self.invoke('INSTALL_COMPLETE')
        self.assertTrue(self.calls.read_text().startswith('native:install '))

    def test_removed_public102_routes_to_authenticated_installer(self):
        self.installed('1.0.2')
        (self.w/'public-1.0.2').rename(self.w/'public-release-1.0.2')
        shutil.rmtree(self.cp)
        self.pkg(0, '')
        self.invoke('INSTALL_COMPLETE')
        self.assertTrue(self.calls.read_text().startswith('native:install '))

    def failed_fresh(self, state='RESTORED', mode='fresh'):
        self.public.mkdir(parents=True)
        (self.w/'UPGRADE_CURRENT.txt').write_text('none\n')
        (self.public/'STATUS').write_text(state+'\n')
        (self.public/'mode').write_text(mode+'\n')

    def test_failed_fresh_rollback_routes_only_to_authenticated_installer(self):
        self.failed_fresh()
        self.invoke('INSTALL_COMPLETE')
        self.assertTrue(self.calls.read_text().startswith('native:install '))

    def test_failed_fresh_incomplete_never_routes(self):
        self.failed_fresh('INSTALLING')
        self.invoke('UNSUPPORTED_WORKSPACE_VERSION', 1)
        self.no_calls()

    def test_failed_fresh_wrong_mode_never_routes(self):
        self.failed_fresh(mode='1.0.1')
        self.invoke('UNSUPPORTED_WORKSPACE_VERSION', 1)
        self.no_calls()

    def test_failed_fresh_live_mod_never_routes(self):
        self.failed_fresh()
        self.cp.mkdir(parents=True)
        self.invoke('UNSUPPORTED_WORKSPACE_VERSION', 1)
        self.no_calls()

    def test_active_public102_requires_manual_uninstall(self):
        self.installed('1.0.2')
        (self.w/'public-1.0.2').rename(self.w/'public-release-1.0.2')
        self.invoke('UNINSTALL_PREVIOUS_RELEASE', 1)
        self.no_calls()

    def test_internal103_is_not_current_public_collect(self):
        self.installed()
        self.public.rename(self.w/'public-1.0.3')
        self.invoke('UNINSTALL_INTERNAL_TEST_VERSION', 1)
        self.no_calls()

    def test_public101_update_unchanged(self):
        self.installed('1.0.1')
        self.invoke('INSTALL_COMPLETE')
        self.assertTrue(self.calls.read_text().startswith('native:install --update '))

    def test_same_public_incomplete_never_reinstalls(self):
        self.installed(state='INSTALLING')
        self.invoke('SAME_VERSION_INCOMPLETE', 1)
        self.no_calls()

    def test_errors_propagate_without_retry(self):
        self.installed()
        self.invoke('COLLECT_FAILED', 4, {'COLLECT_RC': '4'})
        self.assertEqual(self.calls.read_text(), 'collect:--collect\n')

    def test_failed_install_never_reports_completion(self):
        self.invoke('INSTALL_FAILED', 7, {'INSTALL_RC': '7'})
        self.invoke('INSTALL_RESULT_UNVERIFIED', 1, {'FALSE_INSTALL_SUCCESS': '1'})

    def test_modified_usb_never_executes(self):
        (self.usb/'NEMESIS.sh').write_text('changed')
        self.invoke('USB_FILES_CHANGED', 1)
        self.no_calls()


if __name__ == '__main__':
    unittest.main(verbosity=2)
