"""Exercise AP evidence invocations and disconnect ordering with the supervisor fixture."""
import importlib.util
from pathlib import Path
import unittest

source = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('ap_power_fixture', source / 'mod/tests/test_ap.py')
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


class UsbPowerServiceTest(fixture.RadioTest):
    def setUp(self):
        super().setUp()
        patcher = self.path('/system/bin/app_process')
        original = patcher.read_text()
        patcher.write_text(original.replace('#!/bin/sh\n', '''#!/bin/sh
if [ "$2" = kr.ioniq5.usbcp.UsbPowerEvidence ]; then
  printf '%s %s %s\n' "$3" "$4" "$5" >> "$ROOT/power-probes"
  if [ "$5" = STOPPING ]; then
    /usr/bin/grep -Fq 'link set rtwlan0 down' "$ROOT/network" && touch "$ROOT/disconnected-before-probe"
  fi
  [ ! -f "$ROOT/fail-power-probe" ] || exit 1
  exit 0
fi
''', 1))

    def probe_calls(self):
        path = self.root / 'power-probes'
        return path.read_text().splitlines() if path.is_file() else []

    def test_active_and_after_disconnect(self):
        self.attach()
        self.start()
        self.phase('SERVICES_RUNNING')
        self.wait_for(lambda: len(self.probe_calls()) == 1)
        token = '0' * 62 + '11'
        self.assertEqual(self.probe_calls(), ['rtwlan0 ' + token + ' ACTIVE'])
        self.stop()
        self.assertEqual(self.probe_calls(), ['rtwlan0 ' + token + ' ACTIVE', 'rtwlan0 ' + token + ' STOPPING'])
        self.assertTrue((self.root / 'disconnected-before-probe').is_file())
        self.assertFalse((self.root / 'live-hostapd').exists())
        self.assertFalse((self.root / 'live-dnsmasq').exists())

    def test_probe_failure_is_nonfatal(self):
        (self.root / 'fail-power-probe').touch()
        self.attach()
        self.start()
        self.phase('SERVICES_RUNNING')
        self.wait_for(lambda: len(self.probe_calls()) == 1)
        self.assertIsNone(self.child.poll())
        self.assertTrue((self.root / 'live-hostapd').exists())
        self.stop()
        self.assertTrue((self.root / 'disconnected-before-probe').is_file())
        self.assertEqual(len(self.probe_calls()), 2)

    def test_disabled_has_no_probe(self):
        (self.root / 'external-off').touch()
        self.attach()
        self.start()
        self.phase('EXTERNAL_DONGLE_OFF')
        self.stop()
        self.assertEqual(self.probe_calls(), [])


if __name__ == '__main__':
    # Reuse fixture setup/helpers without redundantly running its inherited cases.
    suite = unittest.TestSuite(UsbPowerServiceTest(name) for name in (
        'test_active_and_after_disconnect', 'test_probe_failure_is_nonfatal', 'test_disabled_has_no_probe'))
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    raise SystemExit(0 if result.wasSuccessful() else 1)
