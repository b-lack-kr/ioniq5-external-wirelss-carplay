"""Startup settings parser + actual provider power/gate composition; no vehicle writes."""
from pathlib import Path
import subprocess
import tempfile
from jvm_tools import java_bin

source=Path(__file__).resolve().parents[1]
jdk=java_bin(source)
with tempfile.TemporaryDirectory(prefix='neev-external-dongle-') as temp:
    root=Path(temp).resolve();classes=root/'classes';classes.mkdir()
    files=[source/'mod/src/kr/ioniq5/usbcp'/f'{name}.java' for name in
           ['ExternalDongleSetting','ApState','Snapshot','PowerLatch','PowerControl']]
    files.append(source/'tests/ExternalDongleSettingTest.java')
    subprocess.run([jdk/'javac','--release','8','-encoding','UTF-8','-d',classes,*files],check=True)
    subprocess.run([jdk/'java','-cp',classes,'kr.ioniq5.usbcp.ExternalDongleSettingTest',root/'data'],check=True)
