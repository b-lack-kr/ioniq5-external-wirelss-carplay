"""Find a JDK for host tests; supports a standalone source archive."""
import os
from pathlib import Path
import shutil

def java_bin(source):
    if os.environ.get("JAVA_HOME"):
        return Path(os.environ["JAVA_HOME"]) / "bin"
    for root in source.parents:
        candidate=root/".tools/java-build/jdk-17.0.21+9/Contents/Home/bin"
        if (candidate/"javac").is_file(): return candidate
    found=shutil.which("javac")
    if found: return Path(found).resolve().parent
    raise SystemExit("Set JAVA_HOME to JDK 17 or put javac/java on PATH")
