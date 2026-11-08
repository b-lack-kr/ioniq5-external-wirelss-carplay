package hmgapi.hook;
public class MethodHook {
 public static class Param {
  public Object thisObject;public Object[] args;public Throwable error;
  public Throwable getThrowable(){return error;}
  public void setResult(Object v){throw new AssertionError("observer cannot replace");}
 }
 protected void beforeHookedMethod(Param p){}protected void afterHookedMethod(Param p){}
 public void before(Param p){beforeHookedMethod(p);}public void after(Param p){afterHookedMethod(p);}
}
