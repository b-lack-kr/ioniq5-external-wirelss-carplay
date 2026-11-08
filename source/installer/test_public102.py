"""Public 1.0.2 uses internal v15 runtime, never the historical internal v1.0.2 workspace."""
import hashlib,os,shutil,unittest
from zipfile import ZipFile
import fixture
write=fixture.write

def tree(p):
 return {str(f.relative_to(p)):('link',os.readlink(f)) if f.is_symlink() else ('file',hashlib.sha256(f.read_bytes()).hexdigest()) if f.is_file() else ('dir',) for f in sorted(p.rglob('*'))}

class Public102(unittest.TestCase):
 setUp=fixture.Installation.setUp
 run_usb=fixture.Installation.run_usb
 public101=fixture.Installation.public101
 restore=fixture.Installation.restore
 def internal(self,version):
  self.public101();old=self.W/('public-'+version)
  archive=fixture.PROJECT/'배포용'/version/('NEEV_260902_CarPlay_'+version+'_USB.zip')
  if version=='1.0.2':archive=fixture.PROJECT/'neev-carplay/improvements/20261105/build-1.0.2-map-repair/NEEV_260902_CarPlay_1.0.2_USB.zip'
  expected={'1.0.2':'f2a1db15cb93cf0625059211087f1c84318bdbde7b9b358a1e05034b4a078d95','1.0.15':'3608c6659d4c378ffc84ca19d27a233580c022d19e8939326fc45c516596c6e5'}
  self.assertEqual(hashlib.sha256(archive.read_bytes()).hexdigest(),expected[version])
  with ZipFile(archive) as z:
   for n in z.namelist():
    if n.startswith('NEEV_CP/') and not n.endswith('/'):
     p=old/'kit'/n[len('NEEV_CP/'):];p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(z.read(n))
  write(old/'STATUS','INSTALLED\n');write(old/'mode','fresh\n')
  write(old/'backup/keep','rollback evidence')
  shutil.rmtree(self.M);shutil.copytree(old/'kit/payload/mod',self.M)
  write(self.W/'UPGRADE_CURRENT.txt',version+'\n')
  return old
 def test_fresh_repeat_and_namespace(self):
  self.assertEqual(self.run_usb(),'COMPLETE')
  self.assertEqual(self.R.name,'public-release-1.0.2')
  self.assertEqual((self.W/'UPGRADE_CURRENT.txt').read_text().strip(),'1.0.2')
  self.assertFalse((self.W/'public-1.0.2').exists())
  self.assertEqual(self.run_usb(),'COMPLETE')
 def test_exact_published101_upgrade_keeps_settings_then_restores(self):
  old=self.public101();before=tree(old);config=tree(self.W/'config');mod=tree(self.M);settings=tree(self.H/'settings')
  self.assertEqual(self.run_usb(),'COMPLETE')
  self.assertEqual((self.R/'mode').read_text().strip(),'1.0.1')
  self.assertEqual(tree(old),before);self.assertEqual(tree(self.W/'config'),config);self.assertEqual(tree(self.H/'settings'),settings)
  self.restore();self.assertEqual(tree(self.M),mod);self.assertEqual(tree(old),before)
  self.assertEqual((self.W/'UPGRADE_CURRENT.txt').read_text().strip(),'1.0.1')
  (self.usb/'NEEV-CP-RESTORE').unlink();self.assertEqual(self.run_usb(),'COMPLETE')
 def test_101_failed_install_rolls_back_then_retries(self):
  old=self.public101();before=tree(old);mod=tree(self.M);write(self.root/'fault','engine-install')
  self.assertEqual(self.run_usb(False),'INSTALL_FAILED_RESTORED');self.assertEqual(tree(old),before);self.assertEqual(tree(self.M),mod)
  self.assertEqual(self.run_usb(),'COMPLETE')
 def test_active_internal_same_version_or_v15_requires_uninstall(self):
  for version in ('1.0.2','1.0.15'):
   with self.subTest(version=version):
    old=self.internal(version);before=tree(self.W);hmg=tree(self.H)
    self.assertEqual(self.run_usb(False),'UNINSTALL_INTERNAL_TEST_VERSION')
    self.assertEqual(tree(self.W),before);self.assertEqual(tree(self.H),hmg);self.assertFalse(self.R.exists())
    shutil.rmtree(self.W);shutil.rmtree(self.M)
 def test_deleted_internal_same_version_or_v15_is_preserved_not_overwritten(self):
  for version in ('1.0.2','1.0.15'):
   with self.subTest(version=version):
    old=self.internal(version);before=tree(old)
    shutil.rmtree(self.M);(self.H/'state/applied.fixture').unlink()
    write(self.usb/'packages/usbwifi.hotspot.zip','vendor fixture')
    self.assertEqual(self.run_usb(),'COMPLETE')
    self.assertEqual((self.R/'mode').read_text().strip(),'fresh')
    archives=list(self.W.parent.glob('neev-usbcp-removed-*'))
    matching=[p for p in archives if (p/('public-'+version)).exists()]
    self.assertEqual(len(matching),1);self.assertEqual(tree(matching[0]/('public-'+version)),before)
    self.assertFalse((self.W/('public-'+version)).exists())
    shutil.rmtree(self.W);shutil.rmtree(self.M)
 def test_current_public_remove_and_reinstall_keeps_old_transaction(self):
  self.assertEqual(self.run_usb(),'COMPLETE');before=tree(self.R)
  shutil.rmtree(self.M);(self.H/'state/applied.fixture').unlink();write(self.usb/'packages/usbwifi.hotspot.zip','vendor fixture')
  self.assertEqual(self.run_usb(),'COMPLETE')
  archives=list(self.W.parent.glob('neev-usbcp-removed-*'));self.assertEqual(len(archives),1)
  self.assertEqual(tree(archives[0]/'public-release-1.0.2'),before)
 def test_tampered_same_public_transaction_never_replaced(self):
  self.assertEqual(self.run_usb(),'COMPLETE');write(self.R/'kit/operate.sh','foreign')
  before=tree(self.W);hmg=tree(self.H)
  self.assertEqual(self.run_usb(False),'LOCAL_TRANSACTION_DAMAGED');self.assertEqual(tree(self.W),before);self.assertEqual(tree(self.H),hmg)
 def test_damaged_removed_internal_record_never_retired(self):
  old=self.internal('1.0.2');shutil.rmtree(self.M);(self.H/'state/applied.fixture').unlink();write(old/'kit/operate.sh','foreign')
  before=tree(self.W);self.assertEqual(self.run_usb(False),'UNOWNED_REMOVED_SETUP');self.assertEqual(tree(self.W),before)
 def test_internal_record_cannot_masquerade_under_public_namespace(self):
  old=self.internal('1.0.2');shutil.copytree(old,self.R);before=tree(self.W);hmg=tree(self.H)
  self.assertEqual(self.run_usb(False),'LOCAL_TRANSACTION_DAMAGED');self.assertEqual(tree(self.W),before);self.assertEqual(tree(self.H),hmg)
 def test_newer_unsupported_version_is_not_downgraded(self):
  self.public101();write(self.W/'UPGRADE_CURRENT.txt','1.0.19\n');before=tree(self.H)
  self.assertEqual(self.run_usb(False),'UNINSTALL_INTERNAL_TEST_VERSION');self.assertEqual(tree(self.H),before)

if __name__=='__main__':unittest.main(verbosity=2)
