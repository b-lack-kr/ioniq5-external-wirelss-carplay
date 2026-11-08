// SPDX-License-Identifier: AGPL-3.0-or-later
// Adapted from seogaemo/hmg-seogaemo, commit 26eeb97e0a745528b438d7a946be5450fadbfd04.
// NEEV: junction evidence only; existing transport and lane rendering remain owned locally.
package hmgapi.mods.wireless.dipo;


/** Original HMG-Seogaemo PE14 selector, using observed stock pictures, not OEM source.
 * Stock picks its picture from map data, not from the lane count (at an IC with four
 * through lanes and one exit lane it drew the three-lane 10193/10194), so PE14 pictures
 * are templates. The selection uses only the maneuver type and the phone's lane
 * guidance: which edge the exit lanes are on, how many there are, and whether the route
 * takes them or stays on the through lanes. Instruction text and road names are never used. Callers own visibility,
 * distance gates and clearing the display.
 */
final class CpJunctions {
    private CpJunctions() {}
    private static final int NONE = 65535;

    /** Return {picture A, route highlight B, optional highlight C}, or null when the
     * maneuver and lanes do not show one plain exit. No calls, state or input mutation.
     */
    static int[] select(CpTurns.Maneuver current, CpTurns.Maneuver next, CpLaneDecoder.Info lanes) {
        // Highway exits, highway changes and keeps. A U-turn lane rules out town roads.
        if (current == null || current.junction != 0 || !junctionType(current.type)) return null;
        if (lanes == null || lanes.lanes == null) return null;
        CpLaneDecoder.Lane[] l = lanes.lanes;
        if (l.length < 2 || l.length > 8) return null;
        for (CpLaneDecoder.Lane lane : l) {
            if (lane == null || lane.status < 0 || lane.status > 2 || lane.angles == null) return null;
            boolean known = valid(lane.highlight);
            for (int angle : lane.angles) {
                if (!valid(angle)) return null;
                known = true;
            }
            if (!known) return null; // An unknown through lane cannot establish a road template.
        }
        int n = l.length, typeSide = typeSide(current.type);
        int right = typeSide < 0 ? 0 : exitLanes(l, 1, true), left = typeSide > 0 ? 0 : exitLanes(l, -1, true);
        // A generic ramp takes its side from the lanes; both sides at once is ambiguous.
        if (right > 0 && left > 0) return null;
        if (right == 1) {
            if (n == 2) return pair(10046, 10047);
            // Two consecutive ramps: the second observed stock diagram.
            if (n >= 3 && next != null && next.junction == 0 && rightExit(next.type)) return pair(10168, 10170);
            return n >= 3 ? pair(10193, 10194) : null;
        }
        if (right == 2) return n >= 4 ? pair(10320, 10322) : null;
        if (left == 1) return n >= 3 ? pair(10016, 10018) : null;
        if (right > 0 || left > 0) return null;
        // A keep away from an exit: the route stays on the through lanes while the exit
        // lanes on the other edge peel off. Same picture, straight highlight.
        if (current.type == 13) {
            int exit = exitLanes(l, 1, false);
            if (exit == 1) return n == 2 ? pair(10046, 10048) : pair(10193, 10195);
            if (exit == 2 && n >= 4) return pair(10320, 10323);
        } else if (current.type == 14) {
            int exit = exitLanes(l, -1, false);
            if (exit == 1) return n == 2 ? pair(10001, 10002) : pair(10016, 10017);
        }
        return null;
    }

    /** Number of exit lanes on the side, when the lanes show exactly that exit: contiguous
     * edge lanes turning that way (at most two), at least one through lane without turn
     * arrows, and the route on the exit lanes only (take) or on the through lanes only.
     * A U-turn lane means an arterial road, never a highway junction. 0 otherwise.
     */
    private static int exitLanes(CpLaneDecoder.Lane[] l, int side, boolean take) {
        int n = l.length, exit = 0;
        for (CpLaneDecoder.Lane lane : l) if (lane == null || uTurn(lane)) return 0;
        while (exit < n && turns(l[side > 0 ? n - 1 - exit : exit], side)) exit++;
        if (exit == 0 || exit > 2 || exit == n) return 0;
        boolean routed = false;
        for (int k = 0; k < n; k++) {
            boolean inExit = side > 0 ? k >= n - exit : k < exit;
            if (inExit) {
                if (l[k].status != 0 && !take) return 0;
                routed |= l[k].status != 0;
                continue;
            }
            if (turns(l[k], 1) || turns(l[k], -1)) return 0;
            // Taking the exit with a recommended through lane: a lane change is still to come.
            if (l[k].status != 0 && take) return 0;
            routed |= l[k].status != 0;
        }
        return routed ? exit : 0;
    }

    private static boolean turns(CpLaneDecoder.Lane lane, int side) {
        if (valid(lane.highlight) && direction(lane.highlight) == side) return true;
        if (lane.angles != null) for (int a : lane.angles) if (valid(a) && direction(a) == side) return true;
        return false;
    }
    private static boolean uTurn(CpLaneDecoder.Lane lane) {
        if (valid(lane.highlight) && Math.abs(lane.highlight) >= 150) return true;
        if (lane.angles != null) for (int a : lane.angles) if (valid(a) && Math.abs(a) >= 150) return true;
        return false;
    }

    private static boolean junctionType(int type) {
        return type == 8 || type == 22 || type == 23 || type == 51 || type == 52 || type == 53
                || type == 13 || type == 14;
    }
    private static boolean rightExit(int type) { return type == 8 || type == 23 || type == 53; }
    private static int typeSide(int type) {
        return type == 13 || type == 22 || type == 52 ? -1 : type == 14 || type == 23 || type == 53 ? 1 : 0;
    }
    private static int[] pair(int a, int b) { return new int[] {a, b, NONE}; }
    private static boolean valid(int a) { return a >= -180 && a <= 180; }
    private static int direction(int a) { return a < -15 ? -1 : a > 15 ? 1 : 0; }
}
