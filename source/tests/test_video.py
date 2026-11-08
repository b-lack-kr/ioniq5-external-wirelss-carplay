"""Host tests of actual video observers; no head unit, pixels or network access."""
from pathlib import Path
import os
import subprocess
import tempfile
from jvm_tools import java_bin

source=Path(__file__).resolve().parents[1]
jdk=java_bin(source)
android=Path(os.environ['ANDROID_JAR']) if os.environ.get('ANDROID_JAR') else next(
    root/'.tools/android34/android.jar' for root in source.parents if (root/'.tools/android34/android.jar').is_file())
production=source/'mod/src/hmgapi/mods/wireless/dipo/CarPlayVideoTrace.java'
text=production.read_text()
for forbidden in ('java.io.', 'LocalSocket', 'forceKeyFrame(', 'TccCarPlayNativeMethod_', 'Surface.toString', 'getBitmap(', 'getPixel('):
    assert forbidden not in text, forbidden
with tempfile.TemporaryDirectory(prefix='neev-video-') as tmp:
    classes=Path(tmp)/'classes';classes.mkdir()
    files=[*sorted((source/'tests/audio-routing-support/hmgapi/hook').glob('*.java')),
        source/'upstream/HMG-API-1.0.1/core/runtime/java/hmgapi/hook/MethodHook.java',
        production,*sorted((source/'tests/video-trace-support').rglob('*.java'))]
    subprocess.run([jdk/'javac','--release','8','-encoding','UTF-8','-cp',android,'-d',classes,*files],check=True)
    subprocess.run([jdk/'java','-cp',str(classes)+os.pathsep+str(android),'hmgapi.mods.wireless.dipo.VideoTraceTest'],check=True)
