package hmgapi.mods.wireless.dipo;

/** Fixed native ILS patterns for complete, unambiguous CarPlay road geometry.
 * The inputs are CPManeuver item 11 (selected road) and item 10 (other roads).
 * No lane widths, additional roads, or cursor position are inferred here.
 * See tests/native-junction/EVIDENCE.txt for the captured native producers. */
final class NativeJunction {
    private static final int UNKNOWN = 0, LEFT = 1, STRAIGHT = 2, RIGHT = 3;
    private static final int NO_ANGLE = Integer.MIN_VALUE;
    // A conservative acceptance limit, not a newly discovered native threshold.
    // The existing CP U-turn gate treats >=158 degrees as potentially back-facing.
    private static final int MAX_FORWARD_TURN = 157;

    private NativeJunction() {}

    /** Returns {guide, arrow0, absentArrow1, laneBits, color}, or null.
     * Null means this helper cannot establish a matching native template; the
     * caller must handle prior-frame clearing and ordinary maneuver fallback. */
    static int[] pattern(int maneuverType, int junctionType, int selectedAngle, int[] otherAngles) {
        if (junctionType != 0 || otherAngles == null || otherAngles.length < 1
                || otherAngles.length > 2) return null;
        int selected = side(selectedAngle);
        if (selected == UNKNOWN || !typeAgrees(maneuverType, selected)) return null;
        int sides = 1 << selected;
        for (int angle : otherAngles) {
            int branch = side(angle);
            // Item 10 excludes the selected road. Repeated roads or multiple
            // roads in one coarse class cannot be collapsed into a template.
            if (branch == UNKNOWN || (sides & (1 << branch)) != 0) return null;
            sides |= 1 << branch;
        }
        if (otherAngles.length == 2 && sides == ((1 << LEFT) | (1 << STRAIGHT) | (1 << RIGHT))) {
            return frame(19, selected == LEFT ? 20 : selected == STRAIGHT ? 21 : 22);
        }
        if (otherAngles.length == 1 && sides == ((1 << LEFT) | (1 << RIGHT))) {
            return frame(23, selected == LEFT ? 24 : 25);
        }
        // Native L/S and S/R pairs choose between guide1/10 and guide4/7
        // according to which branch has more lanes. CP item10/11 has no lane
        // counts, so neither equal widths nor the native flag=false is assumed.
        return null;
    }

    private static int[] frame(int guide, int arrow) {
        return new int[] { guide, arrow, 65535, 0, 0 };
    }

    private static int normalized(int angle) {
        if (angle < -360 || angle > 360) return NO_ANGLE;
        return angle > 180 ? angle - 360 : angle < -180 ? angle + 360 : angle;
    }

    private static int side(int rawAngle) {
        int angle = normalized(rawAngle);
        if (angle == NO_ANGLE) return UNKNOWN;
        int magnitude = Math.abs(angle);
        // Native DetermineDirByAngle has straight [-5,5], keep [6,20],
        // and ordinary side [21,179]. Keep classification depends on the other
        // branch, so omit it rather than using a single-angle approximation.
        if (magnitude <= 5) return STRAIGHT;
        if (magnitude <= 20 || magnitude > MAX_FORWARD_TURN) return UNKNOWN;
        return angle < 0 ? LEFT : RIGHT;
    }

    private static boolean typeAgrees(int type, int selected) {
        switch (type) {
            case 1: case 13: case 20: case 22: case 47: case 49: case 52:
                return selected == LEFT;
            case 2: case 14: case 21: case 23: case 48: case 50: case 53:
                return selected == RIGHT;
            case 3:
                return selected == STRAIGHT;
            case 5: case 8: case 9: case 51:
                return true; // follow road / ramp / change highway: geometry selects the branch
            default:
                return false; // includes unknown, arrival, roundabout, and U-turn types
        }
    }
}
