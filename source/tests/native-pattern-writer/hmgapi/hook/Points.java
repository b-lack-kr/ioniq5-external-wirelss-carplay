package hmgapi.hook;

import java.lang.reflect.Method;

/** Only the pre-existing reflection surface used by the production writer. */
public final class Points {
    public static Method selected;
    public static Object receiver;
    public static Throwable methodFailure, receiverFailure;
    public static int methodCalls, receiverCalls;
    public static Object constructorContext;

    public static Method method(ClassLoader loader, String key) throws Throwable {
        if (loader != null || !"framework.navigation_cluster.update_clu_data_new_14".equals(key))
            throw new AssertionError("unexpected method lookup");
        methodCalls++;
        if (methodFailure != null) throw methodFailure;
        return selected;
    }

    public static Object newInstance(ClassLoader loader, String key, Object... args) throws Throwable {
        if (loader != null || !"framework.navigation_cluster.new".equals(key)
                || args == null || args.length != 1) throw new AssertionError("unexpected constructor lookup");
        receiverCalls++;
        constructorContext = args[0];
        if (receiverFailure != null) throw receiverFailure;
        return receiver;
    }

    public static void reset() {
        selected = null;
        receiver = null;
        methodFailure = null;
        receiverFailure = null;
        methodCalls = 0;
        receiverCalls = 0;
        constructorContext = null;
    }
}
