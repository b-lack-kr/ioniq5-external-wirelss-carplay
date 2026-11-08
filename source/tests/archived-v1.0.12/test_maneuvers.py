"""Run the draft's actual CpTurns against existing HMG-API iAP2/Cluster test stubs."""
from pathlib import Path
import subprocess
import tempfile

source = Path(__file__).resolve().parents[1]
from jvm_tools import java_bin
jdk = java_bin(source)
support = source / "tests/tbt-support"
with tempfile.TemporaryDirectory(prefix="neev-maneuver-test-") as scratch:
    java = [
        *sorted(support.rglob("*.java")),
        source / "mod/stubs/com/tcc/iap2/Iap2NotiMsg.java",
        source / "mod/src/hmgapi/mods/wireless/dipo/CpTurns.java",
        source / "mod/src/hmgapi/mods/wireless/dipo/CpLaneFrame.java",
        source / "mod/src/hmgapi/mods/wireless/dipo/LaneDistanceWriter.java",
        source / "mod/src/hmgapi/mods/wireless/dipo/NativeJunction.java",
        source / "mod/src/hmgapi/mods/wireless/dipo/CpLaneDecoder.java",
        source / "mod/src/hmgapi/mods/wireless/dipo/CpJunctions.java",
        source / "mod/src/hmgapi/mods/wireless/dipo/CpJunctionTable.java",
        source / "mod/src/hmgapi/mods/wireless/dipo/NativeSideStreet.java",
        source / "tests/tbt-frames/ClusterFramesTest.java",
        source / "tests/tbt-frames/StandardTurnsTest.java",
        source / "tests/tbt-frames/Guidance147Test.java",
        source / "tests/tbt-frames/NativeTransitionTest.java",
        source / "tests/tbt-frames/PhaseTraceTest.java",
        source / "tests/tbt-frames/JunctionFramesTest.java",
        source / "tests/tbt-frames/CpJunctionsTest.java",
        source / "tests/tbt-frames/CpLaneDecoderTest.java",
    ]
    subprocess.run([jdk / "javac", "--release", "8", "-encoding", "UTF-8", "-d", scratch, *java], check=True)
    subprocess.run([jdk / "java", "-cp", scratch, "hmgapi.mods.wireless.dipo.ClusterFramesTest"], check=True)
    subprocess.run([jdk / "java", "-cp", scratch, "hmgapi.mods.wireless.dipo.StandardTurnsTest"], check=True)
    subprocess.run([jdk / "java", "-cp", scratch, "hmgapi.mods.wireless.dipo.NativeTransitionTest"], check=True)
    subprocess.run([jdk / "java", "-cp", scratch, "hmgapi.mods.wireless.dipo.PhaseTraceTest"], check=True)
    subprocess.run([jdk / "java", "-cp", scratch, "hmgapi.mods.wireless.dipo.JunctionFramesTest"], check=True)
    subprocess.run([jdk / "java", "-cp", scratch, "hmgapi.mods.wireless.dipo.CpJunctionsTest"], check=True)
    subprocess.run([jdk / "java", "-cp", scratch, "hmgapi.mods.wireless.dipo.CpLaneDecoderTest"], check=True)

    subprocess.run([jdk / "java", "-cp", scratch, "hmgapi.mods.wireless.dipo.Guidance147Test"], check=True)
