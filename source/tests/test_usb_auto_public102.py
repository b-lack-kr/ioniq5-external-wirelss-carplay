"""Public release dispatch distinguishes internal 1.0.2 from published 1.0.2."""
import shutil,unittest
import usb_auto_fixture as f
class PublicUsb102(unittest.TestCase):
 setUp=f.UsbAuto.setUp
 tool=f.UsbAuto.tool
 pkg=f.UsbAuto.pkg
 installed=f.UsbAuto.installed
 invoke=f.UsbAuto.invoke
 no_calls=f.UsbAuto.no_calls
 without_printf=f.UsbAuto.without_printf
 def test_fresh_installs(self):
  self.without_printf();self.invoke('INSTALL_COMPLETE')
  self.assertEqual(self.calls.read_text(),'native:install '+str(self.usb/'packages/hmgapi.neev-usb-carplay.zip')+'\n')
 def test_published101_updates(self):
  self.installed('1.0.1');self.without_printf();self.invoke('INSTALL_COMPLETE')
  self.assertEqual(self.calls.read_text(),'native:install --update '+str(self.usb/'packages/hmgapi.neev-usb-carplay.zip')+'\n')
 def test_same_public2_collects_only(self):
  self.installed();self.invoke('COLLECT_COMPLETE');self.assertEqual(self.calls.read_text(),'collect:--collect\n')
 def test_old_internal2_requires_manual_uninstall(self):
  self.installed();self.public.rename(self.w/'public-1.0.2')
  self.invoke('UNINSTALL_INTERNAL_TEST_VERSION',1);self.no_calls()
 def test_active_other_internal_versions_cannot_upgrade_or_downgrade(self):
  for v in ('1.0.0','1.0.3','1.0.7','1.0.12','1.0.15'):
   with self.subTest(version=v):
    self.installed(v);self.invoke('UNINSTALL_INTERNAL_TEST_VERSION',1);self.no_calls()
 def test_removed_internal2_or15_runs_authenticated_installer(self):
  for v in ('1.0.2','1.0.15'):
   with self.subTest(version=v):
    self.installed(v)
    if v=='1.0.2':self.public.rename(self.w/'public-1.0.2')
    shutil.rmtree(self.cp);self.pkg(0,'');self.invoke('INSTALL_COMPLETE')
    self.assertEqual(self.calls.read_text(),'native:install '+str(self.usb/'packages/hmgapi.neev-usb-carplay.zip')+'\n')
    self.calls.unlink();shutil.rmtree(self.public)
 def test_removed_public2_reinstalls(self):
  self.installed();shutil.rmtree(self.cp);self.pkg(0,'');self.invoke('INSTALL_COMPLETE')
  self.assertTrue(self.calls.read_text().startswith('native:install '))
 def test_registry_workspace_mismatch_never_installs(self):
  self.installed('1.0.1');self.pkg(1,'1.0.2');self.invoke('INSTALL_STATE_MISMATCH',1);self.no_calls()
 def test_same_public_incomplete_never_reinstalls(self):
  self.installed(state='INSTALLING');self.invoke('SAME_VERSION_INCOMPLETE',1);self.no_calls()
 def test_newer_unknown_never_downgrades(self):
  self.installed('1.0.16');self.invoke('UNSUPPORTED_WORKSPACE_VERSION',1);self.no_calls()
 def test_tampered_usb_or_collector_errors_propagate(self):
  self.installed();self.invoke('COLLECT_FAILED',4,{'COLLECT_RC':'4'});self.calls.unlink()
  (self.usb/'NEMESIS.sh').write_text('tampered');self.invoke('USB_FILES_CHANGED',1);self.no_calls()
 def test_native_failures_and_false_success_do_not_report_success(self):
  self.invoke('INSTALL_FAILED',7,{'INSTALL_RC':'7'})
  self.invoke('INSTALL_RESULT_UNVERIFIED',1,{'FALSE_INSTALL_SUCCESS':'1'})
if __name__=='__main__':unittest.main(verbosity=2)
