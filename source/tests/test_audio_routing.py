"""Exercise actual audio hook wrappers with stock-order callback fixtures on the host.

This models Java/HMG dispatch and accepted MICOM requests, not physical gain.
"""
from pathlib import Path
import json
import re
import subprocess
import tempfile

from jvm_tools import java_bin

source = Path(__file__).resolve().parents[1]
jdk = java_bin(source)
support = source / "tests/audio-routing-support"
maps = {}
for name in ("dipo", "framework"):
    maps.update(json.loads((source / "maps" / (name + ".json")).read_text()))
manifest = json.loads((source / "mod/mod.json").read_text())
assert manifest["hooks"]["points"]["framework.micom_audio.start_audio"] == "change"
fixture = (support / "hmgapi/mods/wireless/dipo/AudioRoutingIntegrationTest.java").read_text()
for point, method in re.findall(r'method\("([^"]+)",[^,]+,"([^"]+)"', fixture):
    assert maps[point]["method"] == method, (point, "fixture differs from installed method map")
for point, field in re.findall(r'Points.fields.put\("([^"]+)",[^;]+getField\("([^"]+)"\)', fixture):
    assert maps[point]["field"] == field, (point, "fixture differs from installed field map")
with tempfile.TemporaryDirectory(prefix="neev-audio-routing-") as tmp:
    java = [
        *sorted(support.rglob("*.java")),
        source / "upstream/HMG-API-1.0.1/core/runtime/java/hmgapi/hook/MethodHook.java",
        source / "mod/src/hmgapi/mods/wireless/dipo/CarPlayAudioRouting.java",
    ]
    subprocess.run([jdk / "javac", "--release", "8", "-encoding", "UTF-8", "-d", tmp, *java], check=True)
    subprocess.run([jdk / "java", "-cp", tmp, "hmgapi.mods.wireless.dipo.AudioRoutingIntegrationTest"], check=True)
