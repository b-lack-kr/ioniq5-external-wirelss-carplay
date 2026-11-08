"""Exercise the CarPlay-only USB flow; optional package state must not affect it."""
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
app = 'hmgapi.neev-carplay-mute-pause'

class CarPlayInstaller(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory(prefix='neev-joint-')
        self.addCleanup(tmp.cleanup)
        self.root=Path(tmp.name)
        self.usb=self.root/'stick'
        self.bin=self.root/'tools'
        self.w=self.root/'base'
        self.mods=self.root/'mods'
        self.apps=self.root/'apps'
        self.stage=self.w/'public-release-1.0.2'
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
        self.tool('am','printf "%s\\n" "$*" > "$TEST_ROOT/notice"')
        self.tool('id',"echo 'uid=0(root)'")
        self.tool('sync','exit 0')
        self.tool('md5','if [ -x /sbin/md5 ]; then exec /sbin/md5 -r "$1"; else exec md5sum "$1"; fi')
        self.tool('getprop','echo NEEV.KOR.0000.V039.001.260902')
        self.tool('hmgapid','''case "$1" in
 version) [ ! -f "$TEST_ROOT/hmg-version-fail" ] || exit 2
          if [ -f "$TEST_ROOT/hmg-version" ]; then cat "$TEST_ROOT/hmg-version"; else echo 'hmgapid 1.0.1'; fi;;
 ping) echo ping >> "$TEST_ROOT/hmg-pings"
       [ ! -f "$TEST_ROOT/hmg-ping-fail" ] || exit 3;;
 *) exit 4;;
esac''')
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
    assert os.environ['CLASSPATH']==os.environ['TEST_ROOT']+'/base/public-release-1.0.2/kit/payload/setup.jar'
    with (Path(os.environ['TEST_ROOT'])/'history-calls').open('a') as f:f.write('history\n')
    if os.environ.get('FAULT')=='history-kill':sys.exit(137)
    if os.environ.get('FAULT')=='history-fail':sys.exit(5)
    if os.environ.get('FAULT')=='history-partial':sys.exit(7)
    (Path(sys.argv[4])/'test-collector.txt').write_text('mock collector\n')
else:
    sys.exit(4)
""")
        self.tool('cp-upgrade','''[ "$NEEV_CP_BUNDLE" = 1 ] || exit 9
echo cp >> "$TEST_ROOT/calls.txt"
base=$TEST_ROOT/base
stage=$base/public-release-1.0.2
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
echo 1.0.2 > "$base/UPGRADE_CURRENT.txt"
echo INSTALLED > "$stage/STATUS"
case "${FAULT:-}" in
 cp-hmg-ping) touch "$TEST_ROOT/hmg-ping-fail";;
 cp-hmg-version) echo 'hmgapid 1.0.0' > "$TEST_ROOT/hmg-version";;
 cp-nemesis-missing) chmod -x "$TEST_ROOT/tools/nemesis";;
esac
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

    def run_cp(self,success=True,fault='',args=('--install',)):
        before=set(self.usb.glob('NEEV_BUNDLE_RESULT_*'))
        result=subprocess.run(['/bin/sh',str(self.usb/'NEMESIS.sh'),*args],capture_output=True,
                              env={**os.environ,'TEST_ROOT':str(self.root),'FAULT':fault})
        new, = set(self.usb.glob('NEEV_BUNDLE_RESULT_*'))-before
        self.assertEqual(result.returncode==0,success,(new/'INSTALL.log').read_text())
        self.result_dir=new
        return (new/'STATUS.txt').read_text().strip()

    def foreign_mute(self):
        self.addon.mkdir(); (self.addon/'hooks.jar').write_bytes(b'unrelated signed version')
        self.wrapper.mkdir(); (self.wrapper/'NEMESIS.data').write_text('another wrapper')
        (self.wrapper/'scripts').mkdir(); (self.wrapper/'scripts/common.sh').write_text('different wrapper revision')
        self.reg.touch()

    def test_carplay_installs_without_mute_archive_or_registration(self):
        self.assertFalse((self.usb/'packages'/f'{app}.zip').exists())
        self.assertEqual(self.run_cp(),'COMPLETE')
        self.assertEqual(self.calls.read_text().splitlines(),['cp'])
        self.assertFalse(self.addon.exists() or self.wrapper.exists() or self.done.exists())

    def test_real_incident_other_mute_wrapper_cannot_fail_carplay(self):
        self.foreign_mute()
        self.assertEqual(self.run_cp(),'COMPLETE')
        self.assertEqual(self.run_cp(args=('--collect',)),'LOGS_EXPORTED')
        self.assertEqual((self.wrapper/'scripts/common.sh').read_text(),'different wrapper revision')
        self.assertEqual((self.addon/'hooks.jar').read_bytes(),b'unrelated signed version')
        self.assertEqual(self.calls.read_text().splitlines(),['cp','cp'])

    def test_broken_optional_zip_is_ignored(self):
        (self.usb/'packages'/f'{app}.zip').write_bytes(b'damaged optional package')
        self.assertEqual(self.run_cp(),'COMPLETE')
        self.assertEqual(self.calls.read_text().splitlines(),['cp'])

    def test_old_mute_receipt_is_not_read_or_modified(self):
        target=self.root/'other-receipt';target.write_text('unknown receipt')
        self.done.symlink_to(target)
        self.assertEqual(self.run_cp(),'COMPLETE')
        self.assertEqual(self.run_cp(args=('--collect',)),'LOGS_EXPORTED')
        self.assertTrue(self.done.is_symlink());self.assertEqual(target.read_text(),'unknown receipt')

    def test_disabled_optional_mod_is_preserved(self):
        self.foreign_mute();(self.addon/'.disabled').touch()
        self.assertEqual(self.run_cp(),'COMPLETE')
        self.assertTrue((self.addon/'.disabled').is_file())

    def test_restore_does_not_install_remove_or_check_mute(self):
        self.foreign_mute();self.run_cp();(self.usb/'NEEV-CP-RESTORE').touch()
        self.assertEqual(self.run_cp(),'CP_RESTORED')
        self.assertEqual((self.wrapper/'NEMESIS.data').read_text(),'another wrapper')
        self.assertEqual((self.addon/'hooks.jar').read_bytes(),b'unrelated signed version')
        self.assertEqual(self.calls.read_text().splitlines(),['cp','cp'])

    def test_failed_carplay_reports_only_carplay_failure(self):
        self.foreign_mute()
        self.assertEqual(self.run_cp(False,'cp-fail'),'CP_FAILED')
        self.assertFalse(self.cp.exists());self.assertTrue(self.addon.exists())

    def test_false_success_still_requires_own_installed_state(self):
        self.assertEqual(self.run_cp(False,'cp-false-success'),'CP_STATE_UNVERIFIED')

    def test_tampered_carplay_launcher_is_not_executed(self):
        (self.usb/'NEEV_CP_INSTALL.sh').write_text('changed')
        self.assertEqual(self.run_cp(False),'BAD_CP_INSTALLER')
        self.assertFalse(self.calls.exists())

    def test_local_signed_mod_tamper_is_rejected(self):
        self.run_cp();(self.stage/'kit/payload/mod/hooks.jar').write_bytes(b'changed')
        self.assertEqual(self.run_cp(False),'CP_STATE_UNVERIFIED')

    def test_live_carplay_tamper_is_rejected(self):
        self.run_cp();(self.cp/'hooks.jar').write_bytes(b'changed')
        self.assertEqual(self.run_cp(False),'CP_STATE_UNVERIFIED')

    def test_history_uses_own_installed_state_without_mute_receipt(self):
        self.run_cp();self.assertFalse(self.done.exists())
        self.assertEqual(self.run_cp(args=('--collect',)),'LOGS_EXPORTED')
        self.assertEqual((self.result_dir/'test-collector.txt').read_text(),'mock collector\n'.replace('\\n','\n'))

    def test_history_error_preserves_install(self):
        self.run_cp();self.assertEqual(self.run_cp(False,'history-fail',args=('--collect',)),'HISTORY_EXPORT_FAILED')
        self.assertEqual(self.state.read_text().strip(),'INSTALLED')

    def test_history_partial_result_is_reported(self):
        self.run_cp();self.assertEqual(self.run_cp(True,'history-partial',args=('--collect',)),'LOGS_EXPORTED_PARTIAL')

    def test_history_rejects_changed_helper(self):
        self.run_cp();(self.stage/'kit/payload/setup.jar').write_bytes(b'changed')
        self.assertEqual(self.run_cp(False,args=('--collect',)),'HISTORY_HELPER_INVALID')

    def test_usb_finder_metadata_stays_outside_signed_staging(self):
        self.add_usb_metadata();self.assertEqual(self.run_cp(),'COMPLETE')
        self.assertFalse((self.stage/'kit/payload/mod/._LICENSE').exists())
        self.assertTrue((self.usb/'NEEV_CP/payload/mod/._LICENSE').exists())

    def test_reinstall_succeeds_without_calling_killed_collector(self):
        self.run_cp()
        self.assertEqual(self.run_cp(True,'history-kill'),'COMPLETE')
        self.assertFalse((self.root/'history-calls').exists())
        self.assertEqual(self.state.read_text().strip(),'INSTALLED')

    def test_explicit_killed_collection_reports_failure_without_changing_install(self):
        self.run_cp()
        self.assertEqual(self.run_cp(False,'history-kill',args=('--collect',)),'HISTORY_EXPORT_FAILED')
        self.assertEqual((self.root/'history-calls').read_text(),'history\n')
        self.assertEqual(self.state.read_text().strip(),'INSTALLED')

    def test_unknown_or_extra_mode_is_rejected_before_installation(self):
        for args in (('--typo',),('--install','--collect')):
            with self.subTest(args=args):
                self.assertEqual(self.run_cp(False,args=args),'INVALID_ACTION')
                self.assertFalse(self.calls.exists())

    def test_collection_does_not_install_a_missing_carplay(self):
        for args in ((),('--collect',)):
            with self.subTest(args=args):
                self.assertEqual(self.run_cp(False,args=args),'CP_NOT_INSTALLED')
                self.assertFalse(self.calls.exists() or self.cp.exists())

    def test_default_execute_mode_collects_existing_install_only(self):
        self.run_cp()
        self.assertEqual(self.run_cp(args=()),'LOGS_EXPORTED')
        self.assertEqual((self.root/'history-calls').read_text(),'history\n')

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive',type=Path,help='Built standalone CarPlay USB release')
    args=parser.parse_args();build_usb=args.archive
    unittest.main(argv=[sys.argv[0]],verbosity=2)
