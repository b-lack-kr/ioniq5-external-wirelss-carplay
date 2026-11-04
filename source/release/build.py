"""Assemble a copyable USB release from the signed, standalone CarPlay and Mute Pause kits."""
import argparse
import hashlib
from io import BytesIO
import json
from pathlib import Path
import re
import subprocess
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

here=Path(__file__).resolve().parent
mute_id='neev-carplay-mute-pause'
mute_name='packages/hmgapi.'+mute_id+'.zip'

def md5(data):
    return hashlib.md5(data).hexdigest()

def sha(data):
    return hashlib.sha256(data).hexdigest()

def readzip(src):
    with ZipFile(src) as z:
        names=[i.filename for i in z.infolist() if not i.is_dir()]
        if len(set(names))!=len(names) or any(n.startswith('/') or '..' in Path(n).parts for n in names):
            raise ValueError('Invalid archive entries')
        return {n:z.read(n) for n in names}

def verify_contents(files, mod_id, owner):
    signed=json.loads(files['META-HMGAPI/manifest.json'])
    assert signed['id']==mod_id and signed['version']=='1.0.0' and signed['uuid']==owner
    assert {n:sha(v) for n,v in files.items() if not n.startswith('META-HMGAPI/')}==signed['files']
    assert files['META-HMGAPI/signature']

def build(cpdir,mute,owner,out):
    assert re.fullmatch(r'[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}',owner)
    files={}
    for f in cpdir.rglob('*'):
        if f.is_symlink(): raise ValueError(f'Symlink: {f.name}')
        if f.is_file(): files[f.relative_to(cpdir).as_posix()]=f.read_bytes()
    assert set(n.split('/')[0] for n in files)=={'NEEV_CP','NEMESIS.sh','NEMESIS.data'}
    prefix='NEEV_CP/payload/mod/'
    cp={n[len(prefix):]:v for n,v in files.items() if n.startswith(prefix)}
    mute_stick=readzip(mute)
    assert set(mute_stick)=={'NEMESIS.data',mute_name}
    capsule=readzip(BytesIO(mute_stick[mute_name]))
    assert set(capsule)=={'NEMESIS.data','mod/id','mod/package.zip','bin/open.sh','scripts/common.sh','scripts/preinstall.sh','scripts/postinstall.sh','scripts/preuninstall.sh'}
    assert capsule['mod/id'].decode().strip()==mute_id and owner in capsule['scripts/common.sh'].decode()
    addon=readzip(BytesIO(capsule['mod/package.zip']))
    for f,i in [(cp,'neev-usb-carplay'),(addon,mute_id)]: verify_contents(f,i,owner)
    original=files.pop('NEMESIS.sh')
    wrapper=(here/'NEMESIS.sh').read_text()
    replacements={
        '@OWNER_UUID@':owner,'@CP_LAUNCHER@':md5(original),
        '@SETUP_JAR@':md5(files['NEEV_CP/payload/setup.jar']),
        '@CP_PAYLOAD_MANIFEST@':md5(files['NEEV_CP/MANIFEST.md5']),
        '@CP_MOD_MANIFEST@':md5(cp['META-HMGAPI/manifest.json']),
        '@MUTE_MOD_MANIFEST@':md5(addon['META-HMGAPI/manifest.json']),
        '@MUTE_APP_ZIP@':md5(mute_stick[mute_name]),
        '@VERIFY_NEMESIS_APP@':'\n'.join(f'    hash_is "$APP/{n}" {md5(v)} || return 1' for n,v in sorted(capsule.items()))+'\n    return 0'
    }
    for k,v in replacements.items():
        assert k in wrapper,k
        wrapper=wrapper.replace(k,v)
    assert not re.search(r'@[A-Z_]+@',wrapper)
    subprocess.run(['/bin/sh','-n'],input=wrapper,text=True,check=True)
    files.update({'NEMESIS.sh':wrapper.encode(),'NEEV_CP_INSTALL.sh':original,mute_name:mute_stick[mute_name]})
    assert not any(n.endswith(('.ko','.odex','.apk','.key','.so')) or 'usbwifistatus.jar' in n for n in files)
    out.parent.mkdir(parents=True,exist_ok=True)
    with ZipFile(out,'w') as z:
        for name,data in sorted(files.items()):
            entry=ZipInfo(name,(2026,11,5,0,0,0))
            entry.create_system=3
            entry.external_attr=(0o100755 if name.endswith('.sh') else 0o100644)<<16
            z.writestr(entry,data,compress_type=ZIP_DEFLATED)
    assert readzip(out)==files
    print('Verified public USB archive',len(files),'files',sha(out.read_bytes()))

if __name__=='__main__':
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--installer',required=True,type=Path)
    ap.add_argument('--mute',required=True,type=Path)
    ap.add_argument('--owner',required=True)
    ap.add_argument('--out',required=True,type=Path)
    a=ap.parse_args()
    build(a.installer,a.mute,a.owner,a.out)
