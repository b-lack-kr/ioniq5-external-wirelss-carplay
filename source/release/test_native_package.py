"""Exercise native package hooks with a modeled USB, without vehicle operations."""
import importlib.util
from io import BytesIO
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from zipfile import ZipFile

HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('cp_release_build', HERE/'build.py')
builder=importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


def write(path,text,executable=False):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(text)
    if executable: path.chmod(0o755)


class NativePackage(unittest.TestCase):
    def setUp(self):
        temp=tempfile.TemporaryDirectory(prefix='neev-native-')
        self.addCleanup(temp.cleanup)
        self.root=Path(temp.name)
        self.pkg=self.root/'package'
        self.usb=self.root/'storage/usb1'
        self.usb.mkdir(parents=True)
        self.wrapper=b'#!/bin/sh\nprintf "%s\\n" "$*" >> "$TEST_ROOT/cp-calls"\nexit "${CP_RESULT:-0}"\n'
        self.launcher=b'authenticated inner launcher fixture\n'
        (self.usb/'NEMESIS.sh').write_bytes(self.wrapper)
        (self.usb/'NEEV_CP_INSTALL.sh').write_bytes(self.launcher)
        with ZipFile(BytesIO(builder.native_package(self.wrapper,self.launcher))) as z:
            z.extractall(self.pkg)
        self.bin=self.root/'system/bin'
        self.bin.mkdir(parents=True)
        (self.bin/'sh').symlink_to('/bin/sh')
        write(self.bin/'md5', '#!/bin/sh\nexec /sbin/md5 -r "$1"\n',True)
        write(self.root/'system/xbin/nemesis',
              '#!/bin/sh\n[ "$1" = usb-info ] || exit 3\ncat "$TEST_ROOT/usb-info"\nexit "${USB_INFO_RESULT:-0}"\n',True)
        for p in self.pkg.rglob('*.sh'):
            script=p.read_text()
            for prefix in ('/system/','/storage/','/mnt/media_rw/','/data/'):
                script=script.replace(prefix,str(self.root)+prefix)
            script=script.replace(str(self.root)+'/data/nemesis/bin\n',
                                  str(self.root)+'/data/nemesis/bin:/bin:/usr/bin\n')
            p.write_text(script)
        self.info()

    def info(self,root=None,mounted='1',suffix=''):
        write(self.root/'usb-info', 'mounted='+mounted+'\nroot='+str(root or self.usb)+'\n'+suffix)

    def run_hook(self,name,ok=True,extra=None):
        env={**os.environ,'TEST_ROOT':str(self.root)}
        env.pop('HMGAPI_NEMESIS_SYNC',None)
        env.update(extra or {})
        result=subprocess.run(['/bin/sh',str(self.pkg/'scripts'/name)],
                              cwd=self.pkg,env=env,capture_output=True,text=True)
        self.assertEqual(result.returncode==0,ok,result.stdout+result.stderr)
        return result

    def not_called(self):
        self.assertFalse((self.root/'cp-calls').exists())

    def test_preinstall_checks_usb_without_running_carplay(self):
        self.run_hook('preinstall.sh')
        self.not_called()

    def test_postinstall_calls_carplay_only_from_reported_usb(self):
        self.run_hook('postinstall.sh')
        self.assertEqual((self.root/'cp-calls').read_text(),'--install\n')

    def test_postinstall_propagates_carplay_failure(self):
        result=self.run_hook('postinstall.sh',False,{'CP_RESULT':'4'})
        self.assertEqual(result.returncode,4)

    def test_removed_usb_and_failed_usb_query_stop_before_execution(self):
        for info_result,mounted in (('3','1'),('0','0')):
            with self.subTest(info_result=info_result,mounted=mounted):
                self.info(mounted=mounted)
                self.run_hook('postinstall.sh',False,{'USB_INFO_RESULT':info_result})
                self.not_called()

    def test_usb_source_is_not_inferred_from_cwd(self):
        self.info(root=self.pkg)
        result=self.run_hook('postinstall.sh',False)
        self.assertIn('INVALID_USB_ROOT',result.stderr)
        self.not_called()

    def test_duplicate_root_or_mount_status_is_rejected(self):
        for extra in ('root='+str(self.usb)+'\n','mounted=1\n'):
            with self.subTest(extra=extra):
                self.info(suffix=extra)
                result=self.run_hook('preinstall.sh',False)
                self.assertIn('AMBIGUOUS_USB',result.stderr)
                self.not_called()

    def test_both_entry_scripts_are_checked(self):
        for name,original in (('NEMESIS.sh',self.wrapper),('NEEV_CP_INSTALL.sh',self.launcher)):
            with self.subTest(file=name):
                (self.usb/name).write_bytes(b'changed\n')
                self.run_hook('postinstall.sh',False)
                self.not_called()
                (self.usb/name).write_bytes(original)

    def test_symlinked_entry_script_is_rejected(self):
        target=self.root/'same-wrapper'
        target.write_bytes(self.wrapper)
        (self.usb/'NEMESIS.sh').unlink()
        (self.usb/'NEMESIS.sh').symlink_to(target)
        self.run_hook('postinstall.sh',False)
        self.not_called()

    def test_usb_is_revalidated_after_preinstall(self):
        self.run_hook('preinstall.sh')
        (self.usb/'NEMESIS.sh').write_bytes(b'replaced after preinstall\n')
        self.run_hook('postinstall.sh',False)
        self.not_called()

    def test_hmg_sync_does_not_execute_usb_or_require_usb(self):
        (self.root/'usb-info').unlink()
        for name in ('preinstall.sh','postinstall.sh'):
            self.run_hook(name,extra={'HMGAPI_NEMESIS_SYNC':'1'})
        self.not_called()

    def test_package_descriptor_keeps_existing_hmg_id_and_version(self):
        text=(self.pkg/'NEMESIS.data').read_text()
        self.assertIn('id=hmgapi.neev-usb-carplay\n',text)
        self.assertIn('package-version=1.0.2\n',text)
        self.assertIn('depends="hmgapi@>=1.0.1"',text)
        self.assertEqual((self.pkg/'mod/id').read_text().strip(),'neev-usb-carplay')
        for path in self.pkg.rglob('*'):
            if path.is_file():
                self.assertNotIn(b'mute',path.read_bytes().lower())


