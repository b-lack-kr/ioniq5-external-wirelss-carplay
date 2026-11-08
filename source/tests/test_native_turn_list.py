"""Execute native F2/advanced-TBT ownership hooks and native app-entry branch.

Uses HMG's real MethodHook/Param and a recording original writer. This proves
dispatch and payload preservation, not vehicle HUD rendering or CAN delivery.
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
    end = code.index('{', begin) + 1
    depth = 1
    while depth:
        depth += (code[end] == '{') - (code[end] == '}')
        end += 1
    return code[begin:end]


body = (block('    private static boolean phoneGuiding(')
        + block('    private static void yieldNativeTurnList(')
        + block('    private static void yieldNativeAdvancedTbt('))
entry = block('        if (a.context != null && "com.mnsoft.navi".equals(a.packageName))')
point = 'framework.navigation_cluster.update_tp_turn_list_new'
maps = json.loads((source / 'maps/framework.json').read_text())
manifest = json.loads((source / 'mod/mod.json').read_text())
assert maps[point] == {'method': 'updateTPTurnListNew', 'params': ['byte[]'], 'ret': 'void', 'static': False}
assert manifest['hooks']['points'][point] == 'replace'
for suffix, method, count in [('update_clu_data_14', 'updateCluData_14', 3),
                              ('update_clu_data_new_14', 'updateCluDataNew_14', 5)]:
    key = 'framework.navigation_cluster.' + suffix
    assert maps[key] == {'method': method, 'params': ['int'] * count, 'ret': 'void', 'static': False}
    assert manifest['hooks']['points'][key] == 'replace'

head = r'''package hmgapi.hook;
import java.util.*;
public final class NativeTurnListTest {
 static final String POINT="framework.navigation_cluster.update_tp_turn_list_new";
 static final String[] ADVANCED={"framework.navigation_cluster.update_clu_data_14","framework.navigation_cluster.update_clu_data_new_14"};
 static int checks;
 static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
 static class Context {boolean cp,aa;}
 static class LoadedApp {
  final String packageName;final Context context;
  LoadedApp(String name,Context context){this.packageName=name;this.context=context;}
 }
 static class ModContext {
  boolean active=true,tbt=true,aaTbt=true,standardTurns=true,junctionView=true,ar;int logs,overlays;
  final Map<String,MethodHook> hooks=new LinkedHashMap<String,MethodHook>();
  boolean enabled(){return active;}
  boolean settingBoolean(String key,boolean fallback){
   if(key.equals("ar_view"))return ar;
   if(key.equals("carplay_tbt"))return tbt;
   if(key.equals("carplay_approach_bar"))return true;
   if(key.equals("carplay_standard_turns"))return standardTurns;
   if(key.equals("carplay_junction_view"))return junctionView;
   check(key.equals("aa_tbt"),"known setting");return aaTbt;
  }
  void hook(ClassLoader loader,String point,MethodHook callback){
   check(loader==null,"framework method in current native process");
   check(hooks.put(point,callback)==null,"one callback per writer");
  }
  void log(String line){check(line.equals("USB_CP_TBT_NATIVE_TURN_LIST_YIELDED")||line.equals("USB_CP_TBT_NATIVE_ADVANCED_YIELDED"),"fixed event without payload");logs++;}
 }
 static class Car {
  static boolean carPlayGuiding(Context c){return c.cp;}
  static boolean androidAutoGuiding(Context c){return c.aa;}
 }
 static void yieldNativeDistances(ModContext m,LoadedApp a){}
 static void yieldNativeLanes(ModContext m,LoadedApp a){}
 static class NativeTbtEvidence {static void install(ModContext m,LoadedApp a){}}
 static class CpTrapOverlay {static void install(ModContext m,LoadedApp a){m.overlays++;}}
 static class Writer {
  int calls;byte[] payload;Object[] numbers;
  void write(byte[] bytes){calls++;payload=bytes;}
  void advanced(Object[] values){calls++;numbers=values;}
 }
 static void dispatch(ModContext m,Writer original,byte[] frame,boolean blocked)throws Throwable {
  byte[] saved=frame==null?null:frame.clone();Object[] args={frame};int before=original.calls;
  MethodHook hook=m.hooks.get(POINT);MethodHook.Param p=new MethodHook.Param(null,original,args);
  if(hook!=null)hook.beforeHookedMethod(p);
  check(p.returnEarly==blocked,"expected original-writer admission");
  check(!p.hasThrowable(),"hook does not inject exception");
  check(p.args==args&&p.args[0]==frame&&Arrays.equals(frame,saved),"same payload object and bytes");
  if(!p.returnEarly){original.write((byte[])p.args[0]);p.setResult(null);}
  if(hook!=null)hook.afterHookedMethod(p);
  check(original.calls==before+(blocked?0:1),"original writer ran exactly when admitted");
  if(!blocked)check(original.payload==frame,"original receives original payload");
  check(Arrays.equals(frame,saved),"payload remains unchanged after dispatch");
 }
 static void advanced(ModContext m,Writer original,int writer,boolean clear,boolean blocked)throws Throwable {
  Object[] args=writer==0?new Object[]{1,2,3}:new Object[]{1,2,3,4,5};
  if(clear)Arrays.fill(args,Integer.valueOf(0));
  Object[] saved=args.clone();int before=original.calls;
  MethodHook hook=m.hooks.get(ADVANCED[writer]);MethodHook.Param p=new MethodHook.Param(null,original,args);
  if(hook!=null)hook.beforeHookedMethod(p);
  check(p.returnEarly==blocked,"advanced writer ownership including clear");
  check(!p.hasThrowable()&&p.args==args&&Arrays.equals(args,saved),"advanced arguments unchanged");
  if(!p.returnEarly){original.advanced(p.args);p.setResult(null);}
  if(hook!=null)hook.afterHookedMethod(p);
  check(original.calls==before+(blocked?0:1),"advanced original runs only when admitted");
  if(!blocked)check(original.numbers==args,"advanced original receives original arguments");
  check(Arrays.equals(args,saved),"advanced values remain unchanged");
 }
'''
tail = r'''
 public static void main(String[] ignored)throws Throwable {
  Context c=new Context();ModContext m=new ModContext();Writer original=new Writer();
  route(m,new LoadedApp("com.mnsoft.navi",c));
  check(m.hooks.keySet().equals(new LinkedHashSet<String>(Arrays.asList(POINT,ADVANCED[0],ADVANCED[1]))),"only F2 and advanced writers hooked");
  byte[] frame=new byte[87];frame[0]=(byte)0xf2;frame[1]=2;frame[2]=0x46;frame[3]=0x43;
  byte[] clear={(byte)0xf2,0};
  dispatch(m,original,frame,false); // Native guidance remains unchanged.
  dispatch(m,original,clear,false);
  c.cp=true;dispatch(m,original,frame,true);dispatch(m,original,clear,true);
  c.cp=false;dispatch(m,original,frame,false); // Return to native guidance.
  c.aa=true;dispatch(m,original,frame,true);dispatch(m,original,clear,true);
  c.cp=true;m.active=false;dispatch(m,original,frame,false); // Disabled mod passes through.
  m.active=true;dispatch(m,original,frame,true);
  c.aa=false;m.tbt=false;dispatch(m,original,frame,true); // Obsolete CP flag ignored.
  c.cp=false;c.aa=true;m.aaTbt=false;dispatch(m,original,frame,true); // Obsolete AA flag ignored.
  c.cp=true;m.tbt=true;dispatch(m,original,frame,true); // CP still owns with AA disabled.
  m.tbt=false;m.aaTbt=true;dispatch(m,original,frame,true); // AA still owns with CP disabled.
  c.cp=false;c.aa=false;dispatch(m,original,null,false); // Invalid inputs retain stock behavior.
  check(m.logs==1,"one yield marker across ownership transitions");
  for(int writer=0;writer<2;writer++){
   c.cp=false;c.aa=false;m.active=true;m.tbt=true;m.aaTbt=true;
   advanced(m,original,writer,false,false);advanced(m,original,writer,true,false);
   c.cp=true;advanced(m,original,writer,false,true);advanced(m,original,writer,true,true);
   c.cp=false;c.aa=true;advanced(m,original,writer,false,false);advanced(m,original,writer,true,false);
   m.active=false;advanced(m,original,writer,false,false);
   m.active=true;m.aaTbt=false;advanced(m,original,writer,false,false);
   c.cp=true;c.aa=false;m.tbt=false;advanced(m,original,writer,false,true);
   m.aaTbt=true;c.aa=true;advanced(m,original,writer,false,true);
   m.tbt=true;advanced(m,original,writer,false,true);
   m.standardTurns=false;advanced(m,original,writer,false,true);advanced(m,original,writer,true,true);
   m.standardTurns=true;m.junctionView=false;advanced(m,original,writer,false,true);advanced(m,original,writer,true,true);
   m.junctionView=true;advanced(m,original,writer,false,true);advanced(m,original,writer,true,true);
   c.cp=false;c.aa=false;advanced(m,original,writer,false,false);
  }
  check(m.logs==2,"advanced pair shares one marker; independent from F2");
  // DiPO's own F2, GPM, system_server and unrelated apps must always reach their writers.
  c.cp=true;c.aa=true;
  for(String name:new String[]{"com.daudio.app.dipo","com.google.android.projection.sink","android","com.tcc.iap2server",null}){
   ModContext other=new ModContext();LoadedApp app=new LoadedApp(name,c);route(other,app);
   yieldNativeTurnList(other,app); // Also exercise the hook method's own scope guard.
   yieldNativeAdvancedTbt(other,app);
   check(other.hooks.isEmpty(),"no ownership hooks outside native navigation");
   dispatch(other,new Writer(),frame,false);dispatch(other,new Writer(),clear,false);
   for(int writer=0;writer<2;writer++){advanced(other,new Writer(),writer,false,false);advanced(other,new Writer(),writer,true,false);}
  }
  ModContext noContext=new ModContext();LoadedApp bare=new LoadedApp("com.mnsoft.navi",null);
  route(noContext,bare);yieldNativeTurnList(noContext,bare);yieldNativeAdvancedTbt(noContext,bare);
  check(noContext.hooks.isEmpty(),"contextless process untouched");dispatch(noContext,new Writer(),frame,false);
  for(int flags=0;flags<4;flags++){
   ModContext setting=new ModContext();setting.tbt=(flags&1)!=0;setting.aaTbt=(flags&2)!=0;
   route(setting,new LoadedApp("com.mnsoft.navi",c));
   check(setting.hooks.size()==3&&setting.overlays==0,"core ownership hooks ignore obsolete flags; AR default off installs no overlay");
   dispatch(setting,new Writer(),frame,true);
   for(int writer=0;writer<2;writer++)advanced(setting,new Writer(),writer,false,true);
  }
  // All feature/route combinations execute the original writer unless CP owns junction output.
  // F2/PE14 remain enabled with AR off and any combination of obsolete saved false values.
  for(int flags=0;flags<256;flags++){
   ModContext setting=new ModContext();Context route=new Context();
   setting.active=(flags&1)!=0;setting.tbt=(flags&2)!=0;setting.aaTbt=(flags&4)!=0;
   setting.standardTurns=(flags&8)!=0;setting.junctionView=(flags&16)!=0;setting.ar=(flags&128)!=0;
   route.cp=(flags&32)!=0;route.aa=(flags&64)!=0;
   route(setting,new LoadedApp("com.mnsoft.navi",route));
   check(setting.overlays==(setting.ar?1:0),"only unified AR switch controls overlay registration");
   boolean ownsF2=setting.active&&(route.cp||route.aa);
   boolean ownsAdvanced=setting.active&&route.cp; // PE14 independent of AR and obsolete switches
   dispatch(setting,new Writer(),frame,ownsF2);dispatch(setting,new Writer(),clear,ownsF2);
   for(int writer=0;writer<2;writer++){
    advanced(setting,new Writer(),writer,false,ownsAdvanced);
    advanced(setting,new Writer(),writer,true,ownsAdvanced);
   }
  }
  System.out.println("NativeTurnListTest PASS checks="+checks);
 }
}
'''
jdk = java_bin(source)
with tempfile.TemporaryDirectory(prefix='neev-native-turn-list-') as temp:
    java = Path(temp) / 'NativeTurnListTest.java'
    java.write_text(head + body + '\nstatic void route(ModContext m,LoadedApp a){\n' + entry + '\n}\n' + tail)
    subprocess.run([jdk / 'javac', '--release', '8', '-encoding', 'UTF-8', '-d', temp,
                    source / 'upstream/HMG-API-1.0.1/core/runtime/java/hmgapi/hook/MethodHook.java', java], check=True)
    subprocess.run([jdk / 'java', '-cp', temp, 'hmgapi.hook.NativeTurnListTest'], check=True)
