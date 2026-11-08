package android.content;
public class Context { public BroadcastReceiver receiver; public String permission;
 public Intent registerReceiver(BroadcastReceiver r,IntentFilter f,String p,android.os.Handler h){receiver=r;permission=p;return null;} }
