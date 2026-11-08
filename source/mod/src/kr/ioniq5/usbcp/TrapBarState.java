// Adapted for NEEV USB CarPlay by blackr on 2026-11-07.
// Reference: HMG-Seogaemo v0.1.46, commit 26eeb97e0a745528b438d7a946be5450fadbfd04.
// https://github.com/seogaemo/hmg-seogaemo (AGPL-3.0-or-later).
// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.ioniq5.usbcp;

/** Value-only lease logic. A snapshot always comes from a real stock call. */
final class TrapBarState {
    static final long LEASE_MS = 3500;
    private static final int[] ALERTS = {0,1,2,3,4,5,6,7,9,10,13};
    private int[] stock;
    private boolean active, overlaid, stockUnsafe;
    private int level;
    private long expiry;

    static boolean alert(int index, int v) {
        // A real idle vehicle snapshot has overspeedAlarm=3; it is not an alert.
        return v != 0 && v != -1 && !(index == 2 && v == 3);
    }
    static boolean hasAlert(int[] v) {
        if (v == null || v.length != 17) return true;
        for (int index : ALERTS) if (alert(index, v[index])) return true;
        return false;
    }
    int[] capture(int[] v, boolean extraAlert, long now, boolean owner) {
        if (v == null || v.length != 17) { stock=null; overlaid=false; return null; }
        stock=v.clone(); stockUnsafe=extraAlert || hasAlert(v);
        if (!owner || !active || now >= expiry || stockUnsafe) {
            if (!owner || now >= expiry) active=false;
            overlaid=false;
            return v.clone();
        }
        int[] result=v.clone(); result[11]=level; overlaid=true; return result;
    }
    int[] request(boolean on, int value, long now, boolean owner) {
        if (on && (value < 0 || value > 100 || !owner)) return null;
        if (!on || !owner) return restore(owner);
        active=true; expiry=now+LEASE_MS; level=value;
        if (stock == null || stockUnsafe) return null;
        int[] out=stock.clone(); out[11]=level; return out;
    }
    void applied(boolean ok) { overlaid=ok; }
    boolean waitingSnapshot() { return stock==null; }
    boolean warningOwnsBar() { return stock!=null && stockUnsafe; }
    boolean isActive(long now) { return active && now < expiry; }
    boolean isExpired(long now) { return active && now >= expiry; }
    int[] expire(long now, boolean owner) {
        if (!active || now < expiry) return null;
        return restore(owner);
    }
    private int[] restore(boolean owner) {
        active=false;
        if (!overlaid || stock==null || !owner) { overlaid=false; return null; }
        return stock.clone(); // A successful writer call, not intent alone, clears this flag.
    }
    void invalid() { stock=null; active=false; overlaid=false; }
}
