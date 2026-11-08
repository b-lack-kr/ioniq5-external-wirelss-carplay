"""Compile the production reflection writer without an HMG-API v2/cluster stub."""
from pathlib import Path
import json
import subprocess
import tempfile
from jvm_tools import java_bin

source = Path(__file__).resolve().parents[1]
maps = json.loads((source / 'maps/framework.json').read_text())
assert maps['framework.navigation_cluster.update_clu_data_new_14'] == {
    'method': 'updateCluDataNew_14', 'params': ['int'] * 5, 'ret': 'void', 'static': False,
}
assert maps['framework.navigation_cluster.new'] == {'ctor': ['android.content.Context']}
jdk = java_bin(source)
stubs = sorted((source / 'tests/native-pattern-writer').rglob('*.java'))
with tempfile.TemporaryDirectory(prefix='neev-native-pattern-writer-') as temp:
    subprocess.run([jdk / 'javac', '--release', '8', '-encoding', 'UTF-8', '-d', temp,
                    source / 'mod/src/hmgapi/mods/wireless/dipo/NativePatternWriter.java',
                    *stubs], check=True)
    subprocess.run([jdk / 'java', '-cp', temp,
                    'hmgapi.mods.wireless.dipo.NativePatternWriterTest'], check=True)
