package android.content;
public class Context {
 public final android.content.res.Resources resources=new android.content.res.Resources();
 public final android.content.pm.PackageManager packages=new android.content.pm.PackageManager();
 public volatile int starts,mainStarts;
 public volatile Intent started;
 public boolean nullStart,wrongStart,deniedStart,failedStart;
 public android.content.res.Resources getResources(){return resources;}
 public android.content.pm.PackageManager getPackageManager(){return packages;}
 public ComponentName startService(Intent intent){
  starts++;started=intent;
  if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())mainStarts++;
  if(deniedStart)throw new SecurityException("PRIVATE-SERVICE-DETAIL");
  if(failedStart)throw new IllegalStateException("PRIVATE-SERVICE-DETAIL");
  return nullStart?null:wrongStart?new ComponentName("other.package","other.Service"):intent.getComponent();
 }
}
