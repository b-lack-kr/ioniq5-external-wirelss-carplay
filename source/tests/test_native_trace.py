"""Exercise numeric native-Navi recording with production helper and exact map signatures."""
from pathlib import Path
import json
import subprocess
import tempfile
from jvm_tools import java_bin

source = Path(__file__).resolve().parents[1]
maps = json.loads((source / "maps/framework.json").read_text())
for writer, types in {
    "update_tbt_info": "IIFFIIIII",
    "update_tbt_sub_info": "FIIIIIIFI",
    "update_tbt_sub_info_b": "FIIIIIIFII",
    "update_tbt_sub_info_2c": "IIIIIIIII",
    "update_remain_dis": "IIII",
}.items():
    assert maps["framework.navigation_cluster." + writer]["params"] == ["int" if t == "I" else "float" for t in types], writer
jdk = java_bin(source)
with tempfile.TemporaryDirectory(prefix="neev-native-trace-") as scratch:
    subprocess.run([jdk / "javac", "--release", "8", "-encoding", "UTF-8", "-d", scratch,
                    source / "mod/src/kr/ioniq5/usbcp/NativeGuidanceTrace.java",
                    source / "tests/native-trace-support/NativeGuidanceTraceTest.java"], check=True)
    subprocess.run([jdk / "java", "-cp", scratch, "kr.ioniq5.usbcp.NativeGuidanceTraceTest"], check=True)
