// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.ioniq5.usbcp;

/** Numeric native-Navi output evidence only. Never alters or invokes a writer.
 * Per-writer deduplication and a one-second rate limit bound logging work; the
 * existing rotating recorder owns disk retention. No road names or GPS fields. */
public final class NativeGuidanceTrace {
    public interface Sink { void event(String line); }
    private static final String[] WRITERS = { "update_tbt_info", "update_tbt_sub_info",
        "update_tbt_sub_info_b", "update_tbt_sub_info_2c", "update_remain_dis" };
    // F = boxed float, I = boxed int. Exact NavigationCluster signatures.
    private static final String[] TYPES = { "IIFFIIIII", "FIIIIIIFI", "FIIIIIIFII", "IIIIIIIII", "IIII" };
    private final Sink sink;
    private final String[] sent = new String[WRITERS.length];
    private final long[] at = new long[WRITERS.length];

    public NativeGuidanceTrace(Sink sink) { this.sink = sink; }

    /** nativeOwner is true only for enabled native Navi when neither phone guides.
     * A suppressed phone-owned callback cannot be labeled native evidence. */
    public synchronized void observe(String writer, Object[] args, long now, boolean nativeOwner) {
        int slot = -1;
        for (int i = 0; i < WRITERS.length; i++) if (WRITERS[i].equals(writer)) { slot = i; break; }
        if (slot < 0) return;
        if (!nativeOwner) { sent[slot] = null; at[slot] = 0; return; }
        if (sink == null || now < 0 || args == null || args.length != TYPES[slot].length()) return;
        if (sent[slot] != null && now >= at[slot] && now - at[slot] < 1000) return;
        StringBuilder values = new StringBuilder(128);
        for (int i = 0; i < args.length; i++) {
            Object value = args[i];
            if (value == null) return;
            if (i != 0) values.append(',');
            if (TYPES[slot].charAt(i) == 'I') {
                if (value.getClass() != Integer.class) return;
                values.append(((Integer) value).intValue());
            } else {
                if (value.getClass() != Float.class) return;
                float f = ((Float) value).floatValue();
                if (Float.isNaN(f) || Float.isInfinite(f)) return;
                values.append(f == 0f ? 0f : f);
            }
        }
        String key = values.toString();
        if (key.equals(sent[slot])) return;
        try { sink.event("USB_CP_NATIVE_TBT writer=" + WRITERS[slot] + " values=" + key); }
        catch (Throwable ignored) { return; } // Diagnostics cannot break vehicle guidance.
        sent[slot] = key;
        at[slot] = now;
    }
}
