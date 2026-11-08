package android.os;
import java.util.ArrayList;
import java.util.List;
public final class Handler {
 public static final List<Runnable> queue=new ArrayList<Runnable>();
 public Handler(Object looper){}
 public boolean postDelayed(Runnable r,long delay){queue.add(r);return true;}
 public void removeCallbacks(Runnable r){queue.remove(r);}
 public static void drain(){SystemClock.now+=250;List<Runnable> ready=new ArrayList<Runnable>(queue);queue.clear();for(Runnable r:ready)r.run();}
}
