"""Bounded auxiliary IPC output, including blocked gate/sink concurrency faults."""
from pathlib import Path
import subprocess
import tempfile
from jvm_tools import java_bin

source = Path(__file__).resolve().parents[1]
jdk = java_bin(source)
with tempfile.TemporaryDirectory(prefix="neev-aux-writer-") as tmp:
    subprocess.run([jdk/'javac', '--release', '8', '-encoding', 'UTF-8', '-d', tmp,
                    source/'mod/src/hmgapi/mods/wireless/dipo/CpAuxWriter.java',
                    source/'tests/aux-writer/CpAuxWriterTest.java'], check=True)
    subprocess.run([jdk/'java', '-cp', tmp, 'hmgapi.mods.wireless.dipo.CpAuxWriterTest'], check=True)
