"""Verified OEM animation sender mapping and narrow, passive power-hook behavior."""
from pathlib import Path
import hashlib,json,subprocess,tempfile
from jvm_tools import java_bin
source=Path(__file__).resolve().parents[1]
point=json.loads((source/'maps/welcome.json').read_text())
assert point=={'welcome.service':{'class':'com.mobis.welcome.WelcomeService'},
 'welcome.service.animation_status':{'method':'e','params':['int'],'ret':'void','static':False},
 'welcome.service.mode':{'method':'getMode','params':[],'ret':'int','static':True}}
manifest=json.loads((source/'mod/mod.json').read_text())
assert manifest['hooks']['points']['welcome.service.animation_status']=='observe'
car=json.loads((source/'maps/car.json').read_text());target=source/'target/TARGET.md5'
assert car['app_files']['welcome']=={'package':'com.mobis.welcome','files':['/system/app/WelcomeGoodbye.odex']}
assert car['files']['/system/app/WelcomeGoodbye.odex']=='33376e36199a38b928bceec54b0c148b'
assert target.read_text().count('33376e36199a38b928bceec54b0c148b  /system/app/WelcomeGoodbye.odex\n')==1
assert hashlib.sha256(target.read_bytes()).hexdigest() in (source/'installer/build_installer.py').read_text()
assert "'welcome.json'" in (source/'installer/build_installer.py').read_text()
assert '"gpm","welcome"' in (source/'setup/Setup.java').read_text()
code=(source/'mod/src/kr/ioniq5/usbcp/WelcomePower.java').read_text()
assert 'registerReceiver' not in code and 'setResult(' not in code and 'setThrowable(' not in code
jdk=java_bin(source)
with tempfile.TemporaryDirectory(prefix='neev-welcome-power-') as temp:
 root=Path(temp).resolve()
 files=list((source/'tests/welcome-power-support').rglob('*.java'))
 files += [source/'tests/audio-routing-support/hmgapi/hook'/name for name in ['ModContext.java','Points.java']]
 files += [source/'upstream/HMG-API-1.0.1/core/runtime/java/hmgapi/hook/MethodHook.java',
           source/'mod/src/kr/ioniq5/usbcp/WelcomePower.java']
 subprocess.run([jdk/'javac','--release','8','-encoding','UTF-8','-d',root,*files],check=True)
 subprocess.run([jdk/'java','-cp',root,'kr.ioniq5.usbcp.WelcomePowerTest'],check=True)
 subprocess.run([jdk/'javac','--release','8','-encoding','UTF-8','-d',root,
   source/'setup/Setup.java',source/'upstream/HMG-API-1.0.1/core/runtime/java/hmgapi/engine/Json.java',
   source/'tests/WelcomeMapTest.java'],check=True)
 subprocess.run([jdk/'java','-cp',root,'kr.ioniq5.usbcp.setup.WelcomeMapTest',root/'maps',source/'maps'],check=True)
print('Welcome map/target pin/passive source checks PASS')
