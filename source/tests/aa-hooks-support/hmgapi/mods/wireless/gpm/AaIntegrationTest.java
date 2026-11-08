package hmgapi.mods.wireless.gpm;

import android.os.Handler;
import hmgapi.hook.Car;
import hmgapi.hook.Cluster;
import java.util.ArrayList;

public final class AaIntegrationTest {
    static int checks;
    static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    static void lane(GpmTest.Rig r,String text)throws Throwable {
        r.call(Gpm.TURN,r.status,new Object[]{text,0,AaTurns.EV_LANES,new byte[0],0,0},null);
    }
    static AaTurns live(GpmTest.Rig r)throws Throwable {
        r.start();r.state(1);r.turn("private destination");r.distance(1);r.worker.run();
        AaTurns t=r.session().turns;t.frozen=true;t.skew=100000;t.lastIn=100000;t.lastSendAt=100000;return t;
    }
    static void callbacksAndTimers()throws Throwable {
        GpmTest.Rig r=new GpmTest.Rig(null,100);r.start();lane(r,"0102010300");r.worker.run();
        check(Cluster.current.calls.isEmpty()&&r.session().turns.lastLane!=null,"pre-active lanes cache without output");
        r.state(1);r.worker.run();check(r.contains("lanes f101"),"active status displays cached lanes");
        r.state(2);r.worker.run();int n=Cluster.current.calls.size();lane(r,"0101030000");r.worker.run();
        check(Cluster.current.calls.size()==n&&r.session().turns.lastLane==null,"final stop rejects late lanes");
        r.state(1);r.worker.run();check(Cluster.current.calls.size()==n,"new route does not reuse old lanes");

        r=new GpmTest.Rig(null,100);AaTurns t=live(r);
        t.skew+=AaTurns.GUIDE_LOST_MS;t.heartbeat();check(t.lost&&!t.navLive,"watchdog enters revivable lost state");
        r.distance(1);r.worker.run();check(t.navLive&&!t.lost,"real hook distance revives lost route");
        r.state(2);r.worker.run();n=Cluster.current.calls.size();r.distance(1);r.worker.run();
        check(Cluster.current.calls.size()==n&&!t.navLive,"real end cannot revive from distance");

        r=new GpmTest.Rig(null,100);t=live(r);lane(r,"0101030000");r.worker.run();
        ArrayList<Runnable> stale=new ArrayList<Runnable>(r.worker.handler.pending);
        r.call(Gpm.FOCUS,r.receiver,new Object[]{0},null);n=Cluster.current.calls.size();
        for(Runnable work:stale)work.run();
        check(Cluster.current.calls.size()==n&&r.worker.handler.pending.isEmpty(),"focus takes ownership before worker drain; queued timers go quiet");
        r.worker.run();r.call(Gpm.FOCUS,r.receiver,new Object[]{1},null);r.state(1);r.turn("new route");r.worker.run();
        AaTurns newer=r.session().turns;check(newer!=t,"new focus epoch creates a new timer owner");
        n=Cluster.current.calls.size();for(Runnable work:stale)work.run();
        check(Cluster.current.calls.size()==n,"old epoch timers cannot render over new session");
        r.call(Gpm.FOCUS,r.receiver,new Object[]{1},null);r.state(1);r.turn("same projection reaffirmed");r.worker.run();
        check(r.session().turns!=newer&&r.contains("same projection reaffirmed"),"projected epoch change also renews owner");
        stale=new ArrayList<Runnable>(r.worker.handler.pending);r.end();r.worker.run();n=Cluster.current.calls.size();
        for(Runnable work:stale)work.run();check(Cluster.current.calls.size()==n,"destroyed owner leaves no ghost timer output");
    }
    static void windowsAndProjection() {
        Car.unit=Car.DISTANCE_KM;Cluster c=new Cluster();AaTurns t=AaTurnsTest.make(c,new Handler());
        AaTurnsTest.freeze(t);t.setCloseApproach(true);t.status(1);t.nextTurn("turn",AaTurns.RIGHT,AaTurns.TURN,-1,0);
        for(int distance:new int[]{301,300,299,0}) {
            t.nextTurnDistance(distance,1,distance,AaTurns.METERS);
            String turn=AaTurnsTest.last(c,"tbt ");check(turn.startsWith(distance>300?"tbt 67 ":"tbt 13 "),"ordinary close boundary "+distance);
            if(distance<=300)check(t.approachBar==Math.round(distance*100f/300),"linear close bar "+distance);
        }
        t.nextTurn("exit",AaTurns.RIGHT,AaTurns.OFF_RAMP,-1,0);
        check(t.approachBar==-1,"changed maneuver loses previous raw distance");
        for(int distance:new int[]{601,600}){t.nextTurnDistance(distance,1,distance,AaTurns.METERS);
            check(AaTurnsTest.last(c,"tbt ").startsWith(distance==600?"tbt 17 ":"tbt 81 "),"highway boundary "+distance);}
        t.setCloseApproach(false);t.nextTurn("ordinary again",AaTurns.RIGHT,AaTurns.TURN,-1,0);
        for(int distance:new int[]{301,300}){t.nextTurnDistance(distance,1,distance,AaTurns.METERS);
            check(AaTurnsTest.last(c,"tbt ").startsWith(distance==300?"tbt 13 ":"tbt 67 "),"reference window "+distance);}
        t.standardTurns=false;t.nextTurnDistance(10,1,10,AaTurns.METERS);
        check(AaTurnsTest.last(c,"tbt ").startsWith("tbt 67 ")&&t.approachBar==-1,"feature off keeps projection and releases bar");
        Car.unit=Car.DISTANCE_MILES;t.nextTurnDistance(100,1,100,AaTurns.METERS);
        check(AaTurnsTest.last(c,"tbt ").contains("328.1"),"existing owner-unit conversion retained");
        Car.unit=Car.DISTANCE_KM;t.nextTurnDistance(1500,1,932,AaTurns.MILES);
        check(AaTurnsTest.last(c,"tbt ").contains(" 1.5 "),"existing miles to km conversion retained");
    }
    static void laneFailuresAndReroute() {
        Car.unit=Car.DISTANCE_KM;final Cluster c=new Cluster();Handler h=new Handler();AaTurns t=AaTurnsTest.make(c,h);
        AaTurnsTest.freeze(t);final int[] writes={0};
        t.laneDistance=new LaneDistanceWriter(){public boolean send(int a,int b,int d,int e){writes[0]++;return writes[0]>1;}};
        t.status(1);t.nextTurn("road",AaTurns.RIGHT,AaTurns.TURN,-1,0);t.nextTurnDistance(100,1,100,AaTurns.METERS);
        t.nextTurn("0101030000",0,AaTurns.EV_LANES,0,0);check(writes[0]==1&&t.laneDist==null,"failed box write is not deduplicated");
        t.refreshLanes();check(writes[0]==2&&t.laneDist!=null,"lane heartbeat retries failed box write");
        int n=c.calls.size();for(String bad:new String[]{"0","01010300","0000","0901030000","01zz030000"})t.sendLanes(bad);
        check(c.calls.size()==n,"truncated/extra/oversize/nonhex event34 has no output");
        t.status(3);n=c.calls.size();t.nextTurn("0101010000",0,AaTurns.EV_LANES,0,0);t.skew+=3000;t.heartbeat();t.refreshLanes();
        check(c.calls.size()==n,"reroute never resends old turn/HUD or new lanes before recovery");
        t.status(1);check(c.calls.size()>n,"active recovery shows current lane cache");
        final boolean[] owner={true};t.owner=new AaTurns.Owner(){public boolean active(){return owner[0];}public boolean release(){return owner[0];}};
        n=c.calls.size();owner[0]=false;t.refreshLanes();check(c.calls.size()==n&&h.pending.isEmpty(),"owner loss retires all timers without clear");
    }
    static void settingsGates()throws Throwable {
        GpmTest.Rig r=new GpmTest.Rig(null,100);AaTurns t=live(r);
        check(!t.standardTurns&&t.closeApproach,"AR defaults off while fixed close policy remains configured");
        r.call(Gpm.DISTANCE,r.status,new Object[]{25,1,25,AaTurns.METERS},null);r.worker.run();
        check(AaTurnsTest.last(Cluster.current,"tbt ").startsWith("tbt 67 "),"obsolete TBT false cannot disable flat guidance");
        lane(r,"0101030000");r.worker.run();
        check(r.contains("lanes f101"),"basic lanes stay enabled with all obsolete toggles false");
        java.util.List<String> sent=ApproachSender.sent;sent.clear();
        t.approach.update(true,50);check(sent.isEmpty(),"default AR off blocks approach sender");
        t.heartbeat();check(t.navLive,"obsolete AA TBT false cannot retire active timers");
        r=new GpmTest.Rig(null,100,Boolean.TRUE);t=live(r);
        check(t.standardTurns&&t.closeApproach,"shared ar_view switch selects fixed close policy");
        r.call(Gpm.DISTANCE,r.status,new Object[]{25,1,25,AaTurns.METERS},null);r.worker.run();
        check(AaTurnsTest.last(Cluster.current,"tbt ").startsWith("tbt 13 "),"unified AR on renders inside50m despite obsolete false values");
        sent.clear();t.approach.update(true,50);check(sent.size()==1,"old AA bar false does not block shared AR switch");
        sent.clear();r.mod.settings.put("ar_view",false);t.approach.update(true,50);
        check(sent.isEmpty(),"shared AR false blocks sender");
        r.mod.settings.put("ar_view",true);Car.aa=false;t.approach.update(true,50);
        check(sent.isEmpty(),"AA guiding false blocks sender");Car.aa=true;
        t.approach.update(true,50);check(sent.size()==1,"current AA owner and shared switch permit sender");
        int n=Cluster.current.calls.size();r.mod.on=false;t.heartbeat();
        check(!t.navLive&&Cluster.current.calls.size()==n&&r.worker.handler.pending.isEmpty(),"module disabled retires timers without replacing display");
        r=new GpmTest.Rig(null,100,Boolean.FALSE);t=live(r);
        check(!t.standardTurns,"explicit unified AR false respected after restart");
        r.call(Gpm.DISTANCE,r.status,new Object[]{25,1,25,AaTurns.METERS},null);r.worker.run();
        check(AaTurnsTest.last(Cluster.current,"tbt ").startsWith("tbt 67 "),"shared off keeps flat projection inside50m");
    }
    public static void main(String[] args)throws Throwable {
        callbacksAndTimers();windowsAndProjection();laneFailuresAndReroute();settingsGates();
        System.out.println("AaIntegrationTest PASS checks="+checks);
    }
}
