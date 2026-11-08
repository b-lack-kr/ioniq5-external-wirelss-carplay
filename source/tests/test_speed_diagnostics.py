"""Verify numeric-only speed extraction, lifecycle resets, units and bounded counters."""
from pathlib import Path
import subprocess
import tempfile
from jvm_tools import java_bin

source=Path(__file__).resolve().parents[1]
jdk=java_bin(source)
production=source/'mod/src/hmgapi/mods/wireless/dipo'
with tempfile.TemporaryDirectory(prefix='neev-speed-diagnostics-') as tmp:
    classes=Path(tmp)/'classes'
    classes.mkdir()
    subprocess.run([jdk/'javac','--release','8','-encoding','UTF-8','-d',classes,
                    production/'LocationTraceWindow.java',production/'SpeedPulseStats.java',
                    source/'tests/LocationTraceWindowTest.java',source/'tests/SpeedDiagnosticsTest.java'],check=True)
    for name in ['LocationTraceWindowTest','SpeedDiagnosticsTest']:
        subprocess.run([jdk/'java','-cp',classes,'hmgapi.mods.wireless.dipo.'+name],check=True)
