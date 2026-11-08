"""Assemble a CarPlay-only USB release without rebuilding its signed transaction."""
import argparse
import hashlib
from io import BytesIO
import json
from pathlib import Path
import re
import subprocess
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

here=Path(__file__).resolve().parent
package_name='packages/hmgapi.neev-usb-carplay.zip'

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
    assert signed['id']==mod_id=='neev-usb-carplay' and signed['version']=='1.0.2' and signed['uuid']==owner
    assert {n:sha(v) for n,v in files.items() if not n.startswith('META-HMGAPI/')}==signed['files']
    assert files['META-HMGAPI/signature']

def zipbytes(files):
    stream=BytesIO()
    with ZipFile(stream,'w') as z:
        for name,data in sorted(files.items()):
            entry=ZipInfo(name,(2026,11,5,0,0,0))
            entry.create_system=3
            entry.external_attr=(0o100755 if name.endswith('.sh') else 0o100644)<<16
            z.writestr(entry,data,compress_type=ZIP_DEFLATED)
    return stream.getvalue()


def native_package(wrapper,launcher):
    files={}
    for f in (here/'native').rglob('*'):
        if f.is_symlink(): raise ValueError(f'Symlink: {f.name}')
        if f.is_file(): files[f.relative_to(here/'native').as_posix()]=f.read_bytes()
    expected={'NEMESIS.data','mod/id','bin/open.sh','scripts/common.sh',
              'scripts/preinstall.sh','scripts/postinstall.sh','scripts/preuninstall.sh'}
    assert set(files)==expected
    common=files['scripts/common.sh'].decode()
    for key,value in {'@CP_WRAPPER_MD5@':md5(wrapper),'@CP_LAUNCHER_MD5@':md5(launcher)}.items():
        assert key in common
        common=common.replace(key,value)
    files['scripts/common.sh']=common.encode()
    for name,data in files.items():
        assert not re.search(rb'@[A-Z_]+@',data)
        if name.endswith('.sh'):
            subprocess.run(['/bin/sh','-n'],input=data,check=True)
    return zipbytes(files)


def automatic_usb(files):
    """Change only USB entry behavior; retain the installed transaction bytes."""
    script=(here/'USB_AUTO.sh').read_text()
    pins={
        '@AUTO_WRAPPER_MD5@':md5(files['NEMESIS.sh']),
        '@AUTO_LAUNCHER_MD5@':md5(files['NEEV_CP_INSTALL.sh']),
        '@AUTO_PACKAGE_MD5@':md5(files[package_name]),
    }
    for key,value in pins.items():
        assert key in script,key
        script=script.replace(key,value)
    assert not re.search(r'@[A-Z_]+@',script)
    subprocess.run(['/bin/sh','-n'],input=script,text=True,check=True)
    files['USB_AUTO.sh']=script.encode()
    files['NEMESIS.data']=b'[nemesis]\nversion=1.1\ntype=execute\npath=USB_AUTO.sh\n'


def build(cpdir,owner,out):
    assert re.fullmatch(r'[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}',owner)
    files={}
    for f in cpdir.rglob('*'):
        if f.is_symlink(): raise ValueError(f'Symlink: {f.name}')
        if f.is_file(): files[f.relative_to(cpdir).as_posix()]=f.read_bytes()
    assert set(n.split('/')[0] for n in files)=={'NEEV_CP','NEMESIS.sh','NEMESIS.data'}
    prefix='NEEV_CP/payload/mod/'
    cp={n[len(prefix):]:v for n,v in files.items() if n.startswith(prefix)}
    verify_contents(cp,'neev-usb-carplay',owner)
    original=files.pop('NEMESIS.sh')
    wrapper=(here/'NEMESIS.sh').read_text()
    replacements={
        '@OWNER_UUID@':owner,'@CP_LAUNCHER@':md5(original),
        '@SETUP_JAR@':md5(files['NEEV_CP/payload/setup.jar']),
        '@CP_PAYLOAD_MANIFEST@':md5(files['NEEV_CP/MANIFEST.md5']),
        '@CP_MOD_MANIFEST@':md5(cp['META-HMGAPI/manifest.json'])
    }
    for k,v in replacements.items():
        assert k in wrapper,k
        wrapper=wrapper.replace(k,v)
    assert not re.search(r'@[A-Z_]+@',wrapper)
    subprocess.run(['/bin/sh','-n'],input=wrapper,text=True,check=True)
    files.update({'NEMESIS.sh':wrapper.encode(),'NEEV_CP_INSTALL.sh':original,
                  package_name:native_package(wrapper.encode(),original),
                  'NEMESIS.data':('[nemesis]\nversion=1.1\ntype=install\npath='+package_name+'\n').encode()})
    automatic_usb(files)
    assert not any(n.endswith(('.ko','.odex','.apk','.key','.so')) or 'usbwifistatus.jar' in n for n in files)
    out.parent.mkdir(parents=True,exist_ok=True)
    out.write_bytes(zipbytes(files))
    assert readzip(out)==files
    print('Verified public USB archive',len(files),'files',sha(out.read_bytes()))

if __name__=='__main__':
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--installer',required=True,type=Path)
    ap.add_argument('--owner',required=True)
    ap.add_argument('--out',required=True,type=Path)
    a=ap.parse_args()
    build(a.installer,a.owner,a.out)
