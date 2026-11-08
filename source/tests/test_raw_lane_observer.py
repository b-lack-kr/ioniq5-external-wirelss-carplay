"""Bounded asynchronous observer: fragments, epochs, pressure and blocked worker isolation."""
from pathlib import Path
import subprocess, tempfile
from jvm_tools import java_bin
source=Path(__file__).resolve().parents[1]
jdk=java_bin(source)
with tempfile.TemporaryDirectory(prefix='neev-raw-lane-observer-') as scratch:
    files=[source/'mod/src/hmgapi/mods/wireless/dipo'/name for name in ('CpLaneDecoder.java','CpLaneObserver.java')]
    files.append(source/'tests/raw-lanes/CpLaneObserverTest.java')
    subprocess.run([jdk/'javac','--release','8','-encoding','UTF-8','-d',scratch,*files],check=True)
    subprocess.run([jdk/'java','-cp',scratch,'hmgapi.mods.wireless.dipo.CpLaneObserverTest'],check=True)
