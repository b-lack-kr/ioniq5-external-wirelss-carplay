"""Execute CP's production constructor with real packets and stale stored switches.

The 1.0.15 ordinary AR baseline uses300m when optional AR is enabled. Basic TBT and
angle-based PE14 must remain usable with the shared AR switch disabled.
"""
from pathlib import Path
import json,re,subprocess,tempfile
from jvm_tools import java_bin
source=Path(__file__).resolve().parents[1]
settings=json.loads((source/'mod/settings.json').read_text())['settings']
assert [(s['key'],s['default'],s['apply']) for s in settings]==[('ar_view',False,'restart'),('external_dongle',True,'restart')]
reads=set()
for java in (source/'mod/src').rglob('*.java'):reads.update(re.findall(r'settingBoolean\("([^"\\]+)"',java.read_text()))
assert reads=={'ar_view','external_dongle'},reads
code=(source/'mod/src/hmgapi/mods/wireless/dipo/Dipo.java').read_text()
start=code.index('        turns = new CpTurns(')
end=code.index('        mod.hook(app.classLoader, "dipo.iap2_proxy.stop_route_guidance"',start)
wiring=code[start:end]
head='''package hmgapi.mods.wireless.dipo;
import android.content.Context;import android.os.Handler;import hmgapi.hook.Car;import hmgapi.hook.Cluster;import java.util.*;
final class Dipo {
 static final List<String> logs=new ArrayList<String>(); static void log(String s){logs.add(s);}
 static int checks;static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 static class Mod{boolean on=true;final Map<String,Boolean> settings=new HashMap<String,Boolean>();boolean enabled(){return on;}
 boolean settingBoolean(String key,boolean fallback){check(key.equals("ar_view"),"obsolete configuration read");return settings.containsKey(key)?settings.get(key):fallback;}}
 static class App{final Context context=new Context();}
 static class RgListener{int component=7;Object service=new Object(),tripGeneration=new Object();void restart(){}}
 static class NativePatternWriter{static final List<int[]> sent=new ArrayList<int[]>();static CpTurns.PatternSink create(Context c){return new CpTurns.PatternSink(){public boolean send(int[] f){sent.add(f.clone());return true;}};}}
 static class NativeLaneWriter{static CpTurns.LaneSink create(Context c){return new CpTurns.LaneSink(){public boolean send(byte[] f){cluster.laneInfo(f);return true;}};}}
 static Mod mod;static App app=new App();static RgListener rg;static Object iap2;static Handler turnsHandler;static CpTurns turns;static Cluster cluster;
 static void init(Boolean ar){mod=new Mod();for(String key:new String[]{"carplay_tbt","bridge","audio","carplay_standard_turns","carplay_junction_view","carplay_approach_bar","carplay_ar_close_approach"})mod.settings.put(key,false);
 if(ar!=null)mod.settings.put("ar_view",ar);Car.cp=true;Car.aa=false;Car.unit=Car.DISTANCE_KM;
 cluster=new Cluster();Cluster c=cluster;rg=new RgListener();iap2=rg.service;turnsHandler=new Handler();NativePatternWriter.sent.clear();
'''
tail='''
 }
 static String last(String prefix){return StandardTurnsTest.latest(cluster,prefix);}
 public static void main(String[] ignored){
 for(Boolean ar:new Boolean[]{null,Boolean.FALSE,Boolean.TRUE}){
 init(ar);boolean enabled=Boolean.TRUE.equals(ar);turns.maneuver(JunctionFramesTest.complete(3,90));
 for(int meters:new int[]{458,457,301,300,299,51,50,49,0}){
 turns.routeGuidance(NativeTransitionTest.route(7,3,meters,null));
 check(last("tbt ").startsWith(enabled&&meters<=300?"tbt 13 ":"tbt 67 "),"baseline300m AR and basic turn "+ar+" "+meters);
 check(last("hud ").startsWith("hud f20143"),"flat HUD turn list survives both modes");
 check(turnsHandler.delayed==null,"maneuver-only update does not schedule display/raw-lane heartbeat");
 int[] frame=NativePatternWriter.sent.get(NativePatternWriter.sent.size()-1);
 check(Arrays.equals(frame,meters>457?JunctionFramesTest.CLEAR:JunctionFramesTest.THREE_RIGHT),"angle-based PE14 independent of AR "+meters);
 }
 turns.stop();check(Arrays.equals(NativePatternWriter.sent.get(NativePatternWriter.sent.size()-1),JunctionFramesTest.CLEAR),"stop clears PE14 in both modes");
 }
 System.out.println("SettingsPolicyTest PASS checks="+checks+" (stale flags ignored; shared AR; default flat; baseline300m; independent angle PE14)");
 }
}
'''
jdk=java_bin(source)
with tempfile.TemporaryDirectory(prefix='neev-settings-policy-') as tmp:
 root=Path(tmp);files=[]
 for java in (source/'tests/tbt-support').rglob('*.java'):
  rel=java.relative_to(source/'tests/tbt-support')
  if rel.as_posix()=='hmgapi/mods/wireless/dipo/Dipo.java':continue
  text=java.read_text()
  if rel.as_posix()=='hmgapi/hook/Car.java':text=text.replace('public static int unit','public static boolean cp,aa; public static boolean carPlayGuiding(android.content.Context c){return cp;} public static boolean androidAutoGuiding(android.content.Context c){return aa;} public static int unit')
  dest=root/rel;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_text(text);files.append(dest)
 clock=root/'android/os/SystemClock.java';clock.write_text('package android.os;public class SystemClock{public static long elapsedRealtime(){return 100000L;}}')
 harness=root/'Dipo.java';harness.write_text(head+wiring+tail)
 production=source/'mod/src/hmgapi/mods/wireless/dipo'
 files += [clock,harness,source/'mod/stubs/com/tcc/iap2/Iap2NotiMsg.java']
 files += [production/(n+'.java') for n in ['CpTurns','NativeJunction','NativeSideStreet','CpAuxWriter','CpLaneCallback','CpLaneFrame','CpLaneDecoder','CpJunctions','CpJunctionTable']]
 files += [source/'tests/tbt-frames'/(n+'.java') for n in ['ClusterFramesTest','StandardTurnsTest','NativeTransitionTest','JunctionFramesTest']]
 subprocess.run([jdk/'javac','--release','8','-encoding','UTF-8','-d',root,*files],check=True)
 subprocess.run([jdk/'java','-cp',root,'hmgapi.mods.wireless.dipo.Dipo'],check=True)
