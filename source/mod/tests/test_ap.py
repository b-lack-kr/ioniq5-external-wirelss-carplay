"""Simulate the public supervisor on a temporary filesystem (no host network changes)."""
import os
import re
import shutil
import signal
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

SOURCE = Path(__file__).resolve().parents[1]
DRIVERS = '2357:0115 88x2bu\n2357:012d 88x2bu\n2357:012e 88x2bu\n2357:0138 88x2bu\n0bda:b851 8851bu\n'
PASS = 'A10b_f9FakeSyntheticPassword'

class RadioTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.usb = self.path('/sys/bus/usb/devices')
        self.driver = self.path('/proc/modules')
        self.tmp = self.path('/data/local/tmp/neev_usbcp')
        self.work = self.path('/data/misc/wifi/neev_usbcp')
        self.base = self.path('/data/nemesis/neev-usbcp')
        self.app = self.path('/data/hmgapi/mods/neev-usb-carplay/ap')
        self.vendor = self.base/'vendor/hkg-2.0.2'
        self.tools = self.root/'fakebin'
        for folder in (self.usb, self.driver.parent, self.app,self.tools,self.base/'config',
                       self.vendor/'modules',self.vendor/'bin',self.path('/data/local/tmp'),
                       self.path('/proc/sys/kernel'),self.path('/system/bin')):
            folder.mkdir(parents=True,exist_ok=True)
        for file in ('modules/88x2bu.ko','modules/8851bu.ko','bin/usbwifistatus.jar'):
            (self.vendor/file).write_text('asset')
        self.driver.write_text('')
        (self.root/'boot-ready').touch()
        self.path('/proc/sys/kernel/osrelease').write_text('3.18.24-tcc\n')
        (self.base/'config/hotspot.conf').write_text('AP_SSID=Hyundai_123456\nAP_PASS='+PASS+'\n')
        (self.app/'driver-ids').write_text(DRIVERS)
        script=(SOURCE/'ap/service.sh').read_text()
        # Make every absolute target path point at this fixture.
        script=re.sub(r'/(?:data|proc|sys|system)/',lambda m:str(self.root)+m.group(),script)
        script=re.sub(r'^export PATH=.*$',f'export PATH={self.tools}:/usr/bin:/bin',script,flags=re.M)
        self.script=self.root/'service'
        self.script.write_text(script)
        self.cmd('getprop', '''case "$1" in ro.build.product) echo NEEV.KOR.0000.V039.001.260902;; ro.board.platform) echo tcc803x;; sys.boot_completed) [ ! -f "$ROOT/boot-ready" ] || echo 1;; esac''')
        self.cmd('chown','''[ "$#" = 2 ] && [ -e "$2" ] || exit 10
case "$1" in 0:0|1010:1010) ;; *) exit 10;; esac
echo "$*" >> "$ROOT/chown"''')
        self.cmd('md5','''[ -s "$1" ] || exit 1
case "${1##*/}" in 88x2bu.ko) sum=dc2f643ceb8d5739d70960a54507fe8f;; 8851bu.ko) sum=f127be464175f901a374554236c147d7;; usbwifistatus.jar) sum=f21b1f06c8b3655b6bea4ed7d19d9b59;; *) exit 1;; esac
echo "$sum  $1"''')
        self.cmd('iptables','echo "$*" >> "$ROOT/firewall"')
        self.cmd('ip','''echo "$*" >> "$ROOT/network"
case "$*" in '-4 addr show dev rtwlan0') [ ! -f "$ROOT/address" ] || echo '    inet 192.168.43.1/24 brd 192.168.43.255';; 'addr add '*) touch "$ROOT/address";; 'addr del '*) rm -f "$ROOT/address";; esac''')
        self.cmd('insmod','''name=${1##*/}; family=${name%.ko}
echo "$*" >> "$ROOT/driver"
[ ! -f "$ROOT/fail-driver-$family" ] || exit 1
echo "$family 1 0" >> "$ROOT/proc/modules"''')
        self.cmd('am','echo "$*" >> "$ROOT/messages"')
        self.cmd('sleep','/bin/sleep 0.03')
        patcher=self.path('/system/bin/app_process')
        patcher.write_text('#!/bin/sh\n[ "$3" = patch-hostapd ] || exit 4\necho patched > "$5"\n')
        patcher.chmod(0o755)
        runtime=(SOURCE/'ap/runtime.sh').read_text()+'''
ap_guard() { [ -d "$ROOT/sys/bus/usb/devices/device" ] || return 1
    [ "$1" = check ] || echo "rtwlan0 35 0000000000000000000000000000000000000000000000000000000000000011"
}
ap_stop_children() { rm -f "$ROOT/live-hostapd" "$ROOT/live-dnsmasq"; }
ap_start_logged() { echo "$*" >> "$ROOT/launch"; touch "$ROOT/live-$1"; }
ap_running() { [ -f "$ROOT/live-$1" ] && [ ! -f "$ROOT/fail-$1" ]; }
'''
        (self.app/'runtime.sh').write_text(runtime)
        self.child=None
        self.supervisor=self.root/'supervisor.log'
        self.addCleanup(self.stop)
    def path(self,name): return self.root/name.lstrip('/')
    def cmd(self,name,body):
        f=self.tools/name
        f.write_text('#!/bin/sh\n'+body+'\n');f.chmod(0o755)
    def start(self):
        with self.supervisor.open('a') as out:
            self.child=subprocess.Popen(['/bin/bash',str(self.script)],env=dict(os.environ,ROOT=str(self.root)),
                                        stdout=out,stderr=subprocess.STDOUT,start_new_session=True)
    def stop(self):
        if self.child and self.child.poll() is None:
            self.child.terminate()
            try: self.child.wait(2)
            except subprocess.TimeoutExpired:
                os.killpg(self.child.pid,signal.SIGKILL);self.child.wait()
    def attach(self,vid='2357',pid='012e',name='device'):
        d=self.usb/name;d.mkdir()
        (d/'idVendor').write_text(vid+'\n');(d/'idProduct').write_text(pid+'\n')
        index=self.path('/sys/class/net/rtwlan0/ifindex')
        index.parent.mkdir(parents=True,exist_ok=True);index.write_text('35\n')
    def wait_for(self,check):
        end=time.monotonic()+5
        while time.monotonic()<end:
            if check(): return
            time.sleep(.01)
        self.fail((self.child.poll(), self.log()))
    def log(self):
        p=self.tmp/'daemon_stdout.log'
        return p.read_text() if p.is_file() else ''
    def phase(self,name):
        self.wait_for(lambda: ('AP_EVENT state='+name+'\n') in self.log())
    def messages(self):
        p=self.root/'messages'
        return p.read_text() if p.is_file() else ''
    def test_boot_attach_detach(self):
        self.start();self.phase('WAIT_DONGLE')
        self.assertEqual(self.messages(),'')
        self.attach();self.phase('SERVICES_RUNNING')
        self.assertEqual(self.messages(),'')
        self.assertIn('88x2bu.ko', (self.root/'driver').read_text())
        self.assertIn('8851bu.ko',(self.root/'driver').read_text())
        conf=(self.work/'hostapd.conf').read_text()
        for s in ['interface=rtwlan0','hw_mode=a','channel=36','ssid=Hyundai_123456','wpa_passphrase='+PASS]:
            self.assertIn(s+'\n',conf)
        self.assertEqual((self.work/'hostapd.conf').stat().st_mode & 0o777,0o600)
        self.assertIn('--dhcp-alternate-port=1067,68',(self.root/'launch').read_text())
        self.assertIn('--interface=rtwlan0',(self.root/'launch').read_text())
        self.assertIn('table 61',(self.root/'network').read_text())
        self.assertNotIn('wlan0 ',(self.root/'network').read_text().replace('rtwlan0','USB'))
        self.assertNotIn(PASS,self.log())
        shutil.rmtree(self.usb/'device');self.path('/sys/class/net/rtwlan0/ifindex').unlink()
        self.wait_for(lambda:self.log().count('AP_EVENT state=WAIT_DONGLE\n')==2)
        self.assertEqual(self.messages(),'');self.assertFalse((self.root/'live-hostapd').exists())
    def test_per_attach_failures(self):
        self.attach();(self.root/'fail-hostapd').touch();self.start()
        self.wait_for(lambda:self.log().count('AP_EVENT state=HOSTAPD_EXITED\n')>=2)
        self.assertEqual(self.messages().count('시작 실패'),1)
        self.assertNotIn(PASS,self.messages())
        (self.root/'fail-hostapd').unlink();self.phase('SERVICES_RUNNING')
        self.assertEqual(self.messages().count('TOAST_MESSAGE'),1)
    def test_ax_selection(self):
        self.attach('0bda','b851');self.start();self.phase('SERVICES_RUNNING')
        self.assertIn('8851bu.ko',(self.root/'driver').read_text())
        self.assertIn('88x2bu.ko',(self.root/'driver').read_text())
    def test_mixed_or_unsupported(self):
        self.attach('ffff','ffff','unknown');self.start();self.phase('WAIT_DONGLE')
        self.assertTrue((self.root/'driver').exists())
        self.assertFalse((self.root/'launch').exists())
        self.attach();self.attach('0bda','b851','second');self.phase('IDENTITY_REJECTED')
        self.assertFalse((self.root/'launch').exists())
    def test_invalid_asset_rejected_before_load(self):
        (self.vendor/'bin/usbwifistatus.jar').write_text('')
        self.attach();self.start();self.phase('VENDOR_ASSETS_INVALID')
        self.assertFalse((self.root/'driver').exists());self.assertFalse((self.root/'launch').exists())
    def test_config_never_executes(self):
        (self.base/'config/hotspot.conf').write_text('AP_SSID=Hyundai_123456\nAP_PASS=$(touch "$ROOT/injected")\n')
        self.attach();self.start();self.phase('SERVICES_RUNNING')
        self.assertFalse((self.root/'injected').exists())
        self.assertIn('wpa_passphrase=$(touch "$ROOT/injected")',(self.work/'hostapd.conf').read_text())

    def assert_fresh_t3u(self, pid):
        # Nothing from a prior beta install is present: importer-owned assets and
        # fresh credentials alone must be sufficient to create the whole AP tree.
        self.assertFalse(self.tmp.exists())
        self.assertFalse(self.work.exists())
        self.attach('2357', pid)
        self.start();self.phase('SERVICES_RUNNING')
        self.assertEqual(self.messages(), '')
        self.assertEqual((self.tmp.stat().st_mode & 0o777), 0o751)
        self.assertEqual((self.work.stat().st_mode & 0o777), 0o700)
        self.assertEqual(((self.work/'ctrl').stat().st_mode & 0o777), 0o700)
        self.assertEqual(((self.work/'hostapd.conf').stat().st_mode & 0o777), 0o600)
        self.assertEqual(((self.work/'entropy.bin').stat().st_mode & 0o777), 0o600)
        self.assertIn('AP_DRIVER state=ready family=88x2bu', self.log())
        self.assertIn('AP_STARTUP version=1.0.1 phase=ENTRY', self.supervisor.read_text())
    def test_fresh_t3u_012d(self): self.assert_fresh_t3u('012d')
    def test_fresh_t3u_0138(self): self.assert_fresh_t3u('0138')
    def test_fresh_t3u_0115(self): self.assert_fresh_t3u('0115')
    def test_driver_preload_precedes_android(self):
        self.attach('2357', '0138');(self.root/'boot-ready').unlink()
        self.start();self.phase('WAIT_ANDROID')
        self.assertIn('88x2bu ', self.driver.read_text())
        self.assertIn('8851bu ', self.driver.read_text())
        self.assertFalse((self.root/'launch').exists())
        (self.root/'boot-ready').touch();self.phase('SERVICES_RUNNING')
        self.assertEqual(self.messages(), '')
    def test_reboot_with_existing_runtime_files(self):
        self.attach();self.start();self.phase('SERVICES_RUNNING');self.stop()
        config=(self.base/'config/hotspot.conf').read_bytes()
        # Kernel state resets at reboot, files under /data remain.
        self.driver.write_text('')
        (self.root/'address').unlink(missing_ok=True);(self.root/'launch').unlink()
        (self.root/'boot-ready').unlink()
        self.start()
        self.wait_for(lambda:self.log().count('AP_EVENT state=WAIT_ANDROID\n')==2)
        self.assertFalse((self.root/'launch').exists())
        (self.root/'boot-ready').touch()
        self.wait_for(lambda:self.log().count('AP_EVENT state=SERVICES_RUNNING\n')==2)
        self.assertEqual((self.base/'config/hotspot.conf').read_bytes(),config)
        self.assertEqual((self.root/'driver').read_text().count('88x2bu.ko'),2)
        self.assertEqual(self.messages(), '')
    def test_retry_failed_driver_when_android_ready(self):
        self.attach();(self.root/'boot-ready').unlink()
        failing=self.root/'fail-driver-88x2bu';failing.touch()
        self.start();self.phase('WAIT_ANDROID')
        self.assertIn('AP_DRIVER state=failed family=88x2bu',self.log())
        self.assertIn('8851bu ',self.driver.read_text())
        failing.unlink();(self.root/'boot-ready').touch();self.phase('SERVICES_RUNNING')
        self.assertIn('88x2bu ',self.driver.read_text())
        self.assertEqual(self.messages(), '')
    def test_usb_detection_does_not_call_grep(self):
        # USB identification used to depend on grep parsing a shell table.
        # Refuse only detector-shaped grep calls; network-state grep is unchanged.
        self.cmd('grep', '''case "$*" in *'2357:'*|*'0bda:'*|*'/proc/modules'*) exit 88;; esac
exec /usr/bin/grep "$@"''')
        self.attach('2357', '0138');self.start();self.phase('SERVICES_RUNNING')
        self.assertEqual(self.messages(),'')
    def test_unrelated_old_link_does_not_block_startup(self):
        self.tmp.mkdir(parents=True)
        link=self.tmp/'old-debug-link';link.symlink_to(self.root/'missing-debug-target')
        self.attach();self.start();self.phase('SERVICES_RUNNING')
        self.assertTrue(link.is_symlink());self.assertEqual(self.messages(), '')
    def test_owned_link_is_refused_and_failure_is_logged(self):
        self.tmp.mkdir(parents=True)
        target=self.root/'do-not-touch';target.write_text('unchanged')
        (self.tmp/'daemon_stdout.log').symlink_to(target)
        self.attach();self.start()
        self.wait_for(lambda:self.child.poll() is not None)
        self.assertEqual(self.child.returncode,3)
        self.assertIn('AP_STARTUP_FAILURE reason=PREPARE_PATHS',self.supervisor.read_text())
        self.assertEqual(target.read_text(),'unchanged')
        self.assertFalse((self.root/'driver').exists())

if __name__=='__main__': unittest.main(verbosity=2)
