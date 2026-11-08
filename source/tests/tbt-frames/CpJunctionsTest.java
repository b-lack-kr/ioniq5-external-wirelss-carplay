// SPDX-License-Identifier: AGPL-3.0-or-later
// Adapted from seogaemo/hmg-seogaemo, commit 26eeb97e0a745528b438d7a946be5450fadbfd04.
// NEEV: junction evidence only; existing transport and lane rendering remain owned locally.
package hmgapi.mods.wireless.dipo;

/** Host proof of selector invariants; the pictures are stock templates, not lane-exact. */
public final class CpJunctionsTest {
    private static int checks;
    private static void check(String label, boolean ok) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }
    private static CpTurns.Maneuver maneuver(int type) {
        CpTurns.Maneuver m = new CpTurns.Maneuver(0, 1, null);
        m.type = type;
        return m;
    }
    private static CpLaneDecoder.Lane lane(int status, int highlight, int... angles) {
        return new CpLaneDecoder.Lane(0, status, angles, highlight);
    }
    private static CpLaneDecoder.Info lanes(CpLaneDecoder.Lane... lanes) {
        return new CpLaneDecoder.Info(1, 0, lanes, 0);
    }
    /** Lane rows as logged on the car: '-' off route, '+' route, '*' preferred; then the arrow
     * angles, e.g. "-0 -0 -0 +90". A route lane's highlight is its first angle. */
    private static CpLaneDecoder.Info row(String spec) {
        String[] parts = spec.trim().split(" +");
        CpLaneDecoder.Lane[] l = new CpLaneDecoder.Lane[parts.length];
        for (int k = 0; k < parts.length; k++) {
            char c = parts[k].charAt(0);
            String[] a = parts[k].substring(1).split(",");
            int[] angles = new int[a.length];
            for (int i = 0; i < a.length; i++) angles[i] = Integer.parseInt(a[i]);
            int status = c == '*' ? 2 : c == '+' ? 1 : 0;
            l[k] = lane(status, status == 0 ? 1000 : angles[0], angles);
        }
        return lanes(l);
    }
    private static int[] select(int type, String spec) {
        return CpJunctions.select(maneuver(type), null, row(spec));
    }
    private static void pair(String label, int[] selected, int a, int b) {
        check(label, selected != null && selected[0] == a && selected[1] == b && selected[2] == 65535);
    }
    private static void belongs(int[] result) {
        boolean found = false;
        for (int h = 0; h < CpJunctionTable.HIGHLIGHTS.length; h += 5)
            if (CpJunctionTable.HIGHLIGHTS[h + 1] == result[1]) {
                found = CpJunctionTable.PICTURES[CpJunctionTable.HIGHLIGHTS[h] * 4] == result[0];
                check("selected highlight is an exit or straight route " + result[1],
                        CpJunctionTable.HIGHLIGHTS[h + 2] == CpJunctionTable.ROUTE_EXIT
                        || CpJunctionTable.HIGHLIGHTS[h + 2] == CpJunctionTable.ROUTE_STRAIGHT);
            }
        check("B belongs to selected A " + result[0], found);
    }
    public static void main(String[] args) {
        check("no current maneuver", CpJunctions.select(null, null, row("-0 -0 +90")) == null);
        // Exit types and highway changes, from the 2026-11-06 drive.
        pair("right exit, one exit lane", select(23, "-0 -0 -0 +90"), 10193, 10194);
        pair("five lanes still take the stock template", select(23, "-0 -0 -0 -0 +90"), 10193, 10194);
        pair("highway change right at an IC", select(53, "-0 -0 +90"), 10193, 10194);
        pair("generic ramp takes its side from the lanes", select(8, "-0 -0 +90"), 10193, 10194);
        pair("generic ramp on the left", select(8, "+-90 -0 -0"), 10016, 10018);
        pair("left exit", select(22, "+-90 -0 -0"), 10016, 10018);
        pair("two-lane road", select(23, "-0 +90"), 10046, 10047);
        pair("two exit lanes", select(23, "-0 -0 -0 +90 +90"), 10320, 10322);
        pair("two exit lanes, route on the outer one", select(23, "-0 -0 -0 -0 -90 +90"), 10320, 10322);
        pair("preferred lane counts as route", select(23, "-0 -0 *90"), 10193, 10194);
        CpTurns.Maneuver next = maneuver(53);
        pair("next ramp selects the second stock diagram",
                CpJunctions.select(maneuver(23), next, row("-0 -0 -0 +90")), 10168, 10170);
        next.type = 2;
        pair("ordinary next turn keeps the default",
                CpJunctions.select(maneuver(23), next, row("-0 -0 -0 +90")), 10193, 10194);
        CpTurns.Maneuver text = maneuver(23);
        text.description = "고가차도"; text.afterRoad = "경부고속도로";
        pair("text never changes the selection", CpJunctions.select(text, null, row("-0 -0 -0 +90")), 10193, 10194);

        // Keeps: toward an exit, or staying on the through lanes while an exit peels off.
        pair("keep right onto the exit lane", select(14, "-0 -0 -0 -0 -0 +90"), 10193, 10194);
        pair("keep right on a two-lane ramp", select(14, "-0 +90"), 10046, 10047);
        pair("keep left onto a left exit", select(13, "+-90 -0 -0"), 10016, 10018);
        pair("keep left past a right exit", select(13, "+0 +0 +0 -90"), 10193, 10195);
        pair("keep left past a two-lane right exit", select(13, "+0 +0 -0 -90 -90"), 10320, 10323);
        pair("keep left past an exit on a two-lane road", select(13, "+0 -90"), 10046, 10048);
        pair("keep right past a left exit", select(14, "--90 +0 +0"), 10016, 10017);
        pair("keep right past a left exit, two lanes", select(14, "--90 +0"), 10001, 10002);
        check("keep left, half split", select(13, "+0 +0 +0 -90 -90 -90") == null);
        check("keep left, the route is on the exit lane", select(13, "-0 -0 +90") == null);
        check("keep right, route still on the through lanes", select(14, "+0 +0 +0 -90") == null);
        check("keep right in town (U-turn lane)", select(14, "-180 -0 +90") == null);
        check("past-exit highlight is for keeps only", select(53, "+0 +0 -90") == null && select(23, "+0 +0 -90") == null);

        // Not a junction-view maneuver.
        for (int type : new int[] {0, 1, 2, 3, 4, 5, 9, 47, 48, 49, 50})
            check("no picture for type " + type, select(type, "-0 -0 -0 +90") == null
                    && select(type, "+-90 -0 -0") == null && select(type, "+0 +0 -90") == null);
        CpTurns.Maneuver round = maneuver(23); round.junction = 1;
        check("roundabout", CpJunctions.select(round, null, row("-0 -0 +90")) == null);
        // Lanes that do not show one plain exit.
        check("no decoded lanes", CpJunctions.select(maneuver(23), null, null) == null);
        check("empty decoded lanes", CpJunctions.select(maneuver(23), null, lanes()) == null);
        check("no recommendation", select(23, "-0 -0 -90") == null);
        check("no exit arrow", select(23, "-0 -0 +0") == null);
        check("one-lane ramp", select(23, "+90") == null);
        check("every lane turns", select(23, "+90 +90") == null);
        check("route stays on the through lanes", select(53, "+0 +0 -90") == null);
        check("lane change still to come", select(23, "-0 +0 +90") == null);
        check("U-turn lane: arterial road", select(23, "-180 -0 +90") == null);
        check("U-turn in a route lane", select(23, "-0 -0 +90,180") == null);
        check("exits on both sides", select(53, "-0 -90 +0 +0 +0 +0 -90") == null);
        check("generic ramp with exits on both sides", select(8, "-90 -0 -0 +90") == null);
        check("type side contradicts the lanes", select(23, "+-90 -0 -0") == null);
        check("type side contradicts the lanes, left", select(22, "-0 -0 +90") == null);
        check("three exit lanes is a split", select(23, "-0 -0 -0 +90 +90 +90") == null);
        check("two exit lanes on a three-lane road", select(23, "-0 +90 +90") == null);
        check("left two exit lanes have no template", select(22, "+-90 +-90 -0 -0") == null);
        check("left exit on a two-lane road", select(22, "+-90 -0") == null);

        String[] specs = {"-0 -0 -0 +90", "-0 +90", "-0 -0 -0 +90 +90", "+-90 -0 -0", "-0 -0 *90",
                "+0 +0 +0 -90", "+0 -90", "+0 +0 -0 -90 -90", "--90 +0 +0", "--90 +0"};
        for (String spec : specs) for (int type : new int[] {8, 13, 14, 22, 23, 51, 52, 53}) {
            CpLaneDecoder.Info info = row(spec);
            int[] result = CpJunctions.select(maneuver(type), null, info);
            if (result == null) continue;
            belongs(result);
            check("deterministic and pure", java.util.Arrays.equals(result, CpJunctions.select(maneuver(type), null, info)));
        }
        check("all observed pictures retained", CpJunctionTable.PICTURES.length == 292 * 4);
        check("all observed highlights retained", CpJunctionTable.HIGHLIGHTS.length == 721 * 5);
        System.out.println("CpJunctionsTest: " + checks + " checks passed");
    }
}
