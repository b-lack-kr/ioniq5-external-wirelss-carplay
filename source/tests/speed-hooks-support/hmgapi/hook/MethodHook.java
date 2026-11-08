package hmgapi.hook;
public abstract class MethodHook {
 public static final class Param {
  public Object thisObject;public Object[] args;public Throwable error;
  public Throwable getThrowable(){return error;}
 }
 protected void beforeHookedMethod(Param p){}
 protected void afterHookedMethod(Param p){}
 public void before(Object object,Object...args){beforeParam(object,args);}
 public Param beforeParam(Object object,Object...args){Param p=new Param();p.thisObject=object;p.args=args;beforeHookedMethod(p);return p;}
 public void after(Object object,Throwable error,Object...args){Param p=new Param();p.thisObject=object;p.args=args;p.error=error;afterHookedMethod(p);}
}
