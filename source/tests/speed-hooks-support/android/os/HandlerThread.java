package android.os;
public final class HandlerThread {
 public static int started,closed;
 public HandlerThread(String name){}
 public void start(){started++;}
 public Object getLooper(){return this;}
 public boolean quit(){closed++;return true;}
}
