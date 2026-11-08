package hmgapi.hook;
import java.util.*;
public class ModContext {
 public boolean active=true,bridge=true;public final Map<String,MethodHook> hooks=new LinkedHashMap<String,MethodHook>();public final List<String> logs=new ArrayList<String>();
 public boolean enabled(){return active;}public boolean settingBoolean(String key,boolean fallback){if(!key.equals("bridge"))throw new AssertionError(key);return bridge;}
 public void hook(ClassLoader cl,String point,MethodHook hook){if(hooks.put(point,hook)!=null)throw new AssertionError("duplicate");}
 public void log(String line){logs.add(line);}
}
