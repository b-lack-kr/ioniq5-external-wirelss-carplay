package android.content;
import android.content.pm.PackageManager;
public class Context {
 public final PackageManager pm=new PackageManager();public Intent launched;public int starts;
 public RuntimeException failure;
 public PackageManager getPackageManager(){return pm;}
 public void startActivity(Intent intent){if(failure!=null)throw failure;launched=intent;starts++;}
}
