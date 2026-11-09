"""Assemble the exact-firmware standalone USB payload. Inputs are specified by the public build."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

HERE = Path(__file__).resolve().parent
DEFAULT_UUID = 'bd5f651c-38f9-8ef4-827c-34ad706c2b5c'
TARGET_PIN = '6026cfd445c4357de3c7162771c40e652609b9c3d85849c8146e72f346a83346'
MAP_NAMES = ('car.json', 'framework.json', 'dipo.json', 'iap2.json', 'btsettings.json', 'phoneprojection.json', 'gpm.json', 'welcome.json')


def md5(p):
    return hashlib.md5(p.read_bytes()).hexdigest()


def build_payload(mod, setup_jar, maps, target, vendor_dir, out, uuid=DEFAULT_UUID):
    signed = json.loads((mod/'META-HMGAPI/manifest.json').read_text())
    meta = json.loads((mod/'mod.json').read_text())
    assert re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', uuid)
    assert signed['uuid'] == uuid and meta['id'] == signed['id'] == 'neev-usb-carplay'
    assert meta['version'] == signed['version'] == '1.0.3'
    assert set(signed['files']) == {f.relative_to(mod).as_posix() for f in mod.rglob('*')
                                    if f.is_file() and 'META-HMGAPI' not in f.parts}
    assert all(not f.is_symlink() for f in mod.rglob('*'))
    for name, digest in signed['files'].items():
        assert hashlib.sha256((mod/name).read_bytes()).hexdigest() == digest
    assert hashlib.sha256(target.read_bytes()).hexdigest() == TARGET_PIN
    with zipfile.ZipFile(setup_jar) as z:
        dex = z.read('classes.dex')
        assert dex.startswith(b'dex\n035\0') and b'Lkr/ioniq5/usbcp/setup/Setup;' in dex
        assert b'Hyundai_%06d' in dex and b'ap-identity' in dex, 'a public Setup.jar is required' 
    if out.exists() and list(out.iterdir()):
        raise ValueError('output must be a new or empty directory')
    pack = out/'NEEV_CP'; payload = pack/'payload'
    (payload/'maps').mkdir(parents=True)
    # Ship only the components the merge helper actually installs.
    for name in MAP_NAMES:
        shutil.copyfile(maps/name, payload/'maps'/name)
    shutil.copytree(mod, payload/'mod', copy_function=shutil.copyfile)
    shutil.copyfile(setup_jar, payload/'setup.jar')
    shutil.copyfile(target, payload/'TARGET.md5')
    for name in ('import-hkg.sh','vendor-import.jar'):
        shutil.copyfile(vendor_dir/name, pack/name)
    public_mod_md5=md5(mod/'META-HMGAPI/manifest.json')
    script=(HERE/'templates/operate.sh').read_text()
    script=script.replace('UUID='+DEFAULT_UUID+'\n','UUID='+uuid+'\n').replace('@PUBLIC_MOD_MD5@',public_mod_md5)
    (pack/'operate.sh').write_text(script)
    entries = [f for f in sorted(pack.rglob('*')) if f.is_file()]
    manifest=''.join(f'{md5(f)}  {f.relative_to(pack).as_posix()}\n' for f in entries)
    (pack/'MANIFEST.md5').write_text(manifest)
    wrapper=(HERE/'templates/NEMESIS.sh').read_text().replace('@HOOK_DIAGNOSTICS@',(HERE/'templates/hook-diagnostics.sh').read_text()).replace('@PACK_MD5@',md5(pack/'MANIFEST.md5')).replace('@OWNER@',uuid).replace('@PUBLIC_MOD_MD5@',public_mod_md5)
    (out/'NEMESIS.sh').write_text(wrapper)
    (out/'NEMESIS.data').write_text('[nemesis]\nversion=1.0\ntype=execute\npath=NEMESIS.sh\n')
    for f in (pack/'operate.sh',out/'NEMESIS.sh',pack/'import-hkg.sh'):
        unresolved=re.search(r'@[A-Z][A-Z0-9_]*@',f.read_text())
        if unresolved:
            raise ValueError(f'unresolved template token {unresolved.group()} in {f}')
        subprocess.run(['/bin/sh','-n',str(f)],check=True)
    return pack


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('mod','setup-jar','maps','target','vendor-dir','out'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--uuid',default=DEFAULT_UUID)
    opt=parser.parse_args()
    print(build_payload(**vars(opt)))
