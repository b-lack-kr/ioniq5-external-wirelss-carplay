#!/usr/bin/env python3
"""Exercise delivered preparation tools in disposable directories, without accessing USB disks."""
import argparse
from io import BytesIO
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from zipfile import ZipFile

KIT = 'NEEV_260902_CarPlay_1.0.2_USB.zip'
DRIVER = 'packages/usbwifi.hotspot.zip'
VENDOR_HASH = '7a7e5c7a46fbf160d7b9b91fe4333ea55e4865233450c285361f9ef1a11059d7'
DRIVER_HASH = 'd9077ba275398c1578444fdea479a5675e21f1b36f9ad3217e57b65aeadc512b'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def contents(path):
    with ZipFile(path) as archive:
        assert archive.testzip() is None
        names = archive.namelist()
        assert len(names) == len(set(names))
        assert all(n and not n.startswith('/') and not n.endswith('/') and '..' not in Path(n).parts for n in names)
        return {n: archive.read(n) for n in names}


def inventory(directory):
    result = {}
    for path in directory.rglob('*'):
        assert not path.is_symlink()
        if path.is_file():
            result[path.relative_to(directory).as_posix()] = sha(path.read_bytes())
    return result


def run(command, cwd):
    return subprocess.run(command, cwd=cwd, input=b'', capture_output=True, timeout=150)


def passed(result):
    assert result.returncode == 0, result.stdout.decode(errors='replace') + result.stderr.decode(errors='replace')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path, required=True, help='Combined or platform preparation ZIP')
    parser.add_argument('--kit', type=Path, required=True)
    parser.add_argument('--vendor', type=Path, required=True, help='Already verified original GRT47 v2.0.2 ZIP')
    parser.add_argument('--platform', choices=('mac','windows','both'), required=True)
    parser.add_argument('--powershell', type=Path)
    parser.add_argument('--published-downloads', action='store_true', help='Do not seed local ZIPs; exercise public HTTPS after publication')
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    bundle = contents(args.archive)
    core = contents(args.kit)
    if KIT in bundle:
        assert bundle[KIT] == args.kit.read_bytes()
    assert not any('mute-pause' in name for name in core), 'Independent mute capsule bundled'
    assert not any(Path(name).name in ('usbwifi.hotspot.zip','HKG_5W_USBWifi_Hotspot.zip') for name in bundle)
    meta = json.loads(core['NEEV_CP/payload/mod/META-HMGAPI/manifest.json'])
    assert meta['version'] == '1.0.2'
    prefix = 'NEEV_CP/payload/mod/'
    assert meta['files'] == {name[len(prefix):]: sha(data) for name,data in core.items()
                             if name.startswith(prefix) and not name.startswith(prefix+'META-HMGAPI/')}
    assert sha(args.vendor.read_bytes()) == VENDOR_HASH
    vendor = contents(args.vendor)
    assert sha(vendor[DRIVER]) == DRIVER_HASH
    expected = {name: sha(data) for name,data in core.items()}
    assert DRIVER not in expected
    expected[DRIVER] = DRIVER_HASH
    results = {}
    with tempfile.TemporaryDirectory(prefix='neev-recipient-') as temp:
        scratch = Path(temp).resolve()
        for platform in ('mac','windows') if args.platform == 'both' else (args.platform,):
            target = scratch / (platform + ' 받는 사람')
            target.mkdir()
            for name,data in bundle.items():
                destination = target / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(data)
                if name.endswith('.command'):
                    destination.chmod(0o755)
            if not args.published_downloads:
                if KIT not in bundle:
                    shutil.copyfile(args.kit, target/KIT)
                shutil.copyfile(args.vendor, target/'HKG_5W_USBWifi_Hotspot.zip')
            if platform == 'mac':
                command = ['/bin/sh', str(target/'USB-준비-macOS.command'), '--prepare-only']
            else:
                assert args.powershell and args.powershell.is_file()
                command = [str(args.powershell.resolve()), '-NoLogo', '-NoProfile', '-NonInteractive',
                           '-File', str(target/'tools/Prepare-USB.ps1'), '-PrepareOnly']
            passed(run(command, scratch))
            assert inventory(target/'USB_READY') == expected, platform+' output differs'
            passed(run(command, scratch))
            assert inventory(target/'USB_READY') == expected, 'Repeat changed output'
            # An existing stale/tampered tree is not silently deleted or published.
            extra = target/'USB_READY/unrelated-owner-file.txt'
            extra.write_bytes(b'do not overwrite')
            result = run(command, scratch)
            assert result.returncode != 0 and extra.read_bytes() == b'do not overwrite'
            extra.unlink()
            # Supply a deliberately altered local ZIP even for the standalone
            # public-download case; it must fail before prepared output changes.
            original = args.kit.read_bytes()
            (target/KIT).write_bytes(original[:-1] + bytes([original[-1] ^ 1]))
            assert run(command, scratch).returncode != 0, 'Changed input ZIP was accepted'
            assert inventory(target/'USB_READY') == expected, 'Bad input mutated prepared tree'
            results[platform] = {'files':len(expected), 'exact_hashes':True, 'repeat':True,
                                 'conflicting_tree_retained':True, 'changed_download_rejected':True,
                                 'sha256':expected}
    report = {'archive_sha256':sha(args.archive.read_bytes()), 'kit_sha256':sha(args.kit.read_bytes()),
              'carplay_only':True, 'physical_usb_accessed':False,
              'published_downloads':args.published_downloads, 'platforms':results}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print('PASS:',len(expected),'files; prepare-only, repeat and rejection checks:',','.join(results))


if __name__ == '__main__':
    main()
