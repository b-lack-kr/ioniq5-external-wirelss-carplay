"""Compile production reflection adapter against exact map signature and failing receivers."""
from pathlib import Path
import json,subprocess,tempfile
from jvm_tools import java_bin
source=Path(__file__).resolve().parents[1];jdk=java_bin(source)
maps=json.loads((source/'maps/framework.json').read_text())
assert maps['framework.navigation_cluster.update_remain_dis']=={'method':'updateRemainDis','params':['int']*4,'ret':'void','static':False}
assert maps['framework.navigation_cluster.new']=={'ctor':['android.content.Context']}
with tempfile.TemporaryDirectory(prefix='neev-lane-box-') as tmp:
    subprocess.run([jdk/'javac','--release','8','-d',tmp,source/'mod/src/hmgapi/mods/wireless/dipo/LaneDistanceWriter.java',*sorted((source/'tests/lane-distance-writer').rglob('*.java'))],check=True)
    subprocess.run([jdk/'java','-cp',tmp,'hmgapi.mods.wireless.dipo.LaneDistanceWriterTest'],check=True)
