package kr.ioniq5.usbcp;
import android.content.*;import android.content.pm.*;import hmgapi.hook.*;
public class ProjectionSettingsTest {
 static int cases;static void ok(boolean b){if(!b)throw new AssertionError();}
 static ResolveInfo target(){ResolveInfo r=new ResolveInfo();r.activityInfo=new ActivityInfo();r.activityInfo.packageName=ProjectionSettings.BLUETOOTH;r.activityInfo.name="com.android.settings2.bluetooth.DeviceConnectionSettingActivity";r.activityInfo.applicationInfo=new ApplicationInfo();return r;}
 static class Case {
  final Context c;final ModContext m=new ModContext();final MethodHook hook;
  Case(String pkg){c=pkg.equals(ProjectionSettings.PROJECTION)?new com.daudio.app.phoneprojection.wireless.popup.WirelessAlertDialogActivity():new Context();android.os.Process.uid=1000;Points.missing=false;c.pm.result=target();ProjectionSettings.install(m,new LoadedApp(pkg,c));ok(c.starts==0);ok(m.hooks.size()==1);String point=pkg.equals(ProjectionSettings.PROJECTION)?"phoneprojection.usbcp_settings.open":"btsettings.usbcp_settings.open";hook=m.hooks.get(point);ok(hook!=null);}
 }
 public static void main(String[] ignored)throws Throwable {
  for(String pkg:new String[]{ProjectionSettings.PROJECTION,ProjectionSettings.BLUETOOTH}){
   Case t=new Case(pkg);ok(Dispatch.replaced(t.hook,t.c));ok(t.c.starts==1);Intent i=t.c.launched;
   ok(i.action.equals("android.bluetooth.DeviceConnectionSettingActivity.action.LAUNCH"));ok(i.pkg.equals(ProjectionSettings.BLUETOOTH));ok(i.cls.equals("com.android.settings2.bluetooth.DeviceConnectionSettingActivity"));ok(i.flags==0x14000000);ok(i.extras.size()==1&&"fr_normal".equals(i.extras.get("fr_mode")));cases++;
  }
  Case t=new Case(ProjectionSettings.PROJECTION);t.c.pm.result=null;ok(!Dispatch.replaced(t.hook,t.c)&&t.c.starts==0);cases++;
  for(int kind=0;kind<6;kind++){
   t=new Case(ProjectionSettings.PROJECTION);ResolveInfo ri=t.c.pm.result;
   if(kind==0)ri.activityInfo=null;if(kind==1)ri.activityInfo.enabled=false;if(kind==2)ri.activityInfo.packageName="other";if(kind==3)ri.activityInfo.name="other";if(kind==4)ri.activityInfo.applicationInfo.enabled=false;if(kind==5)ri.activityInfo.applicationInfo.uid=12345;
   ok(!Dispatch.replaced(t.hook,t.c)&&t.c.starts==0);cases++;
  }
  t=new Case(ProjectionSettings.PROJECTION);t.c.pm.result.activityInfo.applicationInfo=null;ok(!Dispatch.replaced(t.hook,t.c));cases++;
  t=new Case(ProjectionSettings.PROJECTION);t.c.failure=new SecurityException("private data");ok(!Dispatch.replaced(t.hook,t.c)&&t.c.starts==0);ok(!t.m.logs.toString().contains("private data"));cases++;
  t=new Case(ProjectionSettings.BLUETOOTH);t.c.pm.failure=new IllegalArgumentException();ok(!Dispatch.replaced(t.hook,t.c)&&t.c.starts==0);cases++;
  t=new Case(ProjectionSettings.PROJECTION);t.m.active=false;ok(!Dispatch.replaced(t.hook,t.c)&&t.c.starts==0);t.m.active=true;t.m.bridge=false;ok(Dispatch.replaced(t.hook,t.c)&&t.c.starts==1);cases++;
  t=new Case(ProjectionSettings.PROJECTION);ok(!Dispatch.replaced(t.hook,(Object)null));ok(!Dispatch.replaced(t.hook,"bad"));ok(!Dispatch.replaced(t.hook));ok(t.c.starts==0);cases++;
  t=new Case(ProjectionSettings.PROJECTION);Context search=new Context();search.pm.result=target();ok(!Dispatch.replaced(t.hook,search)&&search.starts==0);cases++;
  for(String pkg:new String[]{null,"com.daudio.app.dipo","com.tcc.iap2server","com.android.settings"}){ModContext m=new ModContext();ProjectionSettings.install(m,new LoadedApp(pkg,new Context()));ok(m.hooks.isEmpty());}cases++;
  ModContext m=new ModContext();android.os.Process.uid=10001;ProjectionSettings.install(m,new LoadedApp(ProjectionSettings.PROJECTION,new Context()));ok(m.hooks.isEmpty());android.os.Process.uid=1000;cases++;
  m=new ModContext();Points.missing=true;ProjectionSettings.install(m,new LoadedApp(ProjectionSettings.PROJECTION,new Context()));ok(m.hooks.isEmpty());Points.missing=false;cases++;
  t=new Case(ProjectionSettings.PROJECTION);for(int n=0;n<100;n++)ok(Dispatch.replaced(t.hook,t.c));ok(t.m.logs.size()==25&&t.c.starts==100);cases++;
  System.out.println("PASS "+cases+" settings routing cases; explicit navigation only, failure falls back, no automatic launch");
 }
}
