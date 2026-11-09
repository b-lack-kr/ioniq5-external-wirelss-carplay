"""Run the real shell helper against a recording executable, never against a car."""
from pathlib import Path
import json
import os
import shlex
import subprocess
import tempfile
import unittest


SOURCE = Path(__file__).resolve().parent / "templates" / "cli-runner.sh"


class CliRunnerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="neev-cli-runner-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        fake = self.root / "app_process"
        fake.write_text("""#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
Path(os.environ['CLI_TEST_RECORD']).write_text(json.dumps({
 'args': sys.argv[1:], 'preload': os.environ.get('LD_PRELOAD'),
 'selftest': os.environ.get('HMGAPI_SELFTEST'),
 'classpath': os.environ.get('CLASSPATH'),
 'root': os.environ.get('ANDROID_ROOT'), 'data': os.environ.get('ANDROID_DATA')}))
print('JAVA_STDOUT')
sys.exit(int(os.environ.get('CLI_TEST_EXIT', '0')))
""")
        fake.chmod(0o700)
        text = SOURCE.read_text()
        self.assertEqual(text.count('/system/bin/app_process.hmgapi-stock'), 1)
        self.runner = self.root / "runner.sh"
        self.runner.write_text(text.replace('/system/bin/app_process.hmgapi-stock', shlex.quote(str(fake))))
        self.record = self.root / "record.json"

    def run_cli(self, args, rc=0):
        # Set the poison variables inside the caller shell so its own loader stays clean.
        script = 'set -e\n. ' + shlex.quote(str(self.runner)) + '''
export LD_PRELOAD=private-preload HMGAPI_SELFTEST=private-selftest
cli_java "$@"
echo AFTER_CALL >&2
'''
        env = dict(os.environ, CLI_TEST_RECORD=str(self.record), CLI_TEST_EXIT=str(rc))
        env.pop('LD_PRELOAD', None)
        env.pop('HMGAPI_SELFTEST', None)
        return subprocess.run(['/bin/sh', '-c', script, 'caller', *args], env=env,
                              text=True, capture_output=True)

    def test_interpreter_environment_stdout_and_argument_privacy(self):
        args = ['private classpath.jar', 'kr.ioniq5.usbcp.setup.Setup', 'tree',
                'secret input', 'secret-output.json']
        got = self.run_cli(args)
        self.assertEqual(got.returncode, 0, got.stderr)
        self.assertEqual(got.stdout, 'JAVA_STDOUT\n')
        record = json.loads(self.record.read_text())
        self.assertEqual(record['args'], ['-Xint', '/system/bin', *args[1:]])
        self.assertEqual(record['classpath'], args[0])
        self.assertIsNone(record['preload'])
        self.assertIsNone(record['selftest'])
        self.assertEqual((record['root'], record['data']), ('/system', '/data'))
        self.assertIn('CLI_BEGIN class=Setup operation=tree vm_mode=', got.stderr)
        self.assertIn('CLI_END class=Setup operation=tree rc=0\n', got.stderr)
        for secret in (args[0], *args[3:], 'private-preload', 'private-selftest'):
            self.assertNotIn(secret, got.stderr)

    def test_native_failure_is_reported_before_set_e_exits(self):
        for rc in (139, 134):
            with self.subTest(rc=rc):
                got = self.run_cli(['payload', 'kr.ioniq5.usbcp.setup.Setup', 'tree', 'a', 'b'], rc)
                self.assertEqual(got.returncode, rc)
                self.assertIn('CLI_END class=Setup operation=tree rc=' + str(rc), got.stderr)
                self.assertNotIn('AFTER_CALL', got.stderr)
                self.assertEqual(got.stdout, 'JAVA_STDOUT\n')

    def test_vendor_path_is_never_an_operation_label(self):
        got = self.run_cli(['payload', 'kr.ioniq5.publiccp.VendorAssets', 'secret.zip', 'secret-directory'])
        self.assertEqual(got.returncode, 0, got.stderr)
        self.assertIn('class=VendorAssets operation=import', got.stderr)
        self.assertNotIn('secret', got.stderr)
        self.assertEqual(json.loads(self.record.read_text())['args'][-2:], ['secret.zip', 'secret-directory'])

    def test_fixed_classes_and_operations_only(self):
        for cls, op in [('hmgapi.engine.Engine', 'plan'), ('hmgapi.engine.Signing', 'verify'),
                        ('kr.ioniq5.usbcp.ApIdentity', 'preflight')]:
            with self.subTest(cls=cls):
                self.assertEqual(self.run_cli(['payload', cls, op]).returncode, 0)
        for args in ([], ['payload', 'unknown.secret.Class', 'secret-operation'],
                     ['payload', 'kr.ioniq5.usbcp.setup.Setup', 'secret-operation']):
            with self.subTest(args=args):
                self.record.unlink(missing_ok=True)
                got = self.run_cli(args)
                self.assertEqual(got.returncode, 64)
                self.assertFalse(self.record.exists())
                self.assertNotIn('secret', got.stderr)


if __name__ == '__main__':
    unittest.main()
