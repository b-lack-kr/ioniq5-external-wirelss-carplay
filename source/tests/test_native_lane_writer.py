"""Exercise the production F1 reflection adapter, including real retry semantics."""
from pathlib import Path
import json
import subprocess
import tempfile
from jvm_tools import java_bin

source = Path(__file__).resolve().parents[1]
jdk = java_bin(source)
maps = json.loads((source / 'maps/framework.json').read_text())
assert maps['framework.navigation_cluster.update_lane_info'] == {
    'method': 'updateLaneInfo', 'params': ['byte[]'], 'ret': 'void', 'static': False,
}
assert maps['framework.navigation_cluster.new'] == {'ctor': ['android.content.Context']}
with tempfile.TemporaryDirectory(prefix='neev-native-lane-writer-') as temp:
    subprocess.run([jdk / 'javac', '--release', '8', '-encoding', 'UTF-8', '-d', temp,
                    source / 'mod/src/hmgapi/mods/wireless/dipo/NativeLaneWriter.java',
                    *sorted((source / 'tests/native-lane-writer').rglob('*.java'))], check=True)
    subprocess.run([jdk / 'java', '-cp', temp,
                    'hmgapi.mods.wireless.dipo.NativeLaneWriterTest'], check=True)
