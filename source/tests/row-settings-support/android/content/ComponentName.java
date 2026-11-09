package android.content;
public class ComponentName {
 private final String pkg,cls;
 public ComponentName(String p,String c){pkg=p;cls=c;}
 public String getPackageName(){return pkg;}
 public String getClassName(){return cls;}
}
