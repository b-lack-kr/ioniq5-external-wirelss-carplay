// SPDX-License-Identifier: AGPL-3.0-or-later
// Adapted from seogaemo/hmg-seogaemo, commit 26eeb97e0a745528b438d7a946be5450fadbfd04.
// NEEV: junction evidence only; existing transport and lane rendering remain owned locally.
package hmgapi.mods.wireless.dipo;

import java.util.ArrayList;
import java.util.Arrays;

/** Original HMG-Seogaemo decoder; wire references (no source copied):
 * .agents/carplay-tbt-external/iap2-protocol.md. No Android dependencies. */
final class CpLaneDecoder {
    static final int MAX = 65535;
    static final class Lane {
        final int index, status, highlight;
        final int[] angles;
        Lane(int index, int status, int[] angles, int highlight) {
            this.index = index; this.status = status; this.angles = angles; this.highlight = highlight;
        }
    }
    static final class Info {
        final int component, index, descriptionLength;
        final Lane[] lanes;
        Info(int component, int index, Lane[] lanes, int descriptionLength) {
            this.component = component; this.index = index;
            this.lanes = lanes; this.descriptionLength = descriptionLength;
        }
        String summary() {
            StringBuilder s = new StringBuilder("0x5204 comp=").append(component).append(" index=").append(index)
                    .append(" descBytes=").append(descriptionLength);
            for (Lane l : lanes) s.append(" [i").append(l.index).append(" s").append(l.status)
                    .append(" a").append(Arrays.toString(l.angles)).append(" h").append(l.highlight).append(']');
            return s.toString();
        }
    }
    private final byte[] link = new byte[MAX], control = new byte[MAX];
    private int ln, cn, session = -1, errors;
    synchronized void reset() { ln = cn = errors = 0; session = -1; }
    synchronized int takeErrors() { int n = errors; errors = 0; return n; }
    private void bad() { if (errors < Integer.MAX_VALUE) errors++; }
    private static int u16(byte[] b, int p) { return (b[p] & 255) * 256 + (b[p + 1] & 255); }
    private static int end(byte[] b, int p, int limit) {
        if (limit - p < 4) throw new IllegalArgumentException("short TLV");
        int n = u16(b, p);
        if (n < 4 || n > limit - p) throw new IllegalArgumentException("TLV length");
        return p + n;
    }
    private static void size(int start, int end, int want) {
        if (end - start != want) throw new IllegalArgumentException("field width");
    }
    /** Complete control message including 40 40 / length / id. Other ids are ignored. */
    static Info decode(byte[] b, int offset, int length) {
        if (b == null || offset < 0 || length < 6 || offset > b.length - length || length > MAX)
            throw new IllegalArgumentException("control bounds");
        int limit = offset + length;
        if (u16(b, offset) != 0x4040 || u16(b, offset + 2) != length)
            throw new IllegalArgumentException("control header");
        if (u16(b, offset + 4) != 0x5204) return null;
        int component = -1, index = -1, description = 0;
        ArrayList<Lane> lanes = new ArrayList<Lane>();
        for (int p = offset + 6; p < limit;) {
            int e = end(b, p, limit), id = u16(b, p + 2), v = p + 4;
            if (id == 0 || id == 1) {
                size(v, e, 2);
                if (id == 0) { if (component != -1) throw new IllegalArgumentException("duplicate component"); component = u16(b, v); }
                else { if (index != -1) throw new IllegalArgumentException("duplicate index"); index = u16(b, v); }
            } else if (id == 2) {
                if (lanes.size() == 8) throw new IllegalArgumentException("lane capacity");
                Lane l = lane(b, v, e);
                for (Lane old : lanes) if (old.index == l.index) throw new IllegalArgumentException("duplicate lane");
                lanes.add(l);
            } else if (id == 3) description = e - v;
            p = e;
        }
        if (component < 0 || index < 0) throw new IllegalArgumentException("missing identity");
        // LaneIndex orders lanes even when groups arrive out of order.
        for (int i = 1; i < lanes.size(); i++) {
            Lane l = lanes.get(i); int j = i;
            while (j > 0 && lanes.get(j - 1).index > l.index) { lanes.set(j, lanes.get(j - 1)); j--; }
            lanes.set(j, l);
        }
        return new Info(component, index, lanes.toArray(new Lane[lanes.size()]), description);
    }
    private static Lane lane(byte[] b, int p, int limit) {
        int index = -1, status = -1, highlight = 1000, n = 0;
        int[] angles = new int[16];
        boolean highlighted = false;
        while (p < limit) {
            int e = end(b, p, limit), id = u16(b, p + 2), v = p + 4;
            if (id == 0) { size(v, e, 2); if (index != -1) throw new IllegalArgumentException("duplicate lane index"); index = u16(b, v); }
            else if (id == 1) { size(v, e, 1); if (status != -1) throw new IllegalArgumentException("duplicate status"); status = b[v] & 255; if (status > 2) throw new IllegalArgumentException("status"); }
            else if (id == 2) {
                if ((e - v) % 2 != 0 || (e - v) / 2 > 16 - n) throw new IllegalArgumentException("angles");
                for (; v < e; v += 2) angles[n++] = (short) u16(b, v);
            } else if (id == 3) { size(v, e, 2); if (highlighted) throw new IllegalArgumentException("duplicate highlight"); highlighted = true; highlight = (short) u16(b, v); }
            p = e;
        }
        if (index < 0 || status < 0) throw new IllegalArgumentException("missing lane fields");
        return new Lane(index, status, Arrays.copyOf(angles, n), highlight);
    }
    /** Arbitrarily split/coalesced link frames; at most 64 results per callback. */
    synchronized Info[] feed(byte[] b, int length) {
        ArrayList<Info> out = new ArrayList<Info>();
        if (b == null || length < 0 || length > b.length) { bad(); return new Info[0]; }
        for (int p = 0; p < length; p++) {
            int x = b[p] & 255;
            if (ln == 0 && x != 255) continue;
            if (ln == 1 && x != 0x5a) { ln = x == 255 ? 1 : 0; continue; }
            link[ln++] = b[p];
            if (ln == 4 && u16(link, 2) < 9) { bad(); ln = cn = 0; }
            if (ln >= 9 && ln == u16(link, 2)) {
                int h = 0, d = 0;
                for (int i = 0; i < 9; i++) h += link[i] & 255;
                for (int i = 9; i < ln; i++) d += link[i] & 255;
                if ((h & 255) != 0 || (d & 255) != 0) { bad(); cn = 0; }
                else if (ln > 10 && (link[4] & 0x80) == 0) payload(9, ln - 1, out);
                ln = 0;
            }
        }
        return out.toArray(new Info[out.size()]);
    }
    private void payload(int p, int limit, ArrayList<Info> out) {
        int sid = link[7] & 255;
        if (cn != 0 && session != sid) return;
        while (p < limit) {
            if (cn == 0) {
                if (limit - p < 2 || u16(link, p) != 0x4040) {
                    if (link[p] != 0x40) return;
                }
                session = sid;
            }
            control[cn++] = link[p++];
            if (cn == 2 && u16(control, 0) != 0x4040) { bad(); cn = 0; return; }
            if (cn == 4 && u16(control, 2) < 6) { bad(); cn = 0; return; }
            if (cn >= 6 && cn == u16(control, 2)) {
                try {
                    Info info = decode(control, 0, cn);
                    if (info != null) { if (out.size() < 64) out.add(info); else bad(); }
                } catch (IllegalArgumentException e) { bad(); }
                cn = 0;
            }
        }
    }
}
