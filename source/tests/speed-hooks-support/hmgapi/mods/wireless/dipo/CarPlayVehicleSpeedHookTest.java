package hmgapi.mods.wireless.dipo;
import android.os.*;
import hmgapi.hook.*;
import java.util.*;

/** Exercise the real hook adapter with fake OEM members; no car sensor/IPC calls. */
public final class CarPlayVehicleSpeedHookTest {
 static int checks;
 public static final class Cp { public void created(){} public void started(int type){} public void finalized(int type){} }
 static void cpStart(ModContext mod,Object cp){
  Bridge.sCp=cp;Bridge.sUsb=false;Bridge.sCpWireless=true;
  MethodHook created=mod.hooks.get("dipo.carplay.session_created");created.before(cp);created.after(cp,null);
  MethodHook started=mod.hooks.get("dipo.carplay.session_started");started.before(cp,1);started.after(cp,null,1);
 }
 static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 public static final class Manager {
  private boolean requested=true;boolean rmcRequested=true;private int wrong;
  int latest,formats;boolean fail;String override;Runnable duringFormat;
  final List<String> sent=new ArrayList<String>();
  private String format(){
   formats++;String result=override!=null?override:"$PASCD,fixture,"+latest+"\r\n";
   if(duringFormat!=null){Runnable next=duringFormat;duringFormat=null;next.run();}
   return result;
  }
  public void send(String value){if(fail)throw new IllegalStateException("send failure");sent.add(value);}
  public void start(Object request){}public void stop(){}
 }
 public static final class Listener {
  private final Manager manager;Listener(Manager value){manager=value;}
  public void changed(int speed){manager.latest=speed;}
 }
 static void start(ModContext mod,Manager manager,Object request){MethodHook h=mod.hooks.get("dipo.location.start");h.before(manager,request);h.after(manager,null,request);}
 static void change(ModContext mod,Listener listener,int value){listener.changed(value);mod.hooks.get("dipo.location_speed.changed").after(listener,null,value);}
 public static void main(String[] args){
  Points.mismatch=true;ModContext mismatch=new ModContext();CarPlayVehicleSpeed.install(mismatch,new LoadedApp());Points.mismatch=false;
  check(mismatch.hooks.isEmpty()&&HandlerThread.started==0&&mismatch.logs.contains("USB_CP_LOCATION pulse_unavailable"),"inexact mapped type fails before hooks and worker");
  ModContext partial=new ModContext();partial.rejectHook=3;CarPlayVehicleSpeed.install(partial,new LoadedApp());
  check(HandlerThread.closed==1&&partial.hooks.size()==2,"partial hook failure closes worker");
  Manager inert=new Manager();start(partial,inert,new Object());check(Handler.queue.isEmpty(),"partial hooks remain inert");
  ModContext mod=new ModContext();CarPlayVehicleSpeed.install(mod,new LoadedApp());
  check(mod.hooks.size()==7&&mod.logs.contains("USB_CP_LOCATION pulse_ready min_gap_ms=0"),"location/MICOM plus CP lifecycle points installed, immediate mode");
  cpStart(mod,new Cp());
  Manager manager=new Manager();Listener listener=new Listener(manager);
  change(mod,listener,12);Handler.drain();check(manager.sent.isEmpty(),"speed before phone request is ignored");
  start(mod,manager,new Object());change(mod,listener,13);change(mod,listener,14);
  check(manager.sent.isEmpty()&&Handler.queue.size()==1,"callbacks enqueue only one job");
  Handler.drain();check(manager.sent.equals(Arrays.asList("$PASCD,fixture,14\u0000")),"same OEM formatter/sender preserve current value with stock terminator conversion");
  for(int i=0;i<1000;i++)change(mod,listener,14);Handler.drain();
  check(manager.sent.size()==1&&Handler.queue.isEmpty(),"repeated OEM integer speed does not resend extra PASCD");
  int sent=manager.sent.size();manager.requested=false;change(mod,listener,15);Handler.drain();check(manager.sent.size()==sent,"PASCD must be requested by phone");manager.requested=true;
  change(mod,listener,15);Handler.drain();check(manager.sent.size()==sent+1,"disallowed callback does not hide later first valid raw speed");sent++;
  Bridge.sCpWireless=false;change(mod,listener,16);Handler.drain();check(manager.sent.size()==sent,"wired sessions unchanged");Bridge.sCpWireless=true;
  Bridge.sSession=false;change(mod,listener,17);Handler.drain();check(manager.sent.size()==sent+1,"SPP handover does not disable active wireless CarPlay");sent++;Bridge.sSession=true;
  Bridge.active=false;change(mod,listener,18);Handler.drain();check(manager.sent.size()==sent,"bridge runtime switch enforced");Bridge.active=true;
  mod.fast=false;change(mod,listener,19);Handler.drain();check(manager.sent.size()==sent+1,"removed legacy speed option cannot silently disable immediate path");sent++;mod.fast=true;
  mod.active=false;change(mod,listener,20);Handler.drain();check(manager.sent.size()==sent,"mod disable enforced");mod.active=true;
  change(mod,listener,-1);change(mod,listener,511);Handler.drain();check(manager.sent.size()==sent,"invalid OEM codes do not create extra writes");
  mod.hooks.get("dipo.location_speed.changed").after(listener,new IllegalStateException(),21);Handler.drain();check(manager.sent.size()==sent,"failed OEM callback ignored");
  change(mod,listener,22);manager.requested=false;Handler.drain();check(manager.sent.size()==sent,"request ending after queue blocks send");manager.requested=true;
  change(mod,listener,23);mod.hooks.get("dipo.location.stop").before(manager);Handler.drain();check(manager.sent.size()==sent,"stop removes queued extra write");
  start(mod,manager,null);change(mod,listener,24);Handler.drain();check(manager.sent.size()==sent,"null request cannot start speed supplement");
  start(mod,manager,new Object());manager.override="$GPRMC,unexpected\r\n";change(mod,listener,25);Handler.drain();check(manager.sent.size()==sent,"non PASCD is never sent by supplement");manager.override=null;
  manager.fail=true;change(mod,listener,26);Handler.drain();check(Collections.frequency(mod.logs,"USB_CP_LOCATION pulse_failed")==1,"IPC failure emits finite diagnostic");
  manager.fail=false;change(mod,listener,27);Handler.drain();check(manager.sent.size()==sent,"failed request waits for genuine re-request");
  start(mod,manager,new Object());change(mod,listener,28);Handler.drain();check(manager.sent.size()==sent+1,"new request recovers exact OEM path");
  check(!mod.hooks.containsKey("dipo.location_nmea.received"),"GPS input delivery not hooked or replaced");
  // Regression: the same OEM manager can have requested=true again by the
  // time an older formatter returns. A boolean request gate alone sends 41.
  final Manager switched=new Manager();final Listener switchedListener=new Listener(switched);
  start(mod,switched,new Object());
  switched.duringFormat=new Runnable(){public void run(){
   mod.hooks.get("dipo.location.stop").before(switched);
   start(mod,switched,new Object());change(mod,switchedListener,99);
  }};
  change(mod,switchedListener,41);Handler.drain();
  check(switched.sent.isEmpty()&&Handler.queue.size()==1,"same-manager request replacement discards old formatted value and keeps new task");
  Handler.drain();check(switched.sent.equals(Arrays.asList("$PASCD,fixture,99\u0000")),"replacement request sends its latest actual OEM value once");
  switched.duringFormat=new Runnable(){public void run(){mod.hooks.get("dipo.location.stop").before(switched);}};
  change(mod,switchedListener,100);Handler.drain();
  check(switched.sent.size()==1&&Handler.queue.isEmpty(),"stop while formatting discards value without follow-up work");
  start(mod,switched,new Object());
  switched.duringFormat=new Runnable(){public void run(){switched.requested=false;}};
  change(mod,switchedListener,101);Handler.drain();
  check(switched.sent.size()==1,"PASCD request removed while formatting blocks final send");
  switched.requested=true;start(mod,switched,new Object());
  final Manager nextManager=new Manager();final Listener nextListener=new Listener(nextManager);
  switched.duringFormat=new Runnable(){public void run(){start(mod,nextManager,new Object());change(mod,nextListener,102);}};
  change(mod,switchedListener,103);Handler.drain();
  check(switched.sent.size()==1&&Handler.queue.size()==1,"different-manager replacement discards previous manager payload");
  Handler.drain();check(nextManager.sent.equals(Arrays.asList("$PASCD,fixture,102\u0000")),"new manager receives only its own latest OEM value");
  System.out.println("CarPlayVehicleSpeedHookTest PASS "+checks+" checks");
 }
}
