"""Verify explicit restart policy without a head unit or daemon."""
from pathlib import Path
import os,subprocess,tempfile
from jvm_tools import java_bin
source=Path(__file__).resolve().parents[1]
jdk=java_bin(source)
android = Path(os.environ["ANDROID_JAR"]) if os.environ.get("ANDROID_JAR") else next(
    p/".tools/android34/android.jar" for p in source.parents if (p/".tools/android34/android.jar").is_file())
with tempfile.TemporaryDirectory() as temp:
    subprocess.run([jdk/'javac','--release','8','-encoding','UTF-8','-cp',android,'-d',temp,
        source/'mod/src/kr/ioniq5/usbcp/ApServiceRecovery.java',
        source/'mod/tests/ApServiceRecoveryTest.java',
        source/'mod/src/kr/ioniq5/usbcp/HmgApSupervisor.java',
        source/'mod/tests/HmgApSupervisorTest.java',
        source/'mod/src/kr/ioniq5/usbcp/ManualApRecovery.java',
        source/'mod/src/kr/ioniq5/usbcp/PowerLatch.java',
        source/'mod/tests/ManualApRecoveryTest.java'],check=True)
    subprocess.run([jdk/'java','-cp',temp,'kr.ioniq5.usbcp.ApServiceRecoveryTest'],check=True)
    subprocess.run([jdk/'java','-cp',temp+os.pathsep+str(android),'kr.ioniq5.usbcp.HmgApSupervisorTest'],check=True)
    subprocess.run([jdk/'java','-cp',temp+os.pathsep+str(android),'kr.ioniq5.usbcp.ManualApRecoveryTest',str(Path(temp).resolve()/'recovery')],check=True)
