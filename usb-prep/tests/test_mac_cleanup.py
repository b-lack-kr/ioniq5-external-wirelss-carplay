"""Run the production USB cleanup on disposable directories."""
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1]/'source/USB-준비-macOS.command'
CLEAN = SCRIPT.read_text().split("<<'PERL_CLEAN_USB'",1)[1].split("\n",1)[1].split("\nPERL_CLEAN_USB",1)[0]

class Cleanup(unittest.TestCase):
    def run_cleanup(self, root):
        s = root.stat()
        return subprocess.run(['/usr/bin/perl','-',f'{s.st_dev}:{s.st_ino}'],input=CLEAN,text=True,cwd=root,capture_output=True)

    def test_locked_root_metadata_preserved_and_payload_removed(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); journal=root/'$TXRAJNL.DAT'; journal.write_bytes(b'preserve exactly')
            (root/'.Spotlight-V100').mkdir(); (root/'.Spotlight-V100/keep').write_text('metadata')
            (root/'NEEV_CP').mkdir(); (root/'NEEV_CP/old').write_text('old payload')
            (root/'NEEV_CP/$TXRAJNL.DAT').write_text('nested ordinary file')
            (root/'$TXRAJNL.DAT.extra').write_text('ordinary file')
            (root/'NEMESIS.data').write_text('old trigger')
            os.chflags(journal,stat.UF_IMMUTABLE|stat.UF_HIDDEN)
            try:
                before=journal.stat().st_flags
                result=self.run_cleanup(root)
                self.assertEqual(result.returncode,0,result.stderr)
                self.assertEqual({p.name for p in root.iterdir()},{'$TXRAJNL.DAT','.Spotlight-V100'})
                self.assertEqual(journal.read_bytes(),b'preserve exactly')
                self.assertEqual(journal.stat().st_flags,before)
                self.assertEqual(self.run_cleanup(root).returncode,0)
            finally:
                os.chflags(journal,0)

    def test_same_named_symlink_removed_without_touching_target(self):
        with tempfile.TemporaryDirectory() as d, tempfile.TemporaryDirectory() as outside:
            root=Path(d); target=Path(outside)/'important'; target.write_text('keep')
            (root/'$TXRAJNL.DAT').symlink_to(target)
            result=self.run_cleanup(root)
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertEqual(list(root.iterdir()),[])
            self.assertEqual(target.read_text(),'keep')

    def test_same_named_directory_is_not_exempt(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); (root/'$TXRAJNL.DAT').mkdir(); (root/'$TXRAJNL.DAT/old').write_text('old')
            result=self.run_cleanup(root)
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertEqual(list(root.iterdir()),[])

if __name__=='__main__': unittest.main(verbosity=2)
