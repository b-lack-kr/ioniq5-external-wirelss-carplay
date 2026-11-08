package hmgapi.mods.wireless.dipo;

/** Ordinary intersection arms in the native cluster side-street encoding.
 * Caller must exclude roundabouts, U-turns and absent or untrusted geometry. */
public final class NativeSideStreet {
    private NativeSideStreet() {}

    public static final class Result {
        public final int mask;
        public final float directionDegrees;

        private Result(int mask, float directionDegrees) {
            this.mask = mask;
            this.directionDegrees = directionDegrees;
        }
    }

    /** Angles are signed degrees with right positive. A null result means invalid
     * input or an ambiguous selected arm. Empty otherAngles is valid geometry;
     * callers must distinguish it from an absent item before invoking this method. */
    public static Result map(int[] otherAngles, int selectedAngle) {
        if (otherAngles == null || !valid(selectedAngle)) return null;
        int selected = normalize(selectedAngle);
        // Native SetSideStreetBit has a special case involving incoming geometry
        // here. We cannot infer the necessary state from CarPlay's angle alone.
        if (selected >= 168 && selected <= 192) return null;
        int selectedBin = bin(selected);
        int mask = 1 << selectedBin;
        for (int angle : otherAngles) {
            if (!valid(angle)) return null;
            int normalized = normalize(angle);
            // Incoming/back-facing arms are ambiguous, and are not ordinary exits.
            if (normalized >= 168 && normalized <= 192) continue;
            mask |= 1 << bin(normalized);
        }
        float degrees = selectedBin == 0 ? 0f
                : (selectedBin <= 7 ? selectedBin : selectedBin + 1) * 22.5f;
        return new Result(mask, degrees);
    }

    private static boolean valid(int angle) { return angle > -360 && angle < 360; }
    private static int normalize(int angle) { return (angle + 360) % 360; }

    // SetSideStreetBit uses alternating 22/23 degree integer bins around
    // multiples of 22.5 degrees, with the back-facing bin omitted.
    private static int bin(int angle) {
        if (angle < 12 || angle > 348) return 0;
        int lower = 12;
        int upper = 34;
        int bit = 0;
        for (int index = 0; index < 15; index++) {
            if (index != 7) {
                bit++;
                if (angle >= lower && angle < upper) return bit;
            }
            lower += (index % 2 == 0) ? 22 : 23;
            upper += (index % 2 == 0) ? 23 : 22;
        }
        throw new IllegalArgumentException("ambiguous side-street angle");
    }
}
