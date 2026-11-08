package hmgapi.mods.wireless.dipo;

import android.content.Context;
import com.tcc.iap2.Iap2NotiMsg;
import hmgapi.hook.Cluster;

/** Full route/maneuver/lane event sequences against the production CpTurns. */
public final class LaneLifecycleTest {
    static int checks;
    final Cluster cluster = new Cluster();
    final CpTurns sender = new CpTurns(new Context(), cluster, null, null);
    static void check(boolean b, String why) { checks++; if (!b) throw new AssertionError(why); }
    static void item(Iap2NotiMsg m,int id,int type,int... value) { m.getItemGroup().add(id,type,value); }
    static Iap2NotiMsg route(int component,int turn,int lane,Integer count) {
        Iap2NotiMsg m=new Iap2NotiMsg();
        if(component>=0)item(m,0,5,component);
        item(m,1,8,1);item(m,13,5,turn);item(m,10,6,100);
        if(lane>=0)item(m,16,5,lane);
        if(count!=null)item(m,17,5,count);
        return m;
    }
    static Iap2NotiMsg turn(int component,int index,int linked) {
        Iap2NotiMsg m=new Iap2NotiMsg();
        if(component>=0)item(m,0,5,component);
        item(m,1,5,index);item(m,3,8,2);item(m,11,1,90);
        if(linked>=0)item(m,12,5,linked);
        return m;
    }
    static Iap2NotiMsg lanes(int component,int index,int count,int primary) {
        Iap2NotiMsg m=new Iap2NotiMsg();
        if(component>=0)item(m,0,5,component);
        if(index>=0)item(m,1,5,index);
        Iap2NotiMsg.Iap2ObjGroup[] lanes=new Iap2NotiMsg.Iap2ObjGroup[count];
        for(int i=0;i<count;i++) {
            lanes[i]=new Iap2NotiMsg().getItemGroup();
            lanes[i].add(1,8,new int[]{i==primary?2:0});
            lanes[i].add(i==primary?3:2,5,new int[]{i==primary?90:0});
        }
        m.getItemGroup().add(2,13,lanes);return m;
    }
    void start(int component,int lane,int linked) {
        sender.routeGuidance(route(component,3,lane,null));sender.maneuver(turn(component,3,linked));
    }
    int writes(){int n=0;for(String line:cluster.calls)if(line.startsWith("lanes "))n++;return n;}
    String last(){String found="";for(String line:cluster.calls)if(line.startsWith("lanes "))found=line;return found;}
    static int frameByte(String line,int at){String h=line.substring(6);return h.length()<=at*2?0:Integer.parseInt(h.substring(at*2,at*2+2),16);}
    void earlyAndIndexed(){
        sender.lanes(lanes(7,9,3,2));check(writes()==0,"early lane must wait for a current maneuver");
        sender.routeGuidance(route(-1,3,9,20));check(writes()==0,"route alone must not draw lanes");
        sender.maneuver(turn(-1,3,-1));check(frameByte(last(),1)==3&&frameByte(last(),5)==2,"early packet replays at first matching maneuver, last lane retained");
        int before=writes();sender.lanes(lanes(7,10,2,1));check(writes()==before,"future guidance must not replace current lanes");
        sender.routeGuidance(route(7,3,10,20));check(frameByte(last(),1)==2,"route guidance index selects cached future packet");
        sender.routeGuidance(route(7,3,11,20));check(last().equals("lanes f1"),"new index without packet clears old directions");
        sender.lanes(lanes(8,11,4,0));check(last().equals("lanes f1"),"another display component cannot write current lanes");
        sender.lanes(lanes(7,11,5,4));check(frameByte(last(),1)==5&&frameByte(last(),7)==2,"correct component/index can replace cleared lanes");
        before=writes();sender.lanes(lanes(7,11,5,4));check(writes()==before,"identical lane frames are not resent");
        Iap2NotiMsg metadata=new Iap2NotiMsg();item(metadata,0,5,7);item(metadata,1,5,11);sender.lanes(metadata);
        check(writes()==before,"metadata-only packet is not a clear");
        sender.lanes(lanes(7,11,0,-1));check(last().equals("lanes f1"),"explicit empty lane array clears current record");
    }
    void selectionAndVisibility(){
        sender.lanes(lanes(7,0,8,7));start(7,-1,0);
        check(frameByte(last(),1)==8&&frameByte(last(),10)==2,"linked guidance index zero and eighth lane are valid");
        sender.routeGuidance(route(7,3,0,1));check(frameByte(last(),1)==8,"total guidance count one is not physical lane count one");
        Iap2NotiMsg hidden=new Iap2NotiMsg();item(hidden,0,5,7);item(hidden,18,9,0);sender.routeGuidance(hidden);
        check(frameByte(last(),1)==8,"phone UI hidden does not prove cluster lane guidance ended");
        sender.lanes(lanes(7,4,2,1));sender.routeGuidance(route(7,3,4,1));
        check(frameByte(last(),1)==2,"route current index takes priority over linked maneuver index");
        sender.routeGuidance(route(7,3,4,0));check(last().equals("lanes f1")&&!sender.laneFrames.containsKey(7),"explicit zero total clears cached guidance");
        sender.lanes(lanes(7,4,2,1));sender.routeGuidance(route(7,3,4,1));
        check(last().equals("lanes f1"),"count-zero late packet cannot revive a previous record");
    }
    void stopAndOwnership(){
        start(7,5,-1);sender.lanes(lanes(7,5,2,1));
        Iap2NotiMsg stopped=new Iap2NotiMsg();item(stopped,0,5,7);item(stopped,1,8,0);sender.routeGuidance(stopped);
        int before=writes();sender.lanes(lanes(7,5,2,1));
        check(writes()==before&&!sender.laneFrames.containsKey(7),"late lane after route stop is not cached or drawn");
        start(7,5,-1);check(last().equals("lanes f1"),"restarted route requires fresh lanes");
        sender.lanes(lanes(7,5,2,1));sender.outputAllowed=false;sender.routeGuidance(route(7,3,5,1));
        before=writes();sender.lanes(lanes(7,5,3,1));
        check(writes()==before&&sender.laneFrames.isEmpty(),"native takeover clears cache without writing over native display");
        sender.outputAllowed=true;sender.routeGuidance(route(7,3,5,1));
        check(writes()==before,"old cached lanes do not replay after native takeover");
        sender.lanes(lanes(7,5,2,1));sender.disconnected();
        check(sender.laneFrames.isEmpty(),"disconnect clears every component cache");
        start(7,5,-1);check(last().equals("lanes f1"),"next session does not inherit lanes");
    }
    void sourceAndLegacy(){
        start(7,-1,-1);sender.lanes(lanes(-1,-1,2,1));
        check(frameByte(last(),1)==2,"known component indexless legacy packet remains supported");
        sender.maneuver(turn(7,4,-1));sender.routeGuidance(route(7,4,-1,null));
        check(last().equals("lanes f1"),"indexless packet cannot leak to a later maneuver");
        sender.lanes(lanes(7,9,2,1));Iap2NotiMsg first=route(7,4,9,null);first.getItemGroup().add(19,10,new String[]{"source-a"});sender.routeGuidance(first);
        check(frameByte(last(),1)==2,"initial source retains current guidance");
        Iap2NotiMsg other=route(7,4,9,null);other.getItemGroup().add(19,10,new String[]{"source-b"});sender.routeGuidance(other);
        check(sender.laneFrames.isEmpty()&&last().equals("lanes f1"),"source replacement clears pending and visible lanes");
    }
    void bounded(){
        start(7,0,-1);sender.lanes(lanes(7,0,2,1));
        for(int i=1;i<80;i++)sender.lanes(lanes(7,i,1,0));
        check(sender.laneFrames.get(7).size()==CpTurns.MAX_LANE_FRAMES&&sender.laneFrames.get(7).containsKey(0),"cache is bounded without evicting selected guidance");
        check(frameByte(last(),1)==2,"future records never change selected lane display");
        LaneLifecycleTest unknown=new LaneLifecycleTest();unknown.sender.lanes(lanes(-1,5,2,1));
        check(unknown.sender.laneFrames.isEmpty(),"completely unidentified packet is not assigned to a guessed component");
    }
    public static void main(String[] ignored){
        new LaneLifecycleTest().earlyAndIndexed();new LaneLifecycleTest().selectionAndVisibility();
        new LaneLifecycleTest().stopAndOwnership();new LaneLifecycleTest().sourceAndLegacy();new LaneLifecycleTest().bounded();
        System.out.println("LaneLifecycleTest: "+checks+" checks passed");
    }
}
