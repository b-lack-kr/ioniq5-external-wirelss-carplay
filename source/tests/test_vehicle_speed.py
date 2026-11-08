"""Exercise the actual bounded vehicle-speed sender; no vehicle values are invented."""
from pathlib import Path
import subprocess
import tempfile
from jvm_tools import java_bin

source=Path(__file__).resolve().parents[1]
jdk=java_bin(source)
production=source/'mod/src/hmgapi/mods/wireless/dipo'
with tempfile.TemporaryDirectory(prefix='neev-speed-pulse-') as tmp:
    classes=Path(tmp)/'classes'
    classes.mkdir()
    subprocess.run([jdk/'javac','--release','8','-encoding','UTF-8','-d',classes,
                    production/'VehicleSpeedPulse.java',
                    *sorted((source/'tests/speed-pulse-support').glob('*.java'))],check=True)
    subprocess.run([jdk/'java','-cp',classes,'hmgapi.mods.wireless.dipo.VehicleSpeedPulseTest'],check=True)
    subprocess.run([jdk/'java','-cp',classes,'hmgapi.mods.wireless.dipo.VehicleSpeedPulseConcurrencyTest'],check=True)
    hook_support=source/'tests/speed-hooks-support'
    subprocess.run([jdk/'javac','--release','8','-encoding','UTF-8','-d',classes,
                    production/'VehicleSpeedPulse.java',production/'CarPlaySpeedSession.java',production/'SpeedPulseStats.java',production/'RmcSpeedRewrite.java',production/'RmcSpeedStats.java',production/'RmcVehicleSpeedSource.java',production/'CarPlayVehicleSpeed.java',
                    *sorted(hook_support.rglob('*.java'))],check=True)
    subprocess.run([jdk/'java','-cp',classes,'hmgapi.mods.wireless.dipo.CarPlayVehicleSpeedHookTest'],check=True)

    subprocess.run([jdk/'java','-cp',classes,'hmgapi.mods.wireless.dipo.HandoverReproTest'],check=True)
    subprocess.run([jdk/'java','-cp',classes,'hmgapi.mods.wireless.dipo.CarPlaySpeedLifecycleTest'],check=True)

    subprocess.run([jdk/"java","-cp",classes,"hmgapi.mods.wireless.dipo.RmcVehicleSpeedTest"],check=True)
