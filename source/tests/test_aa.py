"""Host AA renderer and actual hook checks, plus pinned on-car declaration provenance."""
from pathlib import Path
import json,subprocess,tempfile
from jvm_tools import java_bin
source=Path(__file__).resolve().parents[1]
jdk=java_bin(source)
with tempfile.TemporaryDirectory(prefix='neev-aa-') as tmp:
    classes=Path(tmp)/'classes';classes.mkdir()
    files=[*sorted((source/'tests/aa-turns-support').rglob('*.java')),
        *sorted((source/'tests/aa-hooks-support').rglob('*.java')),
        source/'upstream/HMG-API-1.0.1/core/runtime/java/hmgapi/hook/MethodHook.java',
        source/'upstream/HMG-API-1.0.1/core/runtime/java/hmgapi/hook/AppPartLoader.java',
        *[p for p in sorted((source/'mod/src/hmgapi/mods/wireless/gpm').glob('*.java')) if p.name != 'ApproachSender.java']]
    subprocess.run([jdk/'javac','--release','8','-encoding','UTF-8','-d',classes,*files],check=True)
    r=subprocess.run([jdk/'java','-cp',classes,'hmgapi.hook.AppPartBoundaryTest'],capture_output=True,text=True)
    assert r.returncode == 0, r.stdout + r.stderr
    lines=r.stdout.splitlines();assert not any(x.startswith('FAIL') for x in lines),r.stdout
    print(r.stdout.strip())
