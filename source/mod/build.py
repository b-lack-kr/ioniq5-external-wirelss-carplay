"""Build the NEEV public CarPlay HMG mod. See BUILD.md for the required toolchain."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
from zipfile import ZipFile

src = Path(__file__).resolve().parent
parser = argparse.ArgumentParser()
for arg in ('android-jar','d8','hmg-source','java-home','out'):
    parser.add_argument('--' + arg, type=Path, required=True)
parser.add_argument('--sign-key', type=Path)
args = parser.parse_args()
dest = args.out.resolve()
if dest == src or dest in src.parents or src in dest.parents:
    raise SystemExit('Choose a separate build directory, outside the source tree.')
dest.mkdir(parents=True, exist_ok=True)
j = args.java_home / 'bin'
hmg = args.hmg_source / 'core/runtime'
android = args.android_jar
payload = dest/'payload'


def compile_java(sources, name, classpath):
    folder = dest/name
    folder.mkdir(exist_ok=True)
    subprocess.run([j/'javac','--release','8','-encoding','UTF-8','-cp',classpath,
                    '-d',folder,*sorted(sources)],check=True)
    return folder


def dex(name, classes):
    subprocess.run([j/'java','-cp',args.d8,'com.android.tools.r8.D8','--release',
                    '--min-api','19','--lib',android,'--classpath',sdk,'--classpath',stub,
                    '--output',payload/name,*sorted(classes)],check=True)
    with ZipFile(payload/name) as z:
        assert z.namelist() == ['classes.dex'] and z.read('classes.dex')[:8] == b'dex\n035\0'


# Do not overwrite unrelated files in a user-selected output directory.
marker = dest/'.neev-carplay-public-build'
if any(dest.iterdir()) and not marker.is_file():
    raise SystemExit('Output directory is not empty; choose an empty build directory.')
marker.touch()
for name in ('hmg-stubs','hmg-api','stubs','classes','payload'):
    folder = dest/name
    if folder.exists(): shutil.rmtree(folder)
pstub = compile_java((hmg/'stubs').rglob('*.java'),'hmg-stubs',str(android))
sdk = compile_java((hmg/'java').rglob('*.java'),'hmg-api',f'{android}:{pstub}')
stub = compile_java((src/'stubs').rglob('*.java'),'stubs',str(android))
app = compile_java((src/'src').rglob('*.java'),'classes',f'{android}:{sdk}:{stub}')
payload.mkdir()
for name in ('mod.json','settings.json','patches.json','LICENSE','NOTICE'):
    shutil.copyfile(src/name,payload/name)
for folder in ('recipes','ap'):
    shutil.copytree(src/folder,payload/folder)
for name in ('ap/service.sh','ap/runtime.sh'):
    subprocess.run(['/bin/sh','-n',payload/name],check=True)
manifest = json.loads((payload/'mod.json').read_text())
assert manifest['id']=='neev-usb-carplay' and manifest['version']=='1.0.1'
wanted = {s for seq in manifest['hooks']['native'].values() for s in seq}
assert wanted == {str(p.relative_to(payload)) for p in (payload/'recipes').iterdir()}
# Only USB devices that the local identity validator accepts trigger driver loading.
ids = (src/'src/kr/ioniq5/usbcp/UsbIds.java').read_text()
rows = {}
for prefix, driver in [('AC','88x2bu'),('AX','8851bu')]:
    values = re.search(prefix + r'=ids\("([0-9a-f: ]+)"\)', ids).group(1).split()
    for value in values:
        assert value not in rows and re.fullmatch(r'[0-9a-f]{4}:[0-9a-f]{4}',value)
        rows[value] = driver
assert len(rows) == 30
(payload/'ap/driver-ids').write_text(''.join(f'{k} {v}\n' for k,v in sorted(rows.items())))
classes = list(app.rglob('*.class'))
dex('hooks.jar', classes)
provider = [p for p in classes if p.relative_to(app).as_posix().startswith('kr/ioniq5/usbcp/')
            and not p.name.startswith('UsbCarplay')]
dex('provider.jar',provider)
if args.sign_key:
    cmd = [j/'java','-cp',sdk,'hmgapi.engine.Signing']
    subprocess.run([*cmd,'sign',payload,args.sign_key],check=True)
    subprocess.run([*cmd,'verify',payload],check=True)
hashes = {p.relative_to(payload).as_posix():hashlib.sha256(p.read_bytes()).hexdigest()
          for p in sorted(payload.rglob('*')) if p.is_file()}
(dest/'payload-sha256.json').write_text(json.dumps(hashes,indent=2)+'\n')
print(f'Built CarPlay {manifest["version"]}: {len(hashes)} files at {payload}')
