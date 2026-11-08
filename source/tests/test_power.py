"""Actual OEM hook callbacks, fixed wire records and root power consumer on the host."""
from pathlib import Path
import json
import os
import subprocess
import tempfile
from jvm_tools import java_bin

source=Path(__file__).resolve().parents[1]
jdk=java_bin(source)
android=Path(os.environ['ANDROID_JAR']) if os.environ.get('ANDROID_JAR') else next(
    root/'.tools/android34/android.jar' for root in source.parents if (root/'.tools/android34/android.jar').is_file())
manifest=json.loads((source/'mod/mod.json').read_text())
maps=json.loads((source/'maps/dipo.json').read_text())
assert maps['dipo.mode_power_listener']=={'class':'com.daudio.app.dipo.service.bb'}
for point,name in [('power_off','onPowerOff'),('power_on','onPowerOn')]:
    key='dipo.mode_power_listener.'+point
    assert manifest['hooks']['points'][key]=='observe'
    assert maps[key]=={'method':name,'params':[],'ret':'void','static':False}
with tempfile.TemporaryDirectory(prefix='neev-power-') as temp:
    root=Path(temp).resolve();classes=root/'classes';classes.mkdir()
    stub=root/'Provider.java'
    stub.write_text('package kr.ioniq5.usbcp; public final class Provider {public static final String SOCKET="test-only";}')
    files=[*sorted((source/'tests/audio-routing-support/hmgapi/hook').glob('*.java')),
        source/'upstream/HMG-API-1.0.1/core/runtime/java/hmgapi/hook/MethodHook.java',stub,
        source/'tests/power-routing-support/PowerLifecycleTest.java']
    files += [source/'mod/src/kr/ioniq5/usbcp'/f'{name}.java' for name in
        ['OemPower','PowerQueue','PowerLatch','PowerControl','ApState','Snapshot']]
    subprocess.run([jdk/'javac','--release','8','-encoding','UTF-8','-cp',android,'-d',classes,*files],check=True)
    subprocess.run([jdk/'java','-cp',str(classes)+os.pathsep+str(android),'kr.ioniq5.usbcp.PowerLifecycleTest',root/'data'],check=True)
