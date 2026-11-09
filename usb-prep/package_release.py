#!/usr/bin/env python3
"""Package public 1.0.3 PC tools after the signed vehicle/source pins are finalized."""
import argparse
from hashlib import sha256
from io import BytesIO
import json
import os
from pathlib import Path
import re
from tempfile import NamedTemporaryFile
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile, ZipInfo

BASE = Path(__file__).resolve().parent
KIT = 'NEEV_260902_CarPlay_1.0.3_USB.zip'
SRC = 'NEEV_260902_CarPlay_1.0.3_SOURCE.zip'
RELEASE_BASE = 'https://github.com/b-lack-kr/ioniq5-external-wirelss-carplay/releases/download/v1.0.3'
TOKEN = b'@CARPLAY_RELEASE_BASE@'
HASH_TOKEN = b'@CARPLAY_CORE_SHA256@'
MAC = 'USB-준비-macOS.command'
CMD = 'Prepare-USB-Windows.cmd'
WIN = 'tools/Prepare-USB.ps1'
VOLUME = 'tools/WindowsUsbVolume.ps1'
GUIDE = '사용안내.txt'
SUMS = 'SHA256SUMS.txt'
MAC_ZIP = 'Prepare-USB-macOS-v1.0.3.zip'
WIN_ZIP = 'Prepare-USB-Windows-v1.0.3.zip'
COMBINED = 'NEEV_260902_CarPlay_1.0.3_by_blackr.zip'


def require(test, message):
    if not test:
        raise ValueError(message)


def digest(data):
    return sha256(data).hexdigest()


def sums(files):
    return ''.join(f'{digest(data)}  {name}\n' for name, data in sorted(files.items())).encode()


def mode(name):
    return 0o755 if name.endswith('.command') else 0o644


def make_zip(files):
    buffer = BytesIO()
    with ZipFile(buffer, 'w') as archive:
        for name, data in sorted(files.items()):
            entry = ZipInfo(name, (2026, 11, 8, 0, 0, 0))
            entry.create_system = 3
            entry.external_attr = (0o100000 | mode(name)) << 16
            archive.writestr(entry, data, compress_type=ZIP_STORED if name.endswith('.zip') else ZIP_DEFLATED)
    with ZipFile(buffer) as archive:
        require(archive.testzip() is None and {n: archive.read(n) for n in archive.namelist()} == files,
                'Release archive readback mismatch')
    return buffer.getvalue()


def load_pins(path):
    pins = json.loads(path.read_text())
    require(set(pins) == {KIT, SRC}, 'Expected exactly the final vehicle and source archive pins')
    require(all(isinstance(v, str) and re.fullmatch('[0-9a-f]{64}', v) for v in pins.values()),
            'Final release hashes are not set; do not package placeholder pins')
    return pins


def check_core(data):
    with ZipFile(BytesIO(data)) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)) and archive.testzip() is None, 'Invalid core ZIP inventory')
        require(all(n and not n.endswith('/') and not n.startswith('/') and '\\' not in n and
                    all(part not in ('', '.', '..') for part in n.split('/')) for n in names),
                'Core ZIP must contain ordinary relative files only')
        require(not any('mute-pause' in n or Path(n).name in ('usbwifi.hotspot.zip', 'HKG_5W_USBWifi_Hotspot.zip') for n in names),
                'CarPlay core must not bundle the independent mute package or downloaded vendor files')
        require({'NEMESIS.data','NEMESIS.sh','USB_AUTO.sh','NEEV_CP_INSTALL.sh','packages/hmgapi.neev-usb-carplay.zip'} <= set(names),
                'Missing public CarPlay installation entry points')
        prefix = 'NEEV_CP/payload/mod/'
        manifest = json.loads(archive.read(prefix + 'META-HMGAPI/manifest.json'))
        require(manifest['version'] == '1.0.3' and manifest['id'] == 'neev-usb-carplay', 'Wrong signed CarPlay package')
        require(manifest['files'] == {n[len(prefix):]: digest(archive.read(n)) for n in names
                                     if n.startswith(prefix) and not n.startswith(prefix + 'META-HMGAPI/')},
                'Signed CarPlay file inventory mismatch')


