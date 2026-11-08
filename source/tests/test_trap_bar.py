from pathlib import Path
import subprocess
import tempfile
from jvm_tools import java_bin

source = Path(__file__).resolve().parents[1]
jdk = java_bin(source)
with tempfile.TemporaryDirectory(prefix="neev-trap-bar-") as scratch:
    subprocess.run([jdk / "javac", "--release", "8", "-d", scratch,
                    source / "mod/src/kr/ioniq5/usbcp/TrapBarState.java",
                    source / "mod/src/kr/ioniq5/usbcp/CpTrapOverlay.java",
                    *sorted((source / "tests/trap-bar").rglob("*.java"))], check=True)
    subprocess.run([jdk / "java", "-cp", scratch, "kr.ioniq5.usbcp.TrapBarStateTest"], check=True)
    subprocess.run([jdk / "java", "-cp", scratch, "kr.ioniq5.usbcp.CpTrapOverlayTest"], check=True)
