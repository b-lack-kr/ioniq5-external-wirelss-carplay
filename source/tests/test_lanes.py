"""Run the restored v1.0.9 CpTurns behavior against iAP2/Cluster test stubs."""
from pathlib import Path
import subprocess
import tempfile

source = Path(__file__).resolve().parents[1]
from jvm_tools import java_bin
jdk = java_bin(source)
support = source / "tests/tbt-support"
with tempfile.TemporaryDirectory(prefix="neev-lane-test-") as scratch:
    java = [
        *sorted(support.rglob("*.java")),
        source / "mod/stubs/com/tcc/iap2/Iap2NotiMsg.java",
        source / "mod/src/hmgapi/mods/wireless/dipo/CpTurns.java",
        source / "mod/src/hmgapi/mods/wireless/dipo/CpAuxWriter.java",
        source / "mod/src/hmgapi/mods/wireless/dipo/CpLaneCallback.java",
        source / "mod/src/hmgapi/mods/wireless/dipo/CpLaneFrame.java",
        source / "mod/src/hmgapi/mods/wireless/dipo/CpLaneDecoder.java",
        source / "mod/src/hmgapi/mods/wireless/dipo/CpJunctions.java",
        source / "mod/src/hmgapi/mods/wireless/dipo/CpJunctionTable.java",
        source / "mod/src/hmgapi/mods/wireless/dipo/NativeJunction.java",
        source / "mod/src/hmgapi/mods/wireless/dipo/NativeSideStreet.java",
        source / "tests/lanes/LaneGuidanceTest.java",
        source / "tests/lanes/LaneLifecycleTest.java",
        source / "tests/lanes/AuxiliaryIsolationTest.java",
        source / "tests/lanes/LaneTelemetryTest.java",
    ]
    subprocess.run([jdk / "javac", "--release", "8", "-encoding", "UTF-8", "-d", scratch, *java], check=True)
    subprocess.run([jdk / "java", "-cp", scratch, "hmgapi.mods.wireless.dipo.LaneGuidanceTest"], check=True)
    subprocess.run([jdk / "java", "-cp", scratch, "hmgapi.mods.wireless.dipo.LaneLifecycleTest"], check=True)

    subprocess.run([jdk / "java", "-cp", scratch, "hmgapi.mods.wireless.dipo.AuxiliaryIsolationTest"], check=True)

    subprocess.run([jdk / "java", "-cp", scratch, "hmgapi.mods.wireless.dipo.LaneTelemetryTest"], check=True)
