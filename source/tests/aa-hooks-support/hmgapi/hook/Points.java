package hmgapi.hook;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public final class Points {
    public static final Map<String, Method> methods = new HashMap<String, Method>();
    public static final Map<String, Field> fields = new HashMap<String, Field>();
    public static Method method(ClassLoader cl, String id) {
        Method m = methods.get(id);
        if (m == null) throw new IllegalStateException("unmapped method " + id);
        m.setAccessible(true); return m;
    }
    public static Field field(ClassLoader cl, String id) {
        Field f = fields.get(id);
        if (f == null) throw new IllegalStateException("unmapped field " + id);
        f.setAccessible(true); return f;
    }
    public static Object newInstance(ClassLoader cl,String id,Object...args){throw new IllegalStateException("unmapped constructor "+id);}
    public static Object get(ClassLoader cl, String id, Object target) {
        try { return field(cl, id).get(target); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static Object call(ClassLoader cl, String id, Object target, Object... args) {
        try { return method(cl, id).invoke(target, args); }
        catch (InvocationTargetException e) { throw new IllegalStateException(e.getCause()); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