class UninstallLifecycle(unittest.TestCase):
    def setUp(self):
        temp=tempfile.TemporaryDirectory(prefix='neev-native-uninstall-')
        self.addCleanup(temp.cleanup)
        self.root=Path(temp.name)
        self.pkg=self.root/'package'
        write(self.pkg/'mod/id','neev-usb-carplay\n')
        self.stage=self.root/'data/local/tmp/nemesis/stage/hmgapi.neev-usb-carplay'
        self.mod=self.root/'data/hmgapi/mods/neev-usb-carplay'
        self.mod.mkdir(parents=True)
        write(self.root/'system/bin/hmgapid',
              '#!/bin/sh\necho "$*" >> "$ROOT/hmg-calls"\nexit 0\n',True)
        script=(HERE/'native/scripts/preuninstall.sh').read_text()
        script=script.replace('caller=$(cat /proc/$PPID/cmdline 2>/dev/null)',
                              'caller=$(cat "$ROOT/caller")')
        write(self.pkg/'preuninstall.sh',script)
        write(self.root/'caller','nemesis install --update package.zip\n')

    def run_hook(self,sync=False):
        env={**os.environ,'ROOT':str(self.root),'HMGAPI_NEMESIS_SYNC':'1' if sync else ''}
        subprocess.run(['/bin/sh',str(self.pkg/'preuninstall.sh')],cwd=self.pkg,env=env,
                       stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True)

    def test_update_preserves_installed_mod(self):
        write(self.stage/'NEMESIS.data','[nemesis]\nid=hmgapi.neev-usb-carplay\n')
        self.run_hook()
        self.assertFalse((self.root/'hmg-calls').exists())
        self.assertTrue(self.mod.is_dir())

    def test_explicit_uninstall_ignores_stale_staging(self):
        write(self.stage/'NEMESIS.data','[nemesis]\nid=hmgapi.neev-usb-carplay\n')
        write(self.root/'caller','nemesis uninstall hmgapi.neev-usb-carplay\n')
        self.run_hook()
        calls=(self.root/'hmg-calls').read_text()
        self.assertIn('engine remove neev-usb-carplay --from-nemesis\n',calls)
        self.assertIn('engine apply\n',calls)

    def test_unrelated_staged_package_does_not_suppress_removal(self):
        write(self.stage/'NEMESIS.data','[nemesis]\nid=other.package\n')
        self.run_hook()
        self.assertIn('engine remove neev-usb-carplay --from-nemesis',
                      (self.root/'hmg-calls').read_text())

    def test_sync_only_unlists_without_removing_mod(self):
        self.run_hook(sync=True)
        self.assertFalse((self.root/'hmg-calls').exists())
        self.assertTrue(self.mod.is_dir())


if __name__=='__main__':
    unittest.main(verbosity=2)
