package hmgapi.mods.wireless.dipo;

import android.content.Context;
import com.tcc.iap2.Iap2NotiMsg;
import hmgapi.hook.Car;
import hmgapi.hook.Cluster;
import java.util.Arrays;

/** CP enums and transport frames, independently of the AA enum used by PR75. */
public final class StandardTurnsTest {
    static int checks;
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static Iap2NotiMsg maneuver(int type,int side,int junction,Integer angle){
        Iap2NotiMsg m=new Iap2NotiMsg();
        m.getItemGroup().add(0,5,new int[]{7}).add(1,5,new int[]{3}).add(3,8,new int[]{type})
            .add(8,8,new int[]{side}).add(9,8,new int[]{junction});
        if(angle!=null)m.getItemGroup().add(11,1,new int[]{angle});return m;
    }
    static CpTurns.Maneuver facts(int type,int side,int junction,Integer angle){return new CpTurns.Maneuver(3,7,maneuver(type,side,junction,angle));}
    static int[] format(CpTurns.Maneuver m,int meters){return CpTurns.standardTurn(m,m.primaryIcon(1),meters);}
    static void expected(int cp,int side,int junction,Integer angle,int type,int dir){
        check(Arrays.equals(format(facts(cp,side,junction,angle),200),new int[]{type,dir,60}),"CP type "+cp+" side "+side+" angle "+angle);
    }
    static void mappings(){
        expected(1,0,0,null,13,270);expected(2,0,0,null,13,90);expected(3,0,0,null,13,0);
        expected(13,0,0,null,13,315);expected(14,0,0,null,13,45);
        expected(20,0,0,-75,13,285);expected(21,0,0,80,13,80);
        expected(47,0,0,null,13,225);expected(48,0,0,null,13,135);
        expected(49,0,0,-30,13,330);expected(50,0,0,30,13,30);
        expected(4,0,0,null,31,270);expected(4,1,0,180,31,90);
        expected(18,0,0,null,31,270);expected(26,0,0,null,31,270);
        expected(8,0,0,-30,18,330);expected(8,0,0,30,17,30);
        expected(22,0,0,null,18,270);expected(23,0,0,null,17,90);
        expected(7,0,1,150,32,150);expected(7,1,1,-60,33,300);
        expected(19,0,1,null,32,180);expected(28,0,1,90,32,90);expected(46,0,1,-90,32,270);
        expected(1,0,0,270,13,270);expected(2,0,0,-270,13,90);
        expected(7,0,1,300,32,300);expected(7,1,1,-300,33,60);
        expected(3,0,0,360,13,0);expected(3,0,0,-360,13,0);
        for (int zero:new int[]{0,360,-360}) {
            expected(1,0,0,zero,13,270);expected(2,0,0,zero,13,90);
            expected(13,0,0,zero,13,315);expected(14,0,0,zero,13,45);
            expected(20,0,0,zero,13,270);expected(21,0,0,zero,13,90);
            expected(47,0,0,zero,13,225);expected(48,0,0,zero,13,135);
            expected(49,0,0,zero,13,315);expected(50,0,0,zero,13,45);
            expected(22,0,0,zero,18,270);expected(23,0,0,zero,17,90);
        }
        expected(5,0,0,0,13,0);
        for(int cp:new int[]{0,6,9,10,11,15,16,17,24,25,27,51,52,53,99})
            check(format(facts(cp,0,0,null),200)==null,"unsupported CP type retains projection: "+cp);
        check(format(facts(7,0,1,null),200)==null,"roundabout requires a raw selected bearing");
        check(format(facts(1,0,0,90),200)==null,"left with contradictory right bearing falls back");
        check(format(facts(3,0,0,45),200)==null,"straight with contradictory bearing falls back");
        check(format(facts(47,0,0,-45),200)==null,"sharp type with slight bearing falls back");
        check(format(facts(49,0,0,-135),200)==null,"slight type with sharp bearing falls back");
        check(format(facts(4,0,0,90),200)==null,"U-turn with non-U-turn bearing falls back");
        check(format(facts(4,2,0,null),200)==null,"unknown driving side does not choose U-turn side");
        check(format(facts(8,0,0,null),200)==null,"unknown ramp side retains generic projection icon");
        check(format(facts(1,0,2,-90),200)==null,"unknown junction type falls back");
        CpTurns.Maneuver right=facts(2,0,0,90);
        check(format(right,-1)==null&&format(right,301)==null,"unknown/far distance retains projection");
        check(format(right,0)[2]==10&&format(right,45)[2]==10&&format(right,100)[2]==30&&format(right,300)[2]==100,"native bar math with the product's 300m window");
        check(Arrays.equals(CpTurns.standardTurn(right,67,-1,2),new int[]{3,0,0}),"explicit arrived state uses native arrival tuple, independent of guide window");
        for(int invalid:new int[]{-361,361,Integer.MIN_VALUE,Integer.MAX_VALUE}) {
            CpTurns.Maneuver malformed=facts(7,0,1,invalid);
            check(malformed.exit==CpTurns.NO_ANGLE && format(malformed,100)==null,"out-of-range bearing is not normalized: "+invalid);
        }
        for(int type=0;type<=53;type++) for(int side=0;side<2;side++) for(int junction=0;junction<2;junction++) {
            for(int raw=-360;raw<=360;raw++) {
                int signed=raw>180?raw-360:raw< -180?raw+360:raw;
                CpTurns.Maneuver incoming=facts(type,side,junction,raw), canonical=facts(type,side,junction,signed);
                check(incoming.primaryIcon(1)==canonical.primaryIcon(1)
                        && Arrays.equals(format(incoming,200),format(canonical,200))
                        && incoming.displayRoad().equals(canonical.displayRoad()),
                        "full-revolution wire bearing preserves direction: "+type+"/"+side+"/"+junction+"/"+raw);
            }
        }
    }
    static Iap2NotiMsg route(Integer meters,String text){
        Iap2NotiMsg r=new Iap2NotiMsg();r.getItemGroup().add(0,5,new int[]{7}).add(1,8,new int[]{1})
            .add(13,5,new int[]{3}).add(12,8,new int[]{2});
        if(meters!=null)r.getItemGroup().add(10,6,new int[]{meters});
        if(text!=null)r.getItemGroup().add(11,10,new String[]{text});return r;
    }
    static String latest(Cluster c,String prefix){String result="";for(String line:c.calls)if(line.startsWith(prefix))result=line;return result;}
    static void frames(){
        Car.unit=Car.DISTANCE_KM;
        Cluster c=new Cluster();CpTurns s=new CpTurns(new Context(),c,null,null,true);
        s.maneuver(maneuver(2,0,0,90));s.routeGuidance(route(200,null));
        check(latest(c,"tbt ").equals("tbt 13 0 90.0 200.0 0 0 0 60 0"),"optional CP standard type/direction/bar reaches main display");
        check(latest(c,"hud ").startsWith("hud f20143"),"HUD list retains right projection glyph67");
        check(latest(c,"2c ").equals("2c 0 0 200 0 0 0 0 0 0"),"HUD distance unchanged");
        s.routeGuidance(route(301,null));
        check(latest(c,"tbt ").equals("tbt 67 65535 0.0 301.0 0 0 0 0 0"),"far distance returns existing projection tuple");
        s.routeGuidance(route(null,"200"));
        check(latest(c,"tbt ").equals("tbt 13 0 90.0 200.0 0 0 0 60 0"),"formatted-only distance retains native guide output with a formatted display sample");
        s.routeGuidance(route(100,null));check(latest(c,"tbt ").equals("tbt 13 0 90.0 100.0 0 0 0 30 0"),"new raw distance restores optional standard output");
        Cluster legacy=new Cluster();CpTurns off=new CpTurns(new Context(),legacy,null,null);
        off.maneuver(maneuver(2,0,0,90));off.routeGuidance(route(200,null));
        check(latest(legacy,"tbt ").equals("tbt 67 0 0.0 200.0 0 0 0 0 0"),"legacy constructor/explicit-off preserves prior output exactly");
        int before=c.calls.size();s.routeGuidance(route(100,null));check(c.calls.size()==before,"unchanged standard tuple is deduplicated");
        Cluster wrap=new Cluster();CpTurns normalized=new CpTurns(new Context(),wrap,null,null,true);
        normalized.maneuver(maneuver(7,0,1,300));normalized.routeGuidance(route(200,null));
        check(latest(wrap,"tbt ").equals("tbt 32 0 300.0 200.0 0 0 0 60 0"),"full-circle angle reaches native roundabout frame");
        check(latest(wrap,"hud ").startsWith("hud f2016a"),"HUD retains canonical -60-degree roundabout icon106");
    }
    static Iap2NotiMsg displayRoute(int index,String text,int unit){
        Iap2NotiMsg r=new Iap2NotiMsg();r.getItemGroup().add(0,5,new int[]{7}).add(1,8,new int[]{1})
            .add(13,5,new int[]{index}).add(12,8,new int[]{unit});
        if(text!=null)r.getItemGroup().add(11,10,new String[]{text});return r;
    }
    static int traces(){int n=0;for(String line:Dipo.logs)if(line.startsWith("USB_CP_TBT_RENDER "))n++;return n;}
    static String trace(){String result="";for(String line:Dipo.logs)if(line.startsWith("USB_CP_TBT_RENDER "))result=line;return result;}
    static void distanceSequence(){
        Cluster c=new Cluster();ClusterFramesTest.Schedule clock=new ClusterFramesTest.Schedule();
        CpTurns s=new CpTurns(new Context(),c,null,clock,true);Dipo.logs.clear();
        s.maneuver(maneuver(2,0,0,90));s.routeGuidance(route(290,"100"));
        check(latest(c,"tbt ").equals("tbt 13 0 90.0 290.0 0 0 0 90 0"),"raw distance wins over conflicting formatted label");
        check(trace().equals("USB_CP_TBT_RENDER source=raw metersMilli=290000 type=13 direction=90.0 bar=90 distanceMilli=290000 unit=0 valid=1"),"trace captures the tuple actually sent and raw distance source");
        clock.advance(500);s.routeGuidance(route(null,"240"));
        check(latest(c,"tbt ").equals("tbt 13 0 90.0 240.0 0 0 0 80 0"),"formatted-only delta replaces stale raw distance and decreases the guide bar");
        check(traces()==1,"render trace limited to one write sample per second");
        clock.advance(500);s.routeGuidance(route(null,"100"));
        check(latest(c,"tbt ").equals("tbt 13 0 90.0 100.0 0 0 0 30 0"),"successive formatted distances keep the native turn active");
        check(trace().equals("USB_CP_TBT_RENDER source=display metersMilli=100000 type=13 direction=90.0 bar=30 distanceMilli=100000 unit=0 valid=1"),"trace labels formatted samples without road or destination data");
        clock.advance(1000);int before=traces();s.routeGuidance(route(null,"100"));
        check(traces()==before,"no fabricated progress trace when no tuple was written");
        s.routeGuidance(displayRoute(3,"0.2",0));
        check(latest(c,"tbt ").equals("tbt 13 0 90.0 0.2 0 0 1 60 0"),"kilometre display units converted to meters for the guide only");
        s.routeGuidance(displayRoute(3,"300.1 m",2));
        check(latest(c,"tbt ").startsWith("tbt 67 65535 0.0 300.1 "),"distance just outside guide window is not truncated into it");
        s.routeGuidance(displayRoute(3,"0.3 mi",1));
        check(latest(c,"tbt ").equals("tbt 67 65535 0.0 0.3 0 0 2 0 0"),"far imperial formatted distance retains projection output");
        s.routeGuidance(displayRoute(3,"300 ft",4));
        check(latest(c,"tbt ").equals("tbt 13 0 90.0 300.0 0 0 3 30 0"),"feet are converted for native progress but label units remain feet");
        s.routeGuidance(displayRoute(3,"50 yd",3));
        check(latest(c,"tbt ").equals("tbt 13 0 90.0 50.0 0 0 4 10 0"),"yards reach the r8.2 main bar scale");
        s.routeGuidance(route(null,"0"));
        check(latest(c,"tbt ").equals("tbt 13 0 90.0 0.0 0 0 0 10 0"),"explicit zero keeps the r8.2 bar floor; no guessed passed-state");
        Iap2NotiMsg next=new Iap2NotiMsg();next.getItemGroup().add(0,5,new int[]{7}).add(1,5,new int[]{4})
            .add(3,8,new int[]{1}).add(8,8,new int[]{0}).add(9,8,new int[]{0}).add(11,1,new int[]{-90});
        s.maneuver(next);clock.advance(1000);s.routeGuidance(displayRoute(4,null,2));
        check(latest(c,"tbt ").equals("tbt 0 0 0.0 0.0 -1 0 0 0 -1"),"next maneuver cannot inherit previous distance or progress");
        check(latest(c,"hud ").equals("hud f2"),"missing distance uses established whole-maneuver blank, never a zero-valued arrow");
        s.routeGuidance(displayRoute(4,"200",99));
        check(latest(c,"tbt ").equals("tbt 0 0 0.0 0.0 -1 0 0 0 -1"),"unknown unit cannot enable native guide");
        s.routeGuidance(displayRoute(4,"200 m",99));
        check(latest(c,"tbt ").equals("tbt 13 0 270.0 200.0 0 0 0 60 0"),"explicit suffix supplies a verified unit even with unknown phone enum");
        for(float bad:new float[]{Float.NaN,Float.POSITIVE_INFINITY,-1f,65536f})
            check(new CpTurns.Distance(bad,Cluster.UNIT_M).guideMeters(-1)<0,"invalid formatted sample rejected: "+bad);
        check(new CpTurns.Distance(100f,99).guideMeters(-1)<0,"unknown display unit rejected");
        for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,300.001,-0.1})
            check(CpTurns.standardTurn(facts(2,0,0,90),67,bad,1)==null,"invalid/outside guide sample rejected: "+bad);
        for(String line:Dipo.logs)if(line.startsWith("USB_CP_TBT_RENDER "))
            check(line.matches("USB_CP_TBT_RENDER source=(raw|display|none) metersMilli=(-1|[0-9]+) type=[0-9]+ direction=[0-9]+([.][05])? bar=[0-9]+ distanceMilli=[0-9]+ unit=[0-4] valid=[01]"),"numeric trace adheres to USB sanitizer grammar");
    }
    public static void main(String[] ignored){mappings();frames();distanceSequence();System.out.println("StandardTurnsTest: "+checks+" checks passed; KOR visual acceptance remains unverified");}
}
