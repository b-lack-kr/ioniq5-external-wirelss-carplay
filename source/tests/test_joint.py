"""Run the shipping coordinator with simulated NEMESIS/CarPlay endpoints on local storage."""
import argparse
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from zipfile import ZipFile
import unittest

build_usb = None
legacy_mute = None
legacy_unbranded = None
app = 'hmgapi.neev-carplay-mute-pause'


class JointInstaller(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory(prefix='neev-joint-')
        self.addCleanup(tmp.cleanup)
        self.root=Path(tmp.name)
        self.usb=self.root/'stick'
        self.bin=self.root/'tools'
        self.w=self.root/'base'
        self.mods=self.root/'mods'
        self.apps=self.root/'apps'
        self.stage=self.w/'public-1.0.1'
        for d in (self.bin,self.mods,self.apps,self.stage): d.mkdir(parents=True)
        with ZipFile(build_usb) as kit:
            kit.extractall(self.usb)
        self.state=self.stage/'STATUS'
        self.version=self.w/'UPGRADE_CURRENT.txt'
        self.version.write_text('0.1.10\n')
        self.done=self.stage/'MUTE_1_1_0_DONE'
        self.calls=self.root/'calls.txt'
        self.reg=self.root/'registered'
        self.boot=self.root/'boot'
        self.boot.write_text('ab12-1234\n')
        self.addon=self.mods/'neev-carplay-mute-pause'
        self.wrapper=self.apps/app
        self.cp=self.mods/'neev-usb-carplay'
        # Replace only the hardware paths, preserving the actual decision and digest checks.
        script=self.usb/'NEMESIS.sh'
        text=script.read_text().replace('/data/nemesis/neev-usbcp',str(self.w)).replace('/data/hmgapi/mods',str(self.mods)).replace('/data/nemesis/apps',str(self.apps))
        text=text.replace('PATH=/system/bin:/system/xbin:/data/nemesis/bin',f'PATH={self.bin}:/bin:/usr/bin')
        text=text.replace('/system/xbin/nemesis',str(self.bin/'nemesis')).replace('/system/bin/app_process',str(self.bin/'verify')).replace('/system/bin/hmgapid',str(self.bin/'hmgapid'))
        text=text.replace('/proc/sys/kernel/random/boot_id',str(self.boot)).replace('/system/bin/sh "$USB/NEEV_CP_INSTALL.sh"',str(self.bin/'cp-upgrade')+' "$USB/NEEV_CP_INSTALL.sh"')
        script.write_text(text)
        self.tool('am','exit 0')
        self.tool('id',"echo 'uid=0(root)'")
        self.tool('sync','exit 0')
        self.tool('md5','if [ -x /sbin/md5 ]; then exec /sbin/md5 -r "$1"; else exec md5sum "$1"; fi')
        self.tool('getprop','echo NEEV.KOR.0000.V039.001.260902')
        self.tool('hmgapid',"echo 'hmgapid 1.0.1'")
        # Model Signing.verify's strict file inventory as well as file digests.
        # Crypto verification itself is exercised by the independent real-engine suite.
        self.python_tool('verify', r"""import hashlib, json, os, sys
from pathlib import Path
command=sys.argv[2:4]
if command==['hmgapi.engine.Signing','verify']:
    root=Path(sys.argv[4])
    try:
        manifest=json.loads((root/'META-HMGAPI/manifest.json').read_text())
        assert (root/'META-HMGAPI/signature').is_file()
        assert manifest['uuid']==sys.argv[5]
        expected=manifest['files']
        actual={f.relative_to(root).as_posix() for f in root.rglob('*') if f.is_file()
                and f.relative_to(root).parts[0] not in ('META-HMGAPI','.disabled')}
        extra=actual-set(expected)
        if extra:
            print('FAILED: file not in the manifest: '+sorted(extra)[0])
            sys.exit(3)
        assert actual==set(expected)
        for name,digest in expected.items():
            assert hashlib.sha256((root/name).read_bytes()).hexdigest()==digest
    except (OSError,ValueError,KeyError,AssertionError):
        sys.exit(3)
elif command==['kr.ioniq5.usbcp.setup.Setup','history']:
    assert os.environ['CLASSPATH']==os.environ['TEST_ROOT']+'/base/public-1.0.1/kit/payload/setup.jar'
    if os.environ.get('FAULT')=='history-fail':sys.exit(5)
    if os.environ.get('FAULT')=='history-partial':sys.exit(7)
    (Path(sys.argv[4])/'test-collector.txt').write_text('mock collector\n')
else:
    sys.exit(4)
""")
        self.tool('cp-upgrade','''[ "$NEEV_CP_BUNDLE" = 1 ] || exit 9
echo cp >> "$TEST_ROOT/calls.txt"
base=$TEST_ROOT/base
stage=$base/public-1.0.1
case "${FAULT:-}" in cp-fail) exit 7;; cp-false-success) exit 0;; esac
previous=$(cat "$stage/STATUS" 2>/dev/null)
if [ -e "$TEST_ROOT/stick/NEEV-CP-RESTORE" ] && [ -z "$previous" ]; then
  exit 4
fi
if [ -e "$TEST_ROOT/stick/NEEV-CP-RESTORE" ] || [ "$previous" = INSTALLING ]; then
  echo 0.1.10 > "$base/UPGRADE_CURRENT.txt"
  echo RESTORED > "$stage/STATUS"
  exit 0
fi
if [ ! -d "$TEST_ROOT/mods/neev-usb-carplay" ]; then
  mkdir -p "$stage/kit"
  # Production stages only enumerated manifest files. Finder's extra metadata
  # stays on USB and must not enter the internal signed-mod verification tree.
  while read digest path; do
    case "$path" in */*) mkdir -p "$stage/kit/${path%/*}" || exit 8;; esac
    cat "$TEST_ROOT/stick/NEEV_CP/$path" > "$stage/kit/$path" || exit 8
  done < "$TEST_ROOT/stick/NEEV_CP/MANIFEST.md5"
  cat "$TEST_ROOT/stick/NEEV_CP/MANIFEST.md5" > "$stage/kit/MANIFEST.md5" || exit 8
  cp -R "$stage/kit/payload/mod" "$TEST_ROOT/mods/neev-usb-carplay"
  mkdir "$TEST_ROOT/mods/neev-usb-carplay/.features"
  echo generated > "$TEST_ROOT/mods/neev-usb-carplay/.features/generated.xml"
fi
echo 1.0.1 > "$base/UPGRADE_CURRENT.txt"
echo INSTALLED > "$stage/STATUS"
''')
        self.tool('nemesis','''id=hmgapi.neev-carplay-mute-pause
case "$1" in
 pkg-list) [ "${FAULT:-}" != registry ] || exit 1
           echo 'other|Other|0|application|9|keep'
           if [ -f "$TEST_ROOT/registered" ]; then echo "$id|Mute|0|application|1.1.0|test"; fi
           exit 0;;
 install) shift; mode=fresh; if [ "$1" = --update ]; then mode=retry; shift; fi
          echo "$mode" >> "$TEST_ROOT/calls.txt"
          dir="$TEST_ROOT/apps/$id"
          mkdir -p "$dir"; unzip -o -q "$1" -d "$dir" || exit 1
          touch "$TEST_ROOT/registered"
          [ "${FAULT:-}" != mute-postfail ] || exit 5
          [ "${FAULT:-}" != mute-false-success ] || exit 0
          dst="$TEST_ROOT/mods/neev-carplay-mute-pause"; mkdir -p "$dst"
          unzip -o -q "$dir/mod/package.zip" -d "$dst" || exit 5
          exit 0;;
esac
exit 1''')

    def seed_existing_mute(self, archive=None, old_receipt=False):
        env={**os.environ,'TEST_ROOT':str(self.root),'NEEV_CP_BUNDLE':'1'}
        subprocess.run([str(self.bin/'cp-upgrade'),'unused'],env=env,check=True)
        self.calls.write_text('')
        if archive is None:
            raw=(self.usb/'packages'/f'{app}.zip').read_bytes()
        else:
            with ZipFile(archive) as package:
                raw=package.read('packages/'+app+'.zip')
        with ZipFile(io.BytesIO(raw)) as wrapper:
            wrapper.extractall(self.wrapper)
            with ZipFile(io.BytesIO(wrapper.read('mod/package.zip'))) as mod:
                mod.extractall(self.addon)
        self.reg.touch()
        if old_receipt:
            (self.stage/'MUTE_1_0_0_DONE').write_text(app+' 1.0.0\n')
        return (self.addon/'hooks.jar').read_bytes()

    def require_legacy(self, unbranded=False):
        path=legacy_unbranded if unbranded else legacy_mute
        if path is None:self.skipTest('Provide --legacy-mute / --legacy-unbranded to exercise exact old wrappers')
        return path

    def test_published_100_with_old_receipt_upgrades_to_110(self):
        old=self.seed_existing_mute(self.require_legacy(),old_receipt=True)
        (self.addon/'.disabled').touch()
        self.assertEqual(self.run_joint(),'COMPLETE_MUTE_ALREADY_DISABLED')
        self.assertTrue((self.addon/'.disabled').exists())
        self.assertNotEqual((self.addon/'hooks.jar').read_bytes(),old)
        self.assertEqual(json.loads((self.addon/'mod.json').read_text())['version'],'1.1.0')
        self.assertEqual(self.done.read_text().strip(),app+' 1.1.0')
        self.assertEqual(self.calls.read_text().splitlines(),['cp','retry'])
        self.assertEqual(self.run_joint(),'LOGS_EXPORTED')
        self.assertEqual(self.calls.read_text().splitlines(),['cp','retry','cp'])

    def test_unbranded_100_upgrades_to_110(self):
        self.seed_existing_mute(self.require_legacy(True),old_receipt=True)
        self.assertEqual(self.run_joint(),'COMPLETE')
        self.assertEqual(json.loads((self.addon/'mod.json').read_text())['version'],'1.1.0')
        self.assertEqual(self.calls.read_text().splitlines(),['cp','retry'])

    def test_existing_standalone_110_only_registers_completion(self):
        before=self.seed_existing_mute()
        self.assertEqual(self.run_joint(),'COMPLETE')
        self.assertEqual((self.addon/'hooks.jar').read_bytes(),before)
        self.assertEqual(self.calls.read_text().splitlines(),['cp'])
        self.assertEqual(self.done.read_text().strip(),app+' 1.1.0')

    def test_old_upgrade_postinstall_retry(self):
        old=self.seed_existing_mute(self.require_legacy(),old_receipt=True)
        self.assertEqual(self.run_joint(False,'mute-postfail'),'CP_READY_MUTE_REPAIR_FAILED')
        self.assertFalse(self.done.exists())
        self.assertEqual((self.addon/'hooks.jar').read_bytes(),old)
        self.assertEqual(self.run_joint(),'COMPLETE')
        self.assertEqual(self.calls.read_text().splitlines(),['cp','retry','cp','retry'])

    def test_old_upgrade_cannot_report_success_without_new_mod(self):
        old=self.seed_existing_mute(self.require_legacy(),old_receipt=True)
        self.assertEqual(self.run_joint(False,'mute-false-success'),'CP_READY_MUTE_UNVERIFIED')
        self.assertFalse(self.done.exists())
        self.assertEqual((self.addon/'hooks.jar').read_bytes(),old)
        self.assertEqual(self.run_joint(),'COMPLETE')

    def test_damaged_old_wrapper_is_preserved(self):
        self.seed_existing_mute(self.require_legacy(),old_receipt=True)
        target=self.wrapper/'scripts/postinstall.sh'
        target.write_text('changed legacy code')
        self.assertEqual(self.run_joint(False),'CP_READY_MUTE_OTHER_WRAPPER')
        self.assertEqual(target.read_text(),'changed legacy code')
        self.assertEqual(self.calls.read_text().splitlines(),['cp'])

    def test_damaged_old_signed_mod_is_preserved(self):
        self.seed_existing_mute(self.require_legacy(),old_receipt=True)
        target=self.addon/'hooks.jar'
        target.write_bytes(b'changed legacy code')
        self.assertEqual(self.run_joint(False),'CP_READY_MUTE_OTHER_VERSION')
        self.assertEqual(target.read_bytes(),b'changed legacy code')
        self.assertEqual(self.calls.read_text().splitlines(),['cp'])

    def test_restore_clears_new_receipt_but_leaves_mute_installed(self):
        self.assertEqual(self.run_joint(),'COMPLETE')
        before=(self.addon/'hooks.jar').read_bytes()
        (self.usb/'NEEV-CP-RESTORE').touch()
        self.assertEqual(self.run_joint(),'CP_RESTORED_MUTE_UNCHANGED')
        self.assertFalse(self.done.exists())
        self.assertEqual((self.addon/'hooks.jar').read_bytes(),before)
        (self.usb/'NEEV-CP-RESTORE').unlink()
        self.assertEqual(self.run_joint(),'COMPLETE')
        self.assertEqual(self.calls.read_text().splitlines(),['cp','fresh','cp','cp'])

    def tool(self,name,body):
        path=self.bin/name
        path.write_text('#!/bin/sh\n'+body+'\n')
        path.chmod(0o755)

    def python_tool(self,name,body):
        path=self.bin/name
        path.write_text('#!'+sys.executable+'\n'+body+'\n')
        path.chmod(0o755)

    def add_usb_metadata(self):
        for relative in ('payload/mod/._LICENSE','payload/mod/._mod.json',
                         'payload/._mod','payload/mod/.DS_Store',
                         'payload/mod/ap/._service.sh','._MANIFEST.md5'):
            path=self.usb/'NEEV_CP'/relative
            path.write_bytes(b'Finder synthetic metadata')

    def test_appledouble_usb_files_do_not_enter_local_signed_kit(self):
        self.add_usb_metadata()
        self.assertEqual(self.run_joint(),'COMPLETE')
        for root in (self.stage/'kit',self.cp):
            self.assertFalse(any(p.name.startswith('._') or p.name=='.DS_Store' for p in root.rglob('*')))
        self.assertTrue((self.usb/'NEEV_CP/payload/mod/._LICENSE').exists())
        self.assertEqual(self.calls.read_text().splitlines(),['cp','fresh'])

    def test_appledouble_added_before_retry_does_not_block_completion(self):
        self.assertEqual(self.run_joint(False,'mute-postfail'),'CP_READY_MUTE_INSTALL_FAILED')
        self.add_usb_metadata()
        self.assertEqual(self.run_joint(),'COMPLETE')
        self.assertEqual(self.calls.read_text().splitlines(),['cp','fresh','cp','retry'])
        self.assertEqual(self.run_joint(),'LOGS_EXPORTED')

    def test_retry_after_old_coordinator_metadata_failure(self):
        self.add_usb_metadata()
        script=self.usb/'NEMESIS.sh'
        fixed=script.read_text()
        self.assertEqual(fixed.count('cp_kit="$PUBLIC/kit"'),1)
        # Reproduce the exact failed customer state: CP already installed, but
        # coordinator stops before mute installation while verifying raw USB.
        script.write_text(fixed.replace('cp_kit="$PUBLIC/kit"','cp_kit="$USB/NEEV_CP"'))
        self.assertEqual(self.run_joint(False),'CP_STATE_UNVERIFIED')
        self.assertEqual(self.state.read_text().strip(),'INSTALLED')
        self.assertTrue(self.cp.is_dir())
        self.assertFalse(self.addon.exists() or self.done.exists())
        installed=(self.cp/'hooks.jar').read_bytes()
        local_manifest=(self.stage/'kit/MANIFEST.md5').read_bytes()
        script.write_text(fixed)
        self.assertEqual(self.run_joint(),'COMPLETE')
        self.assertEqual((self.cp/'hooks.jar').read_bytes(),installed)
        self.assertEqual((self.stage/'kit/MANIFEST.md5').read_bytes(),local_manifest)
        self.assertEqual(self.calls.read_text().splitlines(),['cp','cp','fresh'])

    def test_unsigned_file_in_local_staging_is_not_ignored(self):
        self.assertEqual(self.run_joint(False,'mute-postfail'),'CP_READY_MUTE_INSTALL_FAILED')
        (self.stage/'kit/payload/mod/._LICENSE').write_bytes(b'Unexpected internal file')
        self.assertEqual(self.run_joint(False),'CP_STATE_UNVERIFIED')
        self.assertFalse(self.addon.exists())

    def test_changed_signed_local_staging_content_is_rejected(self):
        self.assertEqual(self.run_joint(False,'mute-postfail'),'CP_READY_MUTE_INSTALL_FAILED')
        (self.stage/'kit/payload/mod/hooks.jar').write_bytes(b'Changed signed code')
        self.assertEqual(self.run_joint(False),'CP_STATE_UNVERIFIED')
        self.assertFalse(self.addon.exists())

    def run_joint(self,success=True,fault=''):
        before=set(self.usb.glob('NEEV_BUNDLE_RESULT_*'))
        result=subprocess.run(['/bin/sh',str(self.usb/'NEMESIS.sh')],capture_output=True,env={**os.environ,'TEST_ROOT':str(self.root),'FAULT':fault})
        new, = set(self.usb.glob('NEEV_BUNDLE_RESULT_*'))-before
        log=(new/'INSTALL.log').read_text()
        self.assertEqual(result.returncode==0,success,log)
        self.result_dir=new
        return (new/'STATUS.txt').read_text().strip()

    def test_history_failure_preserves_install(self):
        self.assertEqual(self.run_joint(),'COMPLETE')
        self.assertEqual(self.run_joint(False,'history-fail'),'HISTORY_EXPORT_FAILED')
        self.assertTrue(self.done.exists() and self.reg.exists() and self.addon.is_dir())
        self.assertEqual(self.run_joint(),'LOGS_EXPORTED')
        self.assertEqual((self.result_dir/'test-collector.txt').read_text().strip(),'mock collector')
        self.assertEqual(self.calls.read_text().splitlines(),['cp','fresh','cp','cp'])

    def test_history_rejects_corrupt_helper(self):
        self.assertEqual(self.run_joint(),'COMPLETE')
        (self.stage/'kit/payload/setup.jar').write_bytes(b'fixture tampered')
        self.assertEqual(self.run_joint(False),'HISTORY_HELPER_INVALID')
        self.assertFalse((self.result_dir/'test-collector.txt').exists())
        self.assertTrue(self.done.exists() and self.reg.exists() and self.addon.is_dir())
        self.assertEqual(self.calls.read_text().splitlines(),['cp','fresh','cp'])

    def test_history_allows_partial_capture(self):
        self.assertEqual(self.run_joint(),'COMPLETE')
        self.assertEqual(self.run_joint(fault='history-partial'),'LOGS_EXPORTED_PARTIAL')
        self.assertTrue(self.done.exists() and self.reg.exists() and self.addon.is_dir())
        self.assertEqual(self.calls.read_text().splitlines(),['cp','fresh','cp'])

    def test_install_then_log_only(self):
        self.assertEqual(self.run_joint(),'COMPLETE')
        self.assertTrue(self.done.is_file())
        self.assertTrue(self.wrapper.is_dir() and self.addon.is_dir())
        self.assertEqual(self.run_joint(),'LOGS_EXPORTED')
        self.assertEqual(self.calls.read_text().splitlines(),['cp','fresh','cp'])

    def test_respects_disabled_and_deliberate_removal(self):
        self.run_joint()
        self.done.unlink()
        (self.addon/'.disabled').touch()
        self.assertEqual(self.run_joint(),'COMPLETE_MUTE_ALREADY_DISABLED')
        self.assertTrue((self.addon/'.disabled').exists())
        self.reg.unlink();shutil.rmtree(self.wrapper);shutil.rmtree(self.addon)
        self.assertEqual(self.run_joint(),'LOGS_EXPORTED')
        self.assertFalse(self.addon.exists())
        self.assertEqual(self.calls.read_text().count('fresh'),1)

    def test_registered_partial_repairs_before_completion(self):
        self.assertEqual(self.run_joint(False,'mute-postfail'),'CP_READY_MUTE_INSTALL_FAILED')
        self.assertFalse(self.done.exists())
        self.assertEqual(self.version.read_text().strip(),'1.0.1')
        self.assertEqual(self.run_joint(),'COMPLETE')
        self.assertEqual(self.calls.read_text().splitlines(),['cp','fresh','cp','retry'])

    def test_hmg_missing_after_reported_success(self):
        self.assertEqual(self.run_joint(False,'mute-false-success'),'CP_READY_MUTE_UNVERIFIED')
        self.assertFalse(self.done.exists())

    def test_bad_source_and_wrong_cp_do_not_install_mute(self):
        self.assertEqual(self.run_joint(False,'cp-fail'),'CP_FAILED')
        self.assertEqual(self.run_joint(False,'cp-false-success'),'CP_STATE_UNVERIFIED')
        (self.usb/'packages'/f'{app}.zip').write_bytes(b'x')
        self.assertEqual(self.run_joint(False),'BAD_MUTE_INSTALLER')
        self.assertEqual(self.calls.read_text().splitlines(),['cp','cp'])

    def test_foreign_mute_or_wrapper_is_preserved(self):
        self.wrapper.mkdir()
        (self.wrapper/'NEMESIS.data').write_text('other')
        self.reg.touch()
        self.assertEqual(self.run_joint(False),'CP_READY_MUTE_OTHER_WRAPPER')
        self.assertEqual((self.wrapper/'NEMESIS.data').read_text(),'other')
        self.addon.mkdir()
        self.assertEqual(self.run_joint(False),'CP_READY_MUTE_OTHER_VERSION')
        self.assertEqual(self.calls.read_text().splitlines(),['cp','cp'])

    def test_restore_and_interrupted_cp_never_install_mute(self):
        self.state.write_text('INSTALLING')
        self.assertEqual(self.run_joint(),'CP_RESTORED_MUTE_UNCHANGED')
        self.run_joint()
        (self.usb/'NEEV-CP-RESTORE').touch()
        self.assertEqual(self.run_joint(),'CP_RESTORED_MUTE_UNCHANGED')
        self.assertTrue(self.addon.is_dir() and self.reg.exists())
        self.assertEqual(self.version.read_text().strip(),'0.1.10')

    def test_stale_restore_marker_does_not_install(self):
        (self.usb/'NEEV-CP-RESTORE').touch()
        self.assertEqual(self.run_joint(False),'CP_FAILED')
        self.assertEqual(self.calls.read_text().splitlines(),['cp'])
        self.assertFalse(self.reg.exists())

    def test_reboot_after_hard_stop(self):
        lock=self.stage/'.joint-mute-ab12-1234.lock'
        lock.mkdir()
        self.assertEqual(self.run_joint(False),'CP_READY_MUTE_BUSY')
        self.assertFalse(self.reg.exists())
        self.boot.write_text('feed-face')
        self.assertEqual(self.run_joint(),'COMPLETE')

    def test_damaged_live_cp_never_advances_mute(self):
        self.assertEqual(self.run_joint(False,'mute-postfail'),'CP_READY_MUTE_INSTALL_FAILED')
        (self.cp/'hooks.jar').write_bytes(b'tampered')
        self.assertEqual(self.run_joint(False),'CP_STATE_UNVERIFIED')
        self.assertFalse(self.addon.exists())


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive',type=Path,help='Built, trusted USB release archive')
    parser.add_argument('--legacy-mute',type=Path,help='Original published Mute Pause1.0.0 USB ZIP')
    parser.add_argument('--legacy-unbranded',type=Path,help='Original pre-branding Mute Pause1.0.0 USB ZIP')
    a=parser.parse_args()
    build_usb=a.archive.resolve()
    legacy_mute=a.legacy_mute
    legacy_unbranded=a.legacy_unbranded
    unittest.main(argv=[sys.argv[0]],verbosity=2)
