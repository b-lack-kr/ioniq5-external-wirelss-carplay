"""Limited hook audit exports no process contents and never changes HMG guard files."""
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

SOURCE=Path(__file__).resolve().parent/'templates/hook-diagnostics.sh'
class HookDiagnostics(unittest.TestCase):
    def setUp(self):
        t=tempfile.TemporaryDirectory();self.addCleanup(t.cleanup);self.root=Path(t.name)
        self.hook=self.root/'data/hmgapi/hook';self.hook.mkdir(parents=True)
        self.lib=self.root/'system/lib/libhmgapi_hook.so';self.lib.parent.mkdir(parents=True);self.lib.write_bytes(b'library')
        self.bin=self.root/'bin';self.bin.mkdir()
        ls=self.bin/'ls';ls.write_text('#!'+sys.executable+'\nimport os,sys,stat\np=sys.argv[2];s=os.stat(p)\nprint("%s root root %d 2026-11-09 10:00 %s"%(stat.filemode(s.st_mode),s.st_size,p))\n');ls.chmod(0o755)
        text=SOURCE.read_text()
        for prefix in ('/data/','/system/','/proc/'):text=text.replace(prefix,str(self.root)+prefix)
        self.script=self.root/'audit.sh';self.script.write_text(text+'\nhook_diagnostics\n')
        (self.hook/'enable').touch();(self.hook/'starts').write_text('3')
    def process(self,pid=10,parent=1,uid=0,mapped=True,name='zygote'):
        p=self.root/'proc'/str(pid);p.mkdir(parents=True)
        (p/'comm').write_text(name+'\n')
        (p/'status').write_text(f'Name:\t{name}\nPPid:\t{parent}\nUid:\t{uid}\t{uid}\t{uid}\t{uid}\n')
        rows='0000-1000 r-xp 0 00:01 10 /secret/UNRELATED_APP_AND_PRIVATE_VALUE\n'
        if mapped:rows+=f'1000-2000 r-xp 0 00:01 11 {self.lib}\n'
        (p/'maps').write_text(rows)
        return p
    def run_audit(self):
        def tree():return {str(p.relative_to(self.root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in self.root.rglob('*') if p.is_file()}
        before=tree()
        r=subprocess.run(['/bin/sh',self.script],capture_output=True,text=True,env={**os.environ,'PATH':str(self.bin)+':/bin:/usr/bin'})
        self.assertEqual(r.returncode,0,r.stderr);self.assertEqual(before,tree())
        self.assertNotIn('PRIVATE',r.stdout);self.assertNotIn('/proc/',r.stdout);self.assertNotIn('/system/',r.stdout)
        parsed={}
        for line in r.stdout.splitlines():
            self.assertRegex(line,r'^(hook_enable_exists|hook_disable_exists|hook_library_exists|hook_starts_exists|hook_starts_valid|zygote_found|zygote_maps_readable|zygote_hook_mapped)=[01]$|^hook_starts=[0-9]{1,10}$')
            key,value=line.split('=');self.assertNotIn(key,parsed);parsed[key]=value
        return parsed
    def test_hook_flags_counter_and_mapping_are_separate(self):
        self.process();r=self.run_audit()
        self.assertEqual(r['hook_starts'],'3');self.assertEqual(r['zygote_hook_mapped'],'1')
        self.assertEqual(r['hook_enable_exists'],'1');self.assertEqual(r['hook_disable_exists'],'0')
        (self.hook/'disable').touch();r=self.run_audit();self.assertEqual(r['hook_disable_exists'],'1')
    def test_zygote_without_mapping(self):
        self.process(mapped=False);r=self.run_audit()
        self.assertEqual(r['zygote_found'],'1');self.assertEqual(r['zygote_maps_readable'],'1');self.assertEqual(r['zygote_hook_mapped'],'0')
    def test_other_apps_and_ambiguous_zygotes_are_not_exported(self):
        self.process(parent=2,uid=1000);r=self.run_audit();self.assertEqual(r['zygote_found'],'0')
        self.process(pid=20);self.process(pid=30);r=self.run_audit()
        self.assertEqual(r['zygote_found'],'1');self.assertEqual(r['zygote_maps_readable'],'0');self.assertEqual(r['zygote_hook_mapped'],'0')
    def test_counter_valid_range_and_optional_newline(self):
        for raw in ('0','1\n','3','2147483647\n'):
            with self.subTest(raw=raw):
                (self.hook/'starts').write_text(raw);r=self.run_audit();self.assertEqual(r['hook_starts_valid'],'1');self.assertEqual(r['hook_starts'],raw.strip())
    def test_invalid_counter_never_exports_raw_content(self):
        for raw in ('','-1','01','2147483648','9999999999999999999999','3\nprivate','3  private','$(touch bad)','private-value'):
            with self.subTest(raw=raw):
                (self.hook/'starts').write_text(raw);r=self.run_audit();self.assertEqual(r['hook_starts_valid'],'0');self.assertNotIn('hook_starts',r)
    def test_symlink_counter_and_missing_files(self):
        (self.hook/'starts').unlink();r=self.run_audit();self.assertEqual(r['hook_starts_exists'],'0')
        target=self.root/'secret';target.write_text('42')
        (self.hook/'starts').symlink_to(target);r=self.run_audit()
        self.assertEqual(r['hook_starts_exists'],'1');self.assertEqual(r['hook_starts_valid'],'0');self.assertNotIn('hook_starts',r)
        (self.hook/'enable').unlink();self.lib.unlink();r=self.run_audit()
        self.assertEqual(r['hook_enable_exists'],'0');self.assertEqual(r['hook_library_exists'],'0')
    def test_similar_library_path_is_not_mapping(self):
        p=self.process(mapped=False);(p/'maps').write_text(f'1000-2000 r-xp 0 00:01 11 {self.lib}.other\n')
        self.assertEqual(self.run_audit()['zygote_hook_mapped'],'0')
    def test_deleted_mapped_library_counts_as_loaded(self):
        p=self.process(mapped=False);(p/'maps').write_text(f'1000-2000 r-xp 0 00:01 11 {self.lib} (deleted)\n')
        self.assertEqual(self.run_audit()['zygote_hook_mapped'],'1')
if __name__=='__main__':unittest.main(verbosity=2)
