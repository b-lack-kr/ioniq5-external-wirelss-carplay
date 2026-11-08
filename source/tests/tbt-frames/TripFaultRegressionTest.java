package hmgapi.mods.wireless.dipo;

import android.content.Context;
import com.tcc.iap2.Iap2NotiMsg;
import hmgapi.hook.Cluster;
import hmgapi.hook.Car;

/** Replays the observed initial-missing-distance and wrong-lane-template faults.
 * Host calls establish encoding and lifetime behavior, not a rendered HU image. */
public final class TripFaultRegressionTest {
    static int checks;
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static String last(Cluster c,String prefix){return StandardTurnsTest.latest(c,prefix);}
    static Iap2NotiMsg route(int index,Integer meters,String formatted){return NativeTransitionTest.route(7,index,meters,formatted);}
    static Iap2NotiMsg maneuver(int index){return NativeTransitionTest.maneuver(7,index,2,0,90);}
    static void missingDistance() {
        Car.unit=Car.DISTANCE_KM;
        Cluster c=new Cluster();ClusterFramesTest.Schedule clock=new ClusterFramesTest.Schedule();
        CpTurns t=new CpTurns(new Context(),c,null,clock,false,null,(CpAuxWriter.Executor)null);
        t.maneuver(maneuver(3));
        Iap2NotiMsg start=route(3,null,null);
        start.getItemGroup().add(7,6,new int[]{12000});
        t.routeGuidance(start);
        check(last(c,"tbt ").isEmpty() && last(c,"hud ").isEmpty(),"unknown initial distance never emits a 0m arrow");
        check(!last(c,"sub ").isEmpty(),"trip totals remain available while next-turn distance is missing");
        t.routeGuidance(route(3,73,null));
        check(last(c,"tbt ").contains(" 73.0 0 0 "),"first measured73m is accepted without GPS trigger or synthetic interpolation");
        t.routeGuidance(route(3,0,null));
        check(last(c,"tbt ").equals("tbt 67 0 0.0 0.0 0 0 0 0 0"),"measured zero remains valid and distinct from unknown");
        int calls=c.calls.size();t.routeGuidance(route(3,0,null));
        check(c.calls.size()==calls,"repeated real zero does not fabricate movement or repeated output");
        t.maneuver(maneuver(4));t.routeGuidance(route(4,null,null));
        check(last(c,"tbt ").equals("tbt 0 0 0.0 0.0 -1 0 0 0 -1") && last(c,"hud ").equals("hud f2"),"new maneuver without distance clears old arrow/distance using existing contract");
        calls=c.calls.size();t.routeGuidance(route(4,null,null));
        check(c.calls.size()==calls,"waiting does not repeatedly clear the HUD");
        t.routeGuidance(route(4,null,"0 m"));
        check(last(c,"tbt ").equals("tbt 67 0 0.0 0.0 0 0 0 0 0"),"formatted actual zero also displays");
        t.routeGuidance(route(4,-1,null));
        check(last(c,"tbt ").startsWith("tbt 0 "),"explicit invalid raw sample cannot preserve formatted stale zero");
    }
    static void diagnostics() {
        Dipo.logs.clear();
        Cluster c=new Cluster();ClusterFramesTest.Schedule clock=new ClusterFramesTest.Schedule();
        CpTurns t=new CpTurns(new Context(),c,null,clock,false,null,(CpAuxWriter.Executor)null);
        Iap2NotiMsg m=maneuver(3);
        m.getItemGroup().add(2,10,new String[]{"PRIVATE_INSTRUCTION"}).add(4,10,new String[]{"PRIVATE_ROAD"});
        t.maneuver(m);
        for(int i=0;i<100;i++)t.routeGuidance(route(3,null,null));
        int routeCount=0,waitCount=0;
        for(String line:Dipo.logs) {
            if(line.startsWith("USB_CP_TBT_DIAG kind=route "))routeCount++;
            if(line.startsWith("USB_CP_TBT_DIAG kind=distance_wait "))waitCount++;
        }
        check(routeCount==1 && waitCount==1,"per-kind sampling limits a burst to one line per second");
        clock.advance(1000);t.routeGuidance(route(3,73,null));
        boolean dropped=false;
        for(String line:Dipo.logs)if(line.startsWith("USB_CP_TBT_DIAG ")) {
            check(line.matches("USB_CP_TBT_DIAG kind=(route|maneuver|lane_input|lane_select|distance_wait|lane_write|pattern_write)( [A-Za-z][A-Za-z0-9]*=-?[0-9]+(,-?[0-9]+)*)+"),"fixed numeric diagnostic grammar");
            check(!line.contains("PRIVATE") && line.length()<4000,"no description/road/source strings or oversized dumps");
            if(line.contains("dropped=99"))dropped=true;
        }
        check(dropped,"next sample reports suppressed event count after burst");
        CpTurns.Update u=t.updates.get(7);
        check((u.inputFields&(1<<10))!=0 && u.distanceCleared==0,"numeric input presence available for known distance");
        t.maneuver(maneuver(4));t.routeGuidance(route(4,null,null));
        check((u.distanceCleared&1)!=0 && u.distNext<0,"new maneuver clear reason is distinguishable");
        t.routeGuidance(route(4,null,"73 m"));
        check((u.distanceCleared&2)!=0,"formatted-only replacement records its clear reason");
        t.routeGuidance(route(4,-1,null));
        check((u.distanceCleared&4)!=0,"invalid raw replacement records its clear reason");
    }
    public static void main(String[] args) {
        missingDistance();diagnostics();
        System.out.println("TripFaultRegressionTest PASS checks="+checks);
    }
}
