"""Actual approach sender with an isolated controllable Android queue and clock."""
from pathlib import Path
import subprocess
import tempfile
from jvm_tools import java_bin

source = Path(__file__).resolve().parents[1]
jdk = java_bin(source)
support = sorted((source / 'tests/approach-sender-support').rglob('*.java'))
with tempfile.TemporaryDirectory(prefix='neev-approach-sender-') as temp:
    subprocess.run([jdk / 'javac', '--release', '8', '-encoding', 'UTF-8', '-d', temp,
                    source / 'mod/src/hmgapi/mods/wireless/dipo/ApproachSender.java',
                    *support], check=True)
    subprocess.run([jdk / 'java', '-cp', temp,
                    'hmgapi.mods.wireless.dipo.ApproachSenderTest'], check=True)
