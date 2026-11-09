"""Simulate the public supervisor on a temporary filesystem (no host network changes)."""
import os
import re
import shutil
import signal
import sys
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

SOURCE = Path(__file__).resolve().parents[1]
DRIVERS = '2357:0115 88x2bu\n2357:012d 88x2bu\n2357:012e 88x2bu\n2357:0138 88x2bu\n0bda:b851 8851bu\n'
PASS = 'A10b_f9FakeSyntheticPassword'
BOOT = '12345678-1234-1234-1234-123456789abc'
OTHER_BOOT = '22345678-1234-1234-1234-123456789abc'
EVENT_NS = '1234567890123456'

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
                       self.path('/proc/sys/kernel/random'),self.path('/system/bin')):
            folder.mkdir(parents=True,exist_ok=True)
        for file in ('modules/88x2bu.ko','modules/8851bu.ko','bin/usbwifistatus.jar'):
            (self.vendor/file).write_text('asset')
        self.driver.write_text('')
        (self.root/'boot-ready').touch()
        self.path('/proc/sys/kernel/osrelease').write_text('3.18.24-tcc\n')
        self.path('/proc/sys/kernel/random/boot_id').write_text(BOOT+'\n')
        self.path('/proc/uptime').write_text('2000000.25 4000000.00\n')
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
[ "$*" != 'link set rtwlan0 down' ] || [ ! -f "$ROOT/fail-link-down" ] || exit 1
case "$*" in '-4 addr show dev rtwlan0') [ ! -f "$ROOT/address" ] || echo '    inet 192.168.43.1/24 brd 192.168.43.255';; 'addr add '*) touch "$ROOT/address";; 'addr del '*) rm -f "$ROOT/address";; esac''')
        self.cmd('insmod','''name=${1##*/}; family=${name%.ko}
echo "$*" >> "$ROOT/driver"
[ ! -f "$ROOT/fail-driver-$family" ] || exit 1
echo "$family 1 0" >> "$ROOT/proc/modules"''')
        self.cmd('am','echo "$*" >> "$ROOT/messages"')
        self.cmd('wc','exit 127')
        # Match this car's verified Android toolbox layout (no link-count
        # column). Desktop ls has a different layout and is not proof of ABI.
        (self.tools/'ls').write_text('#!'+sys.executable+'\nimport os,sys,stat\n'
            'assert len(sys.argv)==3 and sys.argv[1]=="-l"\n'
            'p=sys.argv[2];m=os.stat(p)\nprint("%s root root %d 2026-11-08 16:00 %s" % (stat.filemode(m.st_mode),m.st_size,p))\n')
        (self.tools/'ls').chmod(0o755)
        self.cmd('sleep','/bin/sleep 0.03')
        patcher=self.path('/system/bin/app_process')
        patcher.write_text('#!/bin/sh\n'
                          'if [ "$2" = kr.ioniq5.usbcp.ExternalDongleSetting ]; then\n'
                          '  if [ -f "$ROOT/external-invalid" ]; then echo INVALID; exit 2; fi\n'
                          '  if [ -f "$ROOT/external-off" ]; then echo OFF; else echo ON; fi\n'
                          '  exit 0\nfi\n'
                          'if [ "$2" = kr.ioniq5.usbcp.HeadUnitPower ]; then\n'
                          '  printf "%s\\n" "$CLASSPATH" > "$ROOT/power-classpath"\n'
                          '  exit 4\nfi\n'
                          '[ "$3" = patch-hostapd ] || exit 4\necho patched > "$5"\n')
        patcher.chmod(0o755)
        runtime=re.sub(r'/(?:proc|system)/', lambda m:str(self.root)+m.group(), (SOURCE/'ap/runtime.sh').read_text())+'''
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
    def power_log(self):
        return ''.join(p.read_text() for p in [self.tmp/'power-decision.log.1',self.tmp/'power-decision.log'] if p.is_file())
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
    def test_external_off_before_driver_and_android(self):
        self.attach();(self.root/'external-off').touch();(self.root/'boot-ready').unlink()
        original=(self.base/'config/hotspot.conf').read_bytes()
        self.start();self.phase('EXTERNAL_DONGLE_OFF')
        time.sleep(.1)
        self.assertIsNone(self.child.poll())
        self.assertFalse((self.root/'driver').exists());self.assertFalse((self.root/'launch').exists())
        self.assertEqual((self.base/'config/hotspot.conf').read_bytes(),original)
        self.assertFalse((self.root/'network').exists());self.assertEqual(self.messages(),'')
    def test_external_invalid_dormant_no_restart_storm(self):
        self.attach();(self.root/'external-invalid').touch()
        self.start();self.phase('EXTERNAL_CONFIG_INVALID');time.sleep(.1)
        self.assertIsNone(self.child.poll());self.assertFalse((self.root/'driver').exists())
        self.assertFalse((self.root/'launch').exists());self.assertEqual(self.messages(),'')
    def test_external_setting_applies_only_after_restart(self):
        self.attach();(self.root/'external-off').touch()
        self.start();self.phase('EXTERNAL_DONGLE_OFF');(self.root/'external-off').unlink()
        time.sleep(.1);self.assertFalse((self.root/'launch').exists())
        self.stop();self.start();self.phase('SERVICES_RUNNING')
        (self.root/'external-off').touch();time.sleep(.1)
        self.assertTrue((self.root/'live-hostapd').exists())
        self.stop();self.assertFalse((self.root/'live-hostapd').exists())
        self.start();self.wait_for(lambda:self.log().count('AP_EVENT state=EXTERNAL_DONGLE_OFF\n')==2)
        self.assertFalse((self.root/'live-hostapd').exists())
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
        self.assertIn('AP_STARTUP version=1.0.3 phase=ENTRY', self.supervisor.read_text())
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

    def manual_recovery(self, boot=BOOT, stamp='1000'):
        marker = self.base/'manual-ap-recovery'
        marker.write_text(f'{boot} {stamp}\n')
        marker.chmod(0o600)
        return marker

    def test_manual_recovery_waits_for_new_reader_then_preserves_credentials(self):
        self.mock_power_reader();self.power('ON');self.manual_recovery()
        before=(self.base/'config/hotspot.conf').read_bytes()
        self.attach();self.start();self.phase('WAIT_POWER_READER')
        self.assertFalse((self.root/'launch').exists())
        self.assertFalse((self.root/'driver').exists())
        # An initial old ON file is fresh but predates this service instance.
        time.sleep(.1);self.assertFalse((self.root/'launch').exists())
        self.path('/proc/uptime').write_text('1003.25 2000.00\n')
        self.power('ON',stamp='1002',decision='0');self.phase('SERVICES_RUNNING')
        self.assertEqual((self.base/'config/hotspot.conf').read_bytes(),before)
        self.assertIn('AP_MANUAL_RECOVERY state=POWER_CONFIRMED',self.log())

    def test_manual_recovery_unknown_or_dead_reader_never_starts_radio(self):
        self.mock_power_reader();self.manual_recovery();self.power('UNKNOWN')
        self.attach();self.start();self.phase('WAIT_POWER_READER')
        self.assertFalse((self.root/'launch').exists())
        self.assertFalse((self.root/'driver').exists())
        self.path('/proc/uptime').write_text('1003.25 2000.00\n')
        self.power('ON',stamp='999',decision='0');time.sleep(.15)
        self.assertFalse((self.root/'launch').exists())

    def test_manual_recovery_goodbye_off_remains_dominant(self):
        self.mock_power_reader();self.manual_recovery();self.event('OFF','1000000000000')
        self.power('ON',stamp='1000',decision='0');self.attach();self.start();self.phase('HU_POWER_OFF')
        event=(self.tmp/'power-event').read_bytes()
        self.path('/proc/uptime').write_text('1003.25 2000.00\n')
        self.power('ON',stamp='1002',decision='0');time.sleep(.15)
        self.assertFalse((self.root/'launch').exists())
        self.assertEqual((self.tmp/'power-event').read_bytes(),event)
        # Only an actual newer power ON decision releases the ordinary OFF gate.
        self.event('ON','1002000000000');self.phase('SERVICES_RUNNING')

    def test_manual_recovery_old_boot_does_not_change_normal_boot(self):
        self.manual_recovery(OTHER_BOOT,'999999999')
        self.attach();self.start();self.phase('SERVICES_RUNNING')
        self.assertNotIn('WAIT_POWER_READER',self.log())

    def test_manual_recovery_future_request_refuses_radio(self):
        self.mock_power_reader();self.manual_recovery(stamp='1001')
        self.attach();self.start();self.wait_for(lambda:self.child.poll() is not None)
        self.assertEqual(self.child.returncode,3)
        self.assertIn('MANUAL_RECOVERY_INVALID',self.supervisor.read_text())
        self.assertFalse((self.root/'launch').exists())

    def test_manual_recovery_permissions_and_extra_lines_refuse_radio(self):
        self.mock_power_reader();marker=self.manual_recovery();marker.chmod(0o644)
        self.attach();self.start();self.wait_for(lambda:self.child.poll() is not None)
        self.assertEqual(self.child.returncode,3);self.assertFalse((self.root/'launch').exists())
        marker.chmod(0o600);marker.write_text(f'{BOOT} 1000\nextra\n')
        self.start();self.wait_for(lambda:self.child.poll() is not None)
        self.assertEqual(self.child.returncode,3);self.assertFalse((self.root/'launch').exists())

    def test_manual_recovery_symlink_refuses_without_writing_target(self):
        target=self.root/'keep';target.write_text('unchanged')
        (self.base/'manual-ap-recovery').symlink_to(target)
        self.attach();self.start();self.wait_for(lambda:self.child.poll() is not None)
        self.assertEqual(self.child.returncode,3)
        self.assertEqual(target.read_text(),'unchanged')
        self.assertFalse((self.root/'launch').exists())

    def test_manual_recovery_live_radio_does_not_read_new_request(self):
        self.attach();self.start();self.phase('SERVICES_RUNNING')
        launches=(self.root/'launch').read_text()
        self.manual_recovery(stamp='2000000');time.sleep(.15)
        self.assertEqual((self.root/'launch').read_text(),launches)
        self.assertNotIn('WAIT_POWER_READER',self.log())

    def mock_power_reader(self):
        # Exercise the real freshness/parser/gate functions. Only the Android
        # reader process is replaced; no host power or networking is accessed.
        with (self.app/'runtime.sh').open('a') as out:
            out.write('\nap_power_start() { POWER_PID=987654; }\nap_power_stop() { :; }\n'
                      'ap_child_live() { [ "$1" = 987654 ]; }\n')
        self.path('/proc/uptime').write_text('1000.25 2000.00\n')

    def power(self, value, raw='0', stamp='1000', decision=None, boot=BOOT):
        self.tmp.mkdir(parents=True,exist_ok=True)
        if decision is None:
            if value != getattr(self, 'power_value', None):
                seq = getattr(self, 'power_seq', 0) + 1
                self.power_seq = seq
                self.power_decision = (0 if value == 'ON' and seq == 1 else
                                       int(stamp)*1000000000+seq if stamp.isdigit() else 0)
            decision = self.power_decision
        self.power_value = value
        pending = self.tmp/'fixture-power.new'
        pending.write_text(f'{stamp} {value} {raw} {boot} {decision} FIXTURE\n')
        pending.replace(self.tmp/'power-state')

    def test_power_off_stops_owned_ap_and_on_resumes(self):
        self.mock_power_reader();self.power('ON');self.attach();self.start();self.phase('SERVICES_RUNNING')
        credentials=(self.base/'config/hotspot.conf').read_bytes()
        self.power('OFF','6');self.phase('HU_POWER_OFF')
        self.assertFalse((self.root/'live-hostapd').exists())
        self.assertFalse((self.root/'live-dnsmasq').exists())
        self.assertIn('link set rtwlan0 down\n',(self.root/'network').read_text())
        launches=(self.root/'launch').read_text();time.sleep(.15)
        self.assertEqual((self.root/'launch').read_text(),launches)
        self.power('ON')
        self.wait_for(lambda:self.log().count('AP_EVENT state=SERVICES_RUNNING\n')==2)
        self.assertEqual((self.base/'config/hotspot.conf').read_bytes(),credentials)
        self.assertEqual(self.messages(),'')

    def test_delay_mode_alone_and_unknown_do_not_disconnect(self):
        self.mock_power_reader();self.power('ON','6');self.attach();self.start();self.phase('SERVICES_RUNNING')
        self.power('UNKNOWN','unknown');time.sleep(.15)
        self.assertTrue((self.root/'live-hostapd').exists())
        self.assertNotIn('HU_POWER_OFF',self.log())

    def test_power_off_at_boot_waits_for_explicit_on(self):
        self.mock_power_reader();self.power('OFF','6');self.attach();self.start();self.phase('HU_POWER_OFF')
        self.assertFalse((self.root/'launch').exists())
        self.power('UNKNOWN','unknown');time.sleep(.15)
        self.assertFalse((self.root/'launch').exists())
        self.power('ON');self.phase('SERVICES_RUNNING')

    def test_future_or_malformed_samples_do_not_stop_ap(self):
        self.mock_power_reader();self.power('ON');self.attach();self.start();self.phase('SERVICES_RUNNING')
        for stamp,value in [('1001','OFF'),('x','OFF'),('1000','UNRECOGNIZED')]:
            self.power(value,stamp=stamp);time.sleep(.1)
            self.assertTrue((self.root/'live-hostapd').exists())
            self.assertNotIn('HU_POWER_OFF',self.log())

    def test_power_off_does_not_lower_a_detached_interface(self):
        self.mock_power_reader();self.power('ON');self.attach();self.start();self.phase('SERVICES_RUNNING')
        shutil.rmtree(self.usb/'device')
        self.power('OFF','6');self.phase('HU_POWER_OFF')
        self.assertNotIn('link set rtwlan0 down\n',(self.root/'network').read_text())

    def test_failed_link_down_does_not_block_on_recovery(self):
        self.mock_power_reader();self.power('ON');self.attach();self.start();self.phase('SERVICES_RUNNING')
        (self.root/'fail-link-down').touch()
        self.power('OFF','6');self.phase('HU_POWER_OFF')
        self.assertFalse((self.root/'live-hostapd').exists())
        self.assertIn('AP_LINK_DOWN_FAILED iface=rtwlan0',self.log())
        self.power('ON')
        self.wait_for(lambda:self.log().count('AP_EVENT state=SERVICES_RUNNING\n')==2)
        self.assertTrue((self.root/'live-hostapd').exists())

    def test_only_power_reader_gets_automotive_library(self):
        self.attach();self.start();self.phase('SERVICES_RUNNING')
        self.wait_for(lambda:(self.root/'power-classpath').exists())
        expected=str(self.app/'../provider.jar')+':'+str(self.path('/system/framework/automotive.jar'))
        self.assertEqual((self.root/'power-classpath').read_text().strip(),expected)
        runtime=(SOURCE/'ap/runtime.sh').read_text()
        guard=runtime.split('ap_guard() {',1)[1].split('\n}',1)[0]
        self.assertNotIn('automotive.jar',guard)
        self.assertNotIn('export BOOTCLASSPATH',runtime)

    def event(self, value, nanos=EVENT_NS, boot=BOOT):
        self.tmp.mkdir(parents=True,exist_ok=True)
        pending=self.tmp/'fixture-event.new'
        pending.write_text(f'{boot} {nanos} {value}\n')
        pending.replace(self.tmp/'power-event')

    def test_oem_off_survives_false_getter_and_service_restart(self):
        self.mock_power_reader();self.power('ON',stamp='2000000')
        self.path('/proc/uptime').write_text('2000000.25 4000000.00\n')
        self.attach();self.start();self.phase('SERVICES_RUNNING')
        original=(self.base/'config/hotspot.conf').read_bytes()
        self.event('OFF');self.phase('HU_POWER_OFF')
        self.wait_for(lambda:not (self.root/'live-hostapd').exists())
        launches=(self.root/'launch').read_text()
        self.power('ON',stamp='2000000');time.sleep(.15)
        self.assertEqual((self.root/'launch').read_text(),launches)
        self.stop();self.start();time.sleep(.2)
        self.assertEqual((self.root/'launch').read_text(),launches)
        self.event('ON',str(int(EVENT_NS)+1000))
        self.wait_for(lambda:self.log().count('AP_EVENT state=SERVICES_RUNNING\n')==2)
        self.assertEqual((self.base/'config/hotspot.conf').read_bytes(),original)
        self.assertEqual(self.messages(),'')

    def test_explicit_on_outranks_old_getter_off(self):
        self.mock_power_reader();self.power('OFF',stamp='2000000', decision=str(int(EVENT_NS)-1000))
        self.path('/proc/uptime').write_text('2000000.25 4000000.00\n')
        self.event('ON');self.attach();self.start();self.phase('SERVICES_RUNNING')
        self.assertNotIn('HU_POWER_OFF',self.log())

    def test_stale_oem_on_does_not_mask_new_reader_off(self):
        self.mock_power_reader();self.power('ON',stamp='2000000')
        self.path('/proc/uptime').write_text('2000000.25 4000000.00\n')
        self.event('ON');self.attach();self.start();self.phase('SERVICES_RUNNING')
        self.power('OFF',stamp='2000000',decision=str(int(EVENT_NS)+1000))
        self.phase('HU_POWER_OFF')
        self.assertRegex(self.power_log(),r'AP_POWER_DECISION paused=1 source=READER decision_ns=[0-9]+ event=VALID_ON event_ns=[0-9]+ reader=VALID reader_state=OFF')
        self.wait_for(lambda:not (self.root/'live-hostapd').exists())
        launches=(self.root/'launch').read_text()
        self.stop();self.start();time.sleep(.2)
        self.assertEqual((self.root/'launch').read_text(),launches)
        self.event('ON',str(int(EVENT_NS)+2000))
        self.wait_for(lambda:self.log().count('AP_EVENT state=SERVICES_RUNNING\n')==2)

    def test_power_diagnostic_stable_inputs_do_not_flood(self):
        self.mock_power_reader();self.power('ON');self.attach();self.start();self.phase('SERVICES_RUNNING')
        time.sleep(.3)
        lines=[line for line in self.power_log().splitlines() if line.startswith('AP_POWER_DECISION ')]
        self.assertEqual(len(lines),1)
        self.assertIn('event=NONE',lines[0]);self.assertIn('reader=VALID reader_state=ON',lines[0])
        self.assertNotIn('AP_POWER_DECISION ',self.log())
        self.power('UNKNOWN','unknown');self.wait_for(lambda:'reader=INVALID reader_state=UNKNOWN' in self.power_log())
        self.assertTrue((self.root/'live-hostapd').exists())

    def test_power_diagnostic_file_bounds_and_symlinks(self):
        self.tmp.mkdir(parents=True,exist_ok=True)
        current=self.tmp/'power-decision.log';backup=self.tmp/'power-decision.log.1'
        line='AP_POWER_DECISION paused=1 source=READER'
        def append(value=line):
            run=subprocess.run(['/bin/bash','-c','printf() { return 127; }; . "$1"; TMP_DIR=$2; ap_power_trace_file "$3"','test',str(self.app/'runtime.sh'),str(self.tmp),value],capture_output=True,text=True,env=dict(os.environ,PATH=str(self.tools)+':/usr/bin:/bin'))
            self.assertEqual(run.returncode,0);self.assertEqual(run.stdout,'')
        append();self.assertEqual(current.read_text(),line+'\n')
        self.assertEqual(current.stat().st_mode & 0o777,0o600)
        current.write_bytes(b'x'*65535);append()
        self.assertEqual(backup.stat().st_size,65535);self.assertEqual(current.read_text(),line+'\n')
        self.assertEqual(backup.stat().st_mode & 0o777,0o600)
        current.write_bytes(b'x'*65537);backup.write_bytes(b'x'*65537);append()
        self.assertLessEqual(current.stat().st_size,65536);self.assertFalse(backup.exists())
        before=current.read_bytes();append(line+'\nforged');append(line+'x'*2050)
        self.assertEqual(current.read_bytes(),before)
        target=self.root/'unrelated';target.write_text('untouched')
        current.unlink();current.symlink_to(target);append();self.assertEqual(target.read_text(),'untouched')
        current.unlink();backup.symlink_to(target);append();self.assertFalse(current.exists())
        self.assertEqual(target.read_text(),'untouched')

    def test_reader_checkpoint_off_survives_stale_heartbeat_and_restart(self):
        self.mock_power_reader();self.power('OFF',stamp='900',decision='900000000000')
        self.attach();self.start();self.phase('HU_POWER_OFF')
        self.assertFalse((self.root/'launch').exists())
        self.stop();self.start();time.sleep(.2)
        self.assertFalse((self.root/'launch').exists())
        self.power('ON',stamp='900',decision='999000000000')
        time.sleep(.15);self.assertFalse((self.root/'launch').exists())
        self.power('ON',stamp='1000',decision='1000000000000')
        self.phase('SERVICES_RUNNING')

    def test_previous_boot_reader_checkpoint_is_ignored(self):
        self.mock_power_reader();self.power('OFF',boot=OTHER_BOOT)
        self.attach();self.start();self.phase('SERVICES_RUNNING')
        self.assertNotIn('HU_POWER_OFF',self.log())

    def test_oem_off_at_startup_and_dongle_reattach_stays_paused(self):
        self.event('OFF');self.start();self.phase('HU_POWER_OFF')
        self.attach();time.sleep(.15);self.assertFalse((self.root/'launch').exists())
        shutil.rmtree(self.usb/'device');self.attach();time.sleep(.15)
        self.assertFalse((self.root/'launch').exists())
        self.event('ON',str(int(EVENT_NS)+1000));self.phase('SERVICES_RUNNING')

    def test_previous_boot_event_does_not_pause_current_boot(self):
        self.event('OFF',boot=OTHER_BOOT);self.attach();self.start();self.phase('SERVICES_RUNNING')
        self.assertNotIn('HU_POWER_OFF',self.log())

    def test_malformed_or_future_event_fails_closed_until_explicit_on(self):
        self.event('ON',nanos='2999999999999999');self.attach();self.start();self.phase('HU_POWER_OFF')
        self.assertFalse((self.root/'launch').exists())
        self.event('ON',nanos='0123456789012345');time.sleep(.1)
        self.assertFalse((self.root/'launch').exists())
        self.event('ON',boot='garbage');time.sleep(.1)
        self.assertFalse((self.root/'launch').exists())
        self.event('ON');self.phase('SERVICES_RUNNING')

    def test_oem_off_during_hostapd_start_prevents_dhcp(self):
        self.attach()
        with (self.app/'runtime.sh').open('a') as out:
            out.write('''\nap_start_logged() {
echo "$*" >> "$ROOT/launch"; touch "$ROOT/live-$1"
if [ "$1" = hostapd ] && [ ! -f "$ROOT/injected-off" ]; then
  touch "$ROOT/injected-off"
  echo '12345678-1234-1234-1234-123456789abc 1234567890123456 OFF' > "$TMP_DIR/power-event"
fi
}\n''')
        self.start();self.phase('HU_POWER_OFF')
        self.wait_for(lambda:not (self.root/'live-hostapd').exists())
        self.assertNotIn('dnsmasq',(self.root/'launch').read_text())
        self.wait_for(lambda:'link set rtwlan0 down\n' in (self.root/'network').read_text())
        self.event('ON',str(int(EVENT_NS)+1000));self.phase('SERVICES_RUNNING')

    def test_16_digit_nanoseconds_do_not_use_shell_integer_arithmetic(self):
        self.path('/proc/uptime').write_text('20000000.25 40000000.00\n')
        self.event('OFF',nanos='12345678901234567');self.attach();self.start();self.phase('HU_POWER_OFF')
        self.assertFalse((self.root/'launch').exists())
        self.event('ON',nanos='12345678901234568');self.phase('SERVICES_RUNNING')
        runtime=(SOURCE/'ap/runtime.sh').read_text()
        self.assertNotIn('$((event_ns',runtime)
        self.assertNotRegex(runtime,r'\[\s+"\$\{?event_ns[^\]]*\s-(?:gt|ge|lt|le)\s')

    def test_invalid_uuid_cannot_bypass_latch_as_other_boot(self):
        self.mock_power_reader();self.power('ON',stamp='2000000')
        self.path('/proc/uptime').write_text('2000000.25 4000000.00\n')
        self.event('OFF',boot='-'*36);self.attach();self.start();self.phase('HU_POWER_OFF')
        self.assertFalse((self.root/'launch').exists())
        self.event('ON',boot='123-5678-1234-1234-1234-123456789abc');time.sleep(.15)
        self.assertFalse((self.root/'launch').exists())
        self.event('ON');self.phase('SERVICES_RUNNING')

if __name__=='__main__': unittest.main(verbosity=2)