def build(signed, source, pins):
    payload = {}
    for name, pin in pins.items():
        data = (signed / name).read_bytes()
        require(digest(data) == pin, f'Original signed release digest differs: {name}')
        payload[name] = data
    check_core(payload[KIT])
    for name in (MAC, CMD, WIN, VOLUME, GUIDE):
        path = source / name
        require(path.is_file() and not path.is_symlink(), f'Missing preparation source: {name}')
        data = path.read_bytes()
        if name in (MAC, WIN):
            require(data.count(TOKEN) == 1, f'Expected one public release URL token: {name}')
            require(data.count(HASH_TOKEN) == 1, f'Expected one final core hash token: {name}')
            data = data.replace(TOKEN, RELEASE_BASE.encode()).replace(HASH_TOKEN, pins[KIT].encode())
            for pin in (pins[KIT], '7a7e5c7a46fbf160d7b9b91fe4333ea55e4865233450c285361f9ef1a11059d7',
                        'd9077ba275398c1578444fdea479a5675e21f1b36f9ad3217e57b65aeadc512b'):
                require(pin.encode() in data, f'Preparation input digest missing: {name}')
        require(not re.search(rb'@[A-Z_]+@', data) and b'/Users/' not in data, f'Unresolved token or developer path: {name}')
        payload[name] = data
    payload[SUMS] = sums(payload)
    assets = {n: payload[n] for n in pins}
    assets[MAC_ZIP] = make_zip({MAC: payload[MAC]})
    assets[WIN_ZIP] = make_zip({n: payload[n] for n in (CMD, WIN, VOLUME)})
    assets[COMBINED] = make_zip(payload)
    assets[SUMS] = sums(assets)
    return payload, assets


def put(directory, name, data):
    destination = directory / name
    require(not destination.is_symlink(), f'Refusing symbolic link: {destination}')
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.is_file() and destination.read_bytes() == data:
        if destination.stat().st_mode & 0o777 != mode(name):
            destination.chmod(mode(name))
        return
    tmppath = None
    try:
        with NamedTemporaryFile(dir=destination.parent, delete=False) as temp:
            tmppath = Path(temp.name)
            temp.write(data)
            temp.flush()
            os.fsync(temp.fileno())
        tmppath.chmod(mode(name))
        os.replace(tmppath, destination)
    finally:
        if tmppath is not None and tmppath.exists():
            tmppath.unlink()


def publish_folder(directory, files):
    require(not directory.is_symlink(), f'Refusing symbolic link: {directory}')
    directory.mkdir(parents=True, exist_ok=True)
    expected_dirs = {part for name in files for part in Path(name).parents}
    for p in directory.rglob('*'):
        rel = p.relative_to(directory)
        require(not p.is_symlink(), f'Refusing symbolic link: {p}')
        if p.is_dir():
            require(rel in expected_dirs, f'Unexpected existing folder: {p}')
        elif p.name != '.DS_Store':
            require(rel.as_posix() in files, f'Unexpected existing file: {p}')
    for name, data in files.items():  # publish checksums last
        put(directory, name, data)
    require(all((directory / n).read_bytes() == b for n, b in files.items()), f'Published bytes differ: {directory}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--signed-dir', type=Path, required=True,
                        help='Location of the accepted signed vehicle USB and matching source ZIP')
    parser.add_argument('--source-dir', type=Path, default=BASE / 'source')
    parser.add_argument('--pins', type=Path, default=BASE / 'release-pins.json')
    parser.add_argument('--assets-dir', type=Path, default=BASE / 'assets-carplay')
    parser.add_argument('--publish-folder', type=Path,
                        help='Also put the combined ZIP here and its unpacked files in the 1.0.3 subfolder')
    args = parser.parse_args()
    contents, assets = build(args.signed_dir, args.source_dir, load_pins(args.pins))
    publish_folder(args.assets_dir, assets)
    if args.publish_folder:
        require(not args.publish_folder.is_symlink(), 'Refusing to publish releases through a symbolic link')
        publish_folder(args.publish_folder / '1.0.3', contents)
        put(args.publish_folder, COMBINED, assets[COMBINED])
    print(f'Verified release assets: {args.assets_dir}')
    print(assets[SUMS].decode(), end='')


if __name__ == '__main__':
    main()
