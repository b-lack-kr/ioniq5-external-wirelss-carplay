// SPDX-License-Identifier: AGPL-3.0-or-later
// Adapted from seogaemo/hmg-seogaemo 7616e43f669916445f151a7aeb279b32ea09cd4b (AGPL-3.0).
// Verified glyph sets and three independent two-bit status fields; shared by CP and AA.
package hmgapi.mods.wireless.dipo;

/**
 * One lane as the cluster's per-lane values: the status byte (2 bits per arrow slot, 00 not
 * drawn, 01 white, 10 blue, 11 grey) and up to three arrow image ids
 * (.agents/cluster-lane-arrows.md). The single rendering rule for CarPlay (CpTurns.laneValues)
 * and Android Auto (AaTurns.sendLanes): draw what the phone sends, through CarPlay's LaneStatus.
 * Status 1 (good): the arrows on the route white, the other arrows of that lane grey; no arrow on
 * the route (highlight 1000, or none matches): every arrow white. Status 2 (preferred, the lane
 * the driver must use): the same, with blue instead of white. Status 0 (not good): every arrow
 * grey. The caller hands over each lane's direction classes (CpTurns.laneIcon values 1..8, the
 * plain glyph ids) and which of them are on the route; this turns them into the overlaid
 * component glyphs a multi-direction lane needs. Blue exists only for CarPlay status 2: Android
 * Auto has no must-use signal and never produces it.
 */
public final class CpLaneFrame {
    public static final int WHITE = 1, BLUE = 2, GREY = 3;

    private CpLaneFrame() {}

    /**
     * @param arrows  the lane's arrow slots as direction classes 1..8 (0: unused), at most 8
     * @param onRoute bit k set: the direction in slot k is on the route (the phone's highlight)
     * @param status  CarPlay's LaneStatus: 0 not good, 1 good, 2 preferred (anything else counts as 1)
     * @return {status byte, image id of slot 0, 1, 2}
     */
    public static int[] lane(int[] arrows, int onRoute, int status) {
        int on = status == 2 ? BLUE : WHITE;
        int[] cls = new int[8];
        boolean[] rec = new boolean[8];
        int n = 0;
        for (int k = 0; arrows != null && k < arrows.length && k < 8; k++) {
            int c = arrows[k];
            if (c < 1 || c > 8) continue;
            int i = 0;
            while (i < n && cls[i] != c) i++;
            if (i == n) cls[n++] = c;
            if ((onRoute >> k & 1) != 0) rec[i] = true;
        }
        int[] r = new int[4];
        int idle = status == 0 ? GREY : on;
        if (n == 0) {
            r[0] = idle;
            return r;
        }
        boolean anyRec = false;
        for (int i = 0; i < n; i++) anyRec |= rec[i];
        boolean snap = false;
        int[][] row = LaneGlyphs.find(cls, n, false);
        if (row == null) {
            row = LaneGlyphs.find(cls, n, true);
            snap = row != null;
        }
        if (row == null) {
            // not a verified set: only the route arrow, else the first, as a single plain glyph
            int pick = 0;
            for (int i = n - 1; i >= 0; i--) if (rec[i]) pick = i;
            row = new int[][] { { cls[pick] }, { cls[pick] } };
            rec = new boolean[] { rec[pick], false, false };
            cls = new int[] { cls[pick], 0, 0 };
            n = 1;
        }
        for (int j = 0; j < row[0].length; j++) {
            boolean slotRec = false;
            for (int i = 0; i < n; i++) if (rec[i] && LaneGlyphs.fold(cls[i], snap) == row[0][j]) slotRec = true;
            int colour = status == 0 ? GREY : !anyRec || slotRec ? on : GREY;
            r[0] |= colour << (2 * j);
            r[1 + j] = row[1][j];
        }
        return r;
    }
    static final class LaneGlyphs {
        static final int ST = 1, SLR = 2, R = 3, SHR = 4, UT = 5, SHL = 6, L = 7, SLL = 8;
        static final int[][][] SETS = {
            { { UT, L }, { 10, 14 } },
            { { UT, SLL }, { 10, 15 } },
            { { ST, L }, { 9, 12 } },
            { { ST, SLL }, { 9, 13 } },
            { { ST, SHL }, { 9, 11 } },
            { { ST, UT }, { 9, 10 } },
            { { L, SLL }, { 12, 13 } },
            { { ST, R }, { 16, 18 } },
            { { ST, SLR }, { 16, 19 } },
            { { ST, SHR }, { 16, 17 } },
            { { R, SLR }, { 18, 21 } },
            { { L, R }, { 31, 32 } },
            { { SLL, SLR }, { 30, 33 } },
            { { UT, R }, { 26, 32 } },
            { { UT, SLR }, { 26, 33 } },
            { { L, ST, R }, { 28, 22, 24 } },
            { { SLL, ST, R }, { 30, 22, 24 } },
            { { L, ST, SLR }, { 28, 22, 33 } },
            { { SHL, ST, R }, { 27, 22, 24 } },
            { { L, ST, SHR }, { 28, 22, 25 } },
            { { SLL, ST, SLR }, { 30, 22, 33 } },
            { { L, SLL, ST }, { 12, 13, 9 } },
            { { ST, SLR, R }, { 16, 19, 18 } },
            { { UT, L, ST }, { 26, 31, 22 } },
            { { UT, ST, R }, { 26, 22, 24 } },
            { { UT, L, R }, { 26, 31, 32 } },
        };

        /** snap: slight and sharp turns count as the plain left / right */
        static int fold(int c, boolean snap) {
            if (!snap) return c;
            return c == SLR || c == SHR ? R : c == SLL || c == SHL ? L : c;
        }

        /** the row for the classes cls[0..n) (one class: the plain glyph), or null when none matches */
        static int[][] find(int[] cls, int n, boolean snap) {
            int mask = 0, count = 0, first = 0;
            for (int i = 0; i < n; i++) {
                int c = fold(cls[i], snap);
                if ((mask & (1 << c)) != 0) continue;
                mask |= 1 << c;
                if (count++ == 0) first = c;
            }
            if (count == 1) return new int[][] { { first }, { first } };
            for (int[][] row : SETS) {
                int m = 0;
                for (int c : row[0]) m |= 1 << c;
                if (m == mask) return row;
            }
            return null;
        }
    }

}
