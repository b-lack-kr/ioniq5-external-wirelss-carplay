package android.content.pm;
public class PackageManager {
 public static final int PERMISSION_GRANTED=0,PERMISSION_DENIED=-1;
 public ResolveInfo result=new ResolveInfo();
 public android.content.Intent resolved;
 public int resolves,permissionResult=PERMISSION_GRANTED;
 public String permissionChecked,permissionPackage;
 public ResolveInfo resolveService(android.content.Intent intent,int flags){resolves++;resolved=intent;return result;}
 public int checkPermission(String permission,String pkg){permissionChecked=permission;permissionPackage=pkg;return permissionResult;}
}
