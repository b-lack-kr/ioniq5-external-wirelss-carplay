"""Original published 1.0.3 can be collected with an installer-only revision."""
import hashlib
import os
from pathlib import Path
import shutil
import unittest
from zipfile import ZipFile
import test_joint103 as fixture

SOURCE = Path(__file__).resolve().parents[1]
PROJECT = next(p for p in SOURCE.parents if (p/'neev-carplay').is_dir())
ORIGINAL = PROJECT/'neev-carplay/publishing/20261109-v103-release/assets/NEEV_260902_CarPlay_1.0.3_USB.zip'


class Original103Collection(fixture.CarPlayInstaller):
    # Inherit fixture mechanics only, not the unrelated historical cases.
    __unittest_skip__ = False

    def setUp(self):
        fixture.build_usb = ORIGINAL
        super().setUp()
        self.assertEqual(hashlib.sha256(ORIGINAL.read_bytes()).hexdigest(),
                         '76174dfab95ae3a8b97e7f0a6ce492ec49a507194e9135103deef90dd29e8ef2')
        shutil.copytree(self.usb/'NEEV_CP', self.stage/'kit')
        shutil.copytree(self.stage/'kit/payload/mod', self.cp)
        (self.cp/'.features').mkdir()
        (self.cp/'.features/generated.xml').write_text('generated')
        self.state.write_text('INSTALLED\n')
        self.version.write_text('1.0.3\n')
        self.registrations = self.root/'hmg-state/services.txt'
        self.registrations.parent.mkdir()
        self.registrations.write_text(
            'neev-usb-carplay usb-ap root boot yes script ap/service.sh -\n'
            'neev-usb-carplay ap-provider root android yes jar provider.jar kr.ioniq5.usbcp.Provider\n'
            'neev-usb-carplay development-log root android yes jar provider.jar kr.ioniq5.usbcp.DevelopmentLogger\n')
        wrapper = (SOURCE/'release/NEMESIS.sh').read_text()
        values = {
            '@OWNER_UUID@':'bd5f651c-38f9-8ef4-827c-34ad706c2b5c',
            '@CP_LAUNCHER@':hashlib.md5((self.usb/'NEEV_CP_INSTALL.sh').read_bytes()).hexdigest(),
            '@CP_PAYLOAD_MANIFEST@':'00000000000000000000000000000000',
            '@CP_MOD_MANIFEST@':'4d93094965f723ecb20912d45074cfa2',
            '@SETUP_JAR@':hashlib.md5((self.usb/'NEEV_CP/payload/setup.jar').read_bytes()).hexdigest(),
            '@HOOK_DIAGNOSTICS@':'hook_diagnostics() { :; }',
        }
        runner=SOURCE/'installer/templates/cli-runner.sh'
        if '@CLI_RUNNER@' in wrapper:
            self.assertTrue(runner.is_file())
            values['@CLI_RUNNER@']=runner.read_text()
        for key,value in values.items(): wrapper=wrapper.replace(key,value)
        wrapper=wrapper.replace('/data/nemesis/neev-usbcp',str(self.w)).replace('/data/hmgapi/mods',str(self.mods))
        wrapper=wrapper.replace('/data/hmgapi/state/services.txt',str(self.registrations))
        wrapper=wrapper.replace('PATH=/system/bin:/system/xbin:/data/nemesis/bin',f'PATH={self.bin}:/bin:/usr/bin')
        wrapper=wrapper.replace('/system/bin/app_process.hmgapi-stock',str(self.bin/'verify'))
        wrapper=wrapper.replace('/system/bin/app_process',str(self.bin/'verify'))
        wrapper=wrapper.replace('/system/bin/sh "$USB/NEEV_CP_INSTALL.sh"',str(self.bin/'cp-upgrade')+' "$USB/NEEV_CP_INSTALL.sh"')
        (self.usb/'NEMESIS.sh').write_text(wrapper)
        verifier=self.bin/'verify'
        text=verifier.read_text().replace('command=sys.argv[2:4]',
             "sys.argv=[sys.argv[0]]+[arg for arg in sys.argv[1:] if arg != '-Xint']\ncommand=sys.argv[2:4]")
        text=text.replace("os.environ['TEST_ROOT']+'/base/public-release-1.0.3/kit/payload/setup.jar'",
                          "os.environ['TEST_ROOT']+'/stick/NEEV_CP/payload/setup.jar'")
        text=text.replace("assert os.environ['CLASSPATH']==os.environ['TEST_ROOT']+'/stick/NEEV_CP/payload/setup.jar'",
                          "assert Path(os.environ['CLASSPATH']).resolve()==(Path(os.environ['TEST_ROOT'])/'stick/NEEV_CP/payload/setup.jar').resolve()")
        verifier.write_text(text)

    def test_original103_collects_without_running_new_child(self):
        self.assertEqual(self.run_cp(args=('--collect',)), 'LOGS_EXPORTED')
        self.assertFalse(self.calls.exists())
        self.assertEqual((self.root/'history-calls').read_text(), 'history\n')
        self.assertEqual(self.state.read_text(), 'INSTALLED\n')

    def test_original103_modified_installer_member_rejected(self):
        (self.stage/'kit/operate.sh').write_text('modified')
        self.assertEqual(self.run_cp(False,args=('--collect',)), 'CP_STATE_UNVERIFIED')
        self.assertFalse(self.calls.exists() or (self.root/'history-calls').exists())

    def test_original103_extra_installed_file_rejected(self):
        (self.cp/'extra.bin').write_text('unsigned')
        self.assertEqual(self.run_cp(False,args=('--collect',)), 'CP_STATE_UNVERIFIED')
        self.assertFalse(self.calls.exists() or (self.root/'history-calls').exists())

    def test_original103_missing_service_rejected(self):
        self.registrations.write_text('')
        self.assertEqual(self.run_cp(False,args=('--collect',)), 'CP_STATE_UNVERIFIED')
        self.assertFalse(self.calls.exists() or (self.root/'history-calls').exists())

    def test_original103_disabled_mod_rejected(self):
        (self.cp/'.disabled').touch()
        self.assertEqual(self.run_cp(False,args=('--collect',)), 'CP_STATE_UNVERIFIED')
        self.assertFalse(self.calls.exists() or (self.root/'history-calls').exists())

    def test_original103_changed_usb_history_helper_rejected(self):
        (self.usb/'NEEV_CP/payload/setup.jar').write_text('modified')
        self.assertEqual(self.run_cp(False,args=('--collect',)), 'HISTORY_HELPER_INVALID')
        self.assertFalse(self.calls.exists() or (self.root/'history-calls').exists())

    def test_original103_symlinked_kit_parent_rejected(self):
        payload=self.stage/'kit/payload'
        elsewhere=self.root/'moved-payload'
        payload.rename(elsewhere); payload.symlink_to(elsewhere, target_is_directory=True)
        self.assertEqual(self.run_cp(False,args=('--collect',)), 'CP_STATE_UNVERIFIED')
        self.assertFalse(self.calls.exists() or (self.root/'history-calls').exists())


def load_tests(loader, tests, pattern):
    suite=unittest.TestSuite()
    for name in Original103Collection.__dict__:
        if name.startswith('test_'): suite.addTest(Original103Collection(name))
    return suite

if __name__ == '__main__': unittest.main(verbosity=2)
