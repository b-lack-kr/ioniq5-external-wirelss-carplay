"""Exercise the actual native-only lane hook body with HMG's real hook dispatch.

This verifies ownership/fallback and runtime feature gates; it does not simulate
the vehicle's display parser or claim a particular HUD highlight appearance.
"""
from pathlib import Path
import json
import subprocess
import tempfile
from jvm_tools import java_bin

source=Path(__file__).resolve().parents[1]
code=(source/'mod/src/kr/ioniq5/usbcp/UsbCarplay.java').read_text()
signature='    private static void yieldNativeLanes(final ModContext mod, final LoadedApp app)'
begin=code.index(signature)
at=code.index('{',begin);depth=1;end=at+1
while depth:
    depth+=(code[end]=='{')-(code[end]=='}');end+=1
body=code[begin:end]
helper=code.index('    private static boolean phoneGuiding(')
hend=code.index('\n    }',helper)+6
body=code[helper:hend]+body
assert 'yieldNativeDistances(m, a);\n                yieldNativeLanes(m, a);' in code
points=json.loads((source/'maps/framework.json').read_text())
manifest=json.loads((source/'mod/mod.json').read_text())
for suffix,method,params in [
    ('update_lane_info','updateLaneInfo',['byte[]']),
    ('update_tp_lane_info','updateTPLaneInfo',['com.hkmc.cluster.NavigationCluster$LaneInformation'])]:
    point='framework.navigation_cluster.'+suffix
    assert points[point]=={'method':method,'params':params,'ret':'void','static':False}
    assert manifest['hooks']['points'][point]=='replace'

head=r'''package hmgapi.hook;
import java.util.*;
public class NativeLanesTest {
 static int cases;
 static void ok(boolean b){if(!b)throw new AssertionError();}
 static class Context {boolean guiding,aa;}
 static class LoadedApp {String packageName;Context context;LoadedApp(String p,Context c){packageName=p;context=c;}}
 static class ModContext {
  boolean active=true,tbt=true,aaTbt=true;int logs;
  final Map<String,MethodHook> hooks=new LinkedHashMap<String,MethodHook>();
  boolean enabled(){return active;}
  boolean settingBoolean(String key,boolean fallback){if(key.equals("carplay_tbt"))return tbt;ok(key.equals("aa_tbt"));return aaTbt;}
  void hook(ClassLoader cl,String point,MethodHook hook){ok(cl==null);ok(hooks.put(point,hook)==null);}
  void log(String line){ok(line.equals("USB_CP_TBT_NATIVE_LANES_YIELDED"));logs++;}
 }
 static class Car {static boolean carPlayGuiding(Context c){return c.guiding;}static boolean androidAutoGuiding(Context c){return c.aa;}}
 static boolean replaced(MethodHook hook)throws Throwable {MethodHook.Param p=new MethodHook.Param(null,null,new Object[]{new byte[81]});hook.beforeHookedMethod(p);ok(!p.hasThrowable());return p.returnEarly;}
'''
tail=r'''
 public static void main(String[] ignored)throws Throwable {
  Context c=new Context();ModContext m=new ModContext();yieldNativeLanes(m,new LoadedApp("com.mnsoft.navi",c));
  ok(m.hooks.keySet().equals(new LinkedHashSet<String>(Arrays.asList("framework.navigation_cluster.update_lane_info","framework.navigation_cluster.update_tp_lane_info"))));cases++;
  // Both raw/new and legacy/structured lane writers yield only while a phone guides.
  for(MethodHook hook:m.hooks.values()){
   c.guiding=true;ok(replaced(hook));cases++;
   c.guiding=false;ok(!replaced(hook));cases++;
   c.guiding=true;m.active=false;ok(!replaced(hook));cases++;
   m.active=true;m.tbt=false;ok(replaced(hook));cases++;
   m.tbt=true;ok(replaced(hook));cases++;
  }
  ok(m.logs==1);cases++;
  for(MethodHook hook:m.hooks.values()){
   c.guiding=false;c.aa=true;ok(replaced(hook));cases++;
   m.aaTbt=false;ok(replaced(hook));cases++;
   c.guiding=true;ok(replaced(hook));cases++;
   m.tbt=false;ok(replaced(hook));cases++;
   m.aaTbt=true;ok(replaced(hook));cases++;
   m.active=false;ok(!replaced(hook));cases++;
   m.active=true;m.tbt=true;c.aa=false;c.guiding=false;ok(!replaced(hook));cases++;
  }
  // The class-loader-local hooks are never installed in our own DiPO writer,
  // GPM/Android Auto, system_server, unrelated apps or a contextless process.
  for(String pkg:new String[]{"com.daudio.app.dipo","com.google.android.projection.sink","android","com.android.btsettings",null}){
   ModContext other=new ModContext();yieldNativeLanes(other,new LoadedApp(pkg,c));ok(other.hooks.isEmpty());cases++;
  }
  ModContext noContext=new ModContext();yieldNativeLanes(noContext,new LoadedApp("com.mnsoft.navi",null));ok(noContext.hooks.isEmpty());cases++;
  // No speed-limit, camera, warning, alive, operation-status or other writer
  // appears in the two-entry hook set checked above. No frame is modified.
  System.out.println("PASS "+cases+" native lane ownership cases; CP/AA guidance yield, end/disable fallback, own/AA/warning writers untouched");
 }
}
'''
jdk=java_bin(source)
with tempfile.TemporaryDirectory(prefix='neev-native-lanes-') as tmp:
    java=Path(tmp)/'NativeLanesTest.java';java.write_text(head+body+tail)
    subprocess.run([jdk/'javac','--release','8','-d',tmp,source/'upstream/HMG-API-1.0.1/core/runtime/java/hmgapi/hook/MethodHook.java',java],check=True)
    subprocess.run([jdk/'java','-cp',tmp,'hmgapi.hook.NativeLanesTest'],check=True)
