package android.os;
import java.util.ArrayList;
public class Handler {
    public Handler(){} public Handler(Object looper){}
    public Runnable delayed;
    public final ArrayList<Runnable> pending=new ArrayList<Runnable>();
    public boolean post(Runnable r){r.run();return true;}
    public boolean postDelayed(Runnable r,long ms){pending.add(r);delayed=r;return true;}
    public void removeCallbacks(Runnable r){while(pending.remove(r)){} if(delayed==r)delayed=pending.isEmpty()?null:pending.get(pending.size()-1);}
    public boolean fire(Runnable r){if(!pending.remove(r))return false;if(delayed==r)delayed=null;r.run();return true;}
}
