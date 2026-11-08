"""Execute production RgListener.end against real CpTurns workers.

Both native takeover and transport/session disconnect revoke queued optional output.
Basic TBT/HUD/trip cannot clear a new stock owner. A phone-owned stop or actual
disconnect keeps its original clear. Route state 0 clears optional output normally.
"""
from pathlib import Path
import subprocess,tempfile
from jvm_tools import java_bin
source=Path(__file__).resolve().parents[1]
production=source/'mod/src/hmgapi/mods/wireless/dipo'
text=(production/'Dipo.java').read_text()
a=text.index('        void end(boolean disconnected) {')
b=text.index('\n        // Stock DiPO issues START',a)
body=text[a:b]
harness='''package hmgapi.mods.wireless.dipo;
import java.util.*;
final class Dipo {
 static final List<String> logs=new ArrayList<String>(); static void log(String s){logs.add(s);}
 static final class App { final android.content.Context context=new android.content.Context(); }
 static final App app=new App();
 static final class Car { static boolean guiding=true; static boolean carPlayGuiding(android.content.Context ignored){return guiding;} }
 static CpTurns turns; static RgListener rg; static Runnable queued;
 static void post(Runnable r){if(queued!=null)throw new AssertionError("unbounded stop queue");queued=r;}
 static void flush(){Runnable r=queued;queued=null;if(r!=null)r.run();}
 static final class RgListener {
  int component=5;Object tripGeneration=new Object();final java.util.concurrent.atomic.AtomicLong epoch=new java.util.concurrent.atomic.AtomicLong();
  boolean requested,receivedRoute,receivedManeuver;
'''+body+'''
 }
 public static void main(String[] args) {
  AuxiliaryIsolationTest f=new AuxiliaryIsolationTest();turns=f.turns;rg=new RgListener();
  f.start();turns.lanes(AuxiliaryIsolationTest.exit(5));f.worker.run();
  com.tcc.iap2.Iap2NotiMsg trip=AuxiliaryIsolationTest.route(3,5,100);
  trip.getItemGroup().add(7,6,new int[]{12000});turns.routeGuidance(trip);
  AuxiliaryIsolationTest.check(f.count("sub ")>0,"fixture has active trip total before stock takeover");
  int lanes=f.count("lanes "),patterns=f.pattern.sent.size();
  turns.lanes(AuxiliaryIsolationTest.lanes(7,5,AuxiliaryIsolationTest.lane(0,2,new int[]{0},1000)));
  rg.end(false); // Exact production native-takeover path; before handler stop runs.
  f.worker.run();AuxiliaryIsolationTest.check(f.count("lanes ")==lanes && f.pattern.sent.size()==patterns,"immediate takeover revokes queued old auxiliary state");
  Car.guiding=false; // OEM takes ownership while end's clear is still queued.
  int primaryWrites=f.cluster.calls.size();
  flush();f.worker.run();AuxiliaryIsolationTest.check(f.count("lanes ")==lanes && f.pattern.sent.size()==patterns,"delayed disconnect clear cannot overwrite new native owner");
  AuxiliaryIsolationTest.check(f.cluster.calls.size()==primaryWrites,"queued stock takeover cannot clear main/HUD/trip/name output");
  Car.guiding=true;
  turns.setOutputAllowed(true);f.start();turns.lanes(AuxiliaryIsolationTest.exit(5));f.worker.run();
  AuxiliaryIsolationTest.check(f.count("lanes ")==lanes+1 && LaneGuidanceTest.frameByte(f.last("lanes "),1)==3
      && f.pattern.sent.size()>patterns && f.pattern.last()[0]==65535,"real phone ownership return resumes lanes with conservative pattern clear");
  lanes=f.count("lanes ");patterns=f.pattern.sent.size();
  turns.lanes(AuxiliaryIsolationTest.lanes(7,5)); // A clear is queued but not yet inside IPC.
  rg.end(true);f.worker.run();
  AuxiliaryIsolationTest.check(f.count("lanes ")==lanes && f.pattern.sent.size()==patterns,"session disconnect immediately revokes previously queued optional clear");
  flush();f.worker.run();
  AuxiliaryIsolationTest.check(f.count("lanes ")==lanes && f.pattern.sent.size()==patterns,"ended session cannot enqueue a new optional clear over another owner");
  AuxiliaryIsolationTest.check(f.last("tbt ").startsWith("tbt 0 ") && f.last("hud ").equals("hud f2"),"actual disconnect preserves baseline main/HUD clear");
  rg=new RgListener();turns.setOutputAllowed(true);f.start();turns.lanes(AuxiliaryIsolationTest.exit(5));f.worker.run();
  lanes=f.count("lanes ");patterns=f.pattern.sent.size();
  rg.end(false);flush();f.worker.run();
  AuxiliaryIsolationTest.check(f.last("tbt ").startsWith("tbt 0 ") && f.last("hud ").equals("hud f2"),"still phone-owned stop preserves baseline main/HUD clear");
  AuxiliaryIsolationTest.check(f.count("lanes ")==lanes && f.pattern.sent.size()==patterns,"phone-owned stop never revives revoked auxiliary gate");
  rg=new RgListener();turns.setOutputAllowed(true);f.start();turns.lanes(AuxiliaryIsolationTest.exit(5));f.worker.run();
  com.tcc.iap2.Iap2NotiMsg stopped=new com.tcc.iap2.Iap2NotiMsg();
  stopped.getItemGroup().add(0,5,new int[]{7}).add(1,8,new int[]{0});
  turns.routeGuidance(stopped);f.worker.run();
  AuxiliaryIsolationTest.check(f.last("lanes ").equals("lanes f1") && f.pattern.last()[0]==65535,"route state zero in a live phone-owned session retains ordinary auxiliary clear");
  System.out.println("ProductionAuxOwnershipTest: stock takeover primary/trip gate, exact end(false/true) revocation and live-session route-stop clear pass");
 }
}
'''
jdk=java_bin(source)
with tempfile.TemporaryDirectory(prefix='neev-aux-owner-') as temp:
 root=Path(temp);dipo=root/'Dipo.java';dipo.write_text(harness)
 files=[p for p in (source/'tests/tbt-support').rglob('*.java') if p.name!='Dipo.java']
 names=['CpTurns','NativeJunction','NativeSideStreet','CpAuxWriter','CpLaneCallback','CpLaneFrame','CpLaneDecoder','CpJunctions','CpJunctionTable']
 files += [production/(name+'.java') for name in names]
 files += [source/'tests/lanes'/(name+'.java') for name in ['LaneGuidanceTest','LaneLifecycleTest','AuxiliaryIsolationTest']]
 files += [source/'mod/stubs/com/tcc/iap2/Iap2NotiMsg.java',dipo]
 subprocess.run([jdk/'javac','--release','8','-encoding','UTF-8','-d',root,*files],check=True)
 subprocess.run([jdk/'java','-cp',root,'hmgapi.mods.wireless.dipo.Dipo'],check=True)
