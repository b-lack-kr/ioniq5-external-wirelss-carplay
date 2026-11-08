package hmgapi.mods.wireless.dipo;
import android.os.Handler;
import hmgapi.hook.*;
import java.util.*;

/** Drive the actual candidate hook adapter through realistic callback ordering. */
public final class CarPlaySpeedLifecycleTest {
 static int checks;
 static void check(boolean pass,String why){checks++;if(!pass)throw new AssertionError(why);}
 static CarPlayVehicleSpeedHookTest.Manager manager(){return new CarPlayVehicleSpeedHookTest.Manager();}
 static CarPlayVehicleSpeedHookTest.Listener listener(CarPlayVehicleSpeedHookTest.Manager m){return new CarPlayVehicleSpeedHookTest.Listener(m);}
 static void request(ModContext mod,CarPlayVehicleSpeedHookTest.Manager m){CarPlayVehicleSpeedHookTest.start(mod,m,new Object());}
 static void change(ModContext mod,CarPlayVehicleSpeedHookTest.Listener l,int v){CarPlayVehicleSpeedHookTest.change(mod,l,v);}
 static void create(ModContext mod,Object cp){
  // Candidate Dipo registers Bridge first, so its before-hook resolves owner
  // and transport before the supplemental-location observer runs.
  Bridge.sCp=cp;Bridge.sCpWireless=false;
  mod.hooks.get("dipo.carplay.session_created").before(cp);
  mod.hooks.get("dipo.carplay.session_created").after(cp,null);
 }
 static void begin(ModContext mod,Object cp,boolean wireless){
  // Bridge runs its own before-hook first, resolving proven raw-0 transport too.
  if(cp==Bridge.sCp)Bridge.sCpWireless=wireless;
  mod.hooks.get("dipo.carplay.session_started").before(cp,wireless?1:0);
 }
 static void complete(ModContext mod,Object cp,Throwable error){mod.hooks.get("dipo.carplay.session_started").after(cp,error,1);}
 static void start(ModContext mod,Object cp){begin(mod,cp,true);complete(mod,cp,null);}
 static void end(ModContext mod,Object cp){
  mod.hooks.get("dipo.carplay.session_finalized").before(cp,0);
  if(cp==Bridge.sCp){Bridge.sCp=null;Bridge.sCpWireless=false;}
 }
 public static void main(String[] args){
  final ModContext mod=new ModContext();CarPlayVehicleSpeed.install(mod,new LoadedApp());
  final CarPlayVehicleSpeedHookTest.Manager m=manager();final CarPlayVehicleSpeedHookTest.Listener l=listener(m);
  Bridge.sCp=null;Bridge.sSession=true;Bridge.sCpWireless=true;
  request(mod,m);change(mod,l,1);Handler.drain();
  check(m.sent.isEmpty(),"SPP bootstrap plus stale wireless flag does not imply active CP object");
  final Object first=new CarPlayVehicleSpeedHookTest.Cp();create(mod,first);
  change(mod,l,2);Handler.drain();check(m.sent.isEmpty(),"created CarPlay object alone cannot send");
  begin(mod,first,true);change(mod,l,3);Handler.drain();
  check(m.sent.isEmpty(),"callback in progress cannot send even after Bridge sets wireless");
  complete(mod,first,null);change(mod,l,4);Handler.drain();
  check(m.sent.equals(Arrays.asList("$PASCD,fixture,4\u0000")),"bootstrap request binds once after successful wireless start");
  Bridge.sSession=false;change(mod,l,5);Handler.drain();
  check(m.sent.size()==2,"normal SPP disconnection preserves wireless speed supplement");
  Bridge.sUsb=true;change(mod,l,6);Handler.drain();
  check(m.sent.size()==2,"wired USB transport veto remains in effect");Bridge.sUsb=false;
  Bridge.active=false;change(mod,l,7);Handler.drain();
  check(m.sent.size()==2,"bridge disable remains in effect");Bridge.active=true;
  // Old queued callback must not migrate to a replacement CP object, even if
  // the OEM location manager's requested flag remains true.
  change(mod,l,8);final Object second=new CarPlayVehicleSpeedHookTest.Cp();create(mod,second);start(mod,second);
  Handler.drain();check(m.sent.size()==2,"queued old transport task cannot send on replacement");
  change(mod,l,9);Handler.drain();check(m.sent.size()==2,"bound old location request never auto-rebinds to replacement");
  request(mod,m);change(mod,l,10);Handler.drain();check(m.sent.size()==3,"genuine location re-request admits replacement CP");
  end(mod,first);change(mod,l,11);Handler.drain();check(m.sent.size()==4,"late finalize for old object cannot kill replacement");
  begin(mod,first,true);complete(mod,first,null);change(mod,l,12);Handler.drain();
  check(m.sent.size()==5,"late start for old object cannot replace current active CP");
  // Replacing CP while OEM formatting is in progress must discard the old
  // string, even if the same manager and requested=true survive.
  final Object third=new CarPlayVehicleSpeedHookTest.Cp();
  m.duringFormat=new Runnable(){public void run(){create(mod,third);start(mod,third);}};
  change(mod,l,13);Handler.drain();check(m.sent.size()==5,"replacement during format invalidates final send guard");
  request(mod,m);change(mod,l,14);Handler.drain();check(m.sent.size()==6,"new generation can send after a real location request");
  m.duringFormat=new Runnable(){public void run(){end(mod,third);}};
  change(mod,l,15);Handler.drain();check(m.sent.size()==6,"finalization during formatting blocks send");
  change(mod,l,16);Handler.drain();check(m.sent.size()==6,"after finalized no supplemental writes");
  // Same native owner reused in a new session still receives a distinct token.
  create(mod,third);start(mod,third);change(mod,l,17);Handler.drain();
  check(m.sent.size()==6,"same CP object reuse cannot reactivate old location request");
  request(mod,m);change(mod,l,18);Handler.drain();check(m.sent.size()==7,"same-object new location request is accepted");
  begin(mod,third,true);complete(mod,third,new IllegalStateException("OEM start failed"));
  change(mod,l,19);Handler.drain();check(m.sent.size()==7,"failed started callback never activates sender");
  start(mod,third);change(mod,l,20);Handler.drain();check(m.sent.size()==8,"successful subsequent start restores same CP generation");
  // A location request arriving after creation but before start must retain that
  // created generation, and no writes happen until the start completion.
  final Object fourth=new CarPlayVehicleSpeedHookTest.Cp();create(mod,fourth);request(mod,m);
  change(mod,l,21);Handler.drain();check(m.sent.size()==8,"created-but-not-started request remains inert");
  start(mod,fourth);change(mod,l,22);Handler.drain();check(m.sent.size()==9,"pre-start request survives normal activation");
  // Native raw transport zero may be classified as wireless by existing Bridge
  // evidence. The candidate uses that proven flag, not raw transport alone.
  final Object fifth=new CarPlayVehicleSpeedHookTest.Cp();create(mod,fifth);request(mod,m);
  mod.hooks.get("dipo.carplay.session_started").before(fifth,0);Bridge.sCpWireless=true;
  mod.hooks.get("dipo.carplay.session_started").after(fifth,null,0);
  change(mod,l,23);Handler.drain();check(m.sent.size()==10,"Bridge-confirmed wireless raw-0 session is accepted");
  final Object wired=new CarPlayVehicleSpeedHookTest.Cp();create(mod,wired);request(mod,m);
  begin(mod,wired,false);complete(mod,wired,null);change(mod,l,24);Handler.drain();
  check(m.sent.size()==10,"unconfirmed raw-0 wired session is excluded");
  // Nested/out-of-order start completion cannot revive an older attempt.
  begin(mod,wired,true);begin(mod,wired,true);complete(mod,wired,new IllegalStateException());complete(mod,wired,null);
  change(mod,l,25);Handler.drain();check(m.sent.size()==10,"older success cannot override newer failed nested start");
  start(mod,wired);m.override="$GPRMC,not-speed\r\n";change(mod,l,26);Handler.drain();
  check(m.sent.size()==10,"PASCD header guard remains after lifecycle changes");
  m.override=null;m.duringFormat=new Runnable(){public void run(){Bridge.sCp=null;}};
  change(mod,l,27);Handler.drain();check(m.sent.size()==10,"Bridge reset while formatting blocks stale send even without finalize callback");
  final Object sixth=new CarPlayVehicleSpeedHookTest.Cp();create(mod,sixth);start(mod,sixth);request(mod,m);
  final Object seventh=new CarPlayVehicleSpeedHookTest.Cp();
  m.duringFormat=new Runnable(){public void run(){create(mod,seventh);start(mod,seventh);request(mod,m);change(mod,l,99);}};
  change(mod,l,28);Handler.drain();
  check(m.sent.size()==10&&Handler.queue.size()==1,"replacement CP and fresh request during old formatter discard old data and preserve new pending task");
  Handler.drain();check(m.sent.size()==11&&m.sent.get(10).equals("$PASCD,fixture,99\u0000"),"new CP generation receives only fresh request's actual value");
  final Object failedCreate=new CarPlayVehicleSpeedHookTest.Cp();
  mod.hooks.get("dipo.carplay.session_created").before(failedCreate);Bridge.sCp=failedCreate;
  mod.hooks.get("dipo.carplay.session_created").after(failedCreate,new IllegalStateException());
  start(mod,failedCreate);request(mod,m);change(mod,l,100);Handler.drain();
  check(m.sent.size()==11,"failed created callback cannot be revived by started callback alone");
  System.out.println("CarPlaySpeedLifecycleTest PASS "+checks+" checks");
 }
}
