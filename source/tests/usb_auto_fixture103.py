"""Run the real USB dispatcher against modeled NEMESIS and installation state."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from zipfile import ZipFile

SOURCE = Path(__file__).resolve().parents[1]


class UsbAuto(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='neev-usb-auto-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.usb = self.root / 'storage/usb1'
        self.bin = self.root / 'tools'
        self.w = self.root / 'workspace'
        self.cp = self.root / 'mods/neev-usb-carplay'
        self.public = self.w / 'public-release-1.0.3'
        self.calls = self.root / 'calls'
        (self.usb / 'packages').mkdir(parents=True)
        self.bin.mkdir()
        wrapper = b'#!/bin/sh\necho "collect:$*" >> "$TEST_ROOT/calls"\nexit "${COLLECT_RC:-0}"\n'
        launcher = b'immutable installer fixture\n'
        package = b'immutable native package fixture\n'
        self.wrapper = wrapper
        (self.usb / 'NEMESIS.sh').write_bytes(wrapper)
        (self.usb / 'NEEV_CP_INSTALL.sh').write_bytes(launcher)
        (self.usb / 'packages/hmgapi.neev-usb-carplay.zip').write_bytes(package)
        (self.usb / 'NEMESIS.data').write_text('[nemesis]\nversion=1.1\ntype=execute\npath=USB_AUTO.sh\n')
        script = (SOURCE / 'release/USB_AUTO.sh').read_text()
        for token, data in [('AUTO_WRAPPER_MD5', wrapper), ('AUTO_LAUNCHER_MD5', launcher), ('AUTO_PACKAGE_MD5', package)]:
            script = script.replace('@' + token + '@', hashlib.md5(data).hexdigest())
        script = script.replace('PATH=/system/bin:/system/xbin:/data/nemesis/bin', 'PATH=' + str(self.bin) + ':/bin:/usr/bin')
        script = script.replace('/data/nemesis/neev-usbcp', str(self.w)).replace('/data/hmgapi/mods/neev-usb-carplay', str(self.cp))
        script = script.replace('/system/xbin/nemesis', str(self.bin / 'nemesis'))
        script = script.replace('/system/bin/app_process', str(self.bin / 'app_process'))
        script = script.replace('/storage/usb1', str(self.usb)).replace('/system/bin/sh', '/bin/sh')
        self.script = self.usb / 'USB_AUTO.sh'
        self.script.write_text(script)
        self.tool('id', 'echo "${TEST_UID:-uid=0(root)}"')
        self.tool('getprop', 'echo "${TEST_PRODUCT:-NEEV.KOR.0000.V039.001.260902}"')
        self.tool('sync', 'exit 0')
        self.tool('am', 'exit 0')
        self.tool('md5', 'if [ -x /sbin/md5 ]; then exec /sbin/md5 -r "$1"; else exec md5sum "$1"; fi')
        self.tool('nemesis', '''case "$1" in
usb-info) cat "$TEST_ROOT/usb-info"; exit "${USB_QUERY_RC:-0}";;
pkg-info) cat "$TEST_ROOT/pkg-info"; exit "${PKG_QUERY_RC:-0}";;
install)
  echo "native:$*" >> "$TEST_ROOT/calls"
  [ "${INSTALL_RC:-0}" = 0 ] || exit "$INSTALL_RC"
  [ "${FALSE_INSTALL_SUCCESS:-0}" = 0 ] || exit 0
  mkdir -p "$TEST_ROOT/mods/neev-usb-carplay" "$TEST_ROOT/workspace/public-release-1.0.3"
  echo 1.0.3 > "$TEST_ROOT/workspace/UPGRADE_CURRENT.txt"
  echo INSTALLED > "$TEST_ROOT/workspace/public-release-1.0.3/STATUS"
  exit 0;;
*) exit 9;;
esac''')
        (self.root / 'usb-info').write_text('mounted=1\nroot=' + str(self.usb) + '\ntype=execute\n')
        self.pkg(0, '')

    def tool(self, name, body):
        path = self.bin / name
        path.write_text('#!/bin/sh\n' + body + '\n')
        path.chmod(0o755)

    def pkg(self, installed, version):
        (self.root / 'pkg-info').write_text('id=hmgapi.neev-usb-carplay\npackage-version=1.0.3\ninstalled=' + str(installed) + '\ninstalled-version=' + version + '\ncompat=full\n')

    def installed(self, version='1.0.3', state='INSTALLED'):
        self.pkg(1, version)
        self.cp.mkdir(parents=True, exist_ok=True)
        record = self.public if version == '1.0.3' else self.w / ('public-' + version)
        record.mkdir(parents=True, exist_ok=True)
        (self.w / 'UPGRADE_CURRENT.txt').write_text(version + '\n')
        (record / 'STATUS').write_text(state + '\n')
        (self.cp / 'keep').write_text('unchanged signed mod fixture')

    def invoke(self, expected, rc=0, extra=None, args=()):
        descriptor = (self.usb / 'NEMESIS.data').read_bytes()
        before = set(self.usb.glob('USB_AUTO_RESULT_*'))
        result = subprocess.run(['/bin/sh', str(self.script), *args], capture_output=True,
                                env={**os.environ, 'TEST_ROOT': str(self.root), **(extra or {})})
        self.assertEqual(result.returncode, rc, result.stdout + result.stderr)
        reports = set(self.usb.glob('USB_AUTO_RESULT_*')) - before
        if expected is None:
            self.assertFalse(reports)
        else:
            self.assertEqual(len(reports), 1)
            report = reports.pop()
            self.assertEqual((report / 'STATUS.txt').read_text().strip(), expected, (report / 'RUN.log').read_text())
        self.assertEqual((self.usb / 'NEMESIS.data').read_bytes(), descriptor)

    def no_calls(self):
        self.assertFalse(self.calls.exists())

    def legacy_descriptor(self, version='1.0.14'):
        releases = {
            '1.0.13': 'c6ba250a209dcc33258a80420ba06bf1c72299ae4e82c069d98801308dc9d7b9',
            '1.0.14': '3d78e92c928d745704275cde94006e1eaaef947254c1d0e30329fda048bd10c7',
        }
        project = next(p for p in SOURCE.parents if (p / '배포용').is_dir())
        release = project / '배포용' / version / ('NEEV_260902_CarPlay_' + version + '_USB.zip')
        self.assertEqual(hashlib.sha256(release.read_bytes()).hexdigest(), releases[version])
        self.installed(version)
        self.pkg(1, '1.0.12')
        record = self.public if version == '1.0.3' else self.w / ('public-' + version)
        with ZipFile(release) as archive:
            for name in archive.namelist():
                if name.startswith('NEEV_CP/') and not name.endswith('/'):
                    dest = record / 'kit' / name[len('NEEV_CP/'):]
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    dest.write_bytes(archive.read(name))
        shutil.rmtree(self.cp)
        shutil.copytree(record / 'kit/payload/mod', self.cp)
        (self.cp / '.features').write_text('engine-generated marker')
        # Production uses HMG Signing.verify. The host stand-in checks all signed
        # content/inventory and an explicit signature failure; it is not a crypto test.
        verifier = self.bin / 'app_process'
        verifier.write_text('''#!/usr/bin/env python3
import hashlib,json,os,sys
from pathlib import Path
root=Path(os.environ['TEST_ROOT'])
with (root/'signature-calls').open('a') as f:f.write('verify\\n')
assert sys.argv[2:4]==['hmgapi.engine.Signing','verify']
assert sys.argv[-1]=='bd5f651c-38f9-8ef4-827c-34ad706c2b5c'
p=Path(sys.argv[-2]);m=json.loads((p/'META-HMGAPI/manifest.json').read_text())
files={str(f.relative_to(p)) for f in p.rglob('*') if f.is_file()}
assert files==set(m['files'])|{'META-HMGAPI/manifest.json','META-HMGAPI/signature'}
assert all(hashlib.sha256((p/name).read_bytes()).hexdigest()==value for name,value in m['files'].items())
assert not os.environ.get('SIGNATURE_FAIL')
''')
        verifier.chmod(0o755)
        return record







    def without_printf(self):
        # Android's target shell has no printf builtin/executable. Override the
        # host builtin as well, so passing on macOS cannot hide that dependency.
        text = self.script.read_text()
        first, rest = text.split('\n', 1)
        self.script.write_text(first + '''
printf() { echo 'printf: not found (vehicle compatibility fixture)' >&2; return 127; }
''' + rest)


































if __name__ == '__main__':
    unittest.main(verbosity=2)
