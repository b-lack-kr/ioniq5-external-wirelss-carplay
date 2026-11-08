package hmgapi.mods.wireless.dipo;
import android.content.Context;
import com.tcc.iap2.Iap2NotiMsg;
import hmgapi.hook.Car;
import hmgapi.hook.Cluster;
import java.util.*;
/** Ported native display policies exercised through actual component/index-scoped packets. */
public final class Guidance147Test {
    static int checks;
    static void check(boolean yes,String why){checks++;if(!yes)throw new AssertionError(why);}
    final Cluster c=new Cluster();final ClusterFramesTest.Schedule clock=new ClusterFramesTest.Schedule();
    final ArrayList<String> fine=new ArrayList<String>();final ArrayList<int[]> pictures=new ArrayList<int[]>();
    final CpTurns turns=new CpTurns(new Context(),c,null,clock,true,new CpTurns.PatternSink(){public boolean send(int[] p){pictures.add(p.clone());return true;}},new CpTurns.ApproachSink(){public void update(boolean a,int b){fine.add(a+":"+b);}});
    Guidance147Test(){Car.unit=Car.DISTANCE_KM;turns.setCloseApproach(true);}
    static Iap2NotiMsg route(int state,Integer distance,int lane,boolean show){Iap2NotiMsg r=NativeTransitionTest.route(7,3,distance,null);r.getItemGroup().add(1,8,new int[]{state});if(lane>=0)r.getItemGroup().add(16,5,new int[]{lane}).add(17,5,new int[]{1});r.getItemGroup().add(18,9,new int[]{show?1:0});return r;}
    void maneuver(int type,Integer angle){turns.maneuver(NativeTransitionTest.maneuver(7,3,type,0,angle));}
    String last(String prefix){return StandardTurnsTest.latest(c,prefix);}
    static CpLaneDecoder.Info lanes(int component,int index,int... statuses){CpLaneDecoder.Lane[] lanes=new CpLaneDecoder.Lane[statuses.length];for(int i=0;i<lanes.length;i++)lanes[i]=new CpLaneDecoder.Lane(i,statuses[i],new int[]{0},0);return new CpLaneDecoder.Info(component,index,lanes,0);}
    void pending(){
        turns.routeGuidance(route(3,null,-1,false));check(last("tbt ").equals("tbt 9 0 0.0 0.0 0 0 0 0 0"),"loading shown before maneuver arrives");
        check(pictures.size()==1&&pictures.get(0)[0]==65535,"loading takeover clears old native picture once");
        maneuver(2,90);turns.routeGuidance(route(1,30,9,true));turns.rawLanes(lanes(7,9,1,0));
        check(last("tbt ").startsWith("tbt 13 ")&&!last("lanes ").equals("lanes f1"),"active current turn and lanes");
        turns.routeGuidance(route(5,null,9,true));check(last("tbt ").equals("tbt 10 0 0.0 0.0 0 0 0 0 0"),"reroute is native10, not prior turn");
        check(last("hud ").equals("hud f2")&&last("lanes ").equals("lanes f1")&&fine.get(fine.size()-1).equals("false:0"),"reroute clears stale F2/lanes and releases finebar");
        int laneWrites=0;for(String line:c.calls)if(line.startsWith("lanes "))laneWrites++;
        clock.advance(2000);check(last("tbt ").startsWith("tbt 10 ")&&last("hud ").equals("hud f2"),"reroute refresh never restores old arrow");
        turns.routeGuidance(route(1,null,9,true));check(last("tbt ").equals("tbt 67 65535 0.0 0.0 -1 0 0 0 0"),"state resume needs fresh distance before AR");
        check(last("lanes ").equals("lanes f1"),"reroute resume cannot replay cached pre-reroute lanes");
        turns.routeGuidance(route(1,29,9,true));check(last("tbt ").startsWith("tbt 13 "),"fresh resumed raw distance restores AR");
        turns.routeGuidance(route(0,null,9,false));check(last("tbt ").startsWith("tbt 8 "),"end restores idle8, not loading0");
        int writes=c.calls.size();clock.advance(3000);check(c.calls.size()==writes,"ended route no heartbeat");
    }
    void rampLanes(){
        maneuver(23,90);turns.rawLanes(lanes(7,9,1,0));turns.routeGuidance(route(1,200,9,true));
        check(last("tbt ").startsWith("tbt 19 0 90.0 "),"right ramp with nonrecommended edge uses19");
        int approach=fine.size();turns.rawLanes(lanes(7,9,0,2));check(last("tbt ").startsWith("tbt 17 0 90.0 "),"edge recommended switches back to plain right17");
        check(fine.size()==approach,"raw lane-only change does not renew distance lease");
        turns.rawLanes(lanes(7,10,1,0));check(last("tbt ").startsWith("tbt 17 "),"future index cannot change current AR");
        turns.rawLanes(lanes(8,9,1,0));check(last("tbt ").startsWith("tbt 17 "),"other component cannot change current AR");
        turns.routeGuidance(route(1,190,10,true));check(last("tbt ").startsWith("tbt 19 "),"route current index selects cached corresponding lanes");
        turns.routeGuidance(route(1,180,10,false));check(last("tbt ").startsWith("tbt 17 "),"not-shown raw lane record does not claim lane-change geometry");
        turns.routeGuidance(route(1,170,11,true));check(last("tbt ").startsWith("tbt 17 "),"missing selected record uses plain exit");
        maneuver(22,-90);turns.rawLanes(lanes(7,11,0,1,0));turns.routeGuidance(route(1,160,11,true));check(last("tbt ").startsWith("tbt 20 0 270.0 "),"left ramp nonrecommended left edge uses20");
        turns.rawLanes(lanes(7,11,2,0,0));check(last("tbt ").startsWith("tbt 18 0 270.0 "),"left recommended edge uses18");
        maneuver(23,-90);turns.routeGuidance(route(1,150,11,true));check(last("tbt ").startsWith("tbt 81 "),"contradictory selected side retains projection");
        maneuver(9,30);turns.routeGuidance(route(1,140,12,true));check(last("tbt ").startsWith("tbt 17 0 90.0 "),"on-ramp supported with known side");
        turns.rawLanes(new CpLaneDecoder.Info(7,12,new CpLaneDecoder.Lane[]{
                new CpLaneDecoder.Lane(0,1,new int[0],1000),new CpLaneDecoder.Lane(1,0,new int[]{0},1000)},0));
        check(last("tbt ").startsWith("tbt 17 "),"unknown raw physical lane cannot prove lane-change geometry");
    }
    void destinations(){
        for(int type:new int[]{10,12,24,25,27}){
            maneuver(type,null);turns.routeGuidance(route(1,51,-1,false));check(!last("tbt ").startsWith("tbt 2 ")&&!last("tbt ").startsWith("tbt 3 "),"destination remains projection outside50m");
            turns.routeGuidance(route(1,50,-1,false));String prefix=type==24?"tbt 2 0 270.0 ":type==25?"tbt 2 0 90.0 ":"tbt 3 0 0.0 ";check(last("tbt ").startsWith(prefix),"native destination geometry type"+type);
            turns.routeGuidance(route(2,null,-1,false));check(last("tbt ").startsWith("tbt 3 0 0.0 ")&&last("hud ").equals("hud f2"),"explicit arrived uses3 and no old F2");
        }
    }
    public static void main(String[] args){new Guidance147Test().pending();new Guidance147Test().rampLanes();new Guidance147Test().destinations();System.out.println("Guidance147Test: "+checks+" checks passed");}
}
