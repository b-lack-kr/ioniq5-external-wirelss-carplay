"""Isolated shell transaction tests. Android IPC and the firmware are modeled, never executed."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from zipfile import ZipFile

HERE=Path(__file__).resolve().parent
PROJECT=next((p for p in HERE.parents if (p/'배포용/1.0.1/NEEV_260902_CarPlay_1.0.1_USB.zip').is_file()),HERE.parent)
PUBLIC_101=Path(os.environ.get('NEEV_PUBLIC_101_USB',str(PROJECT/'배포용/1.0.1/NEEV_260902_CarPlay_1.0.1_USB.zip')))
PUBLIC_101_SHA='17f565786fba31b93ad38d5d5a5f383b9f8fe7d1fab1b9368baa8a08de3167c9'
OLD1='b82a23b7e928c80d4c90fa16e8767eb8'
OLD2='c57459b715e3e5eeb6e6fc5c8bdd35e1'
UUID='bd5f651c-38f9-8ef4-827c-34ad706c2b5c'


def md5(data):
    return hashlib.md5(data).hexdigest()


def write(p, text, exe=False):
    p.parent.mkdir(parents=True,exist_ok=True); p.write_text(text)
    if exe:p.chmod(0o755)


MOCK=r'''#!/usr/bin/env python3
import os,sys,pathlib,shutil,hashlib,json,signal
P=pathlib.Path
root=P(os.environ['TEST_UNIT'])
H=root/'data/hmgapi'; W=root/'data/nemesis/neev-usbcp'; M=H/'mods/neev-usb-carplay'; F=root/'fault'
def hit(key):
 if F.exists() and F.read_text()==key: F.unlink(); return True
 return False
def write(p,s): p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s)
def tree(p):return {str(k.relative_to(p)):hashlib.sha256(k.read_bytes()).hexdigest() for k in sorted(p.rglob('*')) if k.is_file()}
def retire(p):
 if p.exists():shutil.rmtree(p)
name=P(sys.argv[0]).name;a=sys.argv[1:]
if name=='md5': print(hashlib.md5(P(a[0]).read_bytes()).hexdigest()+'  '+a[0]);sys.exit()
if name=='getprop': print({'ro.build.product':'NEEV.KOR.0000.V039.001.260902','ro.board.platform':'tcc803x','sys.boot_completed':'1'}.get(a[0],''));sys.exit()
if name=='am':write(root/'notice',' '.join(a));sys.exit()
if name in ('sync','sleep','hostapd','dnsmasq'):sys.exit()
if name=='id':print('uid=0(root)');sys.exit()
if name=='hmgapid':
 if a[0]=='version':
  if hit('hmg-version'):sys.exit(3)
  version=root/'hmg-version'
  print(version.read_text() if version.exists() else 'hmgapid 1.0.1')
 elif a[0]=='ping':
  if hit('hmg-ping'):sys.exit(3)
 elif a[0]=='enable':(M/'.disabled').unlink(missing_ok=True)
 elif a[0]=='disable': (M/'.disabled').touch()
 elif a[0]=='service-stop':
  with (root/'service-stops').open('a') as trace:trace.write(a[2]+'\n')
  rows=(H/'state/services.status').read_text().splitlines(); write(H/'state/services.status',''.join(('neev-usb-carplay '+a[2]+' stopped 0' if x.startswith('neev-usb-carplay '+a[2]+' ') else x)+'\n' for x in rows))
 elif a[0] not in ('ping','services'):raise ValueError(a)
 sys.exit()
assert name in ('app_process','app_process.hmgapi-stock'),name
if a and a[0]=='-Xint':a=a[1:]
_,cls,op,*a=a
if cls.endswith('.Setup'):
 if op=='uid':pass
 elif op=='tree': write(P(a[1]),json.dumps(tree(P(a[0])),sort_keys=True))
 elif op=='same-tree':sys.exit(0 if tree(P(a[0]))==json.loads(P(a[1]).read_text()) else 2)
 elif op=='merge':
  folder=P(a[0])/'hyundai/neev/kor/260902';write(folder/'car.json','fixture')
 elif op=='config':write(P(a[0]),'AP_PASS=per-target-test\nAP_SSID=Hyundai_123456\n');write(P(a[0]).parent/'ap-identity','Hyundai_123456\n')
 else:raise RuntimeError(op)
elif cls.endswith('.ApIdentity'):
 assert op=='preflight' and P(a[0])==W/'config/hotspot.conf' and P(a[1])==W/'config/ap-identity' and P(a[1]).is_file()
elif cls.endswith('.Signing'):
 folder=P(a[0]);f=folder/'META-HMGAPI/manifest.json'
 try:
  m=json.loads(f.read_text());assert m.get('owner',m.get('uuid'))==a[1]
  assert not (folder/'tamper').exists()
  if 'files' in m:
   assert (folder/'META-HMGAPI/signature').is_file()
   actual={str(x.relative_to(folder)):hashlib.sha256(x.read_bytes()).hexdigest()
           for x in folder.rglob('*') if x.is_file() and x.relative_to(folder).parts[0]!='META-HMGAPI'}
   assert actual==m['files']
 except (OSError,ValueError,AssertionError):sys.exit(1)
elif cls.endswith('.Engine'):
 if hit('engine-'+op):sys.exit(3)
 applied=H/'state/applied.fixture'
 if op=='verify-map':
  write(H/'state/map-check.json','checked here');print("map hyundai/neev/kor/260902: 252 of 252 hook points resolve in this car's apps")
 elif op=='map':print('hook map: probed for Ioniq 5, hooks on')
 elif op=='plan':
  if (root/'foreign-plan').exists(): print('change   /unrelated [other] -> digest')
  elif M.exists() and not applied.exists():
   print('change   '+str(root)+'/system/etc/permissions/com.hkmc.software.connectivity.carplay_wireless.xml [neev-usb-carplay] -> d')
   print('setting  prop/persist.ium.support = carplay [neev-usb-carplay]')
  elif not M.exists() and applied.exists():
   print('restore  '+str(root)+'/system/etc/permissions/com.hkmc.software.connectivity.carplay_wireless.xml (stock)')
   print('setting  prop/persist.ium.support back to stock')
 elif op=='install':
  retire(M);shutil.copytree(a[0],M)
  if hit('engine-install-crash'):
   os.kill(os.getppid(),signal.SIGKILL);sys.exit(8)
  status='neev-usb-carplay usb-ap running 0\nneev-usb-carplay ap-provider running 0\n'
  services='neev-usb-carplay usb-ap root boot yes script ap/service.sh -\nneev-usb-carplay ap-provider root android yes jar provider.jar kr.ioniq5.usbcp.Provider\n'
  version=json.loads((M/'mod.json').read_text()).get('version') or json.loads((M/'META-HMGAPI/manifest.json').read_text()).get('version')
  if version in ('1.0.2','1.0.4','1.0.5','1.0.6','1.0.7','1.0.8','1.0.9','1.0.10','1.0.11','1.0.12','1.0.13','1.0.14','1.0.2'):
   status+='neev-usb-carplay development-log running 0\n'
   line='neev-usb-carplay development-log root android yes jar provider.jar kr.ioniq5.usbcp.DevelopmentLogger\n'
   if not hit('development-registration'):services+=line
   if hit('development-duplicate'):services+=line
  write(H/'state/services.status',status)
  write(H/'state/services.txt',services)
  if hit('services-registration'):write(H/'state/services.txt','')
 elif op=='remove':retire(M)
 elif op=='recover':(H/'state/journal.json').unlink(missing_ok=True)
 elif op=='apply':
  if M.exists(): applied.touch()
  else: applied.unlink(missing_ok=True)
 else:raise RuntimeError(op)
else:raise RuntimeError(cls)
'''


class Installation(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.H=self.root/'data/hmgapi'; self.W=self.root/'data/nemesis/neev-usbcp';self.R=self.W/'public-release-1.0.2'
        self.M=self.H/'mods/neev-usb-carplay';self.usb=self.root/'usb';self.pack=self.usb/'NEEV_CP'
        for d in ('maps','mods','hook','state'):(self.H/d).mkdir(parents=True)
        self.W.parent.mkdir(parents=True); (self.H/'hook/enable').touch()
        self.map=self.H/'maps/hyundai/neev/kor/260902'
        write(self.root/'proc/sys/kernel/random/boot_id','abcd-abcd');write(self.root/'proc/sys/kernel/osrelease','3.18.24-tcc\n')
        self.bin=self.root/'system/bin'
        for cmd in ('getprop','md5','hmgapid','am','app_process','dnsmasq','hostapd','sync','sleep','id'):
            write(self.bin/cmd,MOCK,True)
        write(self.bin/'app_process.hmgapi-stock',MOCK,True);(self.bin/'sh').symlink_to('/bin/sh')
        write(self.root/'system/firmware','fixture')
        write(self.pack/'payload/TARGET.md5',md5(b'fixture')+'  '+str(self.root)+'/system/firmware\n')
        self.new='{"owner":"'+UUID+'","version":"1.0.2"}'
        self.old='{"owner":"'+UUID+'","version":"0.1.10"}'
        self.legacy_manifest='fixture-legacy-manifest'
        write(self.pack/'payload/mod/META-HMGAPI/manifest.json',self.new)
        write(self.pack/'payload/mod/mod.json','{}')
        for dest in ['payload/mod/provider.jar','payload/setup.jar']:write(self.pack/dest,'fixture')
        script='''#!/bin/sh
if [ -e "@ROOT@/no-vendor" ]; then exit 2; fi
if [ "$2" = install ]; then mkdir -p "@ROOT@/data/nemesis/neev-usbcp/vendor/hkg-2.0.2"; echo fixture > "@ROOT@/data/nemesis/neev-usbcp/vendor/hkg-2.0.2/asset"; fi
'''.replace('@ROOT@',str(self.root));write(self.pack/'import-hkg.sh',script)
        def template(name):
            s=(HERE/'templates'/name).read_text().replace('@CLI_RUNNER@',(HERE/'templates/cli-runner.sh').read_text()).replace('@PUBLIC_MOD_MD5@',md5(self.new.encode())).replace('@OWNER@',UUID)
            s=s.replace(OLD1,md5(self.old.encode())).replace('468a2a355ca642dc0b2cfb4c3a45204f',md5(self.legacy_manifest.encode()))
            for prefix in ('/data/','/proc/','/system/'):
                s=s.replace(prefix,str(self.root)+prefix)
            s=s.replace('export PATH=', 'export PATH=').replace(str(self.root)+'/data/nemesis/bin\n',str(self.root)+'/data/nemesis/bin:/bin:/usr/bin\n')
            return s
        write(self.pack/'operate.sh',template('operate.sh'))
        members=''.join(md5(f.read_bytes())+'  '+f.relative_to(self.pack).as_posix()+'\n' for f in sorted(self.pack.rglob('*')) if f.is_file())
        write(self.pack/'MANIFEST.md5',members)
        write(self.usb/'NEMESIS.sh',template('NEMESIS.sh').replace('@PACK_MD5@',md5(members.encode())))
    def run_usb(self, ok=True):
        before=set(self.usb.glob('NEEV_CP_RESULT_*'))
        p=subprocess.run(['/bin/sh',str(self.usb/'NEMESIS.sh')],env=dict(os.environ,TEST_UNIT=str(self.root)),capture_output=True)
        if (p.returncode==0)!=ok:
            for f in sorted(self.usb.glob('NEEV_CP_RESULT_*/install.log')):print(f.read_text()[-1600:])
            if self.R.exists():
                for f in sorted(self.R.glob('*.log')): print(f.name,f.read_text()[-600:])
            self.fail(f'unexpected USB rc {p.returncode} {p.stderr}')
        reports=set(self.usb.glob('NEEV_CP_RESULT_*'))-before
        self.assertEqual(len(reports),1)
        status=reports.pop()/'STATUS.txt'
        # A deliberate SIGKILL fixture stops before the cleanup trap can report.
        return status.read_text().strip() if status.is_file() else None










    def legacy(self):
        write(self.W/'MANIFEST.md5',self.legacy_manifest);write(self.W/'UPGRADE_CURRENT.txt','0.1.10\n');write(self.W/'BACKUP_COMPLETE','')
        write(self.W/'config/hotspot.conf','prior-secret');write(self.W/'config/ap-identity','prior-id')
        write(self.M/'META-HMGAPI/manifest.json',self.old);write(self.M/'mod.json','{}')
        write(self.H/'state/services.status','neev-usb-carplay usb-ap running 0\nneev-usb-carplay ap-provider running 0\n')
        write(self.H/'state/applied.fixture','')
    def public101(self):
        # Keep real published 1.0.1 kit bytes, including its pinned manifests.
        # Its scripts are only hash-checked; the test never runs old payload code.
        self.assertEqual(hashlib.sha256(PUBLIC_101.read_bytes()).hexdigest(),PUBLIC_101_SHA)
        old=self.W/'public-1.0.1'
        with ZipFile(PUBLIC_101) as archive:
            for name in archive.namelist():
                if name.startswith('NEEV_CP/') and not name.endswith('/'):
                    target=old/'kit'/name[len('NEEV_CP/'):]
                    target.parent.mkdir(parents=True,exist_ok=True)
                    target.write_bytes(archive.read(name))
        write(old/'STATUS','INSTALLED\n');write(old/'mode','fresh\n')
        shutil.copytree(old/'kit/payload/mod',self.M)
        write(self.W/'UPGRADE_CURRENT.txt','1.0.1\n')
        write(self.W/'config/hotspot.conf','prior-secret');write(self.W/'config/ap-identity','prior-id')
        write(self.H/'settings/neev-usb-carplay.json','{"carplay_tbt":false}')
        write(self.map/'car.json','previous-map');write(self.map/'custom.json','keep-map')
        write(self.H/'state/map-check.json','previous-map-check')
        write(self.H/'state/applied.fixture','')
        write(self.H/'state/services.status','neev-usb-carplay usb-ap running 0\nneev-usb-carplay ap-provider running 0\n')
        return old













    def manual_old(self):
        self.legacy()
        upgrade=self.W/'upgrades/v0110-20231104-123456-78'
        write(self.W/'V0110_ACTIVE.txt',upgrade.name)
        write(self.W/'V0110_STATUS.txt','UPGRADED_REBOOT_REQUIRED')
        write(self.W/'STATUS.txt','INSTALLED_REBOOT_REQUIRED')
        write(upgrade/'STATUS.txt','UPGRADED_REBOOT_REQUIRED')
        shutil.copytree(self.M,upgrade/'payload/mod')
        shutil.rmtree(self.M)
        (self.H/'state/applied.fixture').unlink()
        write(self.usb/'packages/usbwifi.hotspot.zip','vendor-fixture')
        self.pref=self.H/'settings/neev-usb-carplay.json'
        write(self.pref,'old-pref')
        return upgrade






    def restore(self):
        write(self.usb/'NEEV-CP-RESTORE','');self.run_usb();self.assertEqual((self.R/'STATUS').read_text().strip(),'RESTORED')

if __name__=='__main__':unittest.main(verbosity=2)
