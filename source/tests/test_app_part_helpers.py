"""Exercise GPM-local helpers and ensure duplicated app-part algorithms stay identical."""
from pathlib import Path
import subprocess
import tempfile
from jvm_tools import java_bin

source = Path(__file__).resolve().parents[1]
parts = source / 'mod/src/hmgapi/mods/wireless'
jdk = java_bin(source)
for name in ['ApproachSender', 'LaneDistanceWriter', 'CpLaneFrame']:
    original = (parts / 'dipo' / (name + '.java')).read_text()
    adapted = (parts / 'gpm' / (name + '.java')).read_text().split('\n', 2)[2]
    adapted = adapted.replace('package hmgapi.mods.wireless.gpm;', 'package hmgapi.mods.wireless.dipo;')
    adapted = adapted.replace('implements AaTurns.ApproachSink', 'implements CpTurns.ApproachSink')
    assert adapted == original, name + ' helper drift: port and test both isolated copies together'
for java in (parts / 'gpm').glob('*.java'):
    assert 'hmgapi.mods.wireless.dipo.' not in java.read_text(), java

cases = [
    ('ApproachSender', list((source / 'tests/approach-sender-support').rglob('*.java'))),
    ('LaneDistanceWriter', list((source / 'tests/lane-distance-writer').rglob('*.java'))),
    ('CpLaneFrame', [source / 'tests/lanes/CpLaneFrameTest.java']),
]
for name, support in cases:
    with tempfile.TemporaryDirectory(prefix='neev-gpm-helper-') as temp:
        root = Path(temp)
        files = [parts / 'gpm' / (name + '.java')]
        for i, path in enumerate(support):
            text = path.read_text().replace('package hmgapi.mods.wireless.dipo;', 'package hmgapi.mods.wireless.gpm;')
            filename = path.name
            if filename == 'CpTurns.java':
                text = text.replace('class CpTurns', 'class AaTurns')
                filename = 'AaTurns.java'
            folder = root / ('support' + str(i))
            folder.mkdir()
            target = folder / filename
            target.write_text(text)
            files.append(target)
        subprocess.run([jdk / 'javac', '--release', '8', '-encoding', 'UTF-8', '-d', root, *files], check=True)
        subprocess.run([jdk / 'java', '-cp', root, 'hmgapi.mods.wireless.gpm.' + name + 'Test'], check=True)
print('GPM helper parity PASS; app-part-local sender/distance writer/lane encoder exercised')
