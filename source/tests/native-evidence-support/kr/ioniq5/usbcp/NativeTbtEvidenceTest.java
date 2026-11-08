package kr.ioniq5.usbcp;
import hmgapi.hook.*;import java.util.*;
public final class NativeTbtEvidenceTest {
 static int checks;
 static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 public static class Info {public int fixedSpeed,mobileSpeed,overSpeedAlarm,redlineSpeed,nonSpeed,warningZone,curveWarning,mergeWarning,tollGateCurrency,tollGate,displayTG,barGraphLevel,speedLimit,busSpeed,speedLimitType,speedLimitUnit,speedInfoType,warningZone2,warningZone3,warningZone4,cameraSafetyAlert;public String secret="private";public String toString(){throw new AssertionError("object must never be logged");}}
 public static class Native {
  public void old(int a,int b,int c,int d,int e,int f,int g,int h,int i,int j,int k,int l,int m,int n,int o,int p,int q){}
  public void modern(Info i){}public void clu(int a,int b,int c){}public void cluNew(int a,int b,int c,int d,int e){}
 }
 static final class Port implements NativeTbtEvidence.Port {
  boolean enabled=true,cp,aa,failOwner,failLog,camera;long now=1000;final List<String> logs=new ArrayList<String>();
  public boolean nativeOwner(){if(failOwner)throw new IllegalStateException();return enabled&&!cp&&!aa;}
  public int cameraOwner(){if(failOwner)throw new IllegalStateException();return !camera||!enabled?-1:(cp?1:0)|(aa?2:0);}
  public long now(){return now;}public void event(String line){if(failLog)throw new IllegalStateException();logs.add(line);}
 }
 static MethodHook.Param param(Object...args){MethodHook.Param p=new MethodHook.Param();p.thisObject=new Native();p.args=args;return p;}
 static Object[] old(int bar){Object[] a=new Object[17];for(int i=0;i<a.length;i++)a[i]=Integer.valueOf(500+i);a[11]=bar;return a;}
 static NativeTbtEvidence trace(Port p)throws Exception{return new NativeTbtEvidence(p,Info.class.getField("barGraphLevel"));}
 static void call(NativeTbtEvidence t,MethodHook.Param p,int w){t.before(p,w);t.after(p);}
 static void preservation()throws Exception {
  Port port=new Port();NativeTbtEvidence t=trace(port);Object[] a=old(255),saved=a.clone();MethodHook.Param p=param(a);call(t,p,0);
  check(p.args==a,"same original argument array");for(int i=0;i<17;i++)check(a[i]==saved[i],"legacy argument never replaced "+i);
  check(port.logs.get(0).equals("USB_CP_NATIVE_AR writer=trap values=255"),"only legacy bar logged");
  Info info=new Info();info.barGraphLevel=36;info.warningZone=777;MethodHook.Param q=param(info);t.before(q,1);info.barGraphLevel=5;t.after(q);
  check(q.args[0]==info && info.warningZone==777 && info.barGraphLevel==5,"observer did not alter caller object");
  check(port.logs.get(1).equals("USB_CP_NATIVE_AR writer=trap_new values=36"),"captures original scalar before callback");
  MethodHook.Param c=param(1,2,3);Object[] before=c.args.clone();call(t,c,2);for(int i=0;i<3;i++)check(c.args[i]==before[i],"clu args unchanged");
  check(port.logs.get(2).equals("USB_CP_NATIVE_AR writer=clu14 values=1,2,3"),"three advanced integers only");
  MethodHook.Param n=param(Integer.MIN_VALUE,0,1,Integer.MAX_VALUE,-1);call(t,n,3);
  check(port.logs.get(3).equals("USB_CP_NATIVE_AR writer=clu14_new values=-2147483648,0,1,2147483647,-1"),"five advanced signed ints preserved");
 }
 static void gatingAndRate()throws Exception {
  Port p=new Port();NativeTbtEvidence t=trace(p);call(t,param(old(1)),0);call(t,param(1,2,3),2);check(p.logs.size()==2,"rate per writer");
  p.now+=999;call(t,param(old(2)),0);check(p.logs.size()==2,"strict one Hz");p.now++;call(t,param(old(3)),0);check(p.logs.size()==3,"one-second boundary");
  p.now+=1000;p.cp=true;call(t,param(old(4)),0);p.cp=false;p.aa=true;call(t,param(old(4)),0);p.aa=false;p.enabled=false;call(t,param(old(4)),0);check(p.logs.size()==3,"phone ownership and disabled do not log");
  p.enabled=true;MethodHook.Param error=param(old(9));t.before(error,0);error.error=new IllegalStateException();t.after(error);check(p.logs.size()==3,"failed API call not labeled successful");
  MethodHook.Param a=param(old(8));t.before(a,0);p.cp=true;t.after(a);check(p.logs.size()==3,"post-call ownership checked");
  MethodHook.Param b=param(old(7));t.before(b,0);p.cp=false;t.after(b);check(p.logs.size()==3,"pre-call ownership checked");
  p.failOwner=true;call(t,param(old(6)),0);p.failOwner=false;check(p.logs.size()==3,"ownership exception harmless");
  p.failLog=true;call(t,param(old(5)),0);p.failLog=false;p.now+=1000;call(t,param(old(4)),0);check(p.logs.size()==4,"logger failure cannot stop future native calls");
 }
 static void malformedAndPairs()throws Exception {
  Port p=new Port();NativeTbtEvidence t=trace(p);call(t,param(1),0);call(t,param("wrong"),1);call(t,param(1,2,"wrong"),2);call(t,param(1,2,3,4),3);call(t,param(old(2)),-1);call(t,param(old(2)),4);check(p.logs.isEmpty(),"bad signatures skipped");
  MethodHook.Param orphan=param(old(41));t.before(orphan,0);MethodHook.Param fresh=param(3,4,5);call(t,fresh,2);check(p.logs.get(0).contains("clu14 values=3,4,5"),"orphan after cannot mispair");
  MethodHook.Param first=param(old(19)),second=param(10,20,30,40,50);t.before(first,0);t.before(second,3);t.after(first);t.after(second);
  check(p.logs.get(1).contains("trap values=19")&&p.logs.get(2).contains("clu14_new values=10,20,30,40,50"),"out-of-order after uses Param identity");
  t.after(first);check(p.logs.size()==3,"duplicate after ignored");
 }
 static void maps()throws Exception {
  Class<?>[] ints=new Class<?>[17];Arrays.fill(ints,int.class);Points.methods.clear();
  Points.methods.put(NativeTbtEvidence.POINTS[0],Native.class.getMethod("old",ints));
  Points.methods.put(NativeTbtEvidence.POINTS[1],Native.class.getMethod("modern",Info.class));
  Points.methods.put(NativeTbtEvidence.POINTS[2],Native.class.getMethod("clu",int.class,int.class,int.class));
  Points.methods.put(NativeTbtEvidence.POINTS[3],Native.class.getMethod("cluNew",int.class,int.class,int.class,int.class,int.class));
 }
 static List<String> nativeRecords(List<String> lines){List<String> out=new ArrayList<String>();for(String s:lines)if(s.startsWith("USB_CP_NATIVE_AR writer="))out.add(s);return out;}
 static void camera()throws Exception {
  Port p=new Port();p.camera=true;p.cp=true;NativeTbtEvidence t=trace(p);Info info=new Info();info.fixedSpeed=2;info.speedLimit=50;info.cameraSafetyAlert=1;
  MethodHook.Param call=param(info);t.before(call,1);info.fixedSpeed=3;t.after(call);
  String first=p.logs.get(0);check(first.startsWith("USB_CP_CAMERA writer=trap_new source=navi owner=1 returned=1 elapsed_ms=1000 values=2,"),"captures native producer before mutation during CP ownership");
  check(first.contains(",50,")&&first.endsWith(",1 dropped=0"),"speed limit and extra camera field retained");
  check(nativeRecords(p.logs).isEmpty(),"camera evidence is not mislabeled native TBT while phone guides");
  for(int i=0;i<50;i++)call(t,param(info),1);check(p.logs.size()==1,"camera one second budget");p.now+=1000;call(t,param(info),1);
  check(p.logs.get(1).endsWith("dropped=50"),"camera suppressed count preserved");
  p.now+=1000;Object[] values=old(20),saved=values.clone();MethodHook.Param fail=param(values);t.before(fail,0);fail.error=new IllegalStateException("private");t.after(fail);
  check(p.logs.get(2).contains("writer=trap source=navi owner=1 returned=0"),"API failure not mistaken for delivery");
  check(Arrays.equals(values,saved)&&fail.args==values,"camera observer never rewrites original call");
  p.now+=1000;MethodHook.Param swapped=param(info);t.before(swapped,1);p.cp=false;p.aa=true;t.after(swapped);check(p.logs.size()==3,"ownership change rejects cross-owner capture");
  call(t,param(info),1);check(p.logs.get(3).contains("owner=2"),"AA owner recorded independently");
  p.now+=1000;p.enabled=false;call(t,param(info),1);check(p.logs.size()==4,"disabled camera logging stops");
  for(String line:p.logs)check(line.matches("USB_CP_CAMERA writer=(trap|trap_new) source=navi owner=[0-3] returned=[01] elapsed_ms=[0-9]+ values=-?[0-9]+(,-?[0-9]+){16,20} dropped=[0-9]+")&&!line.contains("private"),"fixed numeric-only grammar");
 }
 static void entry()throws Exception {
  maps();ModContext m=new ModContext();LoadedApp app=new LoadedApp();NativeTbtEvidence.install(m,app);check(m.hooks.size()==4,"four observer hooks installed");check(m.logs.get(0).endsWith("event=ready"),"ready logged");
  MethodHook h=m.hooks.get(NativeTbtEvidence.POINTS[0]);MethodHook.Param p=param(old(32));h.before(p);h.after(p);check(nativeRecords(m.logs).get(0).equals("USB_CP_NATIVE_AR writer=trap values=32"),"production adapter emits scalar");
  Car.cp=true;android.os.SystemClock.now+=1000;h.before(p);h.after(p);check(nativeRecords(m.logs).size()==1,"actual CP gate");check(m.logs.get(m.logs.size()-1).contains("source=navi owner=1"),"actual CP camera adapter");Car.cp=false;Car.aa=true;h.before(p);h.after(p);check(nativeRecords(m.logs).size()==1,"actual AA gate");Car.aa=false;m.active=false;int count=m.logs.size();h.before(p);h.after(p);check(m.logs.size()==count,"actual enabled gate");
  ModContext wrong=new ModContext();app.packageName="com.daudio.app.dipo";NativeTbtEvidence.install(wrong,app);check(wrong.hooks.isEmpty()&&wrong.logs.isEmpty(),"native process only");
  app.packageName="com.mnsoft.navi";Points.methods.remove(NativeTbtEvidence.POINTS[3]);ModContext missing=new ModContext();NativeTbtEvidence.install(missing,app);check(missing.hooks.isEmpty()&&missing.logs.get(0).endsWith("event=unavailable"),"resolve all before partial install");
  maps();ModContext partial=new ModContext();partial.rejectAt=2;NativeTbtEvidence.install(partial,app);int logs=partial.logs.size();MethodHook old=partial.hooks.get(NativeTbtEvidence.POINTS[0]);old.before(p);old.after(p);check(partial.logs.size()==logs,"partial observers stay inactive");
 }
 public static void main(String[] ignored)throws Exception {preservation();gatingAndRate();malformedAndPairs();camera();entry();System.out.println("NativeTbtEvidence: "+checks+" checks PASS");}
}
