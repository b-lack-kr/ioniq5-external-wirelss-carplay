package kr.ioniq5.usbcp;
import hmgapi.hook.*;
import android.content.*;
import android.os.*;
import java.lang.reflect.Method;

public final class CpTrapOverlayTest {
 static int n; static void ok(boolean b,String s){n++;if(!b)throw new AssertionError(s);}
 static final String L="framework.navigation_cluster.update_tbt_trap_info", M=L+"_new";
 public static class Record { public int fixedSpeed,mobileSpeed,overSpeedAlarm,redlineSpeed,nonSpeed,warningZone,curveWarning,mergeWarning,tollGateCurrency,tollGate,displayTG,barGraphLevel,speedLimit,busSpeed,speedLimitType,speedLimitUnit,speedInfoType,warningZone2,warningZone3,warningZone4,cameraSafetyAlert; }
 public static class Service {
  int[] last; int writes; boolean fail;
  public void old(int a,int b,int c,int d,int e,int f,int g,int h,int i,int j,int k,int l,int m,int x,int o,int p,int q){if(fail)throw new IllegalStateException();last=new int[]{a,b,c,d,e,f,g,h,i,j,k,l,m,x,o,p,q};writes++;}
  public void newer(Record r){if(fail)throw new IllegalStateException();last=new int[]{r.fixedSpeed,r.mobileSpeed,r.overSpeedAlarm,r.redlineSpeed,r.nonSpeed,r.warningZone,r.curveWarning,r.mergeWarning,r.tollGateCurrency,r.tollGate,r.displayTG,r.barGraphLevel,r.speedLimit,r.busSpeed,r.speedLimitType,r.speedLimitUnit,r.speedInfoType,r.warningZone2};writes++;}
 }
 static Intent intent(boolean active,int bar){Intent i=new Intent("kr.ioniq5.usbcp.APPROACH_BAR");i.pkg="com.mnsoft.navi";i.active=active;i.bar=bar;return i;}
 static MethodHook.Param call(ModContext mod,String point,Object target,Object[] args)throws Exception {
  MethodHook.Param p=new MethodHook.Param();p.thisObject=target;p.args=args;
  MethodHook hook=mod.hooks.get(point);hook.before(p);
  if(!p.done)try { Points.methods.get(point).invoke(target,p.args); } catch(Exception e){p.failure=e;}
  hook.after(p);return p;
 }
 public static void main(String[] x)throws Exception {
  Class<?>[] ints=new Class<?>[17];java.util.Arrays.fill(ints,int.class);
  Points.methods.put(L,Service.class.getMethod("old",ints));Points.methods.put(M,Service.class.getMethod("newer",Record.class));
  ModContext mod=new ModContext();LoadedApp app=new LoadedApp();Service service=new Service();Car.cp=true;
  CpTrapOverlay.install(mod,app);ok(mod.hooks.size()==2,"both stock hooks installed");
  ok("android.permission.BLUETOOTH_PRIVILEGED".equals(app.context.permission),"privileged sender permission");
  app.context.receiver.onReceive(app.context,intent(true,2));ok(service.writes==0 && !mod.logs.contains("USB_CP_APPROACH_BAR event=waiting_snapshot"),"AR default off rejects overlay lease");
  for(String old:new String[]{"carplay_tbt","carplay_standard_turns","carplay_approach_bar","aa_tbt","aa_standard_turns","aa_approach_bar"})mod.settings.put(old,false);
  mod.settings.put("ar_view",true);
  app.context.receiver.onReceive(app.context,intent(true,2));ok(service.writes==0,"no stock means no fabricated write");
  ok(mod.logs.contains("USB_CP_APPROACH_BAR event=waiting_snapshot"),"waiting status contains no warning data");
  Object[] args=new Object[17];for(int i=0;i<17;i++)args[i]=0;args[2]=3;args[11]=47;args[12]=80;
  MethodHook.Param captured=call(mod,L,service,args);
  ok(captured.args==args && ((Integer)captured.args[11]).intValue()==47,
      "passive observer before or after replacement sees the original argument array");
  ok(service.last[11]==2 && service.last[2]==3 && service.last[12]==80,"legacy idle3 preserves stock");
  app.context.receiver.onReceive(app.context,intent(false,0));ok(service.last[11]==47 && service.last[12]==80,"release restores stock");
  app.context.receiver.onReceive(app.context,intent(true,7));ok(service.last[11]==7,"captured stock can be replayed");
  SystemClock.now=3500;Handler.task.run();ok(service.last[11]==47,"lease expiration restores captured stock");
  Record r=new Record();r.overSpeedAlarm=3;r.barGraphLevel=55;r.speedLimit=70;
  call(mod,M,service,new Object[]{r}); app.context.receiver.onReceive(app.context,intent(true,4));
  ok(service.last[11]==4 && service.last[12]==70 && r.barGraphLevel==55,"modern clone preserves original object");
  r.warningZone2=1;call(mod,M,service,new Object[]{r});ok(service.last[11]==55 && service.last[17]==1,"extended warning overrides overlay");
  int before=service.writes;app.context.receiver.onReceive(app.context,intent(true,6));ok(service.writes==before,"warning prevents replay");
  r.warningZone2=0;call(mod,M,service,new Object[]{r});ok(service.last[11]==6,"overlay resumes after stock warning clears");
  Car.cp=false;before=service.writes;app.context.receiver.onReceive(app.context,intent(false,0));
  ok(service.writes==before+1 && service.last[11]==55,"clean CP end restores latest observed stock");
  Car.cp=true;app.context.receiver.onReceive(app.context,intent(true,8));
  Car.cp=false;r.barGraphLevel=77;call(mod,M,service,new Object[]{r});before=service.writes;
  app.context.receiver.onReceive(app.context,intent(false,0));ok(service.writes==before && service.last[11]==77,"new native stock write supersedes overlay and is not overwritten");
  Car.cp=true;Car.aa=true;app.context.receiver.onReceive(app.context,intent(true,8));ok(service.writes==before,"AA ownership blocks CP replay");
  Car.aa=false;app.context.receiver.onReceive(app.context,intent(true,8));before=service.writes;
  mod.on=false;app.context.receiver.onReceive(app.context,intent(false,0));ok(service.writes==before,"disabled hooks cannot safely verify a newer writer");
  mod.on=true;
  Car.cp=false;Car.aa=true;Intent aa=intent(true,22);aa.source=2;
  app.context.receiver.onReceive(app.context,aa);ok(service.last[11]==22,"AA lease uses same captured stock record");
  before=service.writes;app.context.receiver.onReceive(app.context,intent(false,0));
  ok(service.writes==before && service.last[11]==22,"delayed CP release cannot clear AA");
  app.context.receiver.onReceive(app.context,intent(true,99));
  ok(service.writes==before && service.last[11]==22,"stale CP heartbeat cannot alter AA");
  Intent invalid=intent(true,33);invalid.source=3;app.context.receiver.onReceive(app.context,invalid);
  ok(service.writes==before,"unknown sender rejected");
  Car.cp=true;Car.aa=false;app.context.receiver.onReceive(app.context,intent(true,16));
  ok(service.last[11]==16,"CP can take new lease after AA ends");
  before=service.writes;aa.active=false;app.context.receiver.onReceive(app.context,aa);
  ok(service.writes==before && service.last[11]==16,"late AA release cannot clear CP");
  Car.cp=false;Car.aa=true;r.barGraphLevel=41;call(mod,M,service,new Object[]{r});
  aa.active=true;aa.bar=34;app.context.receiver.onReceive(app.context,aa);
  Car.aa=false;aa.active=false;app.context.receiver.onReceive(app.context,aa);
  ok(service.last[11]==41,"AA end restores newest stock snapshot");
  mod.settings.put("ar_view",false);before=service.writes;Car.aa=true;aa.active=true;app.context.receiver.onReceive(app.context,aa);
  ok(service.writes==before,"shared AR false blocks AA overlay");
  Car.aa=false;Car.cp=true;app.context.receiver.onReceive(app.context,intent(true,9));
  ok(service.writes==before,"shared AR false blocks CP overlay");mod.settings.put("ar_view",true);
  Car.cp=true;before=service.writes;
  Car.aa=false;service.fail=true;SystemClock.now=9000;
  MethodHook.Param failure=call(mod,M,service,new Object[]{r});ok(failure.failure!=null && service.writes==before,"stock exception propagates and invalidates snapshot");
  service.fail=false;app.context.receiver.onReceive(app.context,intent(true,9));ok(service.writes==before,"failed snapshot is never replayed");
  System.out.println("CpTrapOverlayTest: "+n+" checks passed");
 }
}
