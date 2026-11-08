"""Exercise actual native-distance hook/entry bodies with real hook dispatch and trace.

This checks which inputs can be recorded and which writers yield. It does not
establish displayed icons, CAN delivery, or the meaning of unknown native codes.
"""
from pathlib import Path
import json
import subprocess
import tempfile
from jvm_tools import java_bin

source = Path(__file__).resolve().parents[1]
code = (source / 'mod/src/kr/ioniq5/usbcp/UsbCarplay.java').read_text()


def block(signature):
    begin = code.index(signature)
    at = code.index('{', begin)
    depth, end = 1, at + 1
    while depth:
        depth += (code[end] == '{') - (code[end] == '}')
        end += 1
    return code[begin:end]


body = block('    private static boolean phoneGuiding(') + block('    private static void yieldNativeDistances(')
entry = block('        if (a.context != null && "com.mnsoft.navi".equals(a.packageName))')
maps = json.loads((source / 'maps/framework.json').read_text())
manifest = json.loads((source / 'mod/mod.json').read_text())
for writer, method, types in [
    ('update_tbt_info', 'updateTBTInfo', 'IIFFIIIII'),
    ('update_tbt_sub_info', 'updateTBTSubInfo', 'FIIIIIIFI'),
    ('update_tbt_sub_info_b', 'updateTBTSubInfo', 'FIIIIIIFII'),
    ('update_tbt_sub_info_2c', 'updateTBTSubInfo2C', 'IIIIIIIII'),
    ('update_remain_dis', 'updateRemainDis', 'IIII'),
]:
    point = 'framework.navigation_cluster.' + writer
    assert maps[point] == {'method': method, 'params': ['int' if t == 'I' else 'float' for t in types], 'ret': 'void', 'static': False}
    assert manifest['hooks']['points'][point] == 'replace'

