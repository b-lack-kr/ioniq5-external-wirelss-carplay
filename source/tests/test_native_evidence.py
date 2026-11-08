"""Exercise passive exact-target trap/advanced-AR hooks; no vehicle access."""
from pathlib import Path
import subprocess,tempfile,json
from jvm_tools import java_bin
source=Path(__file__).resolve().parents[1]
jdk=java_bin(source)
with tempfile.TemporaryDirectory(prefix='neev-native-evidence-') as tmp:
    classes=Path(tmp)/'classes';classes.mkdir()
    subprocess.run([jdk/'javac','--release','8','-encoding','UTF-8','-d',classes,
                    source/'mod/src/kr/ioniq5/usbcp/NativeTbtEvidence.java',
                    *sorted((source/'tests/native-evidence-support').rglob('*.java'))],check=True)
    subprocess.run([jdk/'java','-cp',classes,'kr.ioniq5.usbcp.NativeTbtEvidenceTest'],check=True)
manifest=json.loads((source/'mod/mod.json').read_text())
points=json.loads((source/'maps/framework.json').read_text())
expected={'update_tbt_trap_info':('updateTBTTrapInfo',['int']*17),
          'update_tbt_trap_info_new':('updateTBTTrapInfoNew',['com.hkmc.cluster.NavigationCluster$NaviTbtTrapInfo']),
          'update_clu_data_14':('updateCluData_14',['int']*3),
          'update_clu_data_new_14':('updateCluDataNew_14',['int']*5)}
for suffix,(method,args) in expected.items():
    key='framework.navigation_cluster.'+suffix
    # Advanced writers yield ownership; trap writers serialize the bar overlay.
    # NativeTbtEvidence itself remains strictly passive (checked below).
    mode = 'replace'
    assert manifest['hooks']['points'][key]==mode,key
    assert points[key]=={'method':method,'params':args,'ret':'void','static':False},key
text=(source/'mod/src/kr/ioniq5/usbcp/NativeTbtEvidence.java').read_text()
for forbidden in ('setResult(', '.invoke(', 'LocalSocket', 'Thread.sleep', 'setInt(', 'TbtProgressClient'):
    assert forbidden not in text, 'observer must not mutate or replay: '+forbidden
entry=(source/'mod/src/kr/ioniq5/usbcp/UsbCarplay.java').read_text()
assert entry.index('NativeTbtEvidence.install(m, a)') < entry.index('CpTrapOverlay.install(m, a)'), 'passive observer must precede replace overlay'
bridge=(source/'upstream/HMG-API-1.0.1/core/runtime/java/hmgapi/hook/Bridge.java').read_text()
assert 'old[at - 1].priority > hook.priority' in bridge and 'if (param.returnEarly) break;' in bridge, 'equal-priority registration order and early-return assumption'
print('NativeTbtEvidence maps/shared ownership permissions/passive-only checks PASS')
