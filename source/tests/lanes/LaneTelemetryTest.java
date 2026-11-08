package hmgapi.mods.wireless.dipo;

import com.tcc.iap2.Iap2NotiMsg;

/** Numeric, bounded evidence from input normalization through optional sink return. */
public final class LaneTelemetryTest {
    static int checks;
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static String last(String kind){String out="";for(String s:Dipo.logs)if(s.startsWith("USB_CP_TBT_DIAG kind="+kind+" "))out=s;return out;}
    static int count(String kind){int n=0;for(String s:Dipo.logs)if(s.startsWith("USB_CP_TBT_DIAG kind="+kind+" "))n++;return n;}
    static void samples() {
        Dipo.logs.clear();AuxiliaryIsolationTest f=new AuxiliaryIsolationTest();f.start();
        Iap2NotiMsg incoming=AuxiliaryIsolationTest.exit(5);
        f.turns.lanes(incoming);f.worker.run();
        String input=last("lane_input");
        check(input.contains("source=1 exact=1 count=3 indexes=0,1,2 states=0,0,2"),"OEM normalization retains physical order and preferred state");
        check(input.contains("highlights=1000,1000,45 angleCounts=1,1,0 angles=0,0"),"angle arrays preserve row boundaries without instruction text");
        check(last("lane_write").contains("returned=1 frame=241,3,"),"actual lane sink return is distinct from submitted candidate");
        check(last("pattern_write").contains("returned=1 frame=65535,65535,65535,0,0"),"unsafe curved template is cleared through sink");
        check(last("lane_select").contains("templatePolicy=0"),"selection evidence explicitly marks guessed template path disabled");
        for(int i=0;i<100;i++)f.turns.lanes(incoming);
        check(count("lane_input")==1,"repeated packets cannot flood diagnostics within one second");
        f.clock.now=1000;f.turns.lanes(incoming);f.worker.run();
        check(count("lane_input")==2 && last("lane_input").contains("dropped=100"),"later sample survives first-record budget and reports skipped count");
        f.clock.now=3600000;
        f.turns.lanes(AuxiliaryIsolationTest.lanes(7,5,
                AuxiliaryIsolationTest.lane(-1,99,new int[]{0},1000),
                AuxiliaryIsolationTest.lane(-1,2,new int[]{90},90)));
        f.worker.run();
        check(last("lane_input").contains("exact=0 count=2 indexes=-1,-1 states=-1,2"),"invalid/indexless OEM input remains diagnosable after a long trip");
        check(last("lane_write").contains("frame=241,2,"),"final encoded incomplete frame is recorded separately");
        f.clock.now+=1000;
        f.cluster.failNextLane=true;
        f.turns.lanes(AuxiliaryIsolationTest.exit(5));f.worker.run();
        check(last("lane_write").contains("returned=0"),"failed sink records failure without claiming hardware ACK");
        for(String line:Dipo.logs)if(line.startsWith("USB_CP_TBT_DIAG ")) {
            check(line.matches("USB_CP_TBT_DIAG kind=(route|maneuver|lane_input|lane_select|distance_wait|lane_write|pattern_write)( [A-Za-z][A-Za-z0-9]*=-?[0-9]+(,-?[0-9]+)*)+"),"fixed numeric grammar");
            check(line.length()<4000,"bounded evidence line size");
        }
    }
    public static void main(String[] args){samples();System.out.println("LaneTelemetryTest PASS checks="+checks);}
}
