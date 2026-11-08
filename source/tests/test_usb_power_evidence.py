"""Run the read-only power probe against real identity selection on temporary sysfs."""
from pathlib import Path
import subprocess
import tempfile
from jvm_tools import java_bin

source = Path(__file__).resolve().parents[1]
jdk = java_bin(source)
with tempfile.TemporaryDirectory(prefix='neev-usb-power-evidence-') as temp:
    root = Path(temp).resolve()
    classes = root / 'classes'
    classes.mkdir()
    java = source / 'mod/src/kr/ioniq5/usbcp'
    files = [java / (name + '.java') for name in ('UsbPowerEvidence', 'UsbIds', 'SafeInterface')]
    files.append(source / 'tests/UsbPowerEvidenceTest.java')
    subprocess.run([jdk / 'javac', '--release', '8', '-encoding', 'UTF-8', '-d', classes, *files], check=True)
    subprocess.run([jdk / 'java', '-cp', classes, 'kr.ioniq5.usbcp.UsbPowerEvidenceTest', root / 'fixtures'], check=True, timeout=20)
