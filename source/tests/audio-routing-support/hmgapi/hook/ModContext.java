package hmgapi.hook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Host fixture reproduces HMG 1.0.1 before/original/after ordering and exception retention. */
public final class ModContext {
    public interface Original { Object run() throws Throwable; }
    public static ModContext active;
    public final Map<String, List<MethodHook>> hooks = new LinkedHashMap<String, List<MethodHook>>();
    public final List<String> logs = new ArrayList<String>();
    public final List<Throwable> hookErrors = new ArrayList<Throwable>();
    public boolean on = true;
    public String rejectPoint;

    public ModContext() { active = this; }
    public boolean enabled() { return on; }
    public void log(String message) { logs.add(message); }
    public void error(String message, Throwable t) { logs.add(message); if (t != null) hookErrors.add(t); }
    public void hook(ClassLoader cl, String point, MethodHook hook) {
        if (point.equals(rejectPoint)) throw new IllegalStateException("injected partial install");
        List<MethodHook> list = hooks.get(point);
        if (list == null) { list = new ArrayList<MethodHook>(); hooks.put(point, list); }
        list.add(hook);
    }

    public static Object dispatch(String point, Object target, Object[] args, Original original) throws Throwable {
        ModContext ctx = active;
        List<MethodHook> found = ctx.hooks.get(point);
        if (!ctx.on || found == null) return original.run();
        MethodHook[] callbacks = found.toArray(new MethodHook[found.size()]);
        MethodHook.Param p = new MethodHook.Param(Points.method(null, point), target, args);
        int ran = 0;
        while (ran < callbacks.length) {
            try { callbacks[ran].beforeHookedMethod(p); }
            catch (Throwable e) { ctx.hookErrors.add(e); p.reset(); }
            ran++;
            if (p.returnEarly) break;
        }
        if (!p.returnEarly) {
            try { p.setResult(original.run()); }
            catch (Throwable e) { p.setThrowable(e); }
        }
        for (int i = ran - 1; i >= 0; i--) {
            Object old = p.getResult(); Throwable failed = p.getThrowable();
            try { callbacks[i].afterHookedMethod(p); }
            catch (Throwable e) {
                ctx.hookErrors.add(e);
                if (failed == null) p.setResult(old); else p.setThrowable(failed);
            }
        }
        return p.getResultOrThrowable();
    }
}
