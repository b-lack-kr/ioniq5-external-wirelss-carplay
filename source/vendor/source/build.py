"""Build the KitKat-compatible dependency importer. It contains no vendor assets."""
import os, shutil, subprocess, zipfile
from pathlib import Path
src=Path(__file__).resolve().parent
out=src.parent/'build'
root=next((p for p in src.parents if (p/'.tools/java-build/r8.jar').is_file()),None)
def tool(var,rel):
    if var in os.environ: return Path(os.environ[var])
    if root: return root/rel
    raise SystemExit(f'Set {var} in the build environment')
jdk=tool('JAVA_HOME','.tools/java-build/jdk-17.0.21+9/Contents/Home')/'bin'
sdk=tool('ANDROID_JAR','.tools/android34/android.jar')
r8=tool('D8_JAR','.tools/java-build/r8.jar')
java_files=sorted((src/'kr').rglob('*.java')); classes=out/'classes'
if classes.is_dir(): shutil.rmtree(classes)
classes.mkdir(parents=True)
subprocess.run([jdk/'javac','--release','8','-d',classes,*java_files],check=True)
jar=out/'vendor-import.jar'
subprocess.run([jdk/'java','-cp',r8,'com.android.tools.r8.D8','--release','--min-api','19','--lib',sdk,'--output',jar,*classes.rglob('*.class')],check=True)
with zipfile.ZipFile(jar) as z: assert z.namelist()==['classes.dex'] and z.read('classes.dex').startswith(b'dex\n035\0')
shutil.copyfile(src/'import-hkg.sh',out/'import-hkg.sh')
