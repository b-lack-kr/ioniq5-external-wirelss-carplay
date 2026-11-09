#!/usr/bin/env python3
"""Verify real policy and PID-cache filesystem fixtures, including reuse and bounded scans.

The host has no Linux /proc. Tests replace real temporary process directories and
their status/cmdline/stat files; they do not claim to force kernel PID recycling.
"""
from pathlib import Path
import subprocess
import tempfile
from jvm_tools import java_bin
ROOT=Path(__file__).resolve().parents[1]
JDK=java_bin(ROOT)
with tempfile.TemporaryDirectory(prefix='neev-manual-power-') as folder:
    subprocess.run([str(JDK/'javac'),'--release','8','-encoding','UTF-8','-d',folder,
        str(ROOT/'mod/src/kr/ioniq5/usbcp/ManualPowerGate.java'),
        str(ROOT/'mod/tests/manual-power/ManualPowerGateTest.java')],check=True)
    subprocess.run([str(JDK/'java'),'-cp',folder,'kr.ioniq5.usbcp.ManualPowerGateTest'],check=True)
