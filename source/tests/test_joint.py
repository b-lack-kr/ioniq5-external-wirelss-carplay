"""Run the shipping coordinator with simulated NEMESIS/CarPlay endpoints on local storage."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from zipfile import ZipFile
import unittest

build_usb = None
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
        self.stage=self.w/'public-1.0.0'
        for d in (self.bin,self.mods,self.apps,self.stage): d.mkdir(parents=True)
        with ZipFile(build_usb) as kit:
            kit.extractall(self.usb)
        self.state=self.stage/'STATUS'
        self.version=self.w/'UPGRADE_CURRENT.txt'
        self.version.write_text('0.1.10\n')
        self.done=self.stage/'MUTE_1_0_0_DONE'
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
        self.tool('verify','''case "$2/$3" in
 hmgapi.engine.Signing/verify) [ -f "$4/META-HMGAPI/signature" ];;
 kr.ioniq5.usbcp.setup.Setup/history)
   [ "$CLASSPATH" = "$TEST_ROOT/base/public-1.0.0/kit/payload/setup.jar" ] || exit 4
   [ "${FAULT:-}" != history-fail ] || exit 5
   [ "${FAULT:-}" != history-partial ] || exit 7
   [ -d "$4" ] && echo "mock collector" > "$4/test-collector.txt";;
 *) exit 4;;
esac''')
        self.tool('cp-upgrade','''[ "$NEEV_CP_BUNDLE" = 1 ] || exit 9
echo cp >> "$TEST_ROOT/calls.txt"
base=$TEST_ROOT/base
stage=$base/public-1.0.0
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
  cp -R "$TEST_ROOT/stick/NEEV_CP/payload/mod" "$TEST_ROOT/mods/neev-usb-carplay"
  mkdir -p "$stage/kit/payload"
  cp "$TEST_ROOT/stick/NEEV_CP/payload/setup.jar" "$stage/kit/payload/setup.jar"
  mkdir "$TEST_ROOT/mods/neev-usb-carplay/.features"
  echo generated > "$TEST_ROOT/mods/neev-usb-carplay/.features/generated.xml"
fi
echo 1.0.0 > "$base/UPGRADE_CURRENT.txt"
echo INSTALLED > "$stage/STATUS"
''')
        self.tool('nemesis','''id=hmgapi.neev-carplay-mute-pause
case "$1" in
 pkg-list) [ "${FAULT:-}" != registry ] || exit 1
           echo 'other|Other|0|application|9|keep'
           if [ -f "$TEST_ROOT/registered" ]; then echo "$id|Mute|0|application|1.0.0|test"; fi
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

    def tool(self,name,body):
        path=self.bin/name
        path.write_text('#!/bin/sh\n'+body+'\n')
        path.chmod(0o755)

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
        self.assertEqual(self.version.read_text().strip(),'1.0.0')
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
    a=parser.parse_args()
    build_usb=a.archive.resolve()
    unittest.main(argv=[sys.argv[0]],verbosity=2)
