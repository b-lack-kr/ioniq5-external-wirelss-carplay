package hmgapi.hook;
import java.util.*;
public final class ModContext {
 public boolean active=true,fast=true;public int rejectHook;
 public final Map<String,MethodHook> hooks=new LinkedHashMap<String,MethodHook>();
 public final List<String> logs=new ArrayList<String>();
 public boolean enabled(){return active;}
 public boolean settingBoolean(String key,boolean fallback){if(!key.equals("carplay_vehicle_speed_fast"))throw new AssertionError(key);return fast;}
 public void hook(ClassLoader cl,String key,MethodHook hook){if(rejectHook!=0&&hooks.size()+1==rejectHook)throw new IllegalStateException("hook failure");hooks.put(key,hook);}
 public void log(String message){logs.add(message);}
}
