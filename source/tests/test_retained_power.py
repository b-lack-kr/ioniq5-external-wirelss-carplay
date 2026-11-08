"""OEM power reader ABI compile and event ordering tests; no vehicle/network writes."""
from pathlib import Path
import os
import subprocess
import tempfile
from jvm_tools import java_bin
source = Path(__file__).resolve().parents[1]
jdk = java_bin(source)
android = Path(os.environ['ANDROID_JAR']) if os.environ.get('ANDROID_JAR') else next(
    p/'.tools/android34/android.jar' for p in source.parents if (p/'.tools/android34/android.jar').is_file())
with tempfile.TemporaryDirectory(prefix='neev-retained-power-') as t:
    root = Path(t); classes = root/'classes'; classes.mkdir()
    stub = root/'Provider.java'
    stub.write_text('package kr.ioniq5.usbcp; public final class Provider { public static final String PRODUCT="test"; public static String property(String k){return "";} }')
    files = [stub, *sorted((source/'mod/stubs/com/hkmc').rglob('*Listener.java')),
             source/'mod/src/kr/ioniq5/usbcp/HeadUnitPower.java',
             source/'mod/src/kr/ioniq5/usbcp/RetainedPower.java',
             source/'mod/src/kr/ioniq5/usbcp/PowerInputTrace.java',
             source/'mod/src/kr/ioniq5/usbcp/PowerTraceFile.java',
             source/'mod/tests/HeadUnitPowerTest.java', source/'mod/tests/RetainedPowerTest.java',
             source/'mod/tests/PowerInputTraceTest.java', source/'mod/tests/PowerTraceFileTest.java']
    subprocess.run([jdk/'javac', '--release', '8', '-encoding', 'UTF-8', '-cp', android, '-d', classes, *files], check=True)
    for name in ['RetainedPowerTest', 'HeadUnitPowerTest','PowerInputTraceTest','PowerTraceFileTest']:
        subprocess.run([jdk/'java', '-cp', str(classes)+os.pathsep+str(android), 'kr.ioniq5.usbcp.'+name], check=True)
