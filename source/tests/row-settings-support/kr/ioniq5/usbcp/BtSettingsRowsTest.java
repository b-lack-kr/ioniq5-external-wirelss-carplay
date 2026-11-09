package kr.ioniq5.usbcp;
import android.bluetooth.BluetoothDevice;
import android.os.*;
import android.view.*;
import android.widget.*;
import hmgapi.hook.*;
import com.android.settings2.bluetooth.*;

/** Execute the real hook/UI controller using deterministic OEM/Android doubles. */
public class BtSettingsRowsTest {
 static int checks;
 static final String A="10:20:30:40:50:60",B="11:21:31:41:51:61";
 static void ok(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
 static class Fixture {
  final ModContext mod=new ModContext();final DeviceConnectionSettingActivity activity=new DeviceConnectionSettingActivity();
  final cy adapter=new cy(activity);final BluetoothDevice phone=new BluetoothDevice(A);
  final View row=new View(activity);final ImageButton cp=new ImageButton(activity),hfp=new ImageButton(activity),audio=new ImageButton(activity);
  final View progress=new View(activity);int nativeClicks;boolean nativeAccept;
  final View.OnClickListener originalCp=new View.OnClickListener(){public void onClick(View v){}};
  final View.OnClickListener originalHfp=new View.OnClickListener(){public void onClick(View v){nativeClicks++;if(nativeAccept)activity.f.bt.hfp=phone;}};
  Fixture()throws Exception {
   Handler.clear();Toast.messages.clear();SystemClock.time+=100000;ManualCarplay.reset();android.os.Process.uid=1000;
   java.lang.reflect.Field installed=BtSettingsRows.class.getDeclaredField("installed");installed.setAccessible(true);installed.setBoolean(null,false);
   adapter.phones.add(phone);row.children.put(0x7f0800ed,cp);row.children.put(0x7f0800e7,hfp);row.children.put(0x7f0800ea,audio);row.children.put(0x7f0800ef,progress);
   for(int[] p:new int[][]{{0x7f080006,486},{0x7f0800ee,270},{0x7f0800e6,180}}){View v=new View(activity);v.layout.width=p[1];row.children.put(p[0],v);}
   BtSettingsRows.install(mod,new LoadedApp());
  }
  void bind()throws Throwable {
   Dispatch.bind(mod.hooks.get("btsettings.usbcp_rows.bind"),adapter,0,row,new Runnable(){public void run(){cp.setOnClickListener(originalCp);hfp.setOnClickListener(originalHfp);cp.setVisibility(View.GONE);progress.setVisibility(View.GONE);}});
  }
  void click(){cp.performClick();}
  void close()throws Throwable {Dispatch.destroy(mod.hooks.get("btsettings.usbcp_devices.destroy"),activity);Handler.clear();}
 }
 interface Condition{boolean ready();}
 static void await(Condition condition)throws Exception {for(int n=0;n<200;n++){Handler.pump();if(condition.ready())return;Thread.sleep(5);}throw new AssertionError("async timeout");}
 static void settle()throws Exception {for(int n=0;n<15;n++){Handler.pump();Thread.sleep(2);}}
 static void tick(long ms)throws Exception {SystemClock.time+=ms;settle();}
 static int startOnWorker(final android.content.Context context)throws Exception {
  final int[] result={0};Thread worker=new Thread(new Runnable(){public void run(){result[0]=OemCarplayService.start(context);}});
  worker.start();worker.join();return result[0];
 }
 static void serviceValidation()throws Exception {
  android.content.Context c=new android.content.Context();
  ok(OemCarplayService.start(c)==OemCarplayService.DENIED&&c.starts==0,"service cannot start on UI thread");
  android.os.Process.uid=2000;
  ok(startOnWorker(c)==OemCarplayService.DENIED&&c.packages.resolves==0,"non-system caller rejected before resolution");
  android.os.Process.uid=1000;
  ok(startOnWorker(null)==OemCarplayService.DENIED,"missing service context rejected");
  for(int variant=0;variant<11;variant++) {
   c=new android.content.Context();android.content.pm.ServiceInfo s=c.packages.result.serviceInfo;
   if(variant==0)c.packages.result=null;
   if(variant==1)c.packages.result.serviceInfo=null;
   if(variant==2)s.enabled=false;
   if(variant==3)s.exported=false;
   if(variant==4)s.packageName="other.package";
   if(variant==5)s.name="com.daudio.app.dipo.service.OtherService";
   if(variant==6)s.processName="com.daudio.app.dipo:other";
   if(variant==7)s.applicationInfo=null;
   if(variant==8)s.applicationInfo.enabled=false;
   if(variant==9)s.applicationInfo.uid=10001;
   if(variant==10)s.applicationInfo.packageName="other.package";
   ok(startOnWorker(c)==OemCarplayService.UNAVAILABLE&&c.starts==0,"invalid OEM service rejected variant "+variant);
  }
  c=new android.content.Context();c.packages.result.serviceInfo.permission="vehicle.permission.CARPLAY";c.packages.permissionResult=-1;
  ok(startOnWorker(c)==OemCarplayService.DENIED&&c.starts==0,"service permission denial prevents start");
  ok("vehicle.permission.CARPLAY".equals(c.packages.permissionChecked)&&"com.android.btsettings".equals(c.packages.permissionPackage),"permission checked for actual settings caller");
  c.packages.permissionResult=0;
  ok(startOnWorker(c)==OemCarplayService.STARTED&&c.starts==1&&c.mainStarts==0,"permitted stock service starts on worker");
  ok(OemCarplayService.PACKAGE.equals(c.started.getComponent().getPackageName())&&OemCarplayService.SERVICE.equals(c.started.getComponent().getClassName()),"exact stock service component");
  ok(c.started.getAction()==null&&c.started.getExtras()==null,"service bootstrap sends no actions or extras");
  for(int variant=0;variant<4;variant++) {
   c=new android.content.Context();c.nullStart=variant==0;c.wrongStart=variant==1;c.deniedStart=variant==2;c.failedStart=variant==3;
   ok(startOnWorker(c)==(variant==2?OemCarplayService.DENIED:OemCarplayService.FAILED),"failed service start is not readiness variant "+variant);
  }
 }
 public static void main(String[] args)throws Throwable {
  Fixture f=new Fixture();f.bind();ok(f.cp.visibility==View.VISIBLE,"Apple third slot visible");ok(f.hfp.listener==f.originalHfp,"OEM HFP unchanged");ok(f.row.findViewById(0x7f0800e6).layout.width==274,"three profile widths");ok(ManualCarplay.queries==0,"binding defers IPC");settle();ok(ManualCarplay.queries==1&&ManualCarplay.mainQueries==0,"first display queries status on worker");ok(ManualCarplay.retries==0&&f.nativeClicks==0&&f.activity.starts==0&&f.activity.packages.resolves==0,"passive query never initiates pairing or service bootstrap");
  BtSettingsRows.install(f.mod,new LoadedApp());ok(f.mod.hooks.size()==2,"install once");f.close();
  for(int variant=0;variant<5;variant++) {f=new Fixture();if(variant==0)f.phone.vendor=117;if(variant==1)f.phone.vendor=0;if(variant==2)f.phone.bond=10;if(variant==3)f.activity.mL=true;if(variant==4)f.mod.external=false;f.bind();ok(f.cp.listener==f.originalCp&&f.cp.visibility==View.GONE,"unsupported/hidden/disabled OEM row intact");ok(f.row.findViewById(0x7f0800e6).layout.width==180,"original row geometry");f.close();}
  f=new Fixture();f.bind();View.OnClickListener old=f.cp.listener;f.phone.vendor=117;f.bind();ok(f.cp.listener==f.originalCp,"recycled Android listener restored");ok(f.row.findViewById(0x7f080006).layout.width==486,"recycled row width restored");old.onClick(f.cp);settle();ok(ManualCarplay.queries==0,"stale Apple listener cannot submit Android");f.close();
  f=new Fixture();f.bind();f.adapter.phones.add(new BluetoothDevice(B));java.util.Collections.swap(f.adapter.phones,0,1);f.click();settle();ok(ManualCarplay.queries==0&&f.nativeClicks==0,"reorder without bind aborts before old position listener");f.close();
  f=new Fixture();f.bind();f.activity.regulated=true;f.click();settle();ok(ManualCarplay.queries==0,"driving guard precedes IPC");f.close();
  f=new Fixture();f.bind();f.activity.f.bt.hfp=f.phone;f.click();await(new Condition(){public boolean ready(){return ManualCarplay.retries==1;}});settle();ok(f.nativeClicks==0,"same HFP never toggles OEM phone button");ok(f.progress.visibility==View.VISIBLE,"accepted not connected");ManualCarplay.queryStatus=ManualCarplay.CONNECTED;ManualCarplay.queryFlags=ManualCarplay.ACTIVE_CP|ManualCarplay.ACTIVE_TARGET;tick(700);ok(f.cp.image==0x7f020017,"actual connected response colors icon");ok(f.progress.visibility==View.GONE,"completed progress cleared");f.bind();settle();ok(f.cp.image==0x7f020017,"OEM rebind reads current connected state");f.close();
  f=new Fixture();f.bind();ManualCarplay.queryStatus=ManualCarplay.PHONE_REQUIRED;ManualCarplay.queryFlags=0;f.nativeAccept=true;f.click();final Fixture accepted=f;await(new Condition(){public boolean ready(){return accepted.nativeClicks==1;}});ManualCarplay.queryStatus=ManualCarplay.READY;ManualCarplay.queryFlags=ManualCarplay.CURRENT_HFP;tick(500);await(new Condition(){public boolean ready(){return ManualCarplay.retries==1;}});ok(f.nativeClicks==1,"different phone delegates OEM once");f.close();
  f=new Fixture();f.bind();ManualCarplay.queryStatus=ManualCarplay.PHONE_REQUIRED;ManualCarplay.queryFlags=0;f.click();final Fixture cancelled=f;await(new Condition(){public boolean ready(){return cancelled.nativeClicks==1;}});tick(2100);ok(ManualCarplay.retries==0,"native refusal/cancel no CP retry");ok(f.cp.visibility==View.VISIBLE,"native refusal makes action retryable");f.close();
  f=new Fixture();f.bind();ManualCarplay.queryStatus=ManualCarplay.BUSY;ManualCarplay.queryFlags=ManualCarplay.ACTIVE_CP;f.click();settle();ok(f.nativeClicks==0&&ManualCarplay.retries==0,"active other CP blocks phone switching");f.close();
  f=new Fixture();f.bind();ManualCarplay.queryStatus=ManualCarplay.PHONE_REQUIRED;ManualCarplay.queryFlags=0;f.activity.f.wireless=true;f.click();settle();ok(f.nativeClicks==0,"local fresh native CP guard before phone action");f.close();
  for(int status:new int[]{ManualCarplay.POWER_OFF,ManualCarplay.DISABLED}) {f=new Fixture();f.bind();ManualCarplay.queryStatus=status;f.click();settle();ok(f.nativeClicks==0&&ManualCarplay.retries==0,"gate refusal no native action");f.close();}
  f=new Fixture();ManualCarplay.queryStatus=ManualCarplay.AP_UNAVAILABLE;ManualCarplay.queryFlags=0;f.bind();settle();
  ok(ManualCarplay.retries==0&&f.activity.starts==0,"passive unavailable AP query never recovers radio");
  f.click();await(new Condition(){public boolean ready(){return ManualCarplay.retries==1;}});settle();
  ok(f.nativeClicks==0&&f.progress.visibility==View.VISIBLE,"explicit unavailable AP query requests one recovery without HFP action");
  ManualCarplay.queryStatus=ManualCarplay.ACCEPTED;ManualCarplay.queryFlags=ManualCarplay.PENDING_TARGET;tick(700);
  ok(ManualCarplay.retries==1&&f.progress.visibility==View.VISIBLE&&f.cp.image!=0x7f020017,"accepted AP recovery polls without duplicate retry or false connection");
  ManualCarplay.queryStatus=ManualCarplay.CONNECTED;ManualCarplay.queryFlags=ManualCarplay.ACTIVE_CP|ManualCarplay.ACTIVE_TARGET;tick(700);
  ok(f.cp.image==0x7f020017&&ManualCarplay.retries==1,"recovered AP requires actual connected target proof");f.close();
  for(int result:new int[]{ManualCarplay.AP_UNAVAILABLE,ManualCarplay.POWER_OFF,ManualCarplay.DISABLED}) {
   f=new Fixture();ManualCarplay.queryStatus=ManualCarplay.AP_UNAVAILABLE;ManualCarplay.retryStatus=result;ManualCarplay.retryFlags=0;f.bind();f.click();
   await(new Condition(){public boolean ready(){return ManualCarplay.retries==1;}});settle();tick(700);
   ok(ManualCarplay.retries==1&&f.cp.visibility==View.VISIBLE&&f.progress.visibility==View.GONE,"AP recovery refusal terminates one explicit retry");
   ok(f.nativeClicks==0,"AP recovery refusal leaves OEM HFP untouched");f.close();
  }
  f=new Fixture();f.bind();ManualCarplay.fail=true;f.click();settle();
  ok(f.activity.starts==1&&f.activity.mainStarts==0&&f.progress.visibility==View.VISIBLE,"explicit click waits for stock service readiness on worker");
  ok(ManualCarplay.retries==0&&f.cp.image!=0x7f020017,"service start is not successful CarPlay connection");
  tick(750);ok(f.activity.starts==1&&f.progress.visibility==View.VISIBLE,"readiness query retries without repeated service start");
  tick(35000);ok(f.cp.visibility==View.VISIBLE&&f.progress.visibility==View.GONE,"IPC readiness deadline restores button");
  ok(Toast.messages.toString().contains("CarPlay 연결 기능이 준비되지 않았습니다. 내비를 재부팅해 주세요."),"missing hook server gives reboot instruction");
  ok(!f.mod.logs.toString().contains("PRIVATE-ADDRESS")&&!Toast.messages.toString().contains("PRIVATE-ADDRESS"),"no exception/identity leak");f.close();
  f=new Fixture();f.bind();ManualCarplay.fail=true;f.click();settle();ManualCarplay.fail=false;tick(750);
  await(new Condition(){public boolean ready(){return ManualCarplay.retries==1;}});settle();
  ok(f.activity.starts==1&&f.cp.image!=0x7f020017,"late server becomes ready without false connection indication");
  ManualCarplay.queryStatus=ManualCarplay.CONNECTED;ManualCarplay.queryFlags=ManualCarplay.ACTIVE_CP|ManualCarplay.ACTIVE_TARGET;tick(700);
  ok(f.cp.image==0x7f020017&&f.progress.visibility==View.GONE,"late server reaches proven target connection");f.close();
  f=new Fixture();f.bind();ManualCarplay.fail=true;f.activity.packages.result=null;f.click();settle();
  ok(f.activity.starts==0&&f.cp.visibility==View.VISIBLE&&f.progress.visibility==View.GONE,"unavailable stock service returns clear terminal failure");
  ok(Toast.messages.toString().contains("순정 CarPlay 서비스를 시작하지 못했습니다"),"unavailable service message");f.close();
  f=new Fixture();f.bind();f.activity.packages.result=null;f.click();
  await(new Condition(){public boolean ready(){return ManualCarplay.retries==1;}});settle();
  ok(f.activity.starts==0&&f.progress.visibility==View.VISIBLE,"already running authenticated server remains usable when bootstrap unavailable");
  ManualCarplay.queryStatus=ManualCarplay.CONNECTED;ManualCarplay.queryFlags=ManualCarplay.ACTIVE_CP|ManualCarplay.ACTIVE_TARGET;tick(700);
  ok(f.cp.image==0x7f020017,"existing server connection is proved independently of service start");f.close();
  f=new Fixture();f.bind();ManualCarplay.fail=true;f.activity.deniedStart=true;f.click();settle();
  ok(f.cp.visibility==View.VISIBLE&&Toast.messages.toString().contains("시작할 권한이 없습니다"),"service permission failure does not leave spinner");
  ok(!Toast.messages.toString().contains("PRIVATE-SERVICE-DETAIL")&&!f.mod.logs.toString().contains("PRIVATE-SERVICE-DETAIL"),"service exception remains private");f.close();
  f=new Fixture();f.bind();ManualCarplay.fail=true;f.click();settle();f.close();int queriesAfterDestroy=ManualCarplay.queries;tick(750);
  ok(f.activity.starts==1&&ManualCarplay.queries==queriesAfterDestroy,"destroy cancels pending bootstrap polls");
  f=new Fixture();f.bind();f.click();f.click();await(new Condition(){public boolean ready(){return ManualCarplay.retries==1;}});settle();ok(ManualCarplay.retries==1,"double click single request");f.close();tick(1000);ok(ManualCarplay.retries==1,"destroy cancels further UI work");
  f=new Fixture();f.bind();ManualCarplay.queryStatus=ManualCarplay.FAILED;f.click();await(new Condition(){public boolean ready(){return ManualCarplay.retries==1;}});ok(ManualCarplay.retries==1,"new click can retry old failed attempt");f.close();
  f=new Fixture();f.bind();ManualCarplay.queryStatus=ManualCarplay.PHONE_REQUIRED;ManualCarplay.queryFlags=0;f.click();final Fixture rebound=f;await(new Condition(){public boolean ready(){return rebound.nativeClicks==1;}});f.activity.mN=f.phone;f.bind();f.activity.f.bt.hfp=f.phone;ManualCarplay.queryStatus=ManualCarplay.READY;ManualCarplay.queryFlags=ManualCarplay.CURRENT_HFP;tick(500);await(new Condition(){public boolean ready(){return ManualCarplay.retries==1;}});ok(ManualCarplay.retries==1,"OEM rebind same target retains pending request");f.close();
  for(int status:new int[]{ManualCarplay.FAILED,ManualCarplay.TIMED_OUT,ManualCarplay.PHONE_REQUIRED}) {
   f=new Fixture();f.bind();ManualCarplay.queryStatus=ManualCarplay.FAILED;ManualCarplay.retryStatus=status;ManualCarplay.retryFlags=ManualCarplay.CURRENT_HFP;
   f.click();await(new Condition(){public boolean ready(){return ManualCarplay.retries>=1;}});settle();
   ok(ManualCarplay.retries==1,"one click issues at most one failed retry");ok(f.cp.visibility==View.VISIBLE&&f.progress.visibility==View.GONE,"retry failure finishes UI request");
   ok(f.nativeClicks==0,"retry failure does not restart native phone selection");tick(1000);ok(ManualCarplay.retries==1,"terminal retry schedules no loop");
   f.click();await(new Condition(){public boolean ready(){return ManualCarplay.retries>=2;}});settle();ok(ManualCarplay.retries==2,"new explicit click permits exactly one new retry");f.close();
  }
  f=new Fixture();ManualCarplay.queryStatus=ManualCarplay.CONNECTED;ManualCarplay.queryFlags=ManualCarplay.ACTIVE_CP|ManualCarplay.ACTIVE_TARGET;f.bind();settle();
  ok(f.cp.image==0x7f020017&&"CarPlay 연결됨".contentEquals(f.cp.description),"already connected before opening shows live connected");
  ok(ManualCarplay.retries==0&&f.nativeClicks==0&&Toast.messages.isEmpty()&&f.activity.starts==0,"initial status is silent and read-only");
  ManualCarplay.queryStatus=ManualCarplay.READY;ManualCarplay.queryFlags=ManualCarplay.CURRENT_HFP;tick(1600);
  ok(f.cp.image==0x7f020018,"disconnect clears live icon without rebind");
  ManualCarplay.queryStatus=ManualCarplay.CONNECTED;ManualCarplay.queryFlags=ManualCarplay.ACTIVE_CP|ManualCarplay.ACTIVE_TARGET;tick(1600);
  ok(f.cp.image==0x7f020017,"external connection updates without a click");
  ManualCarplay.fail=true;tick(1600);ok(f.cp.image==0x7f020018,"failed query cannot retain false connected icon");
  ok(f.activity.starts==0,"all passive refreshes remain unable to bootstrap service");
  int beforeClose=ManualCarplay.queries;f.close();tick(5000);ok(ManualCarplay.queries==beforeClose,"destroy stops background status queries");
  for(int flags:new int[]{0,ManualCarplay.ACTIVE_CP,ManualCarplay.ACTIVE_TARGET}) {
   f=new Fixture();ManualCarplay.queryStatus=ManualCarplay.CONNECTED;ManualCarplay.queryFlags=flags;f.bind();settle();
   ok(f.cp.image==0x7f020018&&ManualCarplay.retries==0,"other or unproven target never shown connected");
   f.click();settle();ok(!Toast.messages.toString().contains("CarPlay가 연결되어 있습니다."),"explicit query also requires active target proof");f.close();
  }
  f=new Fixture();ManualCarplay.queryStatus=ManualCarplay.ACCEPTED;ManualCarplay.queryFlags=ManualCarplay.ACTIVE_CP|ManualCarplay.ACTIVE_TARGET;f.bind();settle();
  ok(f.cp.image==0x7f020018,"pending session flags are not connected proof");f.close();
  f=new Fixture();f.row.attached=false;f.bind();settle();ok(ManualCarplay.queries==0,"detached row does not start status IPC");f.close();
  f=new Fixture();f.activity.focused=false;f.bind();settle();ok(ManualCarplay.queries==0,"background activity does not start status IPC");f.activity.focused=true;tick(1600);ok(ManualCarplay.queries==1,"foreground status refresh resumes");f.close();
  RowConnectState s=new RowConnectState();long n=s.begin(A,1);ok(n>0&&s.live(n,2),"request active");ok(s.begin(B,2)==0,"one request");s.phase(n,RowConnectState.NATIVE);ok(!s.hfpReady(n,B,3),"other HFP cannot satisfy request");ok(s.hfpReady(n,A,3),"selected HFP satisfies");s.cancel();ok(!s.live(n,4),"cancel invalidates token");long n2=s.begin(A,5);s.phase(n,RowConnectState.DONE);ok(s.phase()==RowConnectState.CHECKING,"stale result cannot finish newer request");ok(!s.live(n2,35005)&&s.phase()==RowConnectState.FAILED,"bounded timeout");ok(RowConnectState.bytes("bad")==null,"malformed identity rejected");ok(RowConnectState.bytes(A).length==6,"fixed identity bytes");
  serviceValidation();
  System.out.println("PASS "+checks+" per-phone settings checks");
 }
}
