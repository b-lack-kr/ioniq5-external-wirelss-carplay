"""Run the actual narrow UI hook against host Android/HMG dispatch doubles."""
from pathlib import Path
import json
import subprocess
import tempfile
from jvm_tools import java_bin

source=Path(__file__).resolve().parents[1]
jdk=java_bin(source)
support=source/"tests/settings-routing-support"
for app,cls,method in [("phoneprojection","com.daudio.app.phoneprojection.wireless.a.f","p"),("btsettings","com.android.settings2.bluetooth.iP","aQ")]:
    maps=json.loads((source/"maps"/(app+".json")).read_text())
    point=app+".usbcp_settings"
    assert maps[point]=={"class":cls}
    assert maps[point+".open"]=={"method":method,"params":["android.content.Context"],"ret":"void","static":True}
with tempfile.TemporaryDirectory(prefix="neev-settings-routing-") as tmp:
    subprocess.run([jdk/"javac","--release","8","-d",tmp,*sorted(support.rglob("*.java")),source/"upstream/HMG-API-1.0.1/core/runtime/java/hmgapi/hook/MethodHook.java",source/"mod/src/kr/ioniq5/usbcp/ProjectionSettings.java"],check=True)
    subprocess.run([jdk/"java","-cp",tmp,"kr.ioniq5.usbcp.ProjectionSettingsTest"],check=True)
