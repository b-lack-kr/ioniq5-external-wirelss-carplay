"""Run the production pure-Java native junction selector against evidence cases."""
from pathlib import Path
import subprocess
import tempfile
from jvm_tools import java_bin

source = Path(__file__).resolve().parents[1]
jdk = java_bin(source)
with tempfile.TemporaryDirectory(prefix='neev-native-junction-') as temp:
    subprocess.run([jdk / 'javac', '--release', '8', '-encoding', 'UTF-8', '-d', temp,
                    source / 'mod/src/hmgapi/mods/wireless/dipo/NativeJunction.java',
                    source / 'tests/native-junction/NativeJunctionTest.java'], check=True)
    subprocess.run([jdk / 'java', '-cp', temp,
                    'hmgapi.mods.wireless.dipo.NativeJunctionTest'], check=True)
