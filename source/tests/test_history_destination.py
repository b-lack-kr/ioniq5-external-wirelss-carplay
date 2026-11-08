"""Check the shipping wrapper against the actual Java history destination contract."""
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

from jvm_tools import java_bin


SOURCE = Path(__file__).resolve().parents[1]
STAMP = "20261105_193920_19757"


class HistoryDestination(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="neev-history-destination-")
        cls.addClassCleanup(cls.temp.cleanup)
        # macOS's /var temporary directory is a symlink; the real collector
        # deliberately requires canonical paths, just as it does on the car.
        cls.root = Path(cls.temp.name).resolve()
        cls.classes = cls.root / "classes"
        cls.classes.mkdir()
        cls.jdk = java_bin(SOURCE)
        subprocess.run([
            cls.jdk / "javac", "--release", "8", "-encoding", "UTF-8",
            "-d", cls.classes,
            SOURCE / "setup/Setup.java",
            SOURCE / "upstream/HMG-API-1.0.1/core/runtime/java/hmgapi/engine/Json.java",
        ], check=True, capture_output=True, text=True)

    def collect(self, basename):
        out = self.root / self._testMethodName / basename
        out.mkdir(parents=True)
        result = subprocess.run([
            self.jdk / "java", "-cp", self.classes,
            "kr.ioniq5.usbcp.setup.Setup", "history", out,
        ], capture_output=True, text=True, timeout=15)
        return out, result

    def test_previous_renamed_prefix_is_rejected_before_any_export(self):
        # This is the name in the actual failing USB record. Exercise Java,
        # not a stand-in that merely accepts whichever path the shell passes.
        out, result = self.collect("NEEV_CARPLAY_RESULT_" + STAMP)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("invalid history destination", result.stderr)
        self.assertEqual(list(out.iterdir()), [])

    def test_shipping_wrapper_name_is_accepted_by_real_collector(self):
        wrapper = (SOURCE / "release/NEMESIS.sh").read_text()
        # Read the production output assignment, so a future shell-only rename
        # cannot silently break a still-installed immutable Setup.jar again.
        match = re.search(
            r'^OUT="\$USB/([A-Z0-9_]+)\$\(date \+%Y%m%d_%H%M%S\)_\$\$"$',
            wrapper, re.MULTILINE,
        )
        self.assertIsNotNone(match, "Review the output naming contract if its shell form changes")
        out, result = self.collect(match.group(1) + STAMP)
        # The real car's log sources and Android properties are absent on the
        # host. Partial status is expected; destination rejection is not.
        self.assertEqual(result.returncode, 7, result.stdout + result.stderr)
        self.assertEqual({p.name for p in out.iterdir()}, {
            "ap-events.txt", "provider-events.txt", "carplay-events.txt", "history-status.txt",
            "boot-supervisor.txt", "boot-services.txt", "boot-drivers.txt", "boot-export-status.txt",
            "carplay-persistent-previous.txt", "carplay-persistent-current.txt", "persistent-log-status.txt",
            "development-3.txt", "development-2.txt", "development-1.txt", "development-current.txt",
            "development-status.txt",
        })
        self.assertNotIn("invalid history destination", result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
