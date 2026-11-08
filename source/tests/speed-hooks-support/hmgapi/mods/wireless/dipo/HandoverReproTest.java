package hmgapi.mods.wireless.dipo;
import android.os.Handler;
import hmgapi.hook.*;
/** Same black-box symptom test runs against unchanged 1.0.14 and the candidate. */
public final class HandoverReproTest {
 public static void main(String[] args){
  ModContext mod=new ModContext();CarPlayVehicleSpeed.install(mod,new LoadedApp());
  Object cp=new CarPlayVehicleSpeedHookTest.Cp();Bridge.sCp=cp;Bridge.sCpWireless=true;Bridge.sSession=true;
  MethodHook created=mod.hooks.get("dipo.carplay.session_created");
  MethodHook started=mod.hooks.get("dipo.carplay.session_started");
  if(created!=null){created.before(cp);created.after(cp,null);}
  if(started!=null){started.before(cp,1);started.after(cp,null,1);}
  CarPlayVehicleSpeedHookTest.Manager m=new CarPlayVehicleSpeedHookTest.Manager();
  CarPlayVehicleSpeedHookTest.Listener listener=new CarPlayVehicleSpeedHookTest.Listener(m);
  CarPlayVehicleSpeedHookTest.start(mod,m,new Object());
  Bridge.sSession=false; // Bridge.onSppDown: wireless CP remains active.
  CarPlayVehicleSpeedHookTest.change(mod,listener,50);Handler.drain();
  if(m.sent.size()!=1)throw new AssertionError("active wireless CarPlay after SPP handover expected 1 speed send, got "+m.sent.size());
  System.out.println("HandoverReproTest PASS");
 }
}
