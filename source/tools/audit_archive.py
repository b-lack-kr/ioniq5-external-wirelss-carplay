#!/usr/bin/env python3
"""Recursively inspect public source and USB archives. This is a scoped artifact check."""
import argparse
import hashlib
import io
import re
import stat
import unicodedata
import zipfile
from pathlib import Path, PurePosixPath

SOURCE_EXTENSIONS = {'.java','.py','.md','.txt','.json','.sh','.s','.recipe','.data','.sha256','.md5','.tsv','.ini','.conf','.c','.h','.aidl','.xml','.properties','.yml','.yaml','.toml'}
BLOCKED_EXTENSIONS = {'.ko','.so','.odex','.apk','.img','.der','.p12','.pfx','.key','.pem','.keystore','.jks','.log','.class','.pyc','.b64'}
BAD_COMPONENTS = {'private','.git','.codex','.DS_Store','__MACOSX','__pycache__','previous-usb-files','vehicle-results','vehicle-results-audio','vehicle-evidence'}
HKG_FILES = {'usbwifistatus.jar','usbwifistatus.b64','usbwifi.hotspot.zip','hkg_5w_usbwifi_hotspot.zip','usbwifi-hotspot-daemon.sh','usbwifistatusmain.java'}
PRIVATE_PATTERNS = [
    (b'/Users/', 'machine-specific home'),
    (b'-----BEGIN PRIVATE KEY-----', 'private key'),
    (b'-----BEGIN RSA PRIVATE KEY-----', 'private key'),
    (b'-----BEGIN EC PRIVATE KEY-----', 'private key'),
    (b'-----BEGIN OPENSSH PRIVATE KEY-----', 'private key'),
]
LIMITS = {'member':24*1024*1024,'total':96*1024*1024,'files':4000,'depth':5}


def load_hkg_fingerprints(reference):
    """Exact vendor assets and text fingerprints, from a locally available upstream release."""
    if not reference:
        return set(),set()
    modules = [reference/'modules/88x2bu.ko',reference/'modules/8851bu.ko',reference/'bin/usbwifistatus.jar']
    digests = {hashlib.sha256(p.read_bytes()).hexdigest() for p in modules}
    with zipfile.ZipFile(modules[-1]) as jar:
        digests.add(hashlib.sha256(jar.read('classes.dex')).hexdigest())
    return digests, shell_blocks((reference/'svc/usbwifi-hotspot-daemon.sh').read_text())


def shell_blocks(text):
    rows = [re.sub(r'\s+',' ',row.strip()) for row in text.splitlines() if row.strip() and not row.lstrip().startswith('#')]
    return {tuple(rows[i:i+10]) for i in range(len(rows)-9) if sum(map(len,rows[i:i+10]))>=140}


def scan(archive, profile, reference=None):
    total=0
    inventory=[]
    failures=[]
    digests,borrowed=load_hkg_fingerprints(reference)
    def inspect_zip(z, prefix, level):
        nonlocal total
        names=set()
        if level>LIMITS['depth']:
            raise ValueError('archive nesting limit exceeded')
        for entry in z.infolist():
            name=entry.filename
            path=PurePosixPath(name)
            label=prefix+name
            normalized=unicodedata.normalize('NFC',name).casefold()
            if name.startswith(('/','\\')) or '\\' in name or any(k in {'.','..',''} for k in name.rstrip('/').split('/')) or re.match(r'^\w:',name) or any(ord(c)<32 for c in name):
                raise ValueError(f'unsafe archive path: {label}')
            if normalized in names or stat.S_ISLNK(entry.external_attr>>16):
                raise ValueError(f'duplicate path or symlink: {label}')
            names.add(normalized)
            if entry.is_dir():
                continue
            total+=entry.file_size
            if entry.file_size>LIMITS['member'] or total>LIMITS['total'] or len(inventory)>=LIMITS['files']:
                raise ValueError('archive size or count limit exceeded')
            data=z.read(entry)
            digest=hashlib.sha256(data).hexdigest()
            inventory.append((label,len(data),digest))
            leaf=path.name.lower()
            if (any(p in BAD_COMPONENTS or p.startswith(('._','deployment-','NEEV_CP_RESULT_','NEEV_BUNDLE_RESULT_','NEEV_AUDIO_LIBS_','car-report-')) for p in path.parts)
                    or leaf in HKG_FILES or path.suffix.lower() in BLOCKED_EXTENSIONS):
                failures.append((label,'blocked artifact name'))
            if digest in digests or data.startswith((b'\x7fELF',b'dey\n',b'ANDROID!',b'\x3a\xff\x26\xed')):
                failures.append((label,'upstream or firmware binary'))
            if data.startswith(b'PK\x03\x04'):
                with zipfile.ZipFile(io.BytesIO(data)) as child:
                    inspect_zip(child,label+'!',level+1)
                continue
            if not data.startswith(b'dex\n'):
                try:
                    data.decode('utf-8')
                    if b'\0' in data:
                        failures.append((label,'binary data outside recognized container'))
                except UnicodeDecodeError:
                    failures.append((label,'binary data outside recognized container'))
            if profile=='source' and (data.startswith(b'dex\n') or (path.suffix.lower() not in SOURCE_EXTENSIONS and '.' in path.name and path.name not in {'LICENSE','NOTICE'})):
                failures.append((label,'unexpected non-source member'))
            if profile=='usb' and data.startswith(b'dex\n') and ('!classes.dex' not in label or not data.startswith(b'dex\n035\0')):
                failures.append((label,'unrecognized bytecode or incompatible DEX'))
            if data.startswith(b'dex\n') and b'Lcom/hkg/usbwifi/UsbWifiStatusMain;' in data:
                failures.append((label,'HKG Java implementation'))
            # The audit tool describes these patterns itself. Otherwise do not print matching bytes.
            if path.name!='audit_archive.py':
                for pattern,description in PRIVATE_PATTERNS:
                    if pattern in data:
                        failures.append((label,description))
                if re.search(rb'(?:^|\n)(?:VIN|vehicle_vin|phone_mac|bluetooth_address)\s*[:=]\s*["\']?(?:[A-HJ-NPR-Z0-9]{17}|(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2})(?![A-Za-z0-9])',data):
                    failures.append((label,'literal device or vehicle identifier'))
            if path.suffix=='.sh' and borrowed and (shell_blocks(data.decode('utf-8')) & borrowed):
                failures.append((label,'unlicensed upstream daemon text fingerprint'))
    with zipfile.ZipFile(archive) as z:
        inspect_zip(z,'',0)
    return inventory,failures


def main():
    arg=argparse.ArgumentParser(description=__doc__)
    arg.add_argument('archive',type=Path)
    arg.add_argument('--profile',choices=('usb','source'),required=True)
    arg.add_argument('--hkg-reference',type=Path)
    a=arg.parse_args()
    inv,fail=scan(a.archive,a.profile,a.hkg_reference)
    for name,reason in fail: print(f'REJECT {name}: {reason}')
    if fail: raise SystemExit(1)
    print(f'PASS {a.profile}: {len(inv)} recursively inspected files; {a.archive.name} SHA256 {hashlib.sha256(a.archive.read_bytes()).hexdigest()}')

if __name__=='__main__': main()
