#!/usr/bin/env python3
"""Build the public installer helper and separate NEMESIS mute package from the source kit."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile


def execute(*argv):
    subprocess.run([str(x) for x in argv],check=True)


def java_sources(root):
    return sorted(root.rglob('*.java'))


def zip_tree(root, output):
    with zipfile.ZipFile(output,'w') as z:
        for p in sorted(root.rglob('*')):
            if p.is_file():
                attr=zipfile.ZipInfo(p.relative_to(root).as_posix(),(2026,11,4,0,0,0))
                attr.create_system=3
                attr.external_attr=(0o100755 if p.suffix=='.sh' else 0o100644)<<16
                z.writestr(attr,p.read_bytes(),compress_type=zipfile.ZIP_DEFLATED)


def digest(path):
    return hashlib.md5(path.read_bytes()).hexdigest()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root',type=Path,default=Path(__file__).resolve().parents[1])
    parser.add_argument('--java-home',type=Path,required=True)
    parser.add_argument('--hmg-source',type=Path)
    parser.add_argument('--android-jar',type=Path,required=True)
    parser.add_argument('--d8',type=Path,required=True,help='R8/D8 jar')
    parser.add_argument('--sign-key',type=Path,required=True,help='HMG author PKCS#8 .key; use a local private file')
    parser.add_argument('--out',type=Path,required=True)
    a=parser.parse_args()
    root=a.source_root.resolve()
    hmg=(a.hmg_source or root/'upstream/HMG-API-1.0.1').resolve()
    java=a.java_home/'bin/java'
    javac=a.java_home/'bin/javac'
    out=a.out.resolve()
    out.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory() as temp:
        work=Path(temp)
        def javac_to(name, cp, sources):
            dest=work/name
            dest.mkdir()
            execute(javac,'--release','8','-encoding','UTF-8','-cp',os.pathsep.join(map(str,cp)),'-d',dest,*sources)
            return dest
        def dex(classes, target, cp=()):
            args=[opt for p in cp for opt in ('--classpath',p)]
            execute(java,'-cp',a.d8,'com.android.tools.r8.D8','--release','--min-api','19','--lib',a.android_jar,*args,'--output',target,*sorted(classes.rglob('*.class')))
            with zipfile.ZipFile(target) as jar:
                if jar.read('classes.dex')[:8]!=b'dex\n035\0':
                    raise ValueError('not a KitKat DEX')
        def sign(*args):
            return subprocess.check_output([str(x) for x in (java,'-cp',api,'hmgapi.engine.Signing',*args)],text=True).strip()
        stubs=javac_to('stubs',[a.android_jar],java_sources(hmg/'core/runtime/stubs'))
        api=javac_to('api',[a.android_jar,stubs],java_sources(hmg/'core/runtime/java'))
        src=root/'mute'
        mod=work/'mod';mod.mkdir()
        cls=javac_to('mute',[a.android_jar,api],java_sources(src/'java'))
        dex(cls,mod/'hooks.jar',[api])
        shutil.copyfile(src/'mod.json',mod/'mod.json')
        shutil.copyfile(hmg/'LICENSE',mod/'LICENSE')
        manifest=json.loads((mod/'mod.json').read_text())
        if manifest['id']!='neev-carplay-mute-pause': raise ValueError('unexpected mute package ID')
        sign('sign',mod,a.sign_key.resolve())
        owner=json.loads((mod/'META-HMGAPI/manifest.json').read_text())['uuid']
        sign('verify',mod,owner)
        app=work/'nemesis';shutil.copytree(src/'usb/package',app)
        payload=app/'mod/package.zip';zip_tree(mod,payload)
        common=app/'scripts/common.sh'
        template=common.read_text()
        for k,v in {'@AUTHOR_UUID@':owner,'@MOD_MANIFEST_MD5@':digest(mod/'META-HMGAPI/manifest.json'),'@PACKAGE_ZIP_MD5@':digest(payload)}.items():
            if k not in template: raise ValueError(f'missing {k}')
            template=template.replace(k,v)
        common.write_text(template)
        stick=work/'stick';shutil.copytree(src/'usb/stick',stick)
        (stick/'packages').mkdir()
        zip_tree(app,stick/'packages/hmgapi.neev-carplay-mute-pause.zip')
        zip_tree(stick,out/'mute-usb.zip')
        helper=javac_to('setup',[a.android_jar],java_sources(root/'setup')+[hmg/'core/runtime/java/hmgapi/engine/Json.java'])
        dex(helper,out/'setup.jar')
    print(f'Author {owner}\n{out / "setup.jar"}\n{out / "mute-usb.zip"}')

if __name__=='__main__': main()
