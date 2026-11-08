package hmgapi.hook;
import java.util.*;
public class ModContext {
 public boolean active=true;public int rejectAt=-1;
 public final Map<String,MethodHook> hooks=new LinkedHashMap<String,MethodHook>();
 public final List<String> logs=new ArrayList<String>();
 public boolean enabled(){return active;}public void log(String s){logs.add(s);}
 public void hook(ClassLoader cl,String point,MethodHook h){if(hooks.size()==rejectAt)throw new IllegalStateException();hooks.put(point,h);}
}
