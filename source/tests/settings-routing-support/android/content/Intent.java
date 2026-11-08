package android.content;
import java.util.HashMap;
public class Intent {
 public static final int FLAG_ACTIVITY_NEW_TASK=0x10000000,FLAG_ACTIVITY_CLEAR_TOP=0x04000000;
 public final String action;public String pkg,cls;public int flags;
 public final HashMap<String,String> extras=new HashMap<String,String>();
 public Intent(String action){this.action=action;}
 public Intent setClassName(String pkg,String cls){this.pkg=pkg;this.cls=cls;return this;}
 public Intent putExtra(String key,String value){extras.put(key,value);return this;}
 public Intent addFlags(int flags){this.flags|=flags;return this;}
}
