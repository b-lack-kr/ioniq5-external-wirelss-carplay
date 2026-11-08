package hmgapi.hook;
import java.lang.reflect.Method;
public final class Points {
    public static Method selected;public static Object receiver;public static int constructors;
    public static Method method(ClassLoader cl,String key){
        if(!"framework.navigation_cluster.update_remain_dis".equals(key))throw new AssertionError(key);
        if(selected==null)throw new IllegalStateException();return selected;
    }
    public static Object newInstance(ClassLoader cl,String key,Object...args){
        if(!"framework.navigation_cluster.new".equals(key)||args.length!=1)throw new AssertionError(key);
        constructors++;return receiver;
    }
}
