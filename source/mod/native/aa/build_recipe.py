#!/usr/bin/env python3
"""Rebuild the48ee memory recipe from the pinned base and independently assembled changes.

Base: HMG-Seogaemo v0.1.46 26eeb97e0a745528b438d7a946be5450fadbfd04.
Changes: HMG-Seogaemo v0.1.47 7616e43f669916445f151a7aeb279b32ea09cd4b.
AGPL-3.0-or-later; upstream lineage and changes are recorded beside this script.
Only --output is written. No stock library file or vehicle access is needed to regenerate.
"""
from pathlib import Path
import argparse,hashlib,os,re,shutil,subprocess,tempfile

HERE=Path(__file__).resolve().parent
BASE_SHA='7d7ec2316f6a6956fd57b2d8dc6af362cce6f46edbfbc4136a3ba28008c574d5'
SEGMENT_SHA='77bc34c9fde926eaeb9849fab5b23d7cbf616a9be1dc6b576c7b7d22938980d4'
RECIPE_SHA='88c41e236a839f6fda40287560c9420edc5861f2cda6a48d0eb5d43fac17d2ea'
SITES=((0xf7e,'4ff0010a'),(0xff2,'18bf4ff0020a'),(0x101c,'83f86c10'),(0x1598,'00'))

def tools(ndk_bin=None):
    directory=Path(ndk_bin or os.environ['NDK_BIN']) if ndk_bin or os.environ.get('NDK_BIN') else None
    if directory is None:
        for parent in HERE.parents:
            for candidate in (parent/'.tools/android-ndk-r25c/toolchains/llvm/prebuilt').glob('*/bin'):
                if (candidate/'clang').is_file() and (candidate/'llvm-objcopy').is_file():
                    directory=candidate;break
            if directory is not None:break
    clang=os.environ.get('CLANG') or (str(directory/'clang') if directory is not None else shutil.which('clang'))
    objcopy=os.environ.get('OBJCOPY') or (str(directory/'llvm-objcopy') if directory is not None else shutil.which('llvm-objcopy'))
    if not clang or not objcopy or not Path(clang).is_file() or not Path(objcopy).is_file():
        raise SystemExit('Set NDK_BIN to an Android NDK LLVM bin directory (clang and llvm-objcopy), or set CLANG and OBJCOPY to those executables.')
    return Path(clang),Path(objcopy)

def assembled(src,clang,objcopy,tail=None):
    with tempfile.TemporaryDirectory(prefix='neev-aa-asm-') as tmp:
        obj=Path(tmp)/'code.o';binary=Path(tmp)/'code.bin'
        args=[clang,'--target=thumbv7-linux-androideabi','-x','assembler-with-cpp']
        if tail is not None:args.append('-DTAIL='+str(tail))
        subprocess.run([*args,'-c',src,'-o',obj],check=True)
        subprocess.run([objcopy,'-O','binary','--only-section=.text',obj,binary],check=True)
        return binary.read_bytes()

def build(clang,objcopy):
    base=(HERE/'aa-tbt-48ee793a.base.recipe').read_bytes()
    if hashlib.sha256(base).hexdigest()!=BASE_SHA:raise SystemExit('Pinned base recipe hash mismatch; no output written.')
    text=base.decode();match=re.search(r'^segment (\S+) ([0-9a-f]+)$',text,re.M)
    code=bytearray.fromhex(match[2]);assert len(code)==5546
    for offset,before in SITES:assert code[offset:offset+len(before)//2].hex()==before
    utf8=assembled(HERE/'aa-utf8-len.s',clang,objcopy);assert len(utf8)==32
    tail=(len(code)+3)&~3;lanes=assembled(HERE/'aa-lane-routes.s',clang,objcopy,tail)
    code[0x17a:0x19a]=utf8
    code.extend(bytes(tail-len(code)))
    for offset,before in SITES:code[offset:offset+len(before)//2]=lanes[offset:offset+len(before)//2]
    code.extend(lanes[tail:])
    assert len(code)==5574 and hashlib.sha256(code).hexdigest()==SEGMENT_SHA
    text=text.replace(match[0],'segment '+match[1]+' '+code.hex())
    comments=(
      '# Modified by HMG-Seogaemo: the 32-byte UTF-8 length helper at segment +0x17a is replaced (source payload/recipes/aa-utf8-len.s, test test/aautf8); the patched md5 above is the unmodified upstream recipe\'s\n'
      '# Modified by HMG-Seogaemo: the lane byte of event 34 is the mask of recommended arrow slots, U-turn right gets an arrow (source payload/recipes/aa-lane-routes.s, test test/aalanes); the segment grows by the tail after the upstream code\n')
    text=text.replace('id aa-tbt-48ee793a\n',comments+'id aa-tbt-48ee793a-neev-v0147\n')
    text=('# NEEV v1.0.11: pinned Seogaemo commit 7616e43f669916445f151a7aeb279b32ea09cd4b, AGPL-3.0-or-later.\n'
          '# Changed ownership ID prevents an old applied recipe from authorizing the new event-34 mask decoder.\n'+text)
    output=text.encode();assert hashlib.sha256(output).hexdigest()==RECIPE_SHA
    return output

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk-bin',help='Android NDK LLVM bin directory; otherwise NDK_BIN or tool discovery')
    parser.add_argument('--output',type=Path,required=True,help='Output recipe path (the only file written)')
    args=parser.parse_args();clang,objcopy=tools(args.ndk_bin)
    output=build(clang,objcopy);args.output.write_bytes(output)
    print('Rebuilt recipe: 5574-byte segment, SHA256 '+RECIPE_SHA)

if __name__=='__main__':main()
