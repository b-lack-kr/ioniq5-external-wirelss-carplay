package android.content.pm;
import android.content.Intent;
public class PackageManager {public ResolveInfo result;public RuntimeException failure;
 public ResolveInfo resolveActivity(Intent intent,int flags){if(failure!=null)throw failure;return result;}}
