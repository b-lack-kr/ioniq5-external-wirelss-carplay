package hmgapi.mods.wireless.dipo;

import android.content.Context;
import com.tcc.iap2.Iap2NotiMsg;
import hmgapi.hook.Cluster;
import java.util.ArrayList;
import java.util.List;

/** Real item2 parser input; no assumed meaning for the wire enum numbers. */
public final class PhaseTraceTest {
    static int checks;
    static void check(boolean value,String why) { checks++;if(!value)throw new AssertionError(why); }
    final Cluster cluster = new Cluster();
    final ClusterFramesTest.Schedule clock = new ClusterFramesTest.Schedule();
    final CpTurns turns = new CpTurns(new Context(),cluster,null,clock,true);
    PhaseTraceTest() { Dipo.logs.clear(); turns.maneuver(StandardTurnsTest.maneuver(2,0,0,90)); }
    static Iap2NotiMsg phase(int value) {
        Iap2NotiMsg m=new Iap2NotiMsg();
        m.getItemGroup().add(0,5,new int[]{7}).add(2,8,new int[]{value});return m;
    }
    static Iap2NotiMsg meters(int value) {
        Iap2NotiMsg m=new Iap2NotiMsg();
        m.getItemGroup().add(0,5,new int[]{7}).add(10,6,new int[]{value});return m;
    }
    static List<String> events() {
        List<String> lines=new ArrayList<String>();
        for(String s:Dipo.logs)if(s.startsWith("USB_CP_TBT_PHASE "))lines.add(s);return lines;
    }
    static String last() { List<String> lines=events();return lines.get(lines.size()-1); }
    void start() { turns.routeGuidance(StandardTurnsTest.route(200,null)); }
    void presenceAndPersistence() {
        start();
        check(last().equals("USB_CP_TBT_PHASE route=1 phase=1 present=0 component=7 maneuver=3 meters=200 dropped=0"),"default1 is explicitly not a received phase");
        turns.routeGuidance(meters(190));
        check(events().size()==1,"distance-only change is not a phase event");
        turns.routeGuidance(phase(1));
        check(last().equals("USB_CP_TBT_PHASE route=1 phase=1 present=1 component=7 maneuver=3 meters=190 dropped=0"),"received1 differs from the absent default");
        turns.routeGuidance(meters(180));
        check(events().size()==2 && turns.updates.get(7).maneuverStatePresent,"partial packets preserve actual received presence");
        int calls=cluster.calls.size();
        turns.routeGuidance(phase(2));
        check(last().contains("phase=2 present=1"),"raw2 recorded without assigning an Apple enum name");
        check(cluster.calls.size()==calls,"a phase-only change does not change TBT geometry or synthesize cursor motion");
        turns.routeGuidance(phase(Integer.MIN_VALUE));
        check(last().contains("phase=-2147483648 present=1"),"unknown signed raw enum retained as evidence");
        turns.routeGuidance(phase(Integer.MAX_VALUE));
        check(last().contains("phase=2147483647 present=1"),"maximum signed enum retained as evidence");
        for(String s:events())check(s.matches("USB_CP_TBT_PHASE route=-?[0-9]+ phase=-?[0-9]+ present=[01] component=-?[0-9]+ maneuver=-?[0-9]+ meters=-?[0-9]+ dropped=[0-9]+"),"strict numeric grammar without road/app text");
    }
    void invalidAbsentEndAndReset() {
        Iap2NotiMsg malformed=StandardTurnsTest.route(200,null);
        malformed.getItemGroup().add(2,8,new int[]{}).add(2,10,new String[]{"private route text"});
        turns.routeGuidance(malformed);
        check(!turns.updates.get(7).maneuverStatePresent && last().contains("present=0"),"empty/wrong-type item2 cannot establish presence");
        turns.routeGuidance(phase(73));
        Iap2NotiMsg ended=new Iap2NotiMsg();
        ended.getItemGroup().add(0,5,new int[]{7}).add(1,8,new int[]{0});
        turns.routeGuidance(ended);
        check(last().equals("USB_CP_TBT_PHASE route=0 phase=73 present=1 component=7 maneuver=3 meters=200 dropped=0"),"route end is captured before clear erases fields");
        check(!turns.updates.get(7).maneuverStatePresent,"clear resets presence for the next guidance session");
        turns.disconnected();clock.advance(1000);start();
        check(last().contains("phase=1 present=0"),"reconnect cannot reuse received phase from a previous route");
        int before=events().size();
        Iap2NotiMsg component=new Iap2NotiMsg();
        component.getItemGroup().add(0,5,new int[]{8}).add(1,8,new int[]{1}).add(13,5,new int[]{3});
        turns.routeGuidance(component);
        check(events().size()==before+1 && last().contains("component=8 maneuver=3"),"a new component has independent unknown phase state");
        Iap2NotiMsg next=new Iap2NotiMsg();next.getItemGroup().add(0,5,new int[]{8}).add(13,5,new int[]{4});
        turns.routeGuidance(next);
        check(last().contains("component=8 maneuver=4"),"maneuver identity transition is recorded without a phase change");
    }
    void rollingLimitAndLatestRecovery() {
        List<Long> timestamps=new ArrayList<Long>();
        start();timestamps.add(clock.now);
        for(int i=1;i<=7;i++) {
            clock.advance(100);turns.routeGuidance(phase(i));timestamps.add(clock.now);
        }
        check(events().size()==8,"first eight changed states fit the budget");
        clock.advance(100);turns.routeGuidance(phase(8));
        clock.advance(100);turns.routeGuidance(phase(9));
        check(events().size()==8,"ninth and tenth changes within1000ms are suppressed");
        clock.advance(99);turns.routeGuidance(meters(123));
        check(events().size()==8,"distance-only retry cannot bypass the rolling budget");
        clock.advance(1);turns.routeGuidance(meters(122));timestamps.add(clock.now);
        check(last().equals("USB_CP_TBT_PHASE route=1 phase=9 present=1 component=7 maneuver=3 meters=122 dropped=2"),"next packet flushes only the latest phase and current raw meters with drop count");
        check(events().size()==9,"suppressed phases are not replayed in a burst");
        clock.advance(50);turns.routeGuidance(phase(10));
        check(events().size()==9,"a fixed-boundary reset cannot emit more than eight in a sliding window");
        clock.advance(50);turns.routeGuidance(meters(121));timestamps.add(clock.now);
        check(last().contains("phase=10 present=1") && last().endsWith("dropped=1"),"a second suppressed state recovers at the next available slot");
        for(long end:timestamps) {
            int n=0;for(long t:timestamps)if(t<=end && end-t<1000)n++;
            check(n<=8,"every1000ms rolling interval contains at most eight events");
        }
        int before=events().size();clock.advance(5000);
        check(events().size()==before,"no autonomous timer logs fabricated state");
        turns.routeGuidance(meters(120));check(events().size()==before,"flushed pending state returns to distance-only silence");
    }
    void alternatingDisplays() {
        Iap2NotiMsg first=NativeTransitionTest.route(7,3,200,null);
        first.getItemGroup().add(2,8,new int[]{0});turns.routeGuidance(first);
        Iap2NotiMsg second=NativeTransitionTest.route(8,3,200,null);
        second.getItemGroup().add(2,8,new int[]{3});turns.routeGuidance(second);
        check(events().size()==2,"each display establishes one state observation");
        for(int i=0;i<10;i++) {
            turns.routeGuidance(NativeTransitionTest.route(7,3,199-i,null));
            turns.routeGuidance(NativeTransitionTest.route(8,3,199-i,null));
        }
        check(events().size()==2,"alternating display distance updates do not masquerade as phase transitions");
        for(int i=1;i<=6;i++)turns.routeGuidance(phase(i));
        check(events().size()==8,"both displays share one rolling output budget");
        turns.routeGuidance(phase(99));
        turns.routeGuidance(meters(42));
        clock.advance(999);turns.routeGuidance(NativeTransitionTest.route(8,3,125,null));
        check(events().size()==8,"unchanged second display neither consumes nor escapes the budget");
        clock.advance(1);turns.routeGuidance(NativeTransitionTest.route(8,3,124,null));
        check(last().equals("USB_CP_TBT_PHASE route=1 phase=99 present=1 component=7 maneuver=3 meters=42 dropped=1"),
                "another display cannot overwrite the pending component or mix its distance into the observation");
        int count=events().size();turns.routeGuidance(meters(41));
        check(events().size()==count,"latest recovered component returns to distance-only silence");
    }
    public static void main(String[] ignored) {
        new PhaseTraceTest().presenceAndPersistence();
        new PhaseTraceTest().invalidAbsentEndAndReset();
        new PhaseTraceTest().rollingLimitAndLatestRecovery();
        new PhaseTraceTest().alternatingDisplays();
        System.out.println("PhaseTraceTest: "+checks+" checks passed; enum meaning intentionally unresolved");
    }
}
