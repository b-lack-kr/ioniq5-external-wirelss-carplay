"""Exercise the actual USB History collector and its bounded diagnostic reconstruction."""
from pathlib import Path
import subprocess
import tempfile

source = Path(__file__).resolve().parents[1]
from jvm_tools import java_bin
jdk = java_bin(source)
with tempfile.TemporaryDirectory(prefix="neev-history-test-") as tmp:
    classes = Path(tmp) / "classes"
    classes.mkdir()
    fixtures = Path(tmp) / "fixtures"
    fixtures.mkdir()
    java = [
        source / "setup/Setup.java",
        source / "upstream/HMG-API-1.0.1/core/runtime/java/hmgapi/engine/Json.java",
        source / "tests/HistoryTest.java",
        source / "tests/FirmwareEvidenceTest.java",
    ]
    subprocess.run([jdk / "javac", "--release", "8", "-encoding", "UTF-8", "-d", classes, *java], check=True)
    subprocess.run([jdk / "java", "-cp", classes, "kr.ioniq5.usbcp.setup.HistoryTest", fixtures], check=True)
    firmware = Path(tmp) / "firmware"
    firmware.mkdir()
    subprocess.run([jdk / "java", "-cp", classes, "kr.ioniq5.usbcp.setup.FirmwareEvidenceTest", firmware], check=True)
