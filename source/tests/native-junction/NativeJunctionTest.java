package hmgapi.mods.wireless.dipo;

import java.util.Arrays;

public final class NativeJunctionTest {
    private static int checks;

    private static void check(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }

    private static void expected(int type, int selected, int[] others, int guide, int arrow) {
        check(Arrays.equals(NativeJunction.pattern(type, 0, selected, others),
                new int[] { guide, arrow, 65535, 0, 0 }), "native tuple type=" + type
                + " selected=" + selected + " others=" + Arrays.toString(others));
    }

    private static void absent(int type, int junction, int selected, int[] others, String why) {
        check(NativeJunction.pattern(type, junction, selected, others) == null, why);
    }

    private static void nativeTables() {
        expected(1, -90, new int[] { 90 }, 23, 24);
        expected(2, 90, new int[] { -90 }, 23, 25);
        expected(1, -90, new int[] { 0, 90 }, 19, 20);
        expected(3, 0, new int[] { -90, 90 }, 19, 21);
        expected(2, 90, new int[] { -90, 0 }, 19, 22);
        expected(1, -90, new int[] { 90, 0 }, 19, 20);
        expected(3, 0, new int[] { 90, -90 }, 19, 21);
        expected(2, 90, new int[] { 0, -90 }, 19, 22);
        for (int straight : new int[] { -5, -1, 0, 1, 5 }) {
            expected(3, straight, new int[] { -90, 90 }, 19, 21);
        }
        for (int edge : new int[] { 21, 22, 90, 135, 157 }) {
            expected(1, -edge, new int[] { edge }, 23, 24);
            expected(2, edge, new int[] { -edge }, 23, 25);
        }
        // Branch widths are unknown. Every width-dependent two-branch pair is omitted.
        absent(1, 0, -90, new int[] { 0 }, "left/straight requires lane count");
        absent(3, 0, 0, new int[] { -90 }, "straight/left requires lane count");
        absent(3, 0, 0, new int[] { 90 }, "straight/right requires lane count");
        absent(2, 0, 90, new int[] { 0 }, "right/straight requires lane count");
    }

    private static void rejectUncertainGeometry() {
        absent(1, 0, -90, null, "missing item10");
        absent(1, 0, -90, new int[0], "no alternative road");
        absent(1, 0, -90, new int[] { 0, 90, 180 }, "extra road is never discarded");
        absent(1, 0, -90, new int[1024], "bounded input size");
        absent(1, 0, -90, new int[] { -90 }, "selected repeated in item10");
        absent(1, 0, -90, new int[] { 270 }, "wrapped selected repeated in item10");
        absent(1, 0, -90, new int[] { 0, 360 }, "duplicate other roads after normalization");
        absent(1, 0, -90, new int[] { 0, 5 }, "two roads in same straight class");
        absent(1, 0, -90, new int[] { 45, 90 }, "two roads in same right class");
        absent(1, 0, -90, new int[] { -45, 90 }, "other left arm distinct but same selected class");
        for (int angle : new int[] { -180, -179, -158, -20, -6, 6, 20, 158, 179, 180,
                Integer.MIN_VALUE, Integer.MAX_VALUE, -361, 361 }) {
            absent(8, 0, angle, new int[] { -90, 90 }, "invalid/keep/back-facing selected=" + angle);
            absent(1, 0, -90, new int[] { 0, angle }, "invalid/keep/back-facing other=" + angle);
        }
        for (int junction : new int[] { -1, 1, 2, Integer.MIN_VALUE, Integer.MAX_VALUE }) {
            absent(1, junction, -90, new int[] { 0, 90 }, "only ordinary junction=" + junction);
        }
        for (int type : new int[] { -1, 0, 4, 6, 7, 10, 11, 12, 15, 16, 17, 18, 19,
                24, 25, 26, 27, 28, 29, 46, 54, Integer.MAX_VALUE }) {
            absent(type, 0, -90, new int[] { 0, 90 }, "unsupported/roundabout/U-turn type=" + type);
        }
        absent(1, 0, 90, new int[] { -90, 0 }, "typed left contradicts selected right");
        absent(2, 0, -90, new int[] { 0, 90 }, "typed right contradicts selected left");
        absent(3, 0, -90, new int[] { 0, 90 }, "typed straight contradicts selected left");
    }

    private static void normalizationAndOwnership() {
        expected(1, 270, new int[] { 0, 90 }, 19, 20);
        expected(2, -270, new int[] { 270, -360 }, 19, 22);
        for (int straight : new int[] { 0, 360, -360 }) {
            expected(3, straight, new int[] { 270, -270 }, 19, 21);
        }
        int[] others = { 0, 90 }, original = others.clone();
        int[] first = NativeJunction.pattern(1, 0, -90, others);
        check(Arrays.equals(others, original), "input array never mutated");
        first[0] = 999;
        expected(1, -90, others, 19, 20);
        check(Arrays.equals(others, original), "result shares no input storage");
    }

    private static int reflectedArrow(int arrow) {
        switch (arrow) {
            case 20: return 22;
            case 21: return 21;
            case 22: return 20;
            case 24: return 25;
            case 25: return 24;
            default: throw new AssertionError("unknown arrow");
        }
    }

    private static void symmetryAndBounds() {
        int[] sample = { -360, -270, -180, -158, -157, -90, -21, -20, -6, -5, 0,
                5, 6, 20, 21, 90, 157, 158, 180, 270, 360 };
        for (int selected : sample) for (int other : sample) {
            int[] forward = NativeJunction.pattern(8, 0, selected, new int[] { other });
            int[] mirror = NativeJunction.pattern(8, 0, -selected, new int[] { -other });
            mirrored(forward, mirror);
            int[] triple = NativeJunction.pattern(8, 0, selected, new int[] { 0, other });
            int[] tripleMirror = NativeJunction.pattern(8, 0, -selected, new int[] { 0, -other });
            mirrored(triple, tripleMirror);
        }
        for (int raw = -360; raw <= 360; raw++) {
            int normalized = raw > 180 ? raw - 360 : raw < -180 ? raw + 360 : raw;
            check(Arrays.equals(NativeJunction.pattern(8, 0, raw, new int[] { -90, 90 }),
                    NativeJunction.pattern(8, 0, normalized, new int[] { -90, 90 })),
                    "one revolution equivalent selected=" + raw);
        }
    }

    private static void mirrored(int[] forward, int[] mirror) {
        check((forward == null) == (mirror == null), "reflection preserves acceptance");
        if (forward == null) return;
        check(forward.length == 5 && (forward[0] == 19 || forward[0] == 23)
                && forward[2] == 65535 && forward[3] == 0 && forward[4] == 0,
                "only established resource tuples; absent second arrow and no fabricated lane/color");
        check(forward[0] == mirror[0] && reflectedArrow(forward[1]) == mirror[1],
                "reflection selects the mirrored native arrow");
    }

    public static void main(String[] args) {
        nativeTables();
        rejectUncertainGeometry();
        normalizationAndOwnership();
        symmetryAndBounds();
        System.out.println("NativeJunctionTest PASS checks=" + checks);
    }
}
