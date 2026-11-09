"""Verify the built release and all installer hash bindings."""
import argparse
import hashlib
from io import BytesIO
from pathlib import Path
import re
import shutil
import tempfile
import unittest
from zipfile import ZipFile
import build

INSTALLER = None
OWNER = 'bd5f651c-38f9-8ef4-827c-34ad706c2b5c'

class ReleaseArtifact(unittest.TestCase):
    def test_release_hash_chain_and_reinstall_manifest_are_fully_rendered(self):
        with tempfile.TemporaryDirectory() as d:
            out=Path(d)/'release.zip'
            build.build(INSTALLER,OWNER,out)
            files=build.readzip(out)
            self.assertEqual(len(files),36)
            mod_manifest=files['NEEV_CP/payload/mod/META-HMGAPI/manifest.json']
            self.assertIn(('verify_removed_source "$record/kit/payload/mod" "'+build.md5(mod_manifest)+'"').encode(),files['NEEV_CP_INSTALL.sh'])
            self.assertIn(build.md5(files['NEEV_CP_INSTALL.sh']).encode(),files['NEMESIS.sh'])
            for name in ('NEMESIS.sh','NEEV_CP_INSTALL.sh',build.package_name):
                self.assertIn(build.md5(files[name]).encode(),files['USB_AUTO.sh'])
            with ZipFile(BytesIO(files[build.package_name])) as native:
                common=native.read('scripts/common.sh')
                for name in ('NEMESIS.sh','NEEV_CP_INSTALL.sh'):
                    self.assertIn(build.md5(files[name]).encode(),common)
                scripts={name:native.read(name) for name in native.namelist() if name.endswith('.sh')}
            scripts.update({name:data for name,data in files.items() if name.endswith('.sh')})
            for name,data in scripts.items():
                self.assertIsNone(re.search(rb'@[A-Z][A-Z0-9_]*@',data),name)
            for line in files['NEEV_CP/MANIFEST.md5'].decode().splitlines():
                digest,name=line.split(None,1)
                self.assertEqual(build.md5(files['NEEV_CP/'+name]),digest,name)

    def test_release_rejects_old_unrendered_installer(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); cp=root/'cp';shutil.copytree(INSTALLER,cp)
            script=cp/'NEMESIS.sh'; script.write_text(script.read_text()+'\n# @PUBLIC_MOD_MD5@\n')
            with self.assertRaisesRegex(ValueError,'Unresolved installer token'):
                build.build(cp,OWNER,root/'bad.zip')
            self.assertFalse((root/'bad.zip').exists())

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--installer',type=Path,required=True)
    args=parser.parse_args();INSTALLER=args.installer.resolve()
    result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(ReleaseArtifact))
    raise SystemExit(0 if result.wasSuccessful() else 1)
