"""Execute CP's production renderer wiring with real packets and stale stored settings.

AA's full hooks, navigation ownership and overlay receiver have companion tests.
This checks frame output and configuration admission, not vehicle rendering.
"""
from pathlib import Path
import json
import re
import subprocess
import tempfile
from jvm_tools import java_bin

source = Path(__file__).resolve().parents[1]
settings = json.loads((source / 'mod/settings.json').read_text())['settings']
assert [(s['key'], s['default'], s['apply']) for s in settings] == [
    ('ar_view', False, 'restart'), ('external_dongle', True, 'restart')]
# Removed switches must not survive as hidden gates anywhere in runtime Java.
reads = set()
for java in (source / 'mod/src').rglob('*.java'):
    reads.update(re.findall(r'settingBoolean\("([^"\\]+)"', java.read_text()))
assert reads == {'ar_view'}, reads
code = (source / 'mod/src/hmgapi/mods/wireless/dipo/Dipo.java').read_text()
start = code.index('        final ApproachSender approach = ')
end = code.index('        mod.hook(app.classLoader, "dipo.iap2_proxy.stop_route_guidance"', start)
wiring = code[start:end]
head = '''package hmgapi.mods.wireless.dipo;
import android.content.Context;
import android.os.Handler;
import hmgapi.hook.Car;
import hmgapi.hook.Cluster;
import java.util.*;
final class Dipo {
 static final List<String> logs=new ArrayList<String>();
 static void log(String s){logs.add(s);}
 static int checks;static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 static class Mod { boolean on=true;final Map<String,Boolean> settings=new HashMap<String,Boolean>();
  boolean enabled(){return on;} boolean settingBoolean(String key,boolean fallback){check(key.equals("ar_view"),"no obsolete configuration reads");return settings.containsKey(key)?settings.get(key):fallback;}}
 static class App {final Context context=new Context();}
 static class RgListener {int component=7;Object service=new Object(),tripGeneration=new Object();void restart(){}}
 static class NativePatternWriter {static final List<int[]> sent=new ArrayList<int[]>();
  static CpTurns.PatternSink create(Context c){return new CpTurns.PatternSink(){public boolean send(int[] f){sent.add(f.clone());return true;}};}}
 static class ApproachSender implements CpTurns.ApproachSink {
  interface Clock{long now();}interface Owner{Object current();}interface Gate{boolean allowed(Object o);}
  final Owner owner;final Gate gate;static final List<String> sent=new ArrayList<String>();
  ApproachSender(Context c,Handler h,Clock clock,Owner owner,Gate gate){this.owner=owner;this.gate=gate;}
  public void update(boolean active,int bar){Object o=owner.current();if(!active||o!=null&&gate.allowed(o))sent.add(active+":"+bar);}}
 static Mod mod;static App app=new App();static RgListener rg;static Object iap2;
 static Handler turnsHandler;static CpTurns turns;static Cluster cluster;
 static void init(Boolean ar){mod=new Mod();for(String key:new String[]{"carplay_tbt","bridge","audio","carplay_standard_turns","carplay_junction_view","carplay_approach_bar","carplay_ar_close_approach"})mod.settings.put(key,false);
  if(ar!=null)mod.settings.put("ar_view",ar);Car.cp=true;Car.aa=false;Car.unit=Car.DISTANCE_KM;
  cluster=new Cluster();Cluster c=cluster;rg=new RgListener();iap2=rg.service;turnsHandler=new Handler();
  NativePatternWriter.sent.clear();ApproachSender.sent.clear();
'''
tail = '''
 }
 static String last(String prefix){return StandardTurnsTest.latest(cluster,prefix);}
 static void meters(int m){turns.routeGuidance(NativeTransitionTest.route(7,3,m,null));}
 public static void main(String[] ignored){
  for(Boolean ar:new Boolean[]{null,Boolean.FALSE,Boolean.TRUE}){
   init(ar);boolean enabled=Boolean.TRUE.equals(ar);
   turns.maneuver(NativeTransitionTest.maneuver(7,3,2,0,90));
   for(int meters:new int[]{300,51,50,49,0}){meters(meters);
    check(last("tbt ").startsWith(enabled&&meters<=50?"tbt 13 ":"tbt 67 "),"CP shared AR switch and fixed50m threshold "+ar+" "+meters);
    check(last("hud ").startsWith("hud f20143"),"flat HUD turn list survives both modes");}
   check(enabled==!ApproachSender.sent.isEmpty(),"approach lease uses shared AR switch");
   init(ar);turns.rawLanes(JunctionFramesTest.lanes(7,9,false));
   turns.maneuver(NativeTransitionTest.maneuver(7,3,23,0,null));
   for(int meters:new int[]{601,600,200,0}){
    turns.routeGuidance(JunctionFramesTest.route(7,3,meters,9,true));
    check(last("tbt ").startsWith(enabled&&meters<=600?"tbt 17 ":"tbt 81 "),"highway AR threshold600m");
    int[] frame=NativePatternWriter.sent.get(NativePatternWriter.sent.size()-1);
    check(Arrays.equals(frame,meters>600?JunctionFramesTest.CLEAR:JunctionFramesTest.RIGHT),"PE14 enabled independently of AR at "+meters);
   }
   turns.stop();check(Arrays.equals(NativePatternWriter.sent.get(NativePatternWriter.sent.size()-1),JunctionFramesTest.CLEAR),"stop clears independent PE14");
  }
  System.out.println("SettingsPolicyTest PASS checks="+checks+" (stale flags ignored; shared AR; default flat; independent PE14)");
 }
}
'''
jdk = java_bin(source)
with tempfile.TemporaryDirectory(prefix='neev-settings-policy-') as tmp:
    root = Path(tmp)
    files = []
    for java in (source / 'tests/tbt-support').rglob('*.java'):
        relative = java.relative_to(source / 'tests/tbt-support')
        if relative.as_posix() == 'hmgapi/mods/wireless/dipo/Dipo.java':
            continue
        text = java.read_text()
        if relative.as_posix() == 'hmgapi/hook/Car.java':
            text = text.replace('public static int unit', 'public static boolean cp,aa;\n    public static boolean carPlayGuiding(android.content.Context c){return cp;}\n    public static boolean androidAutoGuiding(android.content.Context c){return aa;}\n    public static int unit')
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        files.append(path)
    clock = root / 'android/os/SystemClock.java'
    clock.write_text('package android.os;public class SystemClock{public static long elapsedRealtime(){return 100000L;}}')
    harness = root / 'Dipo.java'
    harness.write_text(head + wiring + tail)
    production = source / 'mod/src/hmgapi/mods/wireless/dipo'
    files += [clock, harness, source / 'mod/stubs/com/tcc/iap2/Iap2NotiMsg.java']
    files += [production / (name + '.java') for name in ['CpTurns','CpLaneFrame','LaneDistanceWriter','NativeJunction','CpLaneDecoder','CpJunctions','CpJunctionTable','NativeSideStreet']]
    files += [source / 'tests/tbt-frames' / (name + '.java') for name in ['ClusterFramesTest','StandardTurnsTest','NativeTransitionTest','JunctionFramesTest']]
    subprocess.run([jdk / 'javac', '--release', '8', '-encoding', 'UTF-8', '-d', root, *files], check=True)
    subprocess.run([jdk / 'java', '-cp', root, 'hmgapi.mods.wireless.dipo.Dipo'], check=True)
