#!/usr/bin/env python3
"""Rebuild all archive inputs and assemble a USB installer using your own HMG author key."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys


def run(*args,env=None):
    subprocess.run(list(map(str,args)),check=True,env=env)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source-root',type=Path,default=Path(__file__).resolve().parents[1])
    for k in ('java-home','android-jar','d8','out','sign-key'):
        p.add_argument('--'+k,type=Path,required=True)
    p.add_argument('--mute-usb',type=Path,help='Reuse the signed, vehicle-verified Mute Pause 1.1.0 USB archive')
    p.add_argument('--generate-key',action='store_true')
    a=p.parse_args(); root=a.source_root.resolve(); out=a.out.resolve(); key=a.sign_key.resolve()
    if out==root or root in out.parents or out in root.parents or key==out or root in key.parents or out in key.parents:
        raise SystemExit('Keep source tree, output tree and private key separate')
    out.mkdir(parents=True,exist_ok=True)
    if any(out.iterdir()): raise SystemExit('Use an empty output folder')
    hmg=root/'upstream/HMG-API-1.0.1'
    jdk=a.java_home.resolve();sdk=a.android_jar.resolve();d8=a.d8.resolve()
    if a.generate_key:
        if key.exists() or Path(str(key)+'.pub').exists():
            raise SystemExit('Signing key already exists')
        key.parent.mkdir(mode=0o700,parents=True,exist_ok=True)
        engine=out/'signing-tools';engine.mkdir()
        run(jdk/'bin/javac','--release','8','-encoding','UTF-8','-d',engine,*sorted((hmg/'core/runtime/java/hmgapi/engine').glob('*.java')))
        previous_mask=os.umask(0o077)
        try: run(jdk/'bin/java','-cp',engine,'hmgapi.engine.Signing','keygen',key)
        finally: os.umask(previous_mask)
    if not key.is_file(): raise SystemExit('No private key; use --generate-key with a new path')
    flags=['--java-home',jdk,'--android-jar',sdk,'--d8',d8,'--hmg-source',hmg,'--sign-key',key]
    run(sys.executable,root/'mod/build.py',*flags,'--out',out/'mod')
    run(sys.executable,root/'tools/build_support.py',*flags,'--source-root',root,'--out',out/'support')
    # Importer builder writes its build directory beside its source; keep the source kit read-only.
    shutil.copytree(root/'vendor/source',out/'vendor/source')
    run(sys.executable,out/'vendor/source/build.py',env={**os.environ,'JAVA_HOME':str(jdk),'ANDROID_JAR':str(sdk),'D8_JAR':str(d8)})
    owner=json.loads((out/'mod/payload/META-HMGAPI/manifest.json').read_text())['uuid']
    run(sys.executable,root/'installer/build_installer.py','--mod',out/'mod/payload','--setup-jar',out/'support/setup.jar',
        '--maps',root/'maps','--target',root/'target/TARGET.md5','--vendor-dir',out/'vendor/build','--out',out/'cp-usb','--uuid',owner)
    zipout=out/'NEEV_260902_CarPlay_1.0.1_USB.zip'
    run(sys.executable,root/'release/build.py','--installer',out/'cp-usb','--mute',a.mute_usb.resolve() if a.mute_usb else out/'support/mute-usb.zip','--owner',owner,'--out',zipout)
    run(sys.executable,root/'tools/audit_archive.py','--profile','usb',zipout)
    print(f'Finished: {zipout}')

if __name__=='__main__': main()
