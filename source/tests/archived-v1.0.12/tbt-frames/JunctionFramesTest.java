package hmgapi.mods.wireless.dipo;

import android.content.Context;
import com.tcc.iap2.Iap2NotiMsg;
import hmgapi.hook.Car;
import hmgapi.hook.Cluster;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Real packets and decoded wire evidence through production CpTurns. No visual claim. */
public final class JunctionFramesTest {
    static int checks;
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static final int[] CLEAR={65535,65535,65535,0,0}, RIGHT={10193,10194,65535,0,0}, LEFT={10016,10018,65535,0,0};
    static final class Sink implements CpTurns.PatternSink {
        final List<int[]> attempts=new ArrayList<int[]>(); boolean enabled=true;
        public boolean send(int[] frame){attempts.add(frame.clone());return enabled;}
        int[] last(){return attempts.get(attempts.size()-1);}
    }
    static final class Approach implements CpTurns.ApproachSink {
        final List<String> calls=new ArrayList<String>(); boolean fail;
        public void update(boolean active,int bar){calls.add(active+":"+bar);if(fail)throw new IllegalStateException();}
        String last(){return calls.isEmpty()?"":calls.get(calls.size()-1);}
    }
    final Cluster cluster=new Cluster();final Sink sink=new Sink();final Approach approach=new Approach();
    final CpTurns turns;
    JunctionFramesTest(boolean standard,boolean withSink){
        Car.unit=Car.DISTANCE_KM;
        turns=new CpTurns(new Context(),cluster,null,new ClusterFramesTest.Schedule(),standard,withSink?sink:null,approach);
    }
    static Iap2NotiMsg route(int component,int index,Integer meters,int lane,boolean show){
        Iap2NotiMsg r=NativeTransitionTest.route(component,index,meters,null);
        r.getItemGroup().add(16,5,new int[]{lane}).add(17,5,new int[]{1}).add(18,9,new int[]{show?1:0});return r;
    }
    static CpLaneDecoder.Info lanes(int component,int index,boolean left){
        CpLaneDecoder.Lane[] l=new CpLaneDecoder.Lane[3];
        for(int i=0;i<3;i++){boolean exit=i==(left?0:2);int angle=exit?(left?-90:90):0;
            l[i]=new CpLaneDecoder.Lane(i,exit?1:0,new int[]{angle},exit?angle:1000);}
        return new CpLaneDecoder.Info(component,index,l,0);
    }
    void maneuver(int component,int index,int type){turns.maneuver(NativeTransitionTest.maneuver(component,index,type,0,null));}
    void route(int meters){turns.routeGuidance(route(7,3,meters,9,true));}
    void pattern(int[] expected){check(!sink.attempts.isEmpty()&&Arrays.equals(expected,sink.last()),"pattern expected "+Arrays.toString(expected));}
    void start(){turns.rawLanes(lanes(7,9,false));maneuver(7,3,23);route(200);}
    void evidenceAndDelta(){
        turns.rawLanes(lanes(7,9,false));check(cluster.calls.isEmpty()&&sink.attempts.isEmpty(),"raw evidence emits no lane/turn/picture alone");
        maneuver(7,3,23);turns.routeGuidance(route(7,3,200,8,true));pattern(CLEAR);
        check(sink.attempts.size()==1,"unknown window clears old native picture once");
        turns.rawLanes(lanes(8,8,false));check(sink.attempts.size()==1,"other component cannot satisfy lane index");
        turns.rawLanes(lanes(7,8,false));pattern(RIGHT);
        int writes=sink.attempts.size();
        Iap2NotiMsg delta=new Iap2NotiMsg();delta.getItemGroup().add(0,5,new int[]{7}).add(10,6,new int[]{150});
        turns.routeGuidance(delta);pattern(RIGHT);check(sink.attempts.size()==writes,"partial route preserves lane index/show state and deduplicates");
        Iap2NotiMsg m=new Iap2NotiMsg();m.getItemGroup().add(0,5,new int[]{7}).add(1,5,new int[]{3});
        turns.maneuver(m);check(sink.attempts.size()==writes,"partial maneuver retains type without needing angles or text");
        turns.routeGuidance(route(7,3,150,8,false));pattern(CLEAR);
        turns.routeGuidance(route(7,3,150,8,true));pattern(RIGHT);
        Iap2NotiMsg none=route(7,3,150,8,true);none.getItemGroup().add(17,5,new int[]{0});
        turns.routeGuidance(none);pattern(CLEAR);
        check(turns.rawLaneInfos.size()==1,"explicit no lane windows removes only current component evidence");
    }
    void lifecycle(){
        start();pattern(RIGHT);check(Arrays.equals(sink.attempts.get(0),CLEAR),"takeover clear precedes first picture");
        int writes=sink.attempts.size();maneuver(7,4,23);turns.routeGuidance(route(7,4,200,9,true));
        check(sink.attempts.size()==writes,"same diagram survives phone reindex without flicker");
        turns.rawLanes(lanes(7,10,true));maneuver(7,5,22);turns.routeGuidance(route(7,5,200,10,true));pattern(LEFT);
        check(sink.attempts.size()==writes+2&&Arrays.equals(sink.attempts.get(writes),CLEAR),"new different maneuver clears before new picture");
        turns.routeGuidance(route(7,5,601,10,true));pattern(CLEAR);
        turns.routeGuidance(route(7,5,600,10,true));pattern(LEFT);
        turns.routeGuidance(route(7,5,0,10,true));writes=sink.attempts.size();
        turns.rawLanes(new CpLaneDecoder.Info(7,10,new CpLaneDecoder.Lane[0],0));
        check(sink.attempts.size()==writes,"zero-meter current picture survives early next-lane evidence");
        turns.routeGuidance(route(7,5,0,10,false));pattern(CLEAR);
        turns.rawLanes(lanes(7,10,true));turns.routeGuidance(route(7,5,100,10,true));pattern(LEFT);
        Iap2NotiMsg reroute=route(7,5,100,10,true);reroute.getItemGroup().add(1,8,new int[]{5});turns.routeGuidance(reroute);pattern(CLEAR);
        check(approach.last().equals("false:0"),"reroute releases approach");
        turns.routeGuidance(route(7,5,100,10,true));pattern(CLEAR);
        turns.rawLanes(lanes(7,10,true));pattern(LEFT); // Reroute requires fresh lane evidence.
        Iap2NotiMsg arrived=route(7,5,0,10,true);arrived.getItemGroup().add(1,8,new int[]{2});
        turns.routeGuidance(arrived);pattern(CLEAR);check(approach.last().equals("false:0"),"arrival releases picture and approach");
        turns.routeGuidance(route(7,5,100,10,true));pattern(LEFT);
        Iap2NotiMsg loading=route(7,5,100,10,true);loading.getItemGroup().add(1,8,new int[]{3});
        turns.routeGuidance(loading);pattern(CLEAR);check(approach.last().equals("false:0"),"loading releases picture and approach");
        turns.routeGuidance(route(7,5,100,10,true));pattern(CLEAR);
        turns.rawLanes(lanes(7,10,true));pattern(LEFT);
        turns.routeGuidance(route(7,6,100,10,true));pattern(CLEAR);
        check(approach.last().equals("false:0"),"missing current maneuver releases approach");
        maneuver(7,6,22);pattern(LEFT);
        turns.stop();pattern(CLEAR);check(turns.rawLaneInfos.isEmpty(),"stop clears raw evidence");
        writes=sink.attempts.size();turns.stop();check(sink.attempts.size()==writes,"repeated stop does not duplicate clear");
    }
    void ownershipSourceRetry(){
        start();int writes=sink.attempts.size(),calls=cluster.calls.size();turns.setOutputAllowed(false);
        route(100);turns.rawLanes(lanes(7,9,false));
        check(sink.attempts.size()==writes&&cluster.calls.size()==calls,"ownership loss writes no cluster/pattern frame");
        check(approach.last().equals("false:0")&&turns.rawLaneInfos.isEmpty(),"ownership loss releases lease and old evidence");
        turns.setOutputAllowed(true);route(100);pattern(CLEAR);turns.rawLanes(lanes(7,9,false));pattern(RIGHT);
        Iap2NotiMsg a=route(7,3,100,9,true);a.getItemGroup().add(19,10,new String[]{"A"});turns.routeGuidance(a);
        Iap2NotiMsg b=route(7,3,100,9,true);b.getItemGroup().add(19,10,new String[]{"B"});turns.routeGuidance(b);pattern(CLEAR);
        check(turns.rawLaneInfos.isEmpty()&&approach.last().equals("false:0"),"source change clears raw cache and approach");
        JunctionFramesTest failure=new JunctionFramesTest(true,true);failure.sink.enabled=false;failure.start();
        check(failure.sink.attempts.size()==1,"failed initial clear prevents picture");
        failure.sink.enabled=true;failure.route(200);failure.pattern(RIGHT);
        check(failure.sink.attempts.size()==3,"next real update retries clear then picture");
        failure.sink.enabled=false;failure.route(601);failure.pattern(CLEAR);writes=failure.sink.attempts.size();
        failure.sink.enabled=true;failure.route(601);check(failure.sink.attempts.size()==writes+1,"failed clear is retried");
        failure.sink.enabled=false;failure.route(200);failure.pattern(RIGHT);writes=failure.sink.attempts.size();
        failure.sink.enabled=true;failure.route(200);
        check(failure.sink.attempts.size()==writes+1,"failed picture write is retried without declaring success");failure.pattern(RIGHT);
        JunctionFramesTest off=new JunctionFramesTest(false,true);off.start();off.pattern(RIGHT);
        check(off.approach.calls.isEmpty()&&StandardTurnsTest.latest(off.cluster,"tbt ").startsWith("tbt 81 "),"AR off preserves flat TBT and independent junction picture");
        off.route(601);off.pattern(CLEAR);off.route(600);off.pattern(RIGHT);
        off.turns.stop();off.pattern(CLEAR);check(off.approach.calls.isEmpty(),"AR off still clears junction on leave and stop without approach lease");
        JunctionFramesTest noSink=new JunctionFramesTest(true,false);noSink.start();check(noSink.sink.attempts.isEmpty()&&!noSink.cluster.calls.isEmpty(),"optional pattern sink absent leaves TBT intact");
    }
    void approachPolicy(){
        start();check(approach.last().equals("true:33"),"200/600 highway approach uses linear bar");
        route(600);check(approach.last().equals("true:100"),"highway boundary");route(601);check(approach.last().equals("false:0"),"outside highway releases");
        maneuver(7,4,2);turns.routeGuidance(route(7,4,300,9,true));check(approach.last().equals("true:100"),"ordinary boundary");
        turns.routeGuidance(route(7,4,150,9,true));check(approach.last().equals("true:50"),"ordinary midpoint");
        turns.routeGuidance(route(7,4,0,9,true));check(approach.last().equals("true:0"),"actual zero is zero, no invented passed state");
        turns.routeGuidance(NativeTransitionTest.route(7,4,null,"100"));check(approach.last().equals("false:0"),"formatted-only delta cannot renew raw approach");
        turns.routeGuidance(route(7,4,100,9,true));
        Iap2NotiMsg otherEnd=new Iap2NotiMsg();otherEnd.getItemGroup().add(0,5,new int[]{8}).add(1,8,new int[]{0});
        int before=approach.calls.size();turns.routeGuidance(otherEnd);check(approach.calls.size()==before,"another component ending cannot release owner's approach");
        maneuver(7,5,14);turns.routeGuidance(route(7,5,200,9,true));pattern(RIGHT);
        check(approach.last().equals("false:0")&&StandardTurnsTest.latest(cluster,"tbt ").startsWith("tbt 66 "),"keep uses projection plus independently proven picture, no guessed native shape");
        turns.disconnected();check(approach.last().equals("false:0"),"disconnect inactive");
        JunctionFramesTest ended=new JunctionFramesTest(true,true);ended.start();
        Iap2NotiMsg state0=new Iap2NotiMsg();state0.getItemGroup().add(0,5,new int[]{7}).add(1,8,new int[]{0});
        ended.turns.routeGuidance(state0);ended.pattern(CLEAR);
        check(ended.turns.rawLaneInfos.isEmpty()&&ended.approach.last().equals("false:0"),"own state0 releases both outputs and evidence");
    }
    void boundedAndImmutable(){
        for(int i=0;i<20;i++)turns.rawLanes(lanes(7,i,false));check(turns.rawLaneInfos.size()==16,"bounded cache");
        CpLaneDecoder.Info input=lanes(7,9,false);turns.rawLanes(input);input.lanes[2].angles[0]=-90;
        maneuver(7,3,23);route(200);pattern(RIGHT);
        int before=cluster.calls.size();turns.rawLanes(lanes(8,9,true));check(cluster.calls.size()==before,"other component raw event cannot replay current TBT or lanes");
        turns.rawLanes(new CpLaneDecoder.Info(7,9,new CpLaneDecoder.Lane[]{new CpLaneDecoder.Lane(0,0,new int[0],1000),new CpLaneDecoder.Lane(1,1,new int[]{90},90)},0));pattern(CLEAR);
        turns.rawLanes(lanes(7,9,false));pattern(RIGHT);
        turns.setOutputAllowed(false);turns.stop();check(turns.rawLaneInfos.isEmpty(),"stop while another route owns output still clears cache");
    }
    static void sideStreetDeltas(){
        JunctionFramesTest h=new JunctionFramesTest(true,true);
        Iap2NotiMsg m=NativeTransitionTest.maneuver(7,3,2,0,90);m.getItemGroup().add(10,1,new int[]{-90}).add(10,1,new int[]{0});
        h.turns.maneuver(m);h.route(200);h.pattern(CLEAR);
        check(StandardTurnsTest.latest(h.cluster,"tbt ").equals("tbt 13 2065 90.0 200.0 0 0 0 60 0"),"existing main geometry preserved while no highway picture guessed");
        Iap2NotiMsg partial=new Iap2NotiMsg();partial.getItemGroup().add(0,5,new int[]{7}).add(1,5,new int[]{3});h.turns.maneuver(partial);
        check(StandardTurnsTest.latest(h.cluster,"tbt ").contains("13 2065 90.0"),"partial geometry retained");
        partial.getItemGroup().add(10,1,new int[]{});h.turns.maneuver(partial);
        check(StandardTurnsTest.latest(h.cluster,"tbt ").contains("13 0 90.0"),"explicit empty geometry clears only geometry");
    }
    void closePolicyHighway(){
        turns.setCloseApproach(true);start();pattern(RIGHT);
        route(601);pattern(CLEAR);check(approach.last().equals("false:0"),"close mode clears picture and approach beyond600m highway");
        route(600);pattern(RIGHT);check(approach.last().equals("true:100"),"close mode highway picture and bar share600m entry");
        route(599);pattern(RIGHT);route(51);pattern(RIGHT);route(50);pattern(RIGHT);route(49);pattern(RIGHT);
        check(approach.last().equals("true:8"),"highway finebar unaffected by ordinary50m policy");
    }
    public static void main(String[] ignored){new JunctionFramesTest(true,true).closePolicyHighway();
        new JunctionFramesTest(true,true).evidenceAndDelta();new JunctionFramesTest(true,true).lifecycle();
        new JunctionFramesTest(true,true).ownershipSourceRetry();new JunctionFramesTest(true,true).approachPolicy();
        new JunctionFramesTest(true,true).boundedAndImmutable();sideStreetDeltas();
        System.out.println("JunctionFramesTest: "+checks+" packet/ownership/lifecycle/approach checks passed");
    }
}
