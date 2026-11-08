package hmgapi.hook;
import java.lang.reflect.Method;
public final class Points {
    public static Method selected;
    public static Object receiver;
    public static int lookups, constructors;
    public static Method method(ClassLoader loader, String key) {
        if (!"framework.navigation_cluster.update_lane_info".equals(key)) throw new AssertionError(key);
        lookups++;
        if (selected == null) throw new IllegalStateException("map not ready");
        return selected;
    }
    public static Object newInstance(ClassLoader loader, String key, Object... args) {
        if (!"framework.navigation_cluster.new".equals(key) || args.length != 1)
            throw new AssertionError(key);
        constructors++;
        return receiver;
    }
}
