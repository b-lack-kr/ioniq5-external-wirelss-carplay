import hmgapi.mods.wireless.dipo.NativeSideStreet;

public final class NativeSideStreetTest {
    // Independent transcription of native comparisons and returned bit positions.
    private static final int[][] TABLE = {
        {0, 11, 0}, {12, 33, 1}, {34, 56, 2}, {57, 78, 3},
        {79, 101, 4}, {102, 123, 5}, {124, 146, 6}, {147, 167, 7},
        {193, 213, 8}, {214, 236, 9}, {237, 258, 10},
        {259, 281, 11}, {282, 303, 12}, {304, 326, 13},
        {327, 348, 14}, {349, 359, 0}
    };
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static int expectedBit(int angle) {
        for (int[] row : TABLE) if (angle >= row[0] && angle <= row[1]) return row[2];
        return -1;
    }
    private static void example(int angle, int mask, float direction) {
        NativeSideStreet.Result r = NativeSideStreet.map(new int[0], angle);
        require(r != null && r.mask == mask && r.directionDegrees == direction,
                "example " + angle);
    }
    public static void main(String[] args) {
        example(0, 1, 0f);
        example(90, 16, 90f);
        example(270, 2048, 270f);
        example(45, 4, 45f);
        example(315, 8192, 315f);
        example(-45, 8192, 315f);
        for (int signed = -359; signed < 360; signed++) {
            int a = (signed + 360) % 360;
            int bit = expectedBit(a);
            NativeSideStreet.Result selected = NativeSideStreet.map(new int[0], signed);
            if (bit < 0) {
                require(selected == null, "selected back " + signed);
            } else {
                float direction = bit == 0 ? 0f : (bit <= 7 ? bit : bit + 1) * 22.5f;
                require(selected != null && selected.mask == (1 << bit)
                        && selected.directionDegrees == direction, "selected " + signed);
            }
            NativeSideStreet.Result other = NativeSideStreet.map(new int[]{signed}, 0);
            require(other != null && other.mask == (1 | (bit < 0 ? 0 : 1 << bit))
                    && other.directionDegrees == 0f, "other " + signed);
        }
        NativeSideStreet.Result combined = NativeSideStreet.map(new int[]{0, 45, 270, -45, 180}, 90);
        require(combined != null && combined.mask == (1 | 4 | 16 | 2048 | 8192)
                && combined.directionDegrees == 90f, "combined");
        require(NativeSideStreet.map(null, 0) == null, "absent array");
        require(NativeSideStreet.map(new int[]{360}, 0) == null, "invalid other");
        require(NativeSideStreet.map(new int[]{Integer.MIN_VALUE}, 0) == null, "sentinel");
        require(NativeSideStreet.map(new int[0], 360) == null, "invalid selected");
        require(NativeSideStreet.map(new int[0], -360) == null, "invalid selected negative");
        System.out.println("NativeSideStreet: all angle, boundary, mask and invalid-input tests passed");
    }
}
