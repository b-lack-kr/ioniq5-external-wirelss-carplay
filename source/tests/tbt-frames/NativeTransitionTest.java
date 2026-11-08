package hmgapi.mods.wireless.dipo;

import android.content.Context;
import com.tcc.iap2.Iap2NotiMsg;
import hmgapi.hook.Car;
import hmgapi.hook.Cluster;

/** Actual iAP2 packets through CpTurns, with the resulting public Cluster calls.
 * These are protocol regressions, not a claim that host tests render an AR cursor. */
public final class NativeTransitionTest {
    static int checks;
    static void check(boolean value, String why) {
        checks++; if (!value) throw new AssertionError(why);
    }
    final Cluster cluster = new Cluster();
    final ClusterFramesTest.Schedule clock = new ClusterFramesTest.Schedule();
    final CpTurns turns;
    NativeTransitionTest(boolean standard) {
        Car.unit = Car.DISTANCE_KM;
        turns = new CpTurns(new Context(), cluster, null, clock, standard);
    }
    static Iap2NotiMsg maneuver(int component, int index, int type, int junction, Integer angle) {
        Iap2NotiMsg m = new Iap2NotiMsg();
        m.getItemGroup().add(0,5,new int[]{component}).add(1,5,new int[]{index})
            .add(3,8,new int[]{type}).add(8,8,new int[]{0}).add(9,8,new int[]{junction});
        if (angle != null) m.getItemGroup().add(11,1,new int[]{angle});
        return m;
    }
    static Iap2NotiMsg route(int component, int index, Integer meters, String text) {
        Iap2NotiMsg r = new Iap2NotiMsg();
        r.getItemGroup().add(0,5,new int[]{component}).add(1,8,new int[]{1})
            .add(13,5,new int[]{index}).add(12,8,new int[]{2});
        if (meters != null) r.getItemGroup().add(10,6,new int[]{meters});
        if (text != null) r.getItemGroup().add(11,10,new String[]{text});
        return r;
    }
    void meters(int meters) { turns.routeGuidance(route(7,3,meters,null)); }
    String latest(String prefix) { return StandardTurnsTest.latest(cluster, prefix); }
    int count(String prefix) {
        int n=0; for (String s:cluster.calls) if (s.startsWith(prefix)) n++; return n;
    }
    void frame(int type, int sides, int direction, int meters, int bar) {
        check(latest("tbt ").equals("tbt "+type+" "+sides+" "+direction+".0 "+meters+".0 0 0 0 "+bar+" 0"),
                "main tuple for "+meters+"m: "+latest("tbt "));
        check(latest("2c ").equals("2c 0 0 "+meters+" 0 0 0 0 0 0"), "independent HUD distance at "+meters);
    }
    void approachAndNext() {
        turns.maneuver(maneuver(7,3,2,0,90));
        meters(301); frame(67,65535,0,301,0);
        check(latest("hud ").startsWith("hud f20143"), "far HUD uses right projection67");
        int hud=count("hud ");
        for (int m:new int[]{300,45,1,0}) {
            meters(m); frame(13,0,90,m,m==300?100:10);
            check(count("hud ")==hud, "unchanged projection F2 is deduplicated, not blanked in native mode");
            check(latest("hud ").startsWith("hud f20143"), "native main retains projection HUD list");
        }
        int total=cluster.calls.size(); clock.advance(10000);
        check(cluster.calls.size()==total, "no progress heartbeat or fabricated motion after a zero sample");
        check(clock.work==null, "no queued cursor update");
        turns.maneuver(maneuver(7,4,1,0,-90));
        turns.routeGuidance(route(7,4,null,null));
        check(latest("tbt ").equals("tbt 0 0 0.0 0.0 -1 0 0 0 -1"), "new maneuver does not inherit previous zero distance");
        turns.routeGuidance(route(7,4,600,null)); frame(70,65535,0,600,0);
        check(latest("hud ").startsWith("hud f20146"), "new left maneuver replaces HUD right glyph");
        turns.routeGuidance(route(7,4,45,null)); frame(13,0,270,45,10);
        check(latest("tbt ").startsWith("tbt 13 "), "ordinary turn never fabricates route-arrived type3");
    }
    void freshIdentityAndDedup() {
        turns.maneuver(maneuver(7,3,2,0,90)); meters(45);
        int before=count("tbt "); meters(45);
        check(count("tbt ")==before,"identical update of one maneuver is deduplicated");
        turns.maneuver(maneuver(7,4,2,0,90));
        turns.routeGuidance(route(7,4,45,null));
        check(count("tbt ")==before+1,"same numeric tuple at a new maneuver still writes one main frame");
        before=count("tbt ");
        turns.maneuver(maneuver(8,4,2,0,90));
        turns.routeGuidance(route(8,4,45,null));
        check(count("tbt ")==before+1,"same maneuver index on a different display component is a new identity");
    }
    void roundaboutAndFallback() {
        turns.maneuver(maneuver(7,3,7,1,210));
        meters(301); frame(103,65535,0,301,0);
        check(latest("hud ").startsWith("hud f20167"),"210-degree roundabout preserves projection103 on HUD");
        meters(300); frame(32,0,210,300,100);
        meters(1); frame(32,0,210,1,10);
        meters(0); frame(32,0,210,0,10);
        turns.maneuver(maneuver(7,4,1,0,90)); // Contradicts left: keep known projection.
        turns.routeGuidance(route(7,4,45,null)); frame(70,65535,0,45,0);
        turns.routeGuidance(route(7,4,-1,null));
        check(latest("tbt ").equals("tbt 0 0 0.0 0.0 -1 0 0 0 -1"),"invalid raw distance cannot retain native geometry");
        turns.maneuver(maneuver(7,5,7,1,null));
        turns.routeGuidance(route(7,5,45,null));
        check(!latest("tbt ").startsWith("tbt 32 "),"unknown selected bearing does not invent native roundabout geometry");
    }
    void ownershipAndOff() {
        turns.maneuver(maneuver(7,3,2,0,90)); meters(301);
        int before=cluster.calls.size();
        turns.outputAllowed=false; // DiPO's native/Android Auto ownership gate.
        meters(45); clock.advance(5000);
        check(cluster.calls.size()==before,"other navigation owner suppresses CP main/HUD/distance output");
        turns.outputAllowed=true; meters(1); frame(13,0,90,1,10);
        turns.disconnected();
        check(latest("tbt ").equals("tbt 0 0 0.0 0.0 -1 0 0 0 -1"),"disconnect clears the previous turn");
        before=cluster.calls.size(); clock.advance(5000);
        check(cluster.calls.size()==before,"disconnect leaves no cursor replay");
        NativeTransitionTest off = new NativeTransitionTest(false);
        off.turns.maneuver(maneuver(7,3,2,0,90));
        for(int m:new int[]{301,300,45,1,0}) { off.meters(m); off.frame(67,0,0,m,0); }
        check(off.latest("hud ").startsWith("hud f20143"),"explicit-off legacy projection keeps its HUD glyph");
    }
    void typedTurnWithZeroBearing() {
        turns.maneuver(maneuver(7,3,2,0,0));
        meters(301); frame(67,65535,0,301,0);
        meters(300); frame(13,0,90,300,100);
        check(latest("hud ").startsWith("hud f20143"), "zero-bearing right retains HUD right glyph");
        turns.maneuver(maneuver(7,4,1,0,0));
        turns.routeGuidance(route(7,4,45,null)); frame(13,0,270,45,10);
        check(latest("hud ").startsWith("hud f20146"), "zero-bearing left retains HUD left glyph");
        turns.maneuver(maneuver(7,4,1,0,90));
        frame(70,65535,0,45,0);
        check(latest("tbt ").startsWith("tbt 70 "), "contradictory nonzero angle still refuses native left geometry");
    }
    public static void main(String[] ignored) {
        new NativeTransitionTest(true).approachAndNext();
        new NativeTransitionTest(true).freshIdentityAndDedup();
        new NativeTransitionTest(true).roundaboutAndFallback();
        new NativeTransitionTest(true).ownershipAndOff();
        new NativeTransitionTest(true).typedTurnWithZeroBearing();
        System.out.println("NativeTransitionTest: "+checks+" checks passed; visual cursor motion needs vehicle validation");
    }
}
