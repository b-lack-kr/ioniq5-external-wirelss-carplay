package hmgapi.mods.wireless.dipo;

import android.content.Context;
import com.tcc.iap2.Iap2NotiMsg;
import hmgapi.hook.Car;
import hmgapi.hook.Cluster;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Packet-to-cluster tests. These do not claim a visual result on the vehicle. */
public final class JunctionFramesTest {
    static int checks;
    static void check(boolean b, String why) { checks++; if (!b) throw new AssertionError(why); }
    static final int[] CLEAR = {65535,65535,65535,0,0};
    static final int[] THREE_RIGHT = {19,22,65535,0,0};
    static final class Sink implements CpTurns.PatternSink {
        final List<int[]> attempts = new ArrayList<int[]>();
        boolean enabled = true;
        public boolean send(int[] data) { attempts.add(data.clone()); return enabled; }
        int[] last() { return attempts.get(attempts.size()-1); }
    }
    final Cluster cluster = new Cluster();
    final Sink sink = new Sink();
    final CpTurns turns;
    JunctionFramesTest(boolean standard, boolean withSink) {
        Car.unit = Car.DISTANCE_KM;
        turns = new CpTurns(new Context(),cluster,null,new ClusterFramesTest.Schedule(),standard,withSink?sink:null);
    }
    static Iap2NotiMsg delta(int index) {
        Iap2NotiMsg msg=new Iap2NotiMsg();
        msg.getItemGroup().add(0,5,new int[]{7}).add(1,5,new int[]{index});
        return msg;
    }
    static Iap2NotiMsg complete(int index, int angle) {
        Iap2NotiMsg m=delta(index);
        m.getItemGroup().add(3,8,new int[]{2}).add(8,8,new int[]{0}).add(9,8,new int[]{0})
            .add(11,1,new int[]{angle}).add(10,1,new int[]{-90}).add(10,1,new int[]{0});
        return m;
    }
    void route(int index,int distance) { turns.routeGuidance(NativeTransitionTest.route(7,index,distance,null)); }
    String lastMain() { return StandardTurnsTest.latest(cluster,"tbt "); }
    void start() { turns.maneuver(complete(3,90)); route(3,200); }
    void frame(String expected) { check(lastMain().equals(expected),"main expected "+expected+" got "+lastMain()); }
    void pattern(int[] expected) { check(!sink.attempts.isEmpty() && Arrays.equals(expected,sink.last()),"pattern "+Arrays.toString(sink.last())); }
    void aggregationAndClears() {
        start(); pattern(THREE_RIGHT);
        frame("tbt 13 2065 90.0 200.0 0 0 0 60 0");
        int writes=sink.attempts.size();
        turns.maneuver(delta(3));
        check(sink.attempts.size()==writes,"missing arms and exit retain delta state and deduplicate");
        Iap2NotiMsg empty=delta(3); empty.getItemGroup().add(10,1,new int[]{});
        turns.maneuver(empty); pattern(CLEAR);
        frame("tbt 13 0 90.0 200.0 0 0 0 60 0");
        turns.maneuver(complete(3,90)); pattern(THREE_RIGHT);
        Iap2NotiMsg badExit=delta(3); badExit.getItemGroup().add(11,1,new int[]{361});
        turns.maneuver(badExit); pattern(CLEAR);
        frame("tbt 13 0 90.0 200.0 0 0 0 60 0");
        turns.maneuver(complete(3,90)); pattern(THREE_RIGHT);
        Iap2NotiMsg emptyExit=delta(3); emptyExit.getItemGroup().add(11,1,new int[]{});
        turns.maneuver(emptyExit); pattern(CLEAR);
        frame("tbt 13 0 90.0 200.0 0 0 0 60 0");
    }
    void fractionalAndProgress() {
        turns.maneuver(complete(3,30)); route(3,300);
        pattern(THREE_RIGHT); frame("tbt 13 2051 22.5 300.0 0 0 0 100 0");
        route(3,0); frame("tbt 13 2051 22.5 0.0 0 0 0 10 0");
        Iap2NotiMsg different=delta(3);
        different.getItemGroup().add(10,1,new int[]{-45}).add(10,1,new int[]{0});
        turns.maneuver(different);
        frame("tbt 13 8195 22.5 0.0 0 0 0 10 0");
    }
    void dedupAndTransitions() {
        start(); int writes=sink.attempts.size(); route(3,200);
        check(sink.attempts.size()==writes,"same pattern at same maneuver is deduplicated");
        turns.maneuver(complete(4,90)); route(4,200);
        check(sink.attempts.size()==writes+1,"new maneuver identity writes same pattern again"); pattern(THREE_RIGHT);
        route(5,200); pattern(CLEAR);
        check(lastMain().startsWith("tbt 0 "),"next unknown clears prior main rather than inventing an exit");
        turns.maneuver(complete(5,90)); pattern(THREE_RIGHT);
        Iap2NotiMsg reroute=delta(5); reroute.getItemGroup().add(3,8,new int[]{1}).add(11,1,new int[]{-90});
        turns.maneuver(reroute); pattern(CLEAR);
        turns.maneuver(complete(5,90)); pattern(THREE_RIGHT);
        turns.stop(); pattern(CLEAR);
        int before=sink.attempts.size(); turns.stop();
        check(sink.attempts.size()==before,"repeated stop does not duplicate clear");
    }
    void sourceChange() {
        Iap2NotiMsg source=NativeTransitionTest.route(7,3,200,null);
        source.getItemGroup().add(19,10,new String[]{"app-a"});
        turns.maneuver(complete(3,90)); turns.routeGuidance(source); pattern(THREE_RIGHT);
        Iap2NotiMsg changed=NativeTransitionTest.route(7,3,200,null);
        changed.getItemGroup().add(19,10,new String[]{"app-b"}); turns.routeGuidance(changed); pattern(CLEAR);
        check(lastMain().startsWith("tbt 0 "),"source change clears prior main");
    }
    void outputGatesAndRetry() {
        turns.outputAllowed=false; start();
        check(sink.attempts.isEmpty() && cluster.calls.isEmpty(),"ownership gate blocks sink and cluster");
        turns.outputAllowed=true; route(3,200); pattern(THREE_RIGHT);
        JunctionFramesTest noSink=new JunctionFramesTest(true,false); noSink.start();
        check(noSink.sink.attempts.isEmpty() && noSink.lastMain().startsWith("tbt 13 "),"null sink preserves main guidance");
        JunctionFramesTest off=new JunctionFramesTest(false,true); off.start();
        check(!off.sink.attempts.isEmpty() && off.lastMain().startsWith("tbt 67 "),"AR off keeps basic main and independent patterns"); off.pattern(THREE_RIGHT);
        JunctionFramesTest failed=new JunctionFramesTest(true,true); failed.sink.enabled=false; failed.start();
        check(failed.sink.attempts.size()==1,"failed sink has one attempt");
        failed.sink.enabled=true; failed.route(3,200);
        check(failed.sink.attempts.size()==2,"failed sink retries on next real update"); failed.pattern(THREE_RIGHT);
        failed.route(3,200); check(failed.sink.attempts.size()==2,"successful retry is deduplicated");
        failed.sink.enabled=false;
        Iap2NotiMsg empty=delta(3); empty.getItemGroup().add(10,1,new int[]{});
        failed.turns.maneuver(empty); failed.pattern(CLEAR);
        int pending=failed.sink.attempts.size();
        failed.sink.enabled=true; failed.route(3,200);
        check(failed.sink.attempts.size()==pending+1,"failed clear retries on next real update");
        failed.pattern(CLEAR);
    }
    void ownershipTransitions() {
        start(); int calls=cluster.calls.size(), patterns=sink.attempts.size();
        turns.setOutputAllowed(false); route(3,200);
        check(cluster.calls.size()==calls && sink.attempts.size()==patterns,"active ownership loss sends nothing");
        turns.setOutputAllowed(true); route(3,200);
        check(cluster.calls.size()>calls && sink.attempts.size()==patterns+1,"regaining ownership resends same main and pattern");
        pattern(THREE_RIGHT);

        JunctionFramesTest stopped=new JunctionFramesTest(true,true); stopped.start();
        calls=stopped.cluster.calls.size(); patterns=stopped.sink.attempts.size();
        stopped.turns.setOutputAllowed(false); stopped.turns.stop();
        check(stopped.cluster.calls.size()==calls && stopped.sink.attempts.size()==patterns,"stop during ownership loss sends nothing");

        JunctionFramesTest ended=new JunctionFramesTest(true,true); ended.start();
        calls=ended.cluster.calls.size(); patterns=ended.sink.attempts.size();
        ended.turns.setOutputAllowed(false);
        Iap2NotiMsg state0=new Iap2NotiMsg();
        state0.getItemGroup().add(0,5,new int[]{7}).add(1,8,new int[]{0});
        ended.turns.routeGuidance(state0);
        check(ended.cluster.calls.size()==calls && ended.sink.attempts.size()==patterns,"state0 during ownership loss sends nothing");
        ended.turns.setOutputAllowed(true); ended.route(3,200);
        check(ended.cluster.calls.size()==calls && ended.sink.attempts.size()==patterns,"state0 invalidates old maneuver before same-index new route");

        JunctionFramesTest source=new JunctionFramesTest(true,true);
        source.turns.maneuver(complete(3,90));
        Iap2NotiMsg appA=NativeTransitionTest.route(7,3,200,null);
        appA.getItemGroup().add(19,10,new String[]{"app-a"}); source.turns.routeGuidance(appA);
        calls=source.cluster.calls.size(); patterns=source.sink.attempts.size();
        source.turns.setOutputAllowed(false);
        Iap2NotiMsg appB=NativeTransitionTest.route(7,3,200,null);
        appB.getItemGroup().add(19,10,new String[]{"app-b"}); source.turns.routeGuidance(appB);
        check(source.cluster.calls.size()==calls && source.sink.attempts.size()==patterns,"source change during ownership loss sends nothing");
        source.turns.setOutputAllowed(true); source.route(3,200);
        check(source.cluster.calls.size()==calls && source.sink.attempts.size()==patterns,"source change cannot replay old geometry");

        JunctionFramesTest lanes=new JunctionFramesTest(true,true); lanes.start();
        calls=lanes.cluster.calls.size(); patterns=lanes.sink.attempts.size();
        lanes.turns.setOutputAllowed(false); lanes.turns.lanes(new Iap2NotiMsg());
        check(lanes.cluster.calls.size()==calls && lanes.sink.attempts.size()==patterns,"lane-only ownership loss sends nothing");
        lanes.turns.setOutputAllowed(true); lanes.route(3,200);
        check(lanes.cluster.calls.size()>calls && lanes.sink.attempts.size()==patterns+1,"lane-only takeover still invalidates old output cache");

        JunctionFramesTest future=new JunctionFramesTest(true,true); future.start();
        calls=future.cluster.calls.size(); patterns=future.sink.attempts.size();
        future.turns.setOutputAllowed(false); future.turns.maneuver(complete(4,90));
        check(future.cluster.calls.size()==calls && future.sink.attempts.size()==patterns,"future-maneuver ownership loss sends nothing");
        future.turns.setOutputAllowed(true); future.route(3,200);
        check(future.cluster.calls.size()>calls && future.sink.attempts.size()==patterns+1,"future-maneuver takeover invalidates old output cache");
    }
    static void initializeUnknownPattern() {
        JunctionFramesTest unknown=new JunctionFramesTest(true,true);
        unknown.turns.maneuver(NativeTransitionTest.maneuver(7,3,2,0,90));
        unknown.route(3,200);
        check(unknown.sink.attempts.size()==1,"first unknown geometry initializes pattern once");
        unknown.pattern(CLEAR);
        unknown.route(3,200);
        check(unknown.sink.attempts.size()==1,"unchanged unknown geometry does not repeat clear");
        unknown.turns.setOutputAllowed(false);
        unknown.route(3,200);
        check(unknown.sink.attempts.size()==1,"native owner sees no clear");
        unknown.turns.setOutputAllowed(true);
        unknown.route(3,200);
        check(unknown.sink.attempts.size()==2,"returning owner initializes unknown geometry once");
        unknown.pattern(CLEAR);
        unknown.route(3,200);
        check(unknown.sink.attempts.size()==2,"repeated returned unknown does not clear twice");

        JunctionFramesTest far=new JunctionFramesTest(true,true);
        far.turns.maneuver(complete(3,90)); far.route(3,458);
        check(far.sink.attempts.size()==1,"first far guidance also initializes pattern"); far.pattern(CLEAR);
        far.route(3,458);
        check(far.sink.attempts.size()==1,"unchanged far guidance does not repeat clear");
        far.route(3,200);
        check(far.sink.attempts.size()==2,"subsequent known geometry is written"); far.pattern(THREE_RIGHT);
    }
    static Iap2NotiMsg crossing(int type,int angle,int[] others) {
        Iap2NotiMsg m=delta(3);
        m.getItemGroup().add(3,8,new int[]{type}).add(8,8,new int[]{0})
            .add(9,8,new int[]{0}).add(11,1,new int[]{angle});
        for(int other:others)m.getItemGroup().add(10,1,new int[]{other});
        return m;
    }
    static void observedStockGeometry() {
        // 2026-11-08 stock capture: 13,1033,67.5 at20m, and13,2064,270 at20m.
        // This confirms geometry encoding, not an exact AR-onset algorithm.
        JunctionFramesTest typed=new JunctionFramesTest(true,true);
        typed.turns.maneuver(crossing(2,67,new int[]{0,-112}));typed.route(3,20);
        typed.frame("tbt 13 1033 67.5 20.0 0 0 0 10 0");
        JunctionFramesTest left=new JunctionFramesTest(true,true);
        left.turns.maneuver(crossing(1,-90,new int[]{90}));left.route(3,20);
        left.frame("tbt 13 2064 270.0 20.0 0 0 0 10 0");
        // Full intersection geometry is meaningful even when the phone uses
        // a neutral maneuver rather than a typed left/right turn.
        for(int type:new int[]{0,5})for(int angle:new int[]{-135,-90,-67,-45,0,45,67,90,135}) {
            JunctionFramesTest neutral=new JunctionFramesTest(true,true);
            neutral.turns.maneuver(crossing(type,angle,new int[]{-112,112}));neutral.route(3,20);
            NativeSideStreet.Result nativeFrame=NativeSideStreet.map(new int[]{-112,112},angle);
            neutral.frame("tbt 13 "+nativeFrame.mask+" "+nativeFrame.directionDegrees+" 20.0 0 0 0 10 0");
            neutral.route(3,458);
            check(!neutral.lastMain().startsWith("tbt 13 "),"neutral crossing respects approach window");
        }
        for(int type:new int[]{0,5}) {
            CpTurns.Maneuver absent=new CpTurns.Maneuver(3,7,StandardTurnsTest.maneuver(type,0,0,67));
            check(StandardTurnsTest.format(absent,20)==null,"neutral nonzero direction without other arms stays basic");
            CpTurns.Maneuver back=new CpTurns.Maneuver(3,7,crossing(type,180,new int[]{-90,90}));
            check(StandardTurnsTest.format(back,20)==null,"back-facing neutral direction is ambiguous");
            CpTurns.Maneuver bad=new CpTurns.Maneuver(3,7,crossing(type,67,new int[]{-112,361}));
            check(StandardTurnsTest.format(bad,20)==null,"invalid other arm cannot create AR geometry");
        }
        JunctionFramesTest off=new JunctionFramesTest(false,true);
        off.turns.maneuver(crossing(5,67,new int[]{-112,0}));off.route(3,20);
        check(!off.lastMain().startsWith("tbt 13 "),"shared AR-off keeps neutral crossing basic");
    }
    public static void main(String[] ignored) {
        new JunctionFramesTest(true,true).aggregationAndClears();
        new JunctionFramesTest(true,true).fractionalAndProgress();
        new JunctionFramesTest(true,true).dedupAndTransitions();
        new JunctionFramesTest(true,true).sourceChange();
        new JunctionFramesTest(true,true).outputGatesAndRetry();
        new JunctionFramesTest(true,true).ownershipTransitions();
        initializeUnknownPattern();
        observedStockGeometry();
        System.out.println("JunctionFramesTest: "+checks+" packet-to-sink checks passed");
    }
}
