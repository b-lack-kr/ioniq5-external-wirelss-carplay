package hmgapi.hook;
import java.lang.reflect.*;
import hmgapi.mods.wireless.dipo.CarPlayVehicleSpeedHookTest;
public final class Points {
 public static boolean mismatch;
 public static Field field(ClassLoader cl,String point)throws Exception{
  if(point.equals("dipo.location_speed.manager"))return accessible(CarPlayVehicleSpeedHookTest.Listener.class.getDeclaredField("manager"));
  if(point.equals("dipo.location.rmc_requested"))return accessible(CarPlayVehicleSpeedHookTest.Manager.class.getDeclaredField("rmcRequested"));
  if(point.equals("dipo.location.pascd_requested"))return accessible(CarPlayVehicleSpeedHookTest.Manager.class.getDeclaredField(mismatch?"wrong":"requested"));
  throw new AssertionError(point);
 }
 private static Field accessible(Field f){f.setAccessible(true);return f;}
 public static Method method(ClassLoader cl,String point)throws Exception{
  Method m;
  if(point.equals("dipo.location.make_pascd"))m=CarPlayVehicleSpeedHookTest.Manager.class.getDeclaredMethod("format");
  else if(point.equals("dipo.location.sentence"))m=CarPlayVehicleSpeedHookTest.Manager.class.getDeclaredMethod("send",String.class);
  else if(point.equals("dipo.location.start"))m=CarPlayVehicleSpeedHookTest.Manager.class.getDeclaredMethod("start",Object.class);
  else if(point.equals("dipo.location.stop"))m=CarPlayVehicleSpeedHookTest.Manager.class.getDeclaredMethod("stop");
  else if(point.equals("dipo.location_speed.changed"))m=CarPlayVehicleSpeedHookTest.Listener.class.getDeclaredMethod("changed",int.class);
  else if(point.equals("dipo.carplay.session_created"))m=CarPlayVehicleSpeedHookTest.Cp.class.getDeclaredMethod("created");
  else if(point.equals("dipo.carplay.session_started"))m=CarPlayVehicleSpeedHookTest.Cp.class.getDeclaredMethod("started",int.class);
  else if(point.equals("dipo.carplay.session_finalized"))m=CarPlayVehicleSpeedHookTest.Cp.class.getDeclaredMethod("finalized",int.class);
  else throw new AssertionError(point);
  m.setAccessible(true);return m;
 }
}
