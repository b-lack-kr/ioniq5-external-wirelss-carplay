"""Run actual per-phone UI hooks and validate exact map declarations."""
from pathlib import Path
import json
import subprocess
import tempfile
from jvm_tools import java_bin

source=Path(__file__).resolve().parents[1]
maps=json.loads((source/'maps/btsettings.json').read_text())
assert maps['btsettings.usbcp_rows']=={'class':'com.android.settings2.bluetooth.cy'}
assert maps['btsettings.usbcp_rows.bind']=={'method':'getView','params':['int','android.view.View','android.view.ViewGroup'],'ret':'android.view.View','static':False}
assert maps['btsettings.usbcp_rows.owner']['field']=='ja'
assert maps['btsettings.usbcp_devices.restricted']['method']=='fl'
assert maps['btsettings.usbcp_manager.wireless']['method']=='cp'
car=json.loads((source/'maps/car.json').read_text())
assert '/system/framework/com.broadcom.bt.jar' in car['app_files']['btsettings']['files']
assert '/system/framework/com.broadcom.bt.jar' in car['files']
# Validate new points against the real collected NEEV smali when available locally.
collected=next((root/'versions/v1.0.4/research/settings/btsettings-smali/com/android/settings2/bluetooth'
                for root in source.parents
                if (root/'versions/v1.0.4/research/settings/btsettings-smali/com/android/settings2/bluetooth').exists()),None)
if collected is not None:
    adapter=(collected/'cy.1.smali').read_text()
    activity=(collected/'DeviceConnectionSettingActivity.smali').read_text()
    manager=(collected/'aW.smali').read_text()
    assert '.class public final Lcom/android/settings2/bluetooth/cy;' in adapter
    assert '.method public getView(ILandroid/view/View;Landroid/view/ViewGroup;)Landroid/view/View;' in adapter
    assert '.field final synthetic ja:Lcom/android/settings2/bluetooth/DeviceConnectionSettingActivity;' in adapter
    for name,typ in [('mL','Z'),('f','Lcom/android/settings2/bluetooth/aW;'),('mN','Landroid/bluetooth/BluetoothDevice;'),('mR','Landroid/bluetooth/BluetoothDevice;')]:
        assert '.field private '+name+':'+typ in activity
    assert '.method public fl()Z' in activity and '.method protected onDestroy()V' in activity
    assert '.method public aO()Lcom/broadcom/bt/hfdevice/hfbtcm/HfDeviceBTCMManager;' in manager
    assert '.method public cp()Z' in manager
with tempfile.TemporaryDirectory(prefix='neev-per-phone-settings-') as tmp:
    support=list((source/'tests/row-settings-support').rglob('*.java'))
    real=[source/'mod/src/kr/ioniq5/usbcp'/name for name in ['BtSettingsRows.java','RowConnectState.java','OemCarplayService.java']]
    real+=[source/'upstream/HMG-API-1.0.1/core/runtime/java/hmgapi/hook/MethodHook.java']
    jdk=java_bin(source)
    subprocess.run([jdk/'javac','--release','8','-encoding','UTF-8','-d',tmp,*support,*real],check=True)
    subprocess.run([jdk/'java','-cp',tmp,'kr.ioniq5.usbcp.BtSettingsRowsTest'],check=True)
