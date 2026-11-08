package hmgapi.mods.wireless.dipo;

import android.content.Context;
import com.tcc.iap2.Iap2NotiMsg;
import hmgapi.hook.Car;
import hmgapi.hook.Cluster;
import java.util.*;

/** Production CP timer, canonical lane choice, optional lane-box writer, and session gates. */
public final class CpRefreshTest {
    static int checks;
    static void check(boolean yes,String why){checks++;if(!yes)throw new AssertionError(why);}
    static final class Clock implements CpTurns.TripScheduler {
        long time; Object token=new Object(); final LinkedHashMap<Runnable,Long> jobs=new LinkedHashMap<Runnable,Long>();
        public long now(){return time;} public Object owner(){return token;}
        public void later(Runnable work,long delay){check(delay>0&&!jobs.containsKey(work),"bounded one callback per task");jobs.put(work,time+delay);}
        public void cancel(Runnable work){jobs.remove(work);}
        void advance(long amount){long end=time+amount;while(true){Runnable next=null;long due=Long.MAX_VALUE;
            for(Map.Entry<Runnable,Long> e:jobs.entrySet())if(e.getValue()<due){next=e.getKey();due=e.getValue();}
            if(next==null||due>end)break;time=due;jobs.remove(next);next.run();}time=end;}
    }
    static final class Box extends LaneDistanceWriter {
        boolean success=true; final ArrayList<String> calls=new ArrayList<String>();
        public boolean send(int whole,int unit,int tenths,int toll){calls.add(whole+":"+unit+":"+tenths+":"+toll);return success;}
        String last(){return calls.isEmpty()?"":calls.get(calls.size()-1);}
    }
    static final class Approach implements CpTurns.ApproachSink {
        final ArrayList<String> calls=new ArrayList<String>();
        public void update(boolean active,int bar){calls.add(active+":"+bar);}
    }
    final Clock clock=new Clock();final Cluster cluster=new Cluster();final Box box=new Box();final Approach approach=new Approach();
    final CpTurns sender=new CpTurns(new Context(),cluster,null,clock,true,null,approach,box);
    CpRefreshTest(){Car.unit=Car.DISTANCE_KM;}
    static Iap2NotiMsg route(int metres){Iap2NotiMsg r=LaneLifecycleTest.route(7,3,9,1);r.getItemGroup().add(10,6,new int[]{metres});return r;}
    void start(){sender.maneuver(LaneLifecycleTest.turn(7,3,9));sender.routeGuidance(route(100));sender.lanes(LaneLifecycleTest.lanes(7,9,2,1));}
    int count(String prefix){int n=0;for(String line:cluster.calls)if(line.startsWith(prefix))n++;return n;}
    String last(String prefix){String s="";for(String line:cluster.calls)if(line.startsWith(prefix))s=line;return s;}
    void cadence(){
        start();int main=count("tbt "),hud=count("hud "),lanes=count("lanes "),ap=approach.calls.size(),boxes=box.calls.size();
        check(box.last().equals("100:0:0:0"),"box repeats the turn distance");clock.advance(999);
        check(count("tbt ")==main&&count("hud ")==hud,"no early refresh");clock.advance(1);
        check(count("tbt ")==main+1&&count("lanes ")==lanes+1,"main and lanes refresh after1s");
        check(count("hud ")==hud&&box.calls.size()==boxes,"F2 and box wait2s");clock.advance(1000);
        check(count("tbt ")==main+2&&count("hud ")==hud+1&&box.calls.size()==boxes+1,"2s F2/box liveness");
        check(approach.calls.size()==ap,"cached refresh never renews real-distance approach lease");
        check(last("tbt ").contains("100.0"),"refresh invents no distance");
        check(clock.jobs.size()==1,"only one CP refresh timer");
    }
    void frequentDeltas(){
        start();int hud=count("hud "),main=count("tbt ");
        for(int i=0;i<4;i++){clock.advance(400);sender.routeGuidance(route(99-i));}
        clock.advance(400);check(count("hud ")==hud+1,"fresh distance cannot starve F2 2s retry");
        check(count("tbt ")==main+4,"recent main tuple is not duplicated before900ms");
    }
    void ownerAndStop(){
        start();Runnable stale=clock.jobs.keySet().iterator().next();int writes=cluster.calls.size(),boxes=box.calls.size();
        clock.token=new Object();clock.advance(1000);stale.run();
        check(cluster.calls.size()==writes&&box.calls.size()==boxes&&clock.jobs.isEmpty(),"replaced session cannot resend or clear over new owner");
        sender.routeGuidance(route(70));check(clock.jobs.size()==1,"fresh current event starts new owner timer");
        stale=clock.jobs.keySet().iterator().next();sender.disconnected();writes=cluster.calls.size();boxes=box.calls.size();stale.run();clock.advance(4000);
        check(clock.jobs.isEmpty()&&cluster.calls.size()==writes&&box.calls.size()==boxes,"disconnect cancels every stale timer");
        check(box.last().equals("65535:15:15:0"),"owned route termination clears lane box");
    }
    void nativeTakeover(){
        start();Runnable stale=clock.jobs.keySet().iterator().next();int writes=cluster.calls.size(),boxes=box.calls.size();
        sender.setOutputAllowed(false);stale.run();clock.advance(3000);
        check(cluster.calls.size()==writes&&box.calls.size()==boxes,"native takeover never emits phone clear frames");
        check(sender.laneFrames.isEmpty()&&sender.rawLaneInfos.isEmpty(),"takeover discards lane sources");
        int laneWrites=count("lanes ");sender.setOutputAllowed(true);sender.routeGuidance(route(80));
        check(count("lanes ")==laneWrites,"fresh turn does not replay lost lanes");
    }
    static CpLaneDecoder.Info raw(int index,int status,int... angles){return new CpLaneDecoder.Info(7,index,new CpLaneDecoder.Lane[]{new CpLaneDecoder.Lane(0,status,angles,90)},0);}
    void rawPriority(){
        sender.rawLanes(raw(9,2,0,90));check(cluster.calls.isEmpty(),"raw input before route is evidence only");start();
        check(LaneGuidanceTest.frameByte(last("lanes "),3)==11&&LaneGuidanceTest.frameByte(last("lanes "),19)==16
                &&LaneGuidanceTest.frameByte(last("lanes "),20)==18,"canonical wire gives grey straight/blue right overlay");
        int n=count("lanes ");sender.lanes(LaneLifecycleTest.lanes(7,9,5,0));check(count("lanes ")==n,"later OEM callback cannot override same raw record");
        sender.rawLanes(raw(10,1,-90));check(count("lanes ")==n,"future raw index remains cached");
        for(int index=20;index<60;index++)sender.rawLanes(raw(index,1,0));
        check(sender.rawLaneInfos.size()==16,"raw cache bounded");
        sender.routeGuidance(route(95));check(count("lanes ")==n,"future raw flood cannot evict selected canonical record");
        sender.rawLanes(new CpLaneDecoder.Info(7,9,new CpLaneDecoder.Lane[0],0));check(last("lanes ").equals("lanes f1"),"explicit raw empty clears current lane strip");
        sender.lanes(LaneLifecycleTest.lanes(7,9,3,0));check(last("lanes ").equals("lanes f1"),"OEM duplicate cannot resurrect explicit raw empty");
        sender.disconnected();sender.rawLanes(raw(9,2,0,90));check(!sender.displayed,"post-disconnect raw input cannot display without new route");
    }
    void boxFailureAndUnits(){
        box.success=false;start();check(box.calls.size()==1,"first failed optional box call");
        box.success=true;clock.advance(1000);check(box.calls.size()==2&&box.last().equals("100:0:0:0"),"failed write retries at next refresh");
        sender.routeGuidance(route(1560));check(box.last().equals("1:1:6:0"),"km tenths stay coupled to visible main distance");
        Car.unit=Car.DISTANCE_MILES;sender.routeGuidance(route(100));check(box.last().equals("100:0:0:0"),"feet converted to supported metres");
        sender.routeGuidance(route(1609));check(box.last().equals("1:2:0:0"),"mi box uses verified unit");
        box.success=false;sender.lanes(LaneLifecycleTest.lanes(7,9,0,-1));int before=box.calls.size();
        check(box.last().equals("65535:15:15:0"),"empty lanes request native invalid sentinel");
        box.success=true;clock.advance(1000);check(box.calls.size()==before+1&&box.last().equals("65535:15:15:0"),"failed clear retried while same owner remains");
        before=box.calls.size();clock.advance(3000);check(box.calls.size()==before,"successful clear stops invalid retransmission");
    }
    void sourceAndArrived(){
        start();Iap2NotiMsg source=route(90);source.getItemGroup().add(19,10,new String[]{"source-a"});sender.routeGuidance(source);
        Runnable stale=clock.jobs.keySet().iterator().next();source=route(80);source.getItemGroup().add(19,10,new String[]{"source-b"});sender.routeGuidance(source);
        int before=cluster.calls.size();stale.run();clock.advance(2000);check(cluster.calls.size()==before&&clock.jobs.isEmpty(),"source switch cancels old liveness");
        start();Iap2NotiMsg arrived=route(0);arrived.getItemGroup().add(1,8,new int[]{2});sender.routeGuidance(arrived);
        check(last("hud ").equals("hud f2"),"arrival clears old projection F2 list");int h=count("hud ");clock.advance(2000);
        check(count("hud ")==h,"arrival cannot refresh the previous arrow");
    }
    void transientRefreshFailure(){
        start();int main=count("tbt ");cluster.failNextTbt=true;clock.advance(1000);
        check(count("tbt ")==main&&clock.jobs.size()==1,"transient binder failure cannot kill refresh handler");
        clock.advance(1000);check(count("tbt ")==main+1,"next refresh retries unchanged frame after binder failure");
    }
    public static void main(String[] args){new CpRefreshTest().transientRefreshFailure();new CpRefreshTest().cadence();new CpRefreshTest().frequentDeltas();new CpRefreshTest().ownerAndStop();new CpRefreshTest().nativeTakeover();new CpRefreshTest().rawPriority();new CpRefreshTest().boxFailureAndUnits();new CpRefreshTest().sourceAndArrived();System.out.println("CpRefreshTest: "+checks+" checks passed");}
}