head = r'''package hmgapi.hook;
import java.util.*;
import kr.ioniq5.usbcp.NativeGuidanceTrace;
public final class NativeDistancesTest {
 static int checks;
 static void ok(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static final String[] WRITERS={"update_tbt_info","update_tbt_sub_info","update_tbt_sub_info_b","update_tbt_sub_info_2c","update_remain_dis"};
 static final String[] TYPES={"IIFFIIIII","FIIIIIIFI","FIIIIIIFII","IIIIIIIII","IIII"};
 static final String NUMBER="-?[0-9]+(?:\\.[0-9]+)?(?:[Ee][+-]?[0-9]+)?";
 static final String RECORD="USB_CP_NATIVE_TBT writer=(?:"+String.join("|",WRITERS)+") values="+NUMBER+"(?:,"+NUMBER+")*";
 static class SystemClock {static long now;static long elapsedRealtime(){return now;}}
 static class Context {boolean cp,aa;}
 static class LoadedApp {String packageName;Context context;LoadedApp(String p,Context c){packageName=p;context=c;}}
 static class ModContext {
  boolean active=true,tbt=true,aaTbt=true,failLog;int nativeCount,yields;
  final Map<String,MethodHook> hooks=new LinkedHashMap<String,MethodHook>();
  final List<String> lines=new ArrayList<String>();
  boolean enabled(){return active;}
  boolean settingBoolean(String key,boolean fallback){if(key.equals("ar_view"))return false;if(key.equals("carplay_tbt"))return tbt;if(key.equals("carplay_approach_bar"))return true;ok(key.equals("aa_tbt"),"known setting");return aaTbt;}
  void hook(ClassLoader loader,String point,MethodHook hook){ok(loader==null,"framework app-local hook");ok(hooks.put(point,hook)==null,"hook installed once");}
  void log(String line){
   if(failLog)throw new RuntimeException("synthetic recorder failure");
   if(line.equals("USB_CP_TBT_NATIVE_DISTANCE_YIELDED"))yields++;
   else {ok(line.matches(RECORD),"finite numeric whitelist record: "+line);nativeCount++;}
   lines.add(line);
  }
 }
 static class Car {static boolean carPlayGuiding(Context c){return c.cp;}static boolean androidAutoGuiding(Context c){return c.aa;}}
 static void yieldNativeLanes(ModContext m,LoadedApp a){}
 static void yieldNativeTurnList(ModContext m,LoadedApp a){}
 static void yieldNativeAdvancedTbt(ModContext m,LoadedApp a){}
 // Passive AR evidence has its own real-hook fixture; isolate these five distance writers.
 static class NativeTbtEvidence {static void install(ModContext m,LoadedApp a){}}
 static class CpTrapOverlay {static void install(ModContext m,LoadedApp a){}}
 static Object[] values(int writer,int first){
  String types=TYPES[writer];Object[] args=new Object[types.length()];
  for(int i=0;i<args.length;i++){if(types.charAt(i)=='I')args[i]=Integer.valueOf(i==0?first:i);else args[i]=Float.valueOf(i==0?first:(i==2?90.0f:200.0f));}
  return args;
 }
 static boolean callback(ModContext m,String writer,Object[] args)throws Throwable{
  Object[] copy=args==null?null:args.clone();MethodHook hook=m.hooks.get("framework.navigation_cluster."+writer);
  ok(hook!=null,"expected distance writer");MethodHook.Param p=new MethodHook.Param(null,null,args);
  hook.beforeHookedMethod(p);ok(!p.hasThrowable(),"observer must not set a throwable");
  ok(p.args==args&&Arrays.equals(args,copy),"original numeric inputs unchanged");return p.returnEarly;
 }
'''
tail = r'''
 public static void main(String[] ignored)throws Throwable {
  Context c=new Context();ModContext m=new ModContext();route(m,new LoadedApp("com.mnsoft.navi",c));
  Set<String> expected=new LinkedHashSet<String>();for(String w:WRITERS)expected.add("framework.navigation_cluster."+w);
  ok(m.hooks.keySet().equals(expected),"only five native distance writers; no warnings or phone writers");
  for(int w=0;w<WRITERS.length;w++){
   SystemClock.now=w*10000;Object[] args=values(w,13);int count=m.nativeCount;
   ok(!callback(m,WRITERS[w],args),"native owner retains callback");ok(m.nativeCount==count+1,"native inputs recorded");
   SystemClock.now+=1200;ok(!callback(m,WRITERS[w],args),"duplicate still passes through");ok(m.nativeCount==count+1,"duplicate deduplicated");
   c.cp=true;ok(callback(m,WRITERS[w],args),"CarPlay suppresses native distance");ok(m.nativeCount==count+1,"suppressed CP frame never native evidence");
   c.cp=false;c.aa=true;ok(callback(m,WRITERS[w],args),"AA suppresses native distance");ok(m.nativeCount==count+1,"suppressed AA frame never native evidence");
   c.aa=false;ok(!callback(m,WRITERS[w],args),"native guidance restored");ok(m.nativeCount==count+2,"ownership return emits fresh baseline");
   c.cp=true;m.tbt=false;SystemClock.now+=1200;
   ok(callback(m,WRITERS[w],values(w,14)),"obsolete CP false cannot restore conflicting native writer");ok(m.nativeCount==count+2,"CP-owned frames excluded from native evidence");
   c.cp=false;c.aa=true;m.aaTbt=false;SystemClock.now+=1200;
   ok(callback(m,WRITERS[w],values(w,15)),"obsolete AA false cannot restore conflicting native writer");ok(m.nativeCount==count+2,"AA-owned frames excluded from native evidence");
   m.active=false;c.cp=true;SystemClock.now+=1200;
   ok(!callback(m,WRITERS[w],values(w,16)),"disabled mod preserves OEM callback");ok(m.nativeCount==count+2,"disabled mod emits no evidence");
   m.active=true;m.tbt=true;m.aaTbt=true;c.cp=false;c.aa=false;
   ok(!callback(m,WRITERS[w],values(w,15)),"reenabled native callback retained");ok(m.nativeCount==count+3,"reenabled native baseline");
  }
  ok(m.yields==1,"yield message once across writers and CP/AA ownership");
  for(String pkg:new String[]{"com.daudio.app.dipo","com.google.android.projection.sink","android","com.android.btsettings",null}){
   ModContext other=new ModContext();yieldNativeDistances(other,new LoadedApp(pkg,c));ok(other.hooks.isEmpty(),"hook never installed outside native navigation");
  }
  ModContext noContext=new ModContext();yieldNativeDistances(noContext,new LoadedApp("com.mnsoft.navi",null));ok(noContext.hooks.isEmpty(),"contextless process untouched");
  ModContext flagsOff=new ModContext();flagsOff.tbt=false;flagsOff.aaTbt=false;route(flagsOff,new LoadedApp("com.mnsoft.navi",c));ok(flagsOff.hooks.keySet().equals(expected),"obsolete flags off cannot skip core distance ownership hooks");
  ModContext aaOnly=new ModContext();aaOnly.tbt=false;route(aaOnly,new LoadedApp("com.mnsoft.navi",c));ok(aaOnly.hooks.keySet().equals(expected),"AA-only app load retains ownership hooks");
  ModContext cpOnly=new ModContext();cpOnly.aaTbt=false;route(cpOnly,new LoadedApp("com.mnsoft.navi",c));ok(cpOnly.hooks.keySet().equals(expected),"CP-only app load retains ownership hooks");
  ModContext filtered=new ModContext();route(filtered,new LoadedApp("com.mnsoft.navi",c));
  for(Object bad:new Object[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,Double.valueOf(9),"PRIVATE_ROAD",null}){
   Object[] args=values(0,13);args[2]=bad;SystemClock.now+=1000;
   ok(!callback(filtered,WRITERS[0],args),"malformed native tuple still passes through");ok(filtered.nativeCount==0,"malformed/nonfinite values not recorded");
  }
  Object[] badInt=values(0,13);badInt[0]=Long.valueOf(13);ok(!callback(filtered,WRITERS[0],badInt),"wrong integer box passes through");
  ok(!callback(filtered,WRITERS[0],new Object[]{13}),"wrong arity passes through");ok(!callback(filtered,WRITERS[0],null),"missing args passes through");ok(filtered.nativeCount==0,"wrong signatures excluded");
  Object[] extremes=values(0,Integer.MIN_VALUE);extremes[2]=Float.MAX_VALUE;extremes[3]=-Float.MIN_VALUE;SystemClock.now+=1000;
  ok(!callback(filtered,WRITERS[0],extremes),"finite extremes pass through");ok(filtered.nativeCount==1,"finite exponent notation allowed by output grammar");
  filtered.failLog=true;SystemClock.now+=1000;ok(!callback(filtered,WRITERS[0],values(0,14)),"recorder exception does not break original native callback");
  System.out.println("NativeDistancesTest PASS checks="+checks);
 }
}
'''
jdk = java_bin(source)
with tempfile.TemporaryDirectory(prefix='neev-native-distances-') as temp:
    java = Path(temp) / 'NativeDistancesTest.java'
    java.write_text(head + body + '\nstatic void route(ModContext m,LoadedApp a){\n' + entry + '\n}\n' + tail)
    subprocess.run([jdk / 'javac', '--release', '8', '-encoding', 'UTF-8', '-d', temp,
                    source / 'upstream/HMG-API-1.0.1/core/runtime/java/hmgapi/hook/MethodHook.java',
                    source / 'mod/src/kr/ioniq5/usbcp/NativeGuidanceTrace.java', java], check=True)
    subprocess.run([jdk / 'java', '-cp', temp, 'hmgapi.hook.NativeDistancesTest'], check=True)
