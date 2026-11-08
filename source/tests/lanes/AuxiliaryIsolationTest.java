package hmgapi.mods.wireless.dipo;

import android.content.Context;
import com.tcc.iap2.Iap2NotiMsg;
import hmgapi.hook.Cluster;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Actual CpTurns input -> core output + optional-worker fault injection.
 * It does not prove native callback delivery or visual acceptance on the car. */
public final class AuxiliaryIsolationTest {
    static int checks;
    static void check(boolean b,String why) { checks++; if(!b) throw new AssertionError(why); }
    static final class Manual implements CpAuxWriter.Executor {
        Runnable work; int posts;
        public boolean post(Runnable work) { check(this.work==null,"one outstanding worker"); this.work=work; posts++; return true; }
        void run() { Runnable current=work; work=null; if(current!=null) current.run(); }
        Thread start() { final Runnable current=work; work=null; Thread t=new Thread(current); t.setDaemon(true); t.start(); return t; }
    }
    static final class Clock implements CpTurns.TripScheduler {
        long now; public long now(){return now;} public Object owner(){return this;}
        public void later(Runnable r,long ms) {} public void cancel(Runnable r) {}
    }
    static final class Pattern implements CpTurns.PatternSink {
        final List<int[]> sent=new ArrayList<int[]>(); boolean fail;
        public boolean send(int[] values) { if(fail) throw new IllegalStateException("pattern IPC"); sent.add(values.clone()); return true; }
        int[] last(){return sent.isEmpty()?null:sent.get(sent.size()-1);}
    }
    final Cluster cluster=new Cluster(); final Manual worker=new Manual(); final Clock clock=new Clock();
    final Pattern pattern=new Pattern();
    final CpTurns turns=new CpTurns(new Context(),cluster,null,clock,false,pattern,worker);
    static Iap2NotiMsg route(int maneuver,int lane,int distance) {
        Iap2NotiMsg m=LaneLifecycleTest.route(7,maneuver,lane,20);
        // Avoid duplicate raw distance fields in fixture.
        m=new Iap2NotiMsg(); m.getItemGroup().add(0,5,new int[]{7}).add(1,8,new int[]{1})
                .add(13,5,new int[]{maneuver}).add(10,6,new int[]{distance}).add(16,5,new int[]{lane}).add(17,5,new int[]{20});
        return m;
    }
    static Iap2NotiMsg maneuver(int index,int type) {
        Iap2NotiMsg m=new Iap2NotiMsg();
        m.getItemGroup().add(0,5,new int[]{7}).add(1,5,new int[]{index})
                .add(3,8,new int[]{type}).add(8,8,new int[]{0}).add(9,8,new int[]{0}).add(11,1,new int[]{45});
        return m;
    }
    static Iap2NotiMsg.Iap2ObjGroup lane(int physical,int status,int[] secondary,int primary) {
        Iap2NotiMsg.Iap2ObjGroup g=LaneGuidanceTest.lane(status,secondary,new int[]{primary});
        if(physical>=0) g.add(0,5,new int[]{physical}); return g;
    }
    static Iap2NotiMsg lanes(int component,int index,Iap2NotiMsg.Iap2ObjGroup... rows) {
        Iap2NotiMsg m=new Iap2NotiMsg();m.getItemGroup().add(0,5,new int[]{component})
                .add(1,5,new int[]{index}).add(2,13,rows); return m;
    }
    static Iap2NotiMsg exit(int index) {
        // Deliberately shuffled physical lane order. Recommended right edge is
        // primary-only; the union must not lose it or move its status to lane0.
        return lanes(7,index,lane(2,2,new int[0],45),lane(0,0,new int[]{0},1000),lane(1,0,new int[]{0},1000));
    }
    void start(){turns.maneuver(maneuver(3,23));turns.routeGuidance(route(3,5,100));}
    int count(String prefix){int n=0;for(String line:cluster.calls)if(line.startsWith(prefix))n++;return n;}
    String last(String prefix){String out="";for(String line:cluster.calls)if(line.startsWith(prefix))out=line;return out;}
    void lanePatternAndStaleness() {
        start(); turns.lanes(exit(5));
        check(count("tbt ")==1 && count("hud ")==1 && count("2c ")==1,"basic output already sent while aux is queued");
        check(count("lanes ")==0 && pattern.sent.isEmpty(),"optional IPC is not inline with core");
        worker.run();
        check(Arrays.equals(pattern.last(),new int[]{65535,65535,65535,0,0}),"lane-only evidence cannot establish actual road curvature; no guessed exit template");
        check(LaneGuidanceTest.frameByte(last("lanes "),5)==2 && LaneGuidanceTest.frameByte(last("lanes "),25)==2,"physical lane sorting retains preferred right edge");
        int writes=count("lanes "); turns.lanes(exit(6)); worker.run();
        check(count("lanes ")==writes,"future record never replaces current lane");
        turns.routeGuidance(route(3,6,90));worker.run();check(LaneGuidanceTest.frameByte(last("lanes "),1)==3,"selected future record activates");
        turns.maneuver(maneuver(4,23)); turns.routeGuidance(route(4,6,70));worker.run();
        check(last("lanes ").equals("lanes f1") && Arrays.equals(pattern.last(),new int[]{65535,65535,65535,0,0}),"same guidance index on new maneuver cannot inherit previous topology");
        turns.lanes(exit(6));worker.run();clock.now+=CpTurns.MAX_LANE_AGE_MS+1;
        turns.routeGuidance(route(4,6,60));worker.run();check(last("lanes ").equals("lanes f1"),"old evidence expires on next actual route update without timer");
        turns.lanes(lanes(7,6,lane(-1,0,new int[]{0},1000),lane(-1,2,new int[0],45)));worker.run();
        check(LaneGuidanceTest.frameByte(last("lanes "),1)==2 && Arrays.equals(pattern.last(),new int[]{65535,65535,65535,0,0}),"missing physical indexes retain F1 fallback but cannot select PE14 topology");
    }
    void unknownTopology() {
        start();
        turns.lanes(lanes(7,5,lane(0,0,new int[]{0},1000),lane(0,2,new int[0],45)));worker.run();
        check(Arrays.equals(pattern.last(),new int[]{65535,65535,65535,0,0}),"duplicate physical indexes reject PE14");
        turns.lanes(lanes(7,5,lane(0,0,new int[]{0},1000),lane(2,2,new int[0],45)));worker.run();
        check(Arrays.equals(pattern.last(),new int[]{65535,65535,65535,0,0}),"sparse physical indexes reject PE14");
        turns.lanes(lanes(7,5,lane(0,99,new int[]{0},1000),lane(1,2,new int[0],45)));worker.run();
        check(Arrays.equals(pattern.last(),new int[]{65535,65535,65535,0,0}),"unknown status cannot establish topology");
        turns.lanes(lanes(7,5,lane(0,0,new int[]{0},1000),lane(1,2,new int[0],65535)));worker.run();
        check(Arrays.equals(pattern.last(),new int[]{65535,65535,65535,0,0}),"invalid primary cannot establish topology");
    }
    void faults() {
        start();turns.lanes(exit(5));cluster.failNextLane=true;worker.run();
        check(pattern.last()[0]==65535,"failed lane sink does not prevent conservative pattern clear");
        turns.routeGuidance(route(3,5,90));worker.run();
        check(count("tbt ")==2 && count("2c ")==2 && count("lanes ")==1,"next real route continues core and retries failed optional lane");
        pattern.fail=true;turns.lanes(lanes(7,5));worker.run();
        turns.routeGuidance(route(3,5,80));worker.run();
        check(count("tbt ")==3 && count("2c ")==3,"pattern exception cannot prevent next core update");
        Iap2NotiMsg malformed=new Iap2NotiMsg(); malformed.getItemGroup().add(0,5,new int[]{7}).add(2,13,new int[]{1,2,3});
        turns.lanes(malformed);turns.routeGuidance(route(3,5,70));worker.run();
        check(count("tbt ")==4 && count("2c ")==4,"malformed callback is isolated from route state");
        turns.stop();worker.run();
        check(last("tbt ").startsWith("tbt 0 ") && last("hud ").equals("hud f2"),"core clears survive pattern exception");
        pattern.fail=false;turns.stop();worker.run();check(pattern.last()[0]==65535,"failed pattern clear retries on real stop event");
    }
    void rawPriority() {
        start();
        CpLaneDecoder.Info raw=new CpLaneDecoder.Info(7,5,new CpLaneDecoder.Lane[]{
            new CpLaneDecoder.Lane(0,0,new int[]{0},1000),new CpLaneDecoder.Lane(1,2,new int[0],45)},0);
        long generation=turns.laneGeneration();turns.fallbackLanes(raw,generation);worker.run();
        check(LaneGuidanceTest.frameByte(last("lanes "),1)==2 && pattern.last()[0]==65535,"raw fallback retains normalized lanes without inventing road picture");
        turns.lanes(lanes(7,5));worker.run();turns.fallbackLanes(raw,generation);worker.run();
        check(last("lanes ").equals("lanes f1") && pattern.last()[0]==65535,"OEM explicit empty wins over later raw packet");
        turns.stop();turns.maneuver(maneuver(3,23));turns.routeGuidance(route(3,5,100));
        turns.fallbackLanes(raw,generation);worker.run();check(pattern.last()[0]==65535,"stale raw generation cannot cross guidance session");
    }
    void preannouncedAndInputPriority() {
        start(); turns.lanes(exit(5)); worker.run();
        Iap2NotiMsg zero=LaneLifecycleTest.route(7,3,5,0);turns.routeGuidance(zero);worker.run();
        turns.lanes(exit(6));worker.run();check(last("lanes ").equals("lanes f1"),"count-zero future lane is cached without displaying");
        turns.routeGuidance(zero); // A count-zero delta cannot erase future preannouncement.
        turns.routeGuidance(route(3,6,90));worker.run();check(LaneGuidanceTest.frameByte(last("lanes "),1)==3 && turns.laneFrames.get(7).get(6).rows.exact!=null,"future callback survives count-zero updates until selected");
        CpLaneDecoder.Info raw=new CpLaneDecoder.Info(7,7,new CpLaneDecoder.Lane[]{
            new CpLaneDecoder.Lane(0,0,new int[]{0},1000),new CpLaneDecoder.Lane(1,2,new int[0],45)},0);
        long generation=turns.laneGeneration();turns.routeGuidance(route(3,7,80));
        turns.fallbackLanes(raw,generation);worker.run();check(LaneGuidanceTest.frameByte(last("lanes "),1)==2 && !turns.laneFrames.get(7).get(7).oem,"asynchronous raw window survives ordinary current-index advance");
        turns.lanes(lanes(7,7,lane(-1,0,new int[]{0},1000),lane(-1,2,new int[0],45)));worker.run();
        check(!turns.laneFrames.get(7).get(7).oem && turns.laneFrames.get(7).get(7).rows.exact!=null,"incomplete OEM legacy record cannot displace exact raw topology");
        turns.routeGuidance(route(3,8,70));
        turns.lanes(lanes(7,8,lane(-1,0,new int[]{0},1000),lane(-1,2,new int[0],45)));worker.run();
        check(pattern.last()[0]==65535,"legacy OEM alone cannot establish PE14 topology");
        turns.fallbackLanes(new CpLaneDecoder.Info(7,8,raw.lanes,0),turns.laneGeneration());worker.run();
        check(!turns.laneFrames.get(7).get(8).oem && turns.laneFrames.get(7).get(8).rows.exact!=null,"exact raw record can fill incomplete OEM callback fields");
        turns.lanes(lanes(7,8));worker.run();turns.fallbackLanes(new CpLaneDecoder.Info(7,8,raw.lanes,0),turns.laneGeneration());worker.run();
        check(pattern.last()[0]==65535,"explicit valid OEM empty remains authoritative over raw");
    }
    void blockedSink() throws Exception {
        start();turns.lanes(exit(5));
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        cluster.laneHook=new Runnable(){public void run(){entered.countDown();try { if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test timeout"); }catch(InterruptedException e){throw new IllegalStateException(e);}}};
        Thread running=worker.start();check(entered.await(2,TimeUnit.SECONDS),"optional lane IPC entered");
        try {
            turns.routeGuidance(route(3,5,80));
            check(count("tbt ")==2 && count("2c ")==2,"blocked lane IPC does not block next basic main/HUD distance");
            turns.stop();check(last("tbt ").startsWith("tbt 0 ") && last("hud ").equals("hud f2"),"blocked optional IPC does not block core clears");
        } finally { release.countDown();running.join(3000); }
        check(!running.isAlive(),"worker drains after unblocking");
        check(last("lanes ").equals("lanes f1") && pattern.last()[0]==65535,"latest clear replaces stale pending pattern after in-flight IPC returns");
    }
    public static void main(String[] args)throws Exception {
        new AuxiliaryIsolationTest().lanePatternAndStaleness();new AuxiliaryIsolationTest().unknownTopology();
        new AuxiliaryIsolationTest().faults();new AuxiliaryIsolationTest().rawPriority();
        new AuxiliaryIsolationTest().preannouncedAndInputPriority();new AuxiliaryIsolationTest().blockedSink();
        System.out.println("AuxiliaryIsolationTest: "+checks+" actual CpTurns checks; vehicle output unverified");
    }
}
