package hmgapi.hook;
public class Dispatch {public static boolean replaced(MethodHook hook,Object...args)throws Throwable {MethodHook.Param p=new MethodHook.Param(null,null,args);hook.beforeHookedMethod(p);if(p.hasThrowable())throw p.getThrowable();return p.returnEarly;}}
